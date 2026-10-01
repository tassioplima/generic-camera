package com.tassiolima.rawcam.camera

import android.net.Uri
import android.util.Size

enum class CameraMode(val label: String) {
    NIGHT("Noite"),
    PHOTO("Foto"),
    VIDEO("Vídeo"),
    TIMELAPSE("Timelapse");

    val isVideo: Boolean get() = this == VIDEO || this == TIMELAPSE
}

/**
 * Which microphone pipeline video audio comes from. Plain MIC (what this app used before)
 * is the *voice* path - measured on the Xiaomi 17T it hard-gates pauses to digital silence,
 * limits loud passages and has a very uneven EQ, which is the "estourado"/bad-quality sound.
 */
enum class AudioMode(val label: String) {
    /** CAMCORDER: the OEM's video-tuned path - real stereo, louder, controls loud scenes, softer highs. */
    CAMERA("Câmera"),
    /**
     * UNPROCESSED (or VOICE_RECOGNITION where unsupported, as on the 17T): no AGC/noise
     * suppression - the flattest frequency response, but ~6dB quieter.
     */
    RAW("Sem processamento"),
}

/** Output-to-real-time speedup for timelapse: at 30x, 1 minute of filming plays back in 2s. */
val TIMELAPSE_SPEEDS = listOf(5, 10, 30, 60, 120)

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
    // Night / timelapse
    val nightExtensionSupported: Boolean = false,
    // Non-null while a multi-frame night shot is being taken/processed (0-100, or -1 when the
    // HAL doesn't report progress) - the user has to hold still the whole time.
    val nightCaptureProgress: Int? = null,
    val nightVideoEnabled: Boolean = false,
    val timelapseSpeed: Int = 10,
    val audioMode: AudioMode = AudioMode.CAMERA,
)
