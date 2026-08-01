package com.tassiolima.rawcam.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tassiolima.rawcam.camera.FocusIndicator
import com.tassiolima.rawcam.camera.FocusState
import com.tassiolima.rawcam.camera.PhotoAspectRatio
import kotlin.math.roundToInt

/** Computes the on-screen frame (in px) that corresponds to what will actually be captured. */
fun captureFrameRect(viewWidth: Float, viewHeight: Float, aspect: PhotoAspectRatio): Rect {
    val frameHeight = (viewWidth * aspect.ratio).coerceAtMost(viewHeight)
    val frameWidth = (viewHeight / aspect.ratio).coerceAtMost(viewWidth)
    return if (frameHeight <= viewHeight && frameWidth >= viewWidth) {
        val top = (viewHeight - frameHeight) / 2f
        Rect(0f, top, viewWidth, top + frameHeight)
    } else {
        val left = (viewWidth - frameWidth) / 2f
        Rect(left, 0f, left + frameWidth, viewHeight)
    }
}

/** Dims everything outside the actual capture frame for the selected aspect ratio. */
@Composable
fun AspectRatioMask(viewWidth: Float, viewHeight: Float, aspect: PhotoAspectRatio) {
    if (viewWidth <= 0f || viewHeight <= 0f) return
    val frame = captureFrameRect(viewWidth, viewHeight, aspect)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scrim = Color.Black.copy(alpha = 0.55f)
        if (frame.top > 0f) {
            drawRect(color = scrim, topLeft = Offset(0f, 0f), size = Size(size.width, frame.top))
        }
        if (frame.bottom < size.height) {
            drawRect(color = scrim, topLeft = Offset(0f, frame.bottom), size = Size(size.width, size.height - frame.bottom))
        }
        if (frame.left > 0f) {
            drawRect(color = scrim, topLeft = Offset(0f, frame.top), size = Size(frame.left, frame.height))
        }
        if (frame.right < size.width) {
            drawRect(color = scrim, topLeft = Offset(frame.right, frame.top), size = Size(size.width - frame.right, frame.height))
        }
    }
}

/** Rule-of-thirds composition grid, drawn within the actual capture frame. */
@Composable
fun GridOverlay(viewWidth: Float, viewHeight: Float, aspect: PhotoAspectRatio) {
    if (viewWidth <= 0f || viewHeight <= 0f) return
    val frame = captureFrameRect(viewWidth, viewHeight, aspect)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.6f)
        val strokeWidth = 1.dp.toPx()
        val x1 = frame.left + frame.width / 3f
        val x2 = frame.left + frame.width * 2f / 3f
        val y1 = frame.top + frame.height / 3f
        val y2 = frame.top + frame.height * 2f / 3f

        drawLine(lineColor, Offset(x1, frame.top), Offset(x1, frame.bottom), strokeWidth)
        drawLine(lineColor, Offset(x2, frame.top), Offset(x2, frame.bottom), strokeWidth)
        drawLine(lineColor, Offset(frame.left, y1), Offset(frame.right, y1), strokeWidth)
        drawLine(lineColor, Offset(frame.left, y2), Offset(frame.right, y2), strokeWidth)
    }
}

private val FocusSearchingColor = Color.White
private val FocusFocusedColor = Color(0xFF4CAF50)
private val FocusFailedColor = Color(0xFFF44336)
private val FocusLockedColor = Color(0xFFFFC107)

@Composable
fun FocusReticle(
    indicator: FocusIndicator,
    focusState: FocusState,
    exposureCompensation: Int,
    minEv: Int,
    maxEv: Int,
    evStep: Float,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { 72.dp.toPx() }

    // Locked (long-press) always reads as amber - that communicates "AE/AF locked", which
    // matters more here than the instantaneous search state. A plain tap reflects what
    // CONTROL_AF_STATE is actually doing: white while searching, green once it lands, red if
    // the scan gave up, instead of just assuming every tap focuses successfully.
    val targetColor = when {
        focusState == FocusState.FAILED -> FocusFailedColor
        indicator.locked -> FocusLockedColor
        focusState == FocusState.FOCUSED -> FocusFocusedColor
        else -> FocusSearchingColor
    }
    val color by animateColorAsState(targetColor, label = "focusReticleColor")
    val scale by animateFloatAsState(
        targetValue = if (focusState == FocusState.SEARCHING) 1.12f else 1f,
        animationSpec = tween(220),
        label = "focusReticleScale",
    )

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (indicator.xInView - sizePx / 2).roundToInt(),
                    (indicator.yInView - sizePx / 2).roundToInt(),
                )
            }
            .size(72.dp)
            .scale(scale)
            .border(1.5.dp, color, RoundedCornerShape(4.dp)),
    )

    if (indicator.locked && maxEv > minEv) {
        Box(
            modifier = Modifier.offset {
                IntOffset(
                    (indicator.xInView + sizePx / 2 + 12).roundToInt(),
                    (indicator.yInView - sizePx / 2).roundToInt(),
                )
            },
        ) {
            val evValue = (exposureCompensation * evStep * 10).roundToInt() / 10f
            val evText = when {
                evValue == 0f -> "EV 0"
                evValue > 0f -> "EV +$evValue"
                else -> "EV $evValue"
            }
            Text(evText, color = color)
        }
    }
}
