package com.sonicfield.audio

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal fun openNowPlayingSettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

@Composable
internal fun NowPlayingCard(currentAudioLabel: String, onEnableMusicDetection: () -> Unit) {
    val track = NowPlayingState.track.value
    val authorized = NowPlayingState.listenerEnabled.value
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MÚSICA EN REPRODUCCIÓN", color = Color(0xFF69E6C1), style = MaterialTheme.typography.labelMedium)
            when {
                !authorized -> {
                    Text("Activa la detección multimedia", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Android requiere autorización especial para consultar metadatos de sesiones multimedia.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onEnableMusicDetection) { Text("Abrir ajustes de acceso") }
                }
                track == null -> {
                    Text("Esperando una sesión multimedia activa…", color = Color.White)
                    Text("Solo aparecen apps que publican título y estado a Android.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text(track.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(track.artist, track.album).joinToString(" · ").ifBlank { "Artista no disponible" }, color = Color.LightGray)
                    Text("${if (track.isPlaying) "Reproduciendo" else "En pausa"}${track.sourceApp?.let { " · $it" }.orEmpty()}", color = Color(0xFF69E6C1), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("Consulta metadatos; no usa micrófono ni reconoce la huella sonora. El ecualizador solo procesa el audio de SonicField.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
