package com.zaid.screenrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zaid.screenrecorder.audio.AudioCaptureEngine
import com.zaid.screenrecorder.audio.AudioFlingerBackend
import com.zaid.screenrecorder.audio.MicrophoneBackend
import com.zaid.screenrecorder.audio.RootAudioBackend
import com.zaid.screenrecorder.audio.VendorAudioBackend
import com.zaid.screenrecorder.core.AppState
import com.zaid.screenrecorder.diagnostics.DiagnosticsExporter
import com.zaid.screenrecorder.root.RootManager
import com.zaid.screenrecorder.video.DisplayCapabilityDetector
import com.zaid.screenrecorder.video.EncoderCapabilityDetector
import com.zaid.screenrecorder.video.SystemScreenRecordBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZaidApp(
                onStart = { fps, bitrate ->
                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "Permite Mostrar sobre otras apps para usar los controles flotantes.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, RecordingService::class.java)
                                .setAction(RecordingService.ACTION_START)
                                .putExtra(RecordingService.EXTRA_FPS, fps)
                                .putExtra(RecordingService.EXTRA_BITRATE, bitrate)
                        )
                        moveTaskToBack(true)
                    }
                },
                onStop = { startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
            )
        }
    }
}

private data class UiCapabilities(val root: Boolean, val rootLabel: String, val supportedFps: Set<Int>, val detail: String)

