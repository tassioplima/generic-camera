package com.tassiolima.rawcam

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tassiolima.rawcam.camera.CameraController
import com.tassiolima.rawcam.camera.CameraMode
import com.tassiolima.rawcam.camera.CameraUiState
import com.tassiolima.rawcam.camera.CapturedMedia
import com.tassiolima.rawcam.camera.FlashMode
import com.tassiolima.rawcam.camera.PhotoAspectRatio
import com.tassiolima.rawcam.camera.VideoSizeOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = CameraController(application)
    val state: StateFlow<CameraUiState> get() = controller.state

    private var recordingTimerJob: Job? = null

    var reviewMedia by mutableStateOf<CapturedMedia?>(null)
        private set

    fun openReview(media: CapturedMedia) {
        reviewMedia = media
    }

    fun closeReview() {
        reviewMedia = null
    }

    init {
        controller.startBackgroundThread()
    }

    fun onPreviewSurfaceAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        viewModelScope.launch {
            controller.onPreviewSurfaceAvailable(texture, width, height)
        }
    }

    fun switchCamera() {
        viewModelScope.launch { controller.switchCamera() }
    }

    fun pauseCamera() = controller.pauseCamera()

    fun resumeCameraIfNeeded() {
        viewModelScope.launch { controller.resumeCamera() }
    }

    fun setFlashMode(mode: FlashMode) = controller.setFlashMode(mode)

    fun setZoom(ratio: Float) = controller.setZoom(ratio)

    fun setRawEnabled(enabled: Boolean) = controller.setRawEnabled(enabled)

    fun setVideoSettings(size: VideoSizeOption, fps: Int) = controller.setVideoSettings(size, fps)

    fun setMode(mode: CameraMode) = controller.setMode(mode)

    fun setPhotoAspectRatio(ratio: PhotoAspectRatio) {
        viewModelScope.launch { controller.setPhotoAspectRatio(ratio) }
    }

    fun setGridEnabled(enabled: Boolean) = controller.setGridEnabled(enabled)

    fun setShutterSoundEnabled(enabled: Boolean) = controller.setShutterSoundEnabled(enabled)

    fun setShutterFlashEnabled(enabled: Boolean) = controller.setShutterFlashEnabled(enabled)

    fun focusTap(x: Float, y: Float) = controller.focusTap(x, y)

    fun focusLongPressLock(x: Float, y: Float) = controller.focusLongPressLock(x, y)

    fun adjustLockedExposure(deltaSteps: Int) = controller.adjustLockedExposure(deltaSteps)

    fun clearFocusLock() = controller.clearFocusLock()

    private val shutterSound = android.media.MediaActionSound()

    var shutterFlash by mutableStateOf(false)
        private set

    fun takePhoto() {
        val s = state.value
        if (s.shutterFlashEnabled) {
            shutterFlash = true
            viewModelScope.launch {
                delay(150)
                shutterFlash = false
            }
        }
        if (s.shutterSoundEnabled) {
            shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
        }
        viewModelScope.launch {
            runCatching { controller.capturePhoto() }
                .onSuccess { controller.setLastCapture(it) }
        }
    }

    fun toggleRecording() {
        val recording = state.value.isRecording
        if (recording) {
            viewModelScope.launch {
                recordingTimerJob?.cancel()
                runCatching { controller.stopVideoRecording() }
                    .onSuccess { controller.setLastCapture(it) }
            }
        } else {
            viewModelScope.launch {
                runCatching { controller.startVideoRecording() }
                    .onSuccess { startRecordingTimer() }
            }
        }
    }

    private fun startRecordingTimer() {
        val startedAt = System.currentTimeMillis()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(500)
                // Elapsed time is derived here rather than stored in CameraController so the
                // controller stays focused on hardware state, not UI ticking.
                elapsedMs = System.currentTimeMillis() - startedAt
            }
        }
    }

    var elapsedMs by mutableLongStateOf(0L)
        private set

    fun deleteMedia(media: CapturedMedia) {
        controller.deleteCapture(media)
        if (reviewMedia?.uri == media.uri) reviewMedia = null
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
        controller.stopBackgroundThread()
    }
}
