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
import kotlinx.coroutines.CancellationException
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

    /** Short-lived status pill ("Vídeo muito curto…", "Falha ao salvar…"). */
    var infoMessage by mutableStateOf<String?>(null)
        private set
    private var infoJob: Job? = null

    private fun showInfo(message: String) {
        infoMessage = message
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            delay(2500)
            infoMessage = null
        }
    }

    fun openReview(media: CapturedMedia) {
        // The review screen replaces the camera screen (and its TextureView) - never while a
        // clip is rolling, that would cut the recorder's frame source out from under it.
        if (state.value.isRecording) return
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

    fun onPreviewSurfaceDestroyed() = controller.onPreviewSurfaceDestroyed()

    fun switchCamera() {
        viewModelScope.launch { controller.switchCamera() }
    }

    /**
     * Going to background: a recording can't continue (camera access is revoked for background
     * apps and there's no foreground service), so stop and save it instead of leaving a frozen,
     * half-dead recording behind.
     */
    fun pauseCamera() {
        if (state.value.isRecording) {
            viewModelScope.launch {
                recordingTimerJob?.cancel()
                runCatching { controller.stopVideoRecording(reopenPreview = false) }
                    .onSuccess { media -> media?.let { controller.setLastCapture(it) } }
            }
        } else {
            controller.pauseCamera()
        }
    }

    fun resumeCameraIfNeeded() {
        viewModelScope.launch { controller.resumeCamera() }
    }

    fun retryOpenCamera() = controller.retryOpenCamera()

    fun setDeviceOrientation(degrees: Int) = controller.setDeviceOrientation(degrees)

    fun setFlashMode(mode: FlashMode) = controller.setFlashMode(mode)

    fun setZoom(ratio: Float) = controller.setZoom(ratio)

    fun setRawEnabled(enabled: Boolean) = controller.setRawEnabled(enabled)

    fun setVideoSettings(size: VideoSizeOption, fps: Int) = controller.setVideoSettings(size, fps)

    fun setNightVideoEnabled(enabled: Boolean) = controller.setNightVideoEnabled(enabled)

    fun setTimelapseSpeed(speed: Int) = controller.setTimelapseSpeed(speed)

    fun setAudioMode(mode: com.tassiolima.rawcam.camera.AudioMode) = controller.setAudioMode(mode)

    fun setMode(mode: CameraMode) {
        viewModelScope.launch { controller.setMode(mode) }
    }

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

    // Main-thread only. A second tap while a capture is still in flight used to steal the
    // first capture's ImageReader listener and lose a photo.
    private var photoInFlight = false

    fun takePhoto() {
        if (photoInFlight) return
        val s = state.value
        if (!s.ready) return
        photoInFlight = true
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
            try {
                val media = controller.capturePhoto()
                controller.setLastCapture(media)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Photo capture failed", e)
                showInfo(if (s.mode == CameraMode.NIGHT) "Falha na foto noturna" else "Falha ao tirar a foto")
            } finally {
                photoInFlight = false
            }
        }
    }

    private var recordingToggleInFlight = false

    fun toggleRecording() {
        if (recordingToggleInFlight) return
        recordingToggleInFlight = true
        val recording = state.value.isRecording
        viewModelScope.launch {
            try {
                if (recording) {
                    recordingTimerJob?.cancel()
                    val media = controller.stopVideoRecording()
                    if (media != null) {
                        controller.setLastCapture(media)
                    } else {
                        showInfo("Vídeo muito curto — descartado")
                    }
                } else {
                    controller.startVideoRecording()
                    if (state.value.isRecording) startRecordingTimer()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Recording toggle failed", e)
                showInfo(if (recording) "Falha ao salvar o vídeo" else "Não foi possível iniciar a gravação")
            } finally {
                recordingToggleInFlight = false
            }
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        val startedAt = System.currentTimeMillis()
        elapsedMs = 0L
        recordingTimerJob = viewModelScope.launch {
            // Exits on its own once the controller reports the recording ended for any reason
            // (user stop, backgrounding, camera recovery) - no stale timer left ticking.
            while (state.value.isRecording) {
                elapsedMs = System.currentTimeMillis() - startedAt
                delay(250)
            }
        }
    }

    var elapsedMs by mutableLongStateOf(0L)
        private set

    fun deleteMedia(media: CapturedMedia) {
        viewModelScope.launch { controller.deleteCapture(media) }
    }

    override fun onCleared() {
        super.onCleared()
        shutterSound.release()
        controller.release()
        controller.stopBackgroundThread()
    }
}
