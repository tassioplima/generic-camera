package com.tassiolima.rawcam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Size

/**
 * A resolution the camera can record video at, with the frame rates it can actually sustain.
 *
 * [highSpeedFpsOptions] are only reachable through a constrained high-speed capture session
 * (a separate, more restrictive pipeline - no RAW, no manual focus/exposure region changes
 * while it's active). Many phones only expose fixed rates above 30fps this way; if this
 * device also supports a fast rate through a normal session, it shows up in both lists but
 * [isHighSpeed] treats it as normal since the restrictions don't apply.
 */
data class VideoSizeOption(
    val size: Size,
    val normalFpsOptions: List<Int>,
    val highSpeedFpsOptions: List<Int> = emptyList(),
) {
    val label: String
        get() = when {
            size.height >= 2160 -> "4K"
            size.height >= 1440 -> "1440p"
            size.height >= 1080 -> "1080p"
            size.height >= 720 -> "720p"
            else -> "${size.width}x${size.height}"
        }

    val fpsOptions: List<Int>
        get() = (normalFpsOptions + highSpeedFpsOptions).distinct().sorted()

    fun isHighSpeed(fps: Int): Boolean = fps !in normalFpsOptions && fps in highSpeedFpsOptions
}

/**
 * A quick-zoom shortcut. [isOptical] means this ratio lands on (or very near) an actual
 * physical lens switch - computed from real focal length + sensor size, not guessed - so the
 * image quality at that ratio is native-resolution, not a digital crop upscaled to fit.
 */
data class ZoomPreset(val ratio: Float, val isOptical: Boolean)

/** Everything the app needs to know about a single physical camera (front or back). */
data class CameraProfile(
    val cameraId: String,
    val lensFacing: Int,
    val sensorOrientation: Int,
    val supportsRaw: Boolean,
    val jpegSizes: List<Size>,
    val rawSize: Size?,
    val videoSizes: List<VideoSizeOption>,
    val aeFpsRanges: List<android.util.Range<Int>>,
    val zoomRatioRange: android.util.Range<Float>,
    val activeArraySize: android.graphics.Rect,
    val exposureCompensationRange: android.util.Range<Int>,
    val exposureCompensationStep: Float,
    val zoomPresets: List<ZoomPreset>,
    val hasFlash: Boolean,
    val flashStrengthMaxLevel: Int,
    val flashStrengthDefaultLevel: Int,
)

object CameraCapabilities {

    /** Finds the first camera id facing the given direction (CameraCharacteristics.LENS_FACING_*). */
    fun findCameraId(manager: CameraManager, facing: Int): String? {
        return manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    fun loadProfile(manager: CameraManager, cameraId: String): CameraProfile {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera $cameraId has no stream configuration map")

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsRaw = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        )

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: emptyList()

        val rawSize = if (supportsRaw) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
        } else null

        val videoSizes = buildVideoSizeOptions(map)

        val aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()

        val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?: android.util.Range(1f, 1f)
        val activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: android.graphics.Rect(0, 0, 4000, 3000)

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: android.util.Range(0, 0)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.let { it.numerator.toFloat() / it.denominator } ?: 1f

        val zoomPresets = computeZoomPresets(manager, chars, zoomRange)

        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val flashStrengthMax = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        val flashStrengthDefault = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1

