package com.tassiolima.rawcam.ui

import android.graphics.SurfaceTexture
import android.util.Size as AndroidSize
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tassiolima.rawcam.MainViewModel
import com.tassiolima.rawcam.camera.CameraMode
import com.tassiolima.rawcam.camera.FlashMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    var showZoomHud by remember { mutableStateOf(false) }
    var hideZoomHudJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun pingZoomHud() {
        showZoomHud = true
        hideZoomHudJob?.cancel()
        hideZoomHudJob = scope.launch {
            kotlinx.coroutines.delay(900)
            showZoomHud = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) },
    ) {
        CameraPreview(
            previewBufferSize = state.previewBufferSize,
            sensorOrientation = state.sensorOrientation,
            onSurfaceAvailable = { texture, w, h -> viewModel.onPreviewSurfaceAvailable(texture, w, h) },
            onSurfaceDestroyed = { viewModel.onPreviewSurfaceDestroyed() },
            onTap = { x, y ->
                if (state.focusIndicator?.locked == true) viewModel.clearFocusLock() else viewModel.focusTap(x, y)
            },
            onLongPress = { x, y -> viewModel.focusLongPressLock(x, y) },
            onExposureDrag = { deltaSteps -> viewModel.adjustLockedExposure(deltaSteps) },
            onZoomDelta = { factor ->
                val current = viewModel.state.value.zoomRatio
                viewModel.setZoom(current * factor)
                pingZoomHud()
            },
        )

        if (state.gridEnabled) {
            GridOverlay(viewSize.width, viewSize.height, state.photoAspectRatio)
        }
        AspectRatioMask(viewSize.width, viewSize.height, state.photoAspectRatio)

        val flashAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (viewModel.shutterFlash) 0.85f else 0f,
            animationSpec = androidx.compose.animation.core.tween(if (viewModel.shutterFlash) 40 else 250),
            label = "shutterFlash",
        )
        if (flashAlpha > 0f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
        }
        state.focusIndicator?.let { indicator ->
            FocusReticle(
                indicator,
                state.focusState,
                state.exposureCompensation,
                state.minExposureCompensation,
                state.maxExposureCompensation,
                state.exposureCompensationStep,
            )
        }

        // Top bar: flash + settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Night photos use the vendor's multi-frame pipeline, which never fires the flash.
            if (state.mode != CameraMode.NIGHT) {
                IconButton(onClick = { viewModel.setFlashMode(nextFlashMode(state.flashMode)) }) {
                    Icon(flashIcon(state.flashMode), contentDescription = "Flash", tint = Color.White)
                }
            } else {
                Box(modifier = Modifier.size(48.dp))
            }

            if (state.mode.isVideo) {
                val highSpeed = state.mode == CameraMode.VIDEO &&
                    state.selectedVideoSize?.isHighSpeed(state.selectedFps) == true
                val nightOn = state.nightVideoEnabled && !highSpeed
                IconButton(
                    onClick = { viewModel.setNightVideoEnabled(!state.nightVideoEnabled) },
                    enabled = !highSpeed && !state.isRecording,
                ) {
                    Icon(
                        Icons.Filled.NightsStay,
                        contentDescription = if (nightOn) "Vídeo noturno ligado" else "Vídeo noturno desligado",
                        tint = when {
                            highSpeed -> Color.White.copy(alpha = 0.3f)
                            nightOn -> Color(0xFFFFC107)
                            else -> Color.White
                        },
                    )
                }
            }

            IconButton(onClick = { showSettings = true }, enabled = !state.isRecording) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Configurações",
                    tint = if (state.isRecording) Color.White.copy(alpha = 0.3f) else Color.White,
                )
            }
        }

        if (state.mode == CameraMode.NIGHT || (state.mode.isVideo && state.nightVideoEnabled && !state.isHighSpeedRecording)) {
            val hint = when {
                state.mode == CameraMode.NIGHT && state.nightExtensionSupported -> "Modo noite • segure o celular firme"
                state.mode == CameraMode.NIGHT -> "Modo noite (básico) • segure firme"
                else -> "Vídeo noturno"
            }
            Text(
                hint,
                color = Color(0xFFFFC107),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        state.nightCaptureProgress?.let { progress ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (progress >= 0) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { progress / 100f },
                        color = Color(0xFFFFC107),
                    )
                } else {
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFFFC107))
                }
                Text(
                    "Mantenha o celular parado…",
                    color = Color.White,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        viewModel.infoMessage?.let { message ->
            Text(
                message,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 140.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.isRecording) {
                val label = if (state.mode == CameraMode.TIMELAPSE) {
                    // Real time filmed -> how long it will play back for.
                    "${formatElapsed(viewModel.elapsedMs)}  →  ${formatElapsed(viewModel.elapsedMs / state.timelapseSpeed)} no vídeo"
                } else {
                    formatElapsed(viewModel.elapsedMs)
                }
                Text(
                    text = label,
                    color = Color.Red,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (state.mode == CameraMode.TIMELAPSE && !state.isRecording) {
                TimelapseSpeedRow(
                    selected = state.timelapseSpeed,
                    onSelect = { viewModel.setTimelapseSpeed(it) },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (state.zoomPresets.size > 1) {
                androidx.compose.animation.AnimatedVisibility(visible = showZoomHud) {
                    ZoomScaleHud(
                        min = state.minZoomRatio,
                        max = state.maxZoomRatio,
                        presets = state.zoomPresets,
                        current = state.zoomRatio,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                ZoomPresetRow(
                    presets = state.zoomPresets,
                    current = state.zoomRatio,
                    onSelect = {
                        viewModel.setZoom(it)
                        pingZoomHud()
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            ModeToggle(
                mode = state.mode,
                enabled = !state.isRecording && state.nightCaptureProgress == null,
                onModeChange = { viewModel.setMode(it) },
            )

            Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                IconButton(
                    onClick = { viewModel.switchCamera() },
                    enabled = state.hasFrontCamera && !state.isRecording && state.ready &&
                        state.nightCaptureProgress == null,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp),
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Trocar câmera", tint = Color.White)
                }

                CaptureButton(
                    mode = state.mode,
                    isRecording = state.isRecording,
                    busy = state.nightCaptureProgress != null,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = {
                        if (state.mode.isVideo) viewModel.toggleRecording() else viewModel.takePhoto()
                    },
                )

                if (state.lastCapture != null) {
                    ThumbnailBubble(
                        uri = state.lastCapture!!.uri,
                        isVideo = state.lastCapture!!.isVideo,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                        enabled = !state.isRecording,
                        onClick = { viewModel.openReview(state.lastCapture!!) },
                    )
                }
            }
        }

        state.errorMessage?.let {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = it, color = Color.Red)
                androidx.compose.material3.TextButton(onClick = { viewModel.retryOpenCamera() }) {
                    Text("Tentar de novo", color = Color.White)
                }
            }
        }
    }

    if (showSettings) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1C1C1E).copy(alpha = 0.92f),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            SettingsSheet(
                state = state,
                onRawToggle = { viewModel.setRawEnabled(it) },
                onAspectRatioChange = { viewModel.setPhotoAspectRatio(it) },
                onGridToggle = { viewModel.setGridEnabled(it) },
                onShutterSoundToggle = { viewModel.setShutterSoundEnabled(it) },
                onShutterFlashToggle = { viewModel.setShutterFlashEnabled(it) },
                onVideoSettingsChange = { size, fps -> viewModel.setVideoSettings(size, fps) },
                onNightVideoToggle = { viewModel.setNightVideoEnabled(it) },
                onClose = {
                    scope.launch { sheetState.hide() }
                    showSettings = false
                },
            )
        }
    }
}

/** Pixels of vertical drag needed to move exposure compensation by one step. */
private const val PX_PER_EXPOSURE_STEP = 30f
private const val TAP_SLOP_PX = 24f

@Composable
private fun CameraPreview(
    previewBufferSize: AndroidSize?,
    sensorOrientation: Int,
    onSurfaceAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onExposureDrag: (Int) -> Unit,
    onZoomDelta: (Float) -> Unit,
) {
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var lastWidth by remember { mutableStateOf(0) }
    var lastHeight by remember { mutableStateOf(0) }

    LaunchedEffect(previewBufferSize, sensorOrientation, lastWidth, lastHeight) {
        val view = textureViewRef ?: return@LaunchedEffect
        val buffer = previewBufferSize ?: return@LaunchedEffect
        applyPreviewTransform(view, lastWidth, lastHeight, buffer, sensorOrientation)
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onTap, onLongPress, onExposureDrag) {
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val downTimeMs = down.uptimeMillis

                    // Race three outcomes by polling raw events instead of using
                    // detectTapGestures/waitForUpOrCancellation, because those only watch a
                    // single pointer - a second finger landing (pinch-to-zoom) needs to cancel
                    // this gesture immediately, otherwise the first finger's own wobble during
                    // the pinch gets misread as a long-press drag and nudges exposure/brightness.
                    var multiTouch = false
                    var released = false
                    var longPressed = false
                    var lastChange = down
                    while (!multiTouch && !released && !longPressed) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            multiTouch = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            released = true
                            if (change != null) lastChange = change
                            break
                        }
                        lastChange = change
                        if (change.uptimeMillis - downTimeMs >= longPressTimeoutMs) {
                            longPressed = true
                        }
                    }

                    if (multiTouch) {
                        // Do nothing - the sibling pointerInput below owns pinch-to-zoom from here.
                    } else if (released) {
                        val moved = (lastChange.position - down.position).getDistance()
                        if (moved < TAP_SLOP_PX) onTap(lastChange.position.x, lastChange.position.y)
                    } else {
                        onLongPress(down.position.x, down.position.y)
                        var accumulatedDy = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            accumulatedDy += change.position.y - change.previousPosition.y
                            while (accumulatedDy <= -PX_PER_EXPOSURE_STEP) {
                                onExposureDrag(1)
                                accumulatedDy += PX_PER_EXPOSURE_STEP
                            }
                            while (accumulatedDy >= PX_PER_EXPOSURE_STEP) {
                                onExposureDrag(-1)
                                accumulatedDy -= PX_PER_EXPOSURE_STEP
                            }
                            change.consume()
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> onZoomDelta(zoom) }
            }
            .onSizeChanged { lastWidth = it.width; lastHeight = it.height },
        factory = { ctx ->
            TextureView(ctx).apply {
                textureViewRef = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        lastWidth = width
                        lastHeight = height
                        onSurfaceAvailable(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        lastWidth = width
                        lastHeight = height
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        // Stop the camera streaming into this texture before it's released.
                        onSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        },
    )
}

