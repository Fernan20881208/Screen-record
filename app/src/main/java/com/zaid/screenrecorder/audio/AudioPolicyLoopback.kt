package com.zaid.screenrecorder.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Privileged internal-audio loopback using Android's dynamic AudioPolicy / Remote Submix path.
 *
 * The app must be installed as a priv-app and granted MODIFY_AUDIO_ROUTING plus
 * CAPTURE_AUDIO_OUTPUT/CAPTURE_MEDIA_OUTPUT by the accompanying root module.
 * No MediaProjection token is used.
 */
class AudioPolicyLoopback(private val context: Context) {
    companion object {
        const val MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
        const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
        const val CAPTURE_MEDIA_OUTPUT = "android.permission.CAPTURE_MEDIA_OUTPUT"
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        private const val ROUTE_FLAG_RENDER = 0x1
        private const val ROUTE_FLAG_LOOP_BACK = 0x2
        private const val ROUTE_FLAG_LOOP_BACK_RENDER = ROUTE_FLAG_RENDER or ROUTE_FLAG_LOOP_BACK
        private const val AUDIO_POLICY_SUCCESS = 0
    }

    data class Session(
        val record: AudioRecord,
        val detail: String,
        private val closeAction: () -> Unit
    ) {
        fun close() = closeAction()
    }

    fun probe(): AudioBackendCapabilities {
        val modify = hasPermission(MODIFY_AUDIO_ROUTING)
        val capture = hasPermission(CAPTURE_AUDIO_OUTPUT) || hasPermission(CAPTURE_MEDIA_OUTPUT)
        val api = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return AudioBackendCapabilities(
            available = api && modify && capture,
            internalAudio = api && modify && capture,
            microphone = false,
            detail = when {
                !api -> "AudioPolicy loopback requires Android 10+"
                !modify -> "Missing privileged MODIFY_AUDIO_ROUTING; install the Zaid root module and reboot"
                !capture -> "Missing privileged CAPTURE_AUDIO_OUTPUT/CAPTURE_MEDIA_OUTPUT; install the Zaid root module and reboot"
                else -> "Privileged AudioPolicy Remote Submix loopback ready"
            }
        )
    }

    fun open(sampleRate: Int, channels: Int): Session {
        val caps = probe()
        check(caps.available) { caps.detail }

        // AudioPolicy is a SystemApi. The release is a privileged app, but Android still applies
        // non-SDK reflection restrictions to non-platform-signed packages, so exempt only the
        // audio-policy surface that this backend needs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/media/audiopolicy/",
                "Landroid/media/AudioManager;"
            )
        }

        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN).forEach { usage ->
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            addRule.invoke(ruleBuilder, attributes, RULE_MATCH_ATTRIBUTE_USAGE)
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK_RENDER)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val audioManager = context.getSystemService(AudioManager::class.java)
        val registerMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
        val result = (registerMethod.invoke(audioManager, policy) as Number).toInt()
        check(result == AUDIO_POLICY_SUCCESS) {
            "AudioPolicy registration failed ($result). Verify the privileged module is active and rebooted."
        }

        try {
            val createSink = policyClass.getMethod("createAudioRecordSink", mixClass)
            val record = createSink.invoke(policy, mix) as? AudioRecord
                ?: error("AudioPolicy did not return a Remote Submix AudioRecord")
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Remote Submix AudioRecord initialization failed" }

            return Session(
                record = record,
                detail = "AudioPolicy LOOP_BACK|RENDER → REMOTE_SUBMIX ${sampleRate}Hz ${channels}ch"
            ) {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching {
                    AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(audioManager, policy)
                }.recoverCatching {
                    AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(audioManager, policy)
                }
            }
        } catch (t: Throwable) {
            runCatching {
                AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(audioManager, policy)
            }.recoverCatching {
                AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(audioManager, policy)
            }
            throw t
        }
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
