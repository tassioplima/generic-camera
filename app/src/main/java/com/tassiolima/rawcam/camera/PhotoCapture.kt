package com.tassiolima.rawcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Builds the still-capture request and writes the resulting JPEG/RAW frames to disk.
 *
 * Noise reduction and edge enhancement are explicitly turned off so the JPEG is as close as
 * possible to a straight sensor-pipeline output, with no extra "smart" processing layered on
 * top by this app (auto-exposure/auto-white-balance stay on since those are basic exposure
 * correctness, not a stylistic filter).
 */
object PhotoCapture {

    fun buildCaptureRequest(
        device: CameraDevice,
        jpegSurface: Surface,
        rawSurface: Surface?,
        jpegOrientation: Int,
        flashMode: FlashMode,
        zoomRatio: Float,
        exposureCompensation: Int = 0,
        afRegions: Array<android.hardware.camera2.params.MeteringRectangle>?,
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpegSurface)
        rawSurface?.let { builder.addTarget(it) }

        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, flashMode.toAeMode())
        if (flashMode == FlashMode.TORCH) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        afRegions?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, it)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, it)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
        return builder.build()
    }

    fun saveJpeg(context: Context, image: Image, displayName: String): Uri {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return MediaStoreSaver.saveImage(context, bytes, "$displayName.jpg", "image/jpeg")
    }

    fun saveRaw(
        context: Context,
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        displayName: String,
    ): Uri {
        val dngCreator = DngCreator(characteristics, captureResult)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$displayName.dng")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/RawCam"
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore entry for RAW")
        resolver.openOutputStream(uri)?.use { out ->
            dngCreator.writeImage(out, image)
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        dngCreator.close()
        return uri
    }
}

enum class FlashMode {
    OFF, ON, AUTO, TORCH;

    fun toAeMode(): Int = when (this) {
        OFF -> CaptureRequest.CONTROL_AE_MODE_ON
        ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        TORCH -> CaptureRequest.CONTROL_AE_MODE_ON
    }
}
