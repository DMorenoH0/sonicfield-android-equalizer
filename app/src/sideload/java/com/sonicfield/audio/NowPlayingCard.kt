package com.sonicfield.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.app.Activity

internal fun openNowPlayingSettings(activity: Activity) = Unit

@Composable
internal fun NowPlayingCard(currentAudioLabel: String, onEnableMusicDetection: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923))) {
        Column(Modifier.padding(18.dp)) {
            Text("REPRODUCCIÓN EN SONICFIELD", color = Color(0xFF69E6C1), style = MaterialTheme.typography.labelMedium)
            Text(currentAudioLabel, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("El APK descargado desde GitHub no solicita acceso sensible a notificaciones. La detección de canciones de otras apps no está disponible en esta versión.",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