        return CameraProfile(
            cameraId = cameraId,
            lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            supportsRaw = supportsRaw,
            jpegSizes = jpegSizes,
            rawSize = rawSize,
            videoSizes = videoSizes,
            aeFpsRanges = aeFpsRanges,
            zoomRatioRange = zoomRange,
            activeArraySize = activeArraySize,
            exposureCompensationRange = evRange,
            exposureCompensationStep = evStep,
            zoomPresets = zoomPresets,
            hasFlash = hasFlash,
            flashStrengthMaxLevel = flashStrengthMax,
            flashStrengthDefaultLevel = flashStrengthDefault,
        )
    }

    /**
     * The real optical transition ratios for this camera's physical lenses, computed from
     * each one's actual focal length + sensor physical size (field-of-view math), not just
     * the focal-length ratio alone - two lenses with the same focal length but different
     * sensor sizes still have different effective zoom, and vice versa. Filled out with a
     * few standard digital-zoom stops so there's always something to tap between lenses.
     */
    private fun computeZoomPresets(
        manager: CameraManager,
        chars: CameraCharacteristics,
        zoomRange: android.util.Range<Float>,
    ): List<ZoomPreset> {
        fun halfFovTan(focalMm: Float, sensorWidthMm: Float): Double =
            kotlin.math.tan(kotlin.math.atan((sensorWidthMm / 2.0) / focalMm))

        val mainFocal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val mainSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val mainTanHalfFov = if (mainFocal != null && mainSize != null) halfFovTan(mainFocal, mainSize.width) else null

        val opticalRatios = sortedSetOf(1f)
        if (mainTanHalfFov != null) {
            for (physId in chars.physicalCameraIds) {
                val pc = runCatching { manager.getCameraCharacteristics(physId) }.getOrNull() ?: continue
                val pFocal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val pSize = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val pTanHalfFov = halfFovTan(pFocal, pSize.width)
                if (pTanHalfFov <= 0.0) continue
                val ratio = (mainTanHalfFov / pTanHalfFov).toFloat()
                if (ratio in zoomRange) opticalRatios.add(ratio)
            }
        }

        val digitalCandidates = listOf(0.5f, 1f, 2f, 3f, 5f, 10f).filter { it in zoomRange }
        val presets = mutableListOf<ZoomPreset>()
        presets += zoomRange.lower.let { lower ->
            ZoomPreset(lower, opticalRatios.any { kotlin.math.abs(it - lower) < 0.05f })
        }
        opticalRatios.forEach { ratio -> presets += ZoomPreset(ratio, true) }
        digitalCandidates.forEach { ratio ->
            if (presets.none { kotlin.math.abs(it.ratio - ratio) < 0.15f }) {
                presets += ZoomPreset(ratio, false)
            }
        }
        // Round to the same one-decimal granularity the UI displays (formatZoomLabel), so a
        // hardware zoomRange.lower of 0.6 and a computed ultra-wide ratio of 0.6258 - both
        // real, both optical, just from slightly different sources - collapse into one "0.6x"
        // button instead of showing as two visually-identical duplicates.
        return presets.distinctBy { (it.ratio * 10).roundToIntOrZero() }.sortedBy { it.ratio }
    }

    private fun Float.roundToIntOrZero(): Int = if (isFinite()) Math.round(this) else 0

    /** Which of our two offered aspect ratios this camera can actually produce a JPEG for. */
    fun availableAspectRatios(profile: CameraProfile): List<PhotoAspectRatio> {
        return PhotoAspectRatio.entries.filter { ratio -> bestJpegSize(profile, ratio) != null }
    }

    /** Largest JPEG output size matching the requested aspect ratio (within a small tolerance). */
    fun bestJpegSize(profile: CameraProfile, ratio: PhotoAspectRatio): Size? {
        return profile.jpegSizes
            .filter { kotlin.math.abs((it.width.toFloat() / it.height) - ratio.ratio) < 0.05f }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * We only offer 1080p/4K (the ones the user asked for). For each we compute the real fps
     * a normal session can sustain via getOutputMinFrameDuration (never assuming every phone
     * can do 60fps at every resolution), and separately check the constrained high-speed
     * sizes/ranges for fixed rates like 120/240fps that many phones only expose that way.
     */
    private fun buildVideoSizeOptions(map: StreamConfigurationMap): List<VideoSizeOption> {
        val candidateSizes = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        val highSpeedSizes = map.highSpeedVideoSizes?.toList() ?: emptyList()
        val targets = listOf(Size(3840, 2160), Size(1920, 1080))

        return targets.mapNotNull { target ->
            val match = candidateSizes.firstOrNull { it == target } ?: return@mapNotNull null

            val normalFps = sortedSetOf(30)
            val minFrameDurationNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, match)
            if (minFrameDurationNs > 0) {
                // Round rather than truncate: an exact 60fps duration (16.666...ms) truncates
                // to 59 with integer division, which would hide a genuine 60fps normal mode.
                val maxFps = Math.round(1_000_000_000.0 / minFrameDurationNs).toInt()
                if (maxFps >= 60) normalFps.add(60)
            }

            val highSpeedFps = sortedSetOf<Int>()
            if (highSpeedSizes.contains(match)) {
                map.getHighSpeedVideoFpsRangesFor(match)?.forEach { range ->
                    if (range.lower == range.upper) highSpeedFps.add(range.upper)
                }
            }

            VideoSizeOption(match, normalFps.toList(), highSpeedFps.toList())
        }
    }

    /** Picks the AE target fps range that best matches the requested fixed frame rate. */
    fun bestFpsRange(profile: CameraProfile, desiredFps: Int): android.util.Range<Int> {
        return profile.aeFpsRanges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
            ?: profile.aeFpsRanges.filter { it.upper >= desiredFps }
                .minByOrNull { it.upper - it.lower }
            ?: android.util.Range(desiredFps, desiredFps)
    }
}