@Composable
private fun ZaidApp(onStart: (Int, Int) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val status by AppState.recording.collectAsState()
    var caps by remember { mutableStateOf<UiCapabilities?>(null) }
    var selectedFps by remember { mutableIntStateOf(60) }
    var selectedBitrate by remember { mutableIntStateOf(8_000_000) }
    var profile by remember { mutableStateOf("Eficiente") }

    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) {
            val root = RootManager()
            val state = root.detect()
            if (!state.available) UiCapabilities(false, "Sin root", emptySet(), state.detail)
            else {
                val display = DisplayCapabilityDetector(context, root).detect()
                val encoders = EncoderCapabilityDetector().detect()
                val backend = SystemScreenRecordBackend(root).probe(display, encoders)
                val supported = backend.supportedFrameRates
                    .intersect(encoders.supportedFps(com.zaid.screenrecorder.core.VideoCodec.AVC))
                    .intersect(display.refreshCandidates())
                UiCapabilities(true, state.implementation.name, supported, backend.detail)
            }
        }
        caps = detected
        if (selectedFps !in detected.supportedFps) selectedFps = listOf(120, 90, 60, 30).firstOrNull { it in detected.supportedFps } ?: 60
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF080A10), Color(0xFF11182A), Color(0xFF080A10))))) {
            Box(Modifier.size(300.dp).align(Alignment.TopEnd).blur(if (status.active) 0.dp else 80.dp).background(Color(0x554D7CFE), CircleShape))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        painter = painterResource(R.drawable.zaid_logo),
                        contentDescription = "Zaid Screen Recorder",
                        modifier = Modifier.size(58.dp).clip(CircleShape)
                    )
                    Column {
                        Text("Zaid Screen Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                status.paused -> "${status.effectiveConfig.height}p · ${status.effectiveConfig.fps} FPS target · PAUSA"
                                status.active -> "${status.effectiveConfig.height}p · ${status.effectiveConfig.fps} FPS target · grabando"
                                else -> "720p · $selectedFps FPS · Audio interno"
                            },
                            color = Color.White.copy(alpha = .72f)
                        )
                    }
                }

                caps?.let { c ->
                    if (!c.root) GlassCard { Text("Zaid Screen Recorder requiere acceso root para utilizar su motor de captura avanzado.", color = Color(0xFFFFB4AB)) }
                    GlassCard {
                        Text("ROOT · ${c.rootLabel}", fontWeight = FontWeight.SemiBold)
                        Text(c.detail, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Button(
                    onClick = { if (status.active) onStop() else onStart(selectedFps, selectedBitrate) },
                    enabled = caps?.root == true,
                    modifier = Modifier.fillMaxWidth().height(86.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (status.active) Color(0xFFB3261E) else Color(0xFFE53935))
                ) { Text(if (status.active) "■ DETENER" else "● GRABAR", fontWeight = FontWeight.Bold) }

                GlassCard {
                    Text("Perfiles", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { profile = "Eficiente"; selectedFps = if (60 in (caps?.supportedFps ?: emptySet())) 60 else 30; selectedBitrate = 8_000_000 }, enabled = !status.active) { Text("Eficiente") }
                        if (120 in (caps?.supportedFps ?: emptySet())) {
                            Button(onClick = { profile = "Calidad"; selectedFps = 120; selectedBitrate = 12_000_000 }, enabled = !status.active) { Text("Calidad") }
                            Button(onClick = { profile = "Ultra"; selectedFps = 120; selectedBitrate = 19_000_000 }, enabled = !status.active) { Text("Ultra") }
                        }
                    }
                    Text("Activo: $profile", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                }

                GlassCard {
                    Text("FPS", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90, 120).filter { it in (caps?.supportedFps ?: setOf(30, 60)) }.forEach { fps ->
                            Button(onClick = { profile = "Personalizado"; selectedFps = fps }, enabled = !status.active, colors = ButtonDefaults.buttonColors(containerColor = if (fps == selectedFps) Color(0xFF5B77FF) else Color.White.copy(alpha = .10f))) { Text("$fps") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniCard("Resolución", "1280×720", Modifier.weight(1f)); MiniCard("Bitrate", "${selectedBitrate / 1_000_000} Mbps", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniCard("Codec", "H.264 AVC", Modifier.weight(1f)); MiniCard("Audio", "48 kHz", Modifier.weight(1f))
                }
                GlassCard { Text("Grabaciones", fontWeight = FontWeight.Bold); Text("Los MP4 finalizados se guardan en la carpeta de la app y se indexan con MediaScanner.", color = Color.White.copy(alpha = .65f)) }
                GlassCard { Text("Estadísticas", fontWeight = FontWeight.Bold); Text(status.message ?: "Al finalizar se calculan FPS promedio y mínimo a partir de timestamps reales del MP4; dropped frames sólo se muestran si el backend reporta un contador verificable.", color = Color.White.copy(alpha = .65f)) }
                GlassCard {
                    Text("Overlay Liquid Glass", fontWeight = FontWeight.Bold)
                    Text("Activado por defecto. Incluye logo, tiempo, pausar, resumir y terminar. Puedes arrastrarlo y recuerda su última posición. La pausa usa segmentos para no depender de funciones inexistentes de screenrecord.", color = Color.White.copy(alpha = .65f))
                    if (!Settings.canDrawOverlays(context)) {
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }) { Text("Permitir overlay") }
                    }
                }
                GlassCard {
                    Text("Ajustes", fontWeight = FontWeight.Bold)
                    Text("Game Recording Mode activo · Liquid Glass se simplifica durante la grabación para priorizar el juego.", color = Color.White.copy(alpha = .65f))
                    Button(onClick = {
                        val root = RootManager()
                        val display = DisplayCapabilityDetector(context, root)
                        val enc = EncoderCapabilityDetector()
                        val audio = AudioCaptureEngine(listOf(RootAudioBackend(root), AudioFlingerBackend(root), VendorAudioBackend(root)), MicrophoneBackend(context))
                        val file = DiagnosticsExporter(context, root, display, enc, audio).export()
                        Toast.makeText(context, "Diagnóstico: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }) { Text("Exportar diagnóstico") }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(24.dp)).animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xAA1A2030),
        tonalElevation = 6.dp
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
private fun MiniCard(title: String, value: String, modifier: Modifier = Modifier) = GlassCard(modifier) {
    Text(title, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium)
    Text(value, fontWeight = FontWeight.Bold)
}
