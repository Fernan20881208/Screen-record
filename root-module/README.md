# Zaid Screen Recorder Privileged Audio module

This Magisk / KernelSU style module is built together with the public release APK.

It mounts the **same release APK** at:

`/system/priv-app/ZaidScreenRecorder/ZaidScreenRecorder.apk`

and installs a matching `privapp-permissions` allowlist granting the permissions required by Android's dynamic AudioPolicy Remote Submix path:

- `android.permission.MODIFY_AUDIO_ROUTING`
- `android.permission.CAPTURE_AUDIO_OUTPUT`
- `android.permission.CAPTURE_MEDIA_OUTPUT`
- `android.permission.BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` when the ROM provides it

After flashing, reboot before opening the app. The app checks the permissions at runtime and will not claim AudioPolicy capture is available until Android has actually granted them.

The internal-audio backend uses `AudioMix` with `ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER`, creates an `AudioRecord` sink from the registered `AudioPolicy`, and then mixes that PCM stream with the microphone before AAC encoding. MediaProjection is not used.
