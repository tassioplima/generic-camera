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
        ois: Boolean = false,
        nightSceneFallback: Boolean = false,
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpegSurface)
        rawSurface?.let { builder.addTarget(it) }

        if (nightSceneFallback) {
            // Only reached on phones without a vendor night extension: the HAL's own night
            // scene tuning (longer exposure + its noise reduction) is the best we can ask for
            // from a single frame. Noise reduction stays on here on purpose - a dark,
            // un-denoised frame is mostly noise, which defeats the point of a night mode.
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
        } else {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        if (ois) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
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
        try {
            resolver.openOutputStream(uri)?.use { out ->
                dngCreator.writeImage(out, image)
            }
        } catch (e: Exception) {
            // Don't leave a half-written, permanently "pending" DNG behind in the gallery.
            resolver.delete(uri, null, null)
            throw e
        } finally {
            dngCreator.close()
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * On a logical multi-camera, the RAW frame comes from whichever physical lens was active
     * (ultra-wide/main/tele depending on zoom), and the DNG's color matrices, lens shading and
     * calibration should describe THAT sensor, not the logical camera's defaults. Only switch
     * when the physical sensor's pixel array actually matches the RAW buffer, since DngCreator
     * rejects a size mismatch outright.
     */
    fun dngCharacteristics(
        manager: android.hardware.camera2.CameraManager,
        logicalCameraId: String,
        result: TotalCaptureResult,
        image: Image,
    ): CameraCharacteristics {
        val logical = manager.getCameraCharacteristics(logicalCameraId)
        val physicalId = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            ?: return logical
        val physical = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull() ?: return logical
        val pixelArray = physical.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val preCorrection = physical.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val matches = (pixelArray != null && pixelArray.width == image.width && pixelArray.height == image.height) ||
            (preCorrection != null && preCorrection.width() == image.width && preCorrection.height() == image.height)
        return if (matches) physical else logical
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