private fun formatZoomLabel(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else "${rounded}x"
}

/**
 * A dashed horizontal ruler from [min] to [max] (log-scaled, since zoom ratios are
 * multiplicative - a linear scale would crush 0.6x-3x into a sliver next to 10x), with a tick
 * per zoom preset and a solid marker for where you are right now. Only meant to be visible
 * transiently while the user is actively changing zoom.
 */
@Composable
private fun ZoomScaleHud(
    min: Float,
    max: Float,
    presets: List<com.tassiolima.rawcam.camera.ZoomPreset>,
    current: Float,
    modifier: Modifier = Modifier,
) {
    if (max <= min) return
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()

    fun fraction(ratio: Float): Float {
        val logMin = kotlin.math.ln(min.toDouble())
        val logMax = kotlin.math.ln(max.toDouble())
        val logV = kotlin.math.ln(ratio.coerceIn(min, max).toDouble())
        return ((logV - logMin) / (logMax - logMin)).toFloat()
    }

    androidx.compose.foundation.Canvas(modifier = modifier.height(40.dp)) {
        val lineY = size.height - 10.dp.toPx()
        val lineColor = Color.White.copy(alpha = 0.85f)

        drawLine(
            color = lineColor.copy(alpha = 0.4f),
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )

        presets.forEach { preset ->
            val x = fraction(preset.ratio) * size.width
            val tickHeight = if (preset.isOptical) 12.dp.toPx() else 7.dp.toPx()
            drawLine(
                color = if (preset.isOptical) Color(0xFFFFC107) else lineColor,
                start = Offset(x, lineY - tickHeight),
                end = Offset(x, lineY),
                strokeWidth = if (preset.isOptical) 2.5.dp.toPx() else 1.5.dp.toPx(),
            )
        }

        val markerX = (fraction(current) * size.width).coerceIn(0f, size.width)
        drawLine(
            color = Color.White,
            start = Offset(markerX, lineY - 18.dp.toPx()),
            end = Offset(markerX, lineY + 4.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(markerX, lineY - 18.dp.toPx()))

        val label = formatZoomLabel(current)
        val measured = textMeasurer.measure(
            androidx.compose.ui.text.AnnotatedString(label),
            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
        )
        val labelX = (markerX - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
        drawText(measured, topLeft = Offset(labelX, 0f))
    }
}

@Composable
private fun ZoomPresetRow(
    presets: List<com.tassiolima.rawcam.camera.ZoomPreset>,
    current: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { preset ->
            val selected = kotlin.math.abs(current - preset.ratio) < 0.05f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // A lens dot marks ratios that land on a real physical lens (native optical
                // quality) instead of a digital crop - lets people pick knowing which is which.
                Box(
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .size(4.dp)
                        .background(
                            if (preset.isOptical) Color(0xFFFFC107) else Color.Transparent,
                            CircleShape,
                        ),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Color.White else Color.Transparent)
                        .clickable { onSelect(preset.ratio) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        formatZoomLabel(preset.ratio),
                        color = if (selected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelapseSpeedRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.tassiolima.rawcam.camera.TIMELAPSE_SPEEDS.forEach { speed ->
            val isSelected = speed == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFFFC107) else Color.Transparent)
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "${speed}x",
                    color = if (isSelected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: CameraMode, enabled: Boolean, onModeChange: (CameraMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .padding(4.dp),
    ) {
        CameraMode.entries.forEach { m ->
            val selected = mode == m
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(enabled = enabled) { onModeChange(m) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(m.label, color = if (selected) Color.Black else Color.White)
            }
        }
    }
}

@Composable
private fun CaptureButton(
    mode: CameraMode,
    isRecording: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ringColor = Color.White
    val fillColor = when {
        mode.isVideo -> Color(0xFFE53935)
        busy -> Color.White.copy(alpha = 0.4f)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.Transparent),
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (isRecording) 32.dp else 60.dp)
                    .clip(if (isRecording) androidx.compose.foundation.shape.RoundedCornerShape(6.dp) else CircleShape)
                    .background(fillColor),
                contentAlignment = Alignment.Center,
            ) {
                if (mode == CameraMode.NIGHT) {
                    Icon(Icons.Filled.NightsStay, contentDescription = null, tint = Color(0xFF1C1C1E))
                }
            }
        }
    }
}

@Composable
private fun ThumbnailBubble(
    uri: android.net.Uri,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.loadThumbnail(uri, AndroidSize(160, 160), null)
            }
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.DarkGray)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Última captura",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        if (isVideo) {
            Text("▶", color = Color.White)
        }
    }
}

private fun nextFlashMode(current: FlashMode): FlashMode = when (current) {
    FlashMode.OFF -> FlashMode.AUTO
    FlashMode.AUTO -> FlashMode.ON
    FlashMode.ON -> FlashMode.TORCH
    FlashMode.TORCH -> FlashMode.OFF
}

private fun flashIcon(mode: FlashMode) = when (mode) {
    FlashMode.OFF -> Icons.Filled.FlashOff
    FlashMode.AUTO -> Icons.Filled.FlashAuto
    FlashMode.ON -> Icons.Filled.FlashOn
    FlashMode.TORCH -> Icons.Filled.FlashlightOn
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
