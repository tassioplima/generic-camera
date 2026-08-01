package com.tassiolima.rawcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tassiolima.rawcam.camera.CameraUiState
import com.tassiolima.rawcam.camera.PhotoAspectRatio
import com.tassiolima.rawcam.camera.VideoSizeOption

@Composable
fun SettingsSheet(
    state: CameraUiState,
    onRawToggle: (Boolean) -> Unit,
    onAspectRatioChange: (PhotoAspectRatio) -> Unit,
    onGridToggle: (Boolean) -> Unit,
    onShutterSoundToggle: (Boolean) -> Unit,
    onShutterFlashToggle: (Boolean) -> Unit,
    onVideoSettingsChange: (VideoSizeOption, Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Configurações", fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

        Text("Foto", fontWeight = FontWeight.Bold)

        Text("Proporção", modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            state.availableAspectRatios.forEach { ratio ->
                Chip(label = ratio.label, selected = ratio == state.photoAspectRatio) {
                    onAspectRatioChange(ratio)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Grade de composição")
            Switch(checked = state.gridEnabled, onCheckedChange = onGridToggle)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("RAW (.dng) sem processamento")
                Text(
                    if (state.rawSupported) "Grava JPEG + RAW a cada foto" else "Este aparelho não suporta RAW",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = state.rawEnabled, onCheckedChange = onRawToggle, enabled = state.rawSupported)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Som do obturador")
            Switch(checked = state.shutterSoundEnabled, onCheckedChange = onShutterSoundToggle)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Flash na tela ao capturar")
            Switch(checked = state.shutterFlashEnabled, onCheckedChange = onShutterFlashToggle)
        }

        HorizontalDivider()

        Text("Vídeo", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Resolução", modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            state.videoSizeOptions.forEach { option ->
                val selected = option == state.selectedVideoSize
                Chip(label = option.label, selected = selected) {
                    val fps = if (state.selectedFps in option.fpsOptions) state.selectedFps else option.fpsOptions.first()
                    onVideoSettingsChange(option, fps)
                }
            }
        }

        val currentOption = state.selectedVideoSize
        if (currentOption != null) {
            Text("Quadros por segundo", modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                currentOption.fpsOptions.forEach { fps ->
                    Chip(label = "${fps}fps", selected = fps == state.selectedFps) {
                        onVideoSettingsChange(currentOption, fps)
                    }
                }
            }
        }

        TextButton(onClick = onClose, modifier = Modifier.padding(top = 24.dp)) {
            Text("Fechar")
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primary
    else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg)
    }
}
