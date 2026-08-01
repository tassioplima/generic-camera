package com.tassiolima.rawcam.camera

import android.net.Uri
import android.util.Size

enum class CameraMode { PHOTO, VIDEO }

enum class PhotoAspectRatio(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_16_9(16f / 9f, "16:9"),
}

data class CapturedMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val timestampMs: Long,
)

/** Where the user tapped/held on the preview, for the focus/exposure reticle overlay. */
data class FocusIndicator(
    val xInView: Float,
    val yInView: Float,
    val locked: Boolean,
)

/** Mirrors CONTROL_AF_STATE so the reticle can show whether focus actually landed. */
enum class FocusState { SEARCHING, FOCUSED, FAILED }

data class CameraUiState(
    val ready: Boolean = false,
    val mode: CameraMode = CameraMode.PHOTO,
    val facingBack: Boolean = true,
    val hasFrontCamera: Boolean = true,
    val isRecording: Boolean = false,
    val isHighSpeedRecording: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val flashMode: FlashMode = FlashMode.OFF,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomPresets: List<ZoomPreset> = listOf(ZoomPreset(1f, true)),
    val rawSupported: Boolean = false,
    val rawEnabled: Boolean = false,
    val videoSizeOptions: List<VideoSizeOption> = emptyList(),
    val selectedVideoSize: VideoSizeOption? = null,
    val selectedFps: Int = 30,
    val lastCapture: CapturedMedia? = null,
    val errorMessage: String? = null,
    // Preview transform: buffer size (sensor-native orientation) + sensor mounting angle,
    // used by the UI layer to scale the TextureView without stretching.
    val previewBufferSize: Size? = null,
    val sensorOrientation: Int = 90,
    // Framing
    val photoAspectRatio: PhotoAspectRatio = PhotoAspectRatio.RATIO_16_9,
    val availableAspectRatios: List<PhotoAspectRatio> = listOf(PhotoAspectRatio.RATIO_4_3, PhotoAspectRatio.RATIO_16_9),
    val gridEnabled: Boolean = false,
    val shutterSoundEnabled: Boolean = true,
    val shutterFlashEnabled: Boolean = true,
    // Focus / exposure
    val focusIndicator: FocusIndicator? = null,
    val focusState: FocusState = FocusState.SEARCHING,
    val exposureCompensation: Int = 0,
    val minExposureCompensation: Int = 0,
    val maxExposureCompensation: Int = 0,
    val exposureCompensationStep: Float = 1f,
)
