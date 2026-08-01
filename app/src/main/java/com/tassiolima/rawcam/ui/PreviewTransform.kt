package com.tassiolima.rawcam.ui

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import android.view.TextureView
import kotlin.math.max

/**
 * Scales the TextureView's buffer to fill the view without distortion (center-crop), instead
 * of the default behaviour of stretching the raw buffer to the view's bounds. The buffer's
 * sensor-native size is landscape-shaped even in portrait mode; the platform already delivers
 * it right-side-up for TextureView, so only a scale correction is needed here (no rotation).
 */
fun applyPreviewTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    bufferSize: Size,
    sensorOrientation: Int,
) {
    if (viewWidth == 0 || viewHeight == 0) return

    val swapped = sensorOrientation % 180 != 0
    val bufferWidth = if (swapped) bufferSize.height else bufferSize.width
    val bufferHeight = if (swapped) bufferSize.width else bufferSize.height
    if (bufferWidth == 0 || bufferHeight == 0) return

    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

    val matrix = Matrix()
    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
    val scale = max(viewWidth.toFloat() / bufferWidth, viewHeight.toFloat() / bufferHeight)
    matrix.postScale(scale, scale, centerX, centerY)

    textureView.setTransform(matrix)
}
