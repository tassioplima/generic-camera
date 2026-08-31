package com.tassiolima.rawcam.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraController"

/**
 * Owns the Camera2 pipeline end to end: opening the device, keeping a "still" session alive
 * for photo capture (JPEG + optional RAW), and swapping in a recording session on demand for
 * video. All hardware callbacks run on a single dedicated background thread.
 */
class CameraController(private val appContext: Context) {

    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var stillSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    private var previewSurface: Surface? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var normalPreviewSize: Size? = null

    // Set only while a recording is in progress. Every repeating/one-off request built while
    // this is non-null must target the recorder surface too, or the video silently freezes
    // (audio and the on-screen timer keep going, but no more frames reach the encoder) - this
    // bit everyone the first time: tap-to-focus, pinch-zoom, flash toggle and the tap-to-focus
    // auto-revert timer all used to rebuild the repeating request targeting only the preview
    // surface, which drops the recorder surface out of the session's active request.
    private var activeRecorderSurface: Surface? = null
    private var activeRecordingFpsRange: android.util.Range<Int>? = null

    // A constrained high-speed session (needed for fixed rates like 120/240fps that many
    // phones only expose that way) only accepts request batches built via
    // createHighSpeedRequestList - manual focus/exposure/zoom rebuilds must stay hands-off
    // while it's active, since arbitrary single requests aren't supported on this session type.
    private var isHighSpeedRecording = false

    private var profile: CameraProfile? = null
    private var backCameraId: String? = null
    private var frontCameraId: String? = null

    private val settingsStore = SettingsStore(appContext)

    private val _state = MutableStateFlow(
        CameraUiState(
            photoAspectRatio = settingsStore.photoAspectRatio,
            gridEnabled = settingsStore.gridEnabled,
            rawEnabled = settingsStore.rawEnabled,
            shutterSoundEnabled = settingsStore.shutterSoundEnabled,
            shutterFlashEnabled = settingsStore.shutterFlashEnabled,
        ),
    )
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    fun startBackgroundThread() {
        val thread = HandlerThread("RawCamBackground").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private val controllerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    /**
     * Every call into a live CameraCaptureSession can throw if the HAL rejects it or the
     * session already died underneath us - observed in practice when zooming across a
     * physical-lens boundary (e.g. crossing into the ultra-wide or telephoto sensor) while a
     * MediaRecorder surface is bound; some OEM HALs don't support that switch mid-recording
     * and error the whole session out. Letting that exception propagate crashes the app on
     * whatever thread called setZoom/focusTap/etc (usually the main thread from a gesture) -
     * catch it here, log it, and try to recover the camera instead of taking the process down.
     */
    private fun safeSessionOp(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Camera session op failed: $label", e)
            scheduleRecovery()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Camera session op failed: $label", e)
            scheduleRecovery()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Camera session op rejected: $label", e)
        }
    }

    private var recoveryScheduled = false

    private fun scheduleRecovery() {
        if (recoveryScheduled) return
        recoveryScheduled = true

        val texture = retainedTexture
        val cameraId = (if (_state.value.facingBack) backCameraId else frontCameraId)
            ?: backCameraId ?: frontCameraId
        val handler = backgroundHandler
        if (texture == null || cameraId == null || handler == null) {
            recoveryScheduled = false
            return
        }

        _state.update { it.copy(errorMessage = null) }

        stillSession?.close()
        stillSession = null
        cameraDevice?.close()
        cameraDevice = null
        activeRecorderSurface = null
        activeRecordingFpsRange = null
        isHighSpeedRecording = false
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        recordingFile = null
        _state.update { it.copy(isRecording = false, isHighSpeedRecording = false) }

        handler.postDelayed({
            controllerScope.launch {
                recoveryScheduled = false
                runCatching { openCameraInternal(cameraId, texture, previewWidth, previewHeight) }
                    .onFailure {
                        Log.e(TAG, "Camera recovery reopen failed", it)
                        _state.update { s -> s.copy(errorMessage = "Não foi possível reconectar a câmera. Toque para tentar de novo.") }
                    }
            }
        }, 350)
    }

    /** Lets the UI offer a manual "tap to retry" after a recovery attempt gives up. */
    fun retryOpenCamera() {
        val texture = retainedTexture ?: return
        val cameraId = (if (_state.value.facingBack) backCameraId else frontCameraId)
            ?: backCameraId ?: frontCameraId ?: return
        _state.update { it.copy(errorMessage = null) }
        controllerScope.launch {
            runCatching { openCameraInternal(cameraId, texture, previewWidth, previewHeight) }
                .onFailure {
                    Log.e(TAG, "Manual camera retry failed", it)
                    _state.update { s -> s.copy(errorMessage = "Não foi possível reconectar a câmera. Toque para tentar de novo.") }
                }
        }
    }

    /** Called once the TextureView's SurfaceTexture is ready. Opens the back camera by default. */
    suspend fun onPreviewSurfaceAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        backCameraId = CameraCapabilities.findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_BACK)
        frontCameraId = CameraCapabilities.findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_FRONT)
        _state.update { it.copy(hasFrontCamera = frontCameraId != null) }

        val cameraId = backCameraId ?: frontCameraId ?: run {
            _state.update { it.copy(errorMessage = "Nenhuma câmera encontrada neste aparelho") }
            return
        }
        openCameraInternal(cameraId, texture, width, height)
    }

    suspend fun switchCamera() {
        val current = _state.value
        val targetFacingBack = !current.facingBack
        val targetId = if (targetFacingBack) backCameraId else frontCameraId
        if (targetId == null) return
        val tex = retainedTexture ?: return
        closeCameraInternal(keepPreview = true)
        openCameraInternal(targetId, tex, previewWidth, previewHeight)
    }

    private var retainedTexture: SurfaceTexture? = null

    /** Releases the camera device while the app is backgrounded, without forgetting the surface. */
    fun pauseCamera() {
        if (recorder != null) {
            // Don't tear down mid-recording; the recording flow closes/reopens the session itself.
            return
        }
        pendingRevertRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingRevertRunnable = null
        stillSession?.close()
        stillSession = null
        cameraDevice?.close()
        cameraDevice = null
        _state.update { it.copy(ready = false) }
    }

    /** Reopens the camera when the app returns to the foreground, if it isn't already open. */
    suspend fun resumeCamera() {
        if (cameraDevice != null) return
        val texture = retainedTexture ?: return
        val id = (if (_state.value.facingBack) backCameraId else frontCameraId)
            ?: backCameraId ?: frontCameraId ?: return
        openCameraInternal(id, texture, previewWidth, previewHeight)
    }

    // onResume() and the TextureView's onSurfaceTextureAvailable can both race to open the
    // camera on first launch (resume fires right as the surface becomes available); without
    // this guard the second call's openCamera() would collide with the first's in-flight one
    // and leave the preview black. All the setup below this check runs synchronously up to
    // the first suspension point (openDevice), so a plain flag is enough - no actual thread
    // race once we're past that point since backgroundHandler serializes callbacks.
    private var isOpening = false

    private suspend fun openCameraInternal(cameraId: String, texture: SurfaceTexture, width: Int, height: Int) {
        if (isOpening) return
        isOpening = true
        try {
            openCameraInternalLocked(cameraId, texture, width, height)
        } finally {
            isOpening = false
        }
    }

    private suspend fun openCameraInternalLocked(cameraId: String, texture: SurfaceTexture, width: Int, height: Int) {
        // Guard against being called while a device from a previous open is still around
        // (e.g. a stray resume before pause finished) - Camera2 refuses a second open otherwise.
        stillSession?.close()
        stillSession = null
        cameraDevice?.close()
        cameraDevice = null
        activeRecorderSurface = null
        activeRecordingFpsRange = null
        isHighSpeedRecording = false

        retainedTexture = texture
        previewWidth = width
        previewHeight = height

        pendingRevertRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingRevertRunnable = null
        lockedRegion = null

        val prof = CameraCapabilities.loadProfile(cameraManager, cameraId)
        profile = prof

        val previewSize = choosePreviewSize(prof, width, height)
        normalPreviewSize = previewSize
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        val surface = Surface(texture)
        previewSurface = surface

        jpegReader?.close()
        rawReader?.close()

        val availableRatios = CameraCapabilities.availableAspectRatios(prof)
        val chosenRatio = _state.value.photoAspectRatio.takeIf { it in availableRatios }
            ?: availableRatios.firstOrNull() ?: PhotoAspectRatio.RATIO_16_9
        val jpegSize = CameraCapabilities.bestJpegSize(prof, chosenRatio)
            ?: prof.jpegSizes.firstOrNull() ?: previewSize
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, android.graphics.ImageFormat.JPEG, 2)

        rawReader = if (prof.supportsRaw && prof.rawSize != null) {
            ImageReader.newInstance(prof.rawSize.width, prof.rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 2)
        } else null

        val savedVideoSize = prof.videoSizes.firstOrNull {
            it.size.width == settingsStore.videoWidth && it.size.height == settingsStore.videoHeight
        }
        val restoredVideoSize = savedVideoSize
            ?: prof.videoSizes.firstOrNull { v -> v.label == "1080p" }
            ?: prof.videoSizes.firstOrNull()
        val restoredFps = settingsStore.videoFps.takeIf { restoredVideoSize != null && it in restoredVideoSize.fpsOptions }
            ?: restoredVideoSize?.fpsOptions?.firstOrNull() ?: 30

        _state.update {
            it.copy(
                facingBack = prof.lensFacing == CameraCharacteristics.LENS_FACING_BACK,
                rawSupported = prof.supportsRaw,
                rawEnabled = it.rawEnabled && prof.supportsRaw,
                videoSizeOptions = prof.videoSizes,
                selectedVideoSize = restoredVideoSize,
                selectedFps = restoredFps,
                minZoomRatio = prof.zoomRatioRange.lower,
                maxZoomRatio = prof.zoomRatioRange.upper,
                // Always back to 1x on a fresh open (launch, resume-from-background, camera
                // switch) - a zoom left over from before the app was backgrounded shouldn't
                // silently carry over, that surprises people more than it helps them.
                zoomRatio = 1f.coerceIn(prof.zoomRatioRange.lower, prof.zoomRatioRange.upper),
                zoomPresets = prof.zoomPresets,
                previewBufferSize = previewSize,
                sensorOrientation = prof.sensorOrientation,
                availableAspectRatios = availableRatios,
                photoAspectRatio = chosenRatio,
                focusIndicator = null,
                exposureCompensation = 0,
                minExposureCompensation = prof.exposureCompensationRange.lower,
                maxExposureCompensation = prof.exposureCompensationRange.upper,
                exposureCompensationStep = prof.exposureCompensationStep,
            )
        }

        val device = openDevice(cameraId)
        cameraDevice = device
        createStillSession(device)
        _state.update { it.copy(ready = true) }

        if (_state.value.lastCapture == null) {
            val recent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaStoreSaver.findMostRecentCapture(appContext)
            }
            if (recent != null) {
                _state.update { if (it.lastCapture == null) it.copy(lastCapture = recent) else it }
            }
        }
    }

    private suspend fun openDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera error code $error"))
                    }
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        }
    }

    private suspend fun createStillSession(device: CameraDevice) {
        val preview = previewSurface ?: return
        val surfaces = listOfNotNull(preview, jpegReader?.surface, rawReader?.surface)
        val session = createSession(device, surfaces)
        stillSession = session
        startPreviewRepeating(session)
    }

    private suspend fun createSession(device: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                }
            }, backgroundHandler)
        }

    private suspend fun createHighSpeedSession(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession =
        suspendCancellableCoroutine { cont ->
            device.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resume(session as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("High-speed session configuration failed"))
                }
            }, backgroundHandler)
        }

    /**
     * Builds a request targeting every surface that should currently be receiving frames -
     * just the preview while framing a photo, or preview + recorder while a video is rolling.
     * All repeating/one-off requests (focus, zoom, flash, exposure) MUST go through this so
     * none of them accidentally drop the recorder surface mid-recording.
     */
    private fun buildLiveRequestBuilder(device: CameraDevice): CaptureRequest.Builder? {
        val preview = previewSurface ?: return null
        val recording = activeRecorderSurface
        val template = if (recording != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)
        builder.addTarget(preview)
        recording?.let { builder.addTarget(it) }
        applyLiveParams(builder)
        activeRecordingFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        return builder
    }

    private fun startPreviewRepeating(session: CameraCaptureSession) {
        // The high-speed session's burst was already submitted in startVideoRecording() via
        // createHighSpeedRequestList/setRepeatingBurst; rebuilding it here with a plain
        // setRepeatingRequest would be rejected (or worse, silently break the session).
        if (isHighSpeedRecording) return
        val device = cameraDevice ?: return
        val builder = buildLiveRequestBuilder(device) ?: return
        safeSessionOp("setRepeatingRequest") {
            session.setRepeatingRequest(builder.build(), afStateCallback, backgroundHandler)
        }
    }

    /**
     * Only meaningful while a focus reticle is on screen - reads CONTROL_AF_STATE off every
     * frame so the reticle can show "searching" vs. actually landed vs. failed, instead of
     * just assuming a tap or long-press always focuses successfully.
     */
    private val afStateCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (_state.value.focusIndicator == null) return
            val newState = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                -> FocusState.FOCUSED
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> FocusState.FAILED
                else -> FocusState.SEARCHING
            }
            _state.update { if (it.focusState != newState) it.copy(focusState = newState) else it }
        }
    }

    private fun applyLiveParams(builder: CaptureRequest.Builder) {
        val s = _state.value
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.exposureCompensation)

        // The live/repeating stream must never carry a flash-firing AE mode
        // (ON_ALWAYS_FLASH/ON_AUTO_FLASH) - several HALs interpret that literally per frame and
        // strobe the physical LED on every preview frame, which is the "pisca muito rápido" bug.
        // The bright flash belongs only on the still-capture request (PhotoCapture.
        // buildCaptureRequest). Here we just light a steady, dimmed focus-assist torch when the
        // user has flash ON, so framing/focusing in the dark is easier without strobing or
        // wasting the flash's peak brightness before the actual shot.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        when (s.flashMode) {
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            FlashMode.ON -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                val prof = profile
                if (prof != null && prof.flashStrengthMaxLevel > 1) {
                    val half = ((prof.flashStrengthMaxLevel + 1) / 2).coerceIn(1, prof.flashStrengthMaxLevel)
                    builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, half)
                }
            }
            FlashMode.AUTO, FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }

        val continuousAfMode = if (activeRecorderSurface != null) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }

        val region = lockedRegion
        if (region != null && s.focusIndicator?.locked == true) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, continuousAfMode)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
    }

    private fun refreshPreviewRepeating() {
        val session = stillSession ?: return
        startPreviewRepeating(session)
    }

    // ----- Public controls -----

    fun setFlashMode(mode: FlashMode) {
        _state.update { it.copy(flashMode = mode) }
        refreshPreviewRepeating()
    }

    fun setZoom(ratio: Float) {
        val s = _state.value
        val clamped = ratio.coerceIn(s.minZoomRatio, s.maxZoomRatio)
        _state.update { it.copy(zoomRatio = clamped) }
        refreshPreviewRepeating()
    }

    fun setRawEnabled(enabled: Boolean) {
        settingsStore.rawEnabled = enabled
        _state.update { it.copy(rawEnabled = enabled && it.rawSupported) }
    }

    fun setVideoSettings(sizeOption: VideoSizeOption, fps: Int) {
        settingsStore.saveVideoSettings(sizeOption.size.width, sizeOption.size.height, fps)
        _state.update { it.copy(selectedVideoSize = sizeOption, selectedFps = fps) }
    }

    fun setMode(mode: CameraMode) {
        _state.update { it.copy(mode = mode) }
    }

    suspend fun setPhotoAspectRatio(ratio: PhotoAspectRatio) {
        val prof = profile ?: return
        val device = cameraDevice ?: return
        if (ratio !in _state.value.availableAspectRatios) return
        val newSize = CameraCapabilities.bestJpegSize(prof, ratio) ?: return

        stillSession?.close()
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(newSize.width, newSize.height, android.graphics.ImageFormat.JPEG, 2)
        settingsStore.photoAspectRatio = ratio
        _state.update { it.copy(photoAspectRatio = ratio) }
        createStillSession(device)
    }

    fun setGridEnabled(enabled: Boolean) {
        settingsStore.gridEnabled = enabled
        _state.update { it.copy(gridEnabled = enabled) }
    }

    fun setShutterSoundEnabled(enabled: Boolean) {
        settingsStore.shutterSoundEnabled = enabled
        _state.update { it.copy(shutterSoundEnabled = enabled) }
    }

    fun setShutterFlashEnabled(enabled: Boolean) {
        settingsStore.shutterFlashEnabled = enabled
        _state.update { it.copy(shutterFlashEnabled = enabled) }
    }

    private var lockedRegion: MeteringRectangle? = null
    private var pendingRevertRunnable: Runnable? = null

    /** Quick tap: focuses at the point, then reverts to continuous AF/AE shortly after. */
    fun focusTap(xInView: Float, yInView: Float) {
        if (isHighSpeedRecording) return
        val prof = profile ?: return
        val session = stillSession ?: return
        val device = cameraDevice ?: return
        if (previewWidth == 0 || previewHeight == 0) return

        pendingRevertRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        lockedRegion = meteringRectForTap(
            xInView, yInView, previewWidth, previewHeight,
            prof.activeArraySize, prof.sensorOrientation,
            prof.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            _state.value.zoomRatio,
        )
        _state.update {
            it.copy(
                focusIndicator = FocusIndicator(xInView, yInView, locked = false),
                focusState = FocusState.SEARCHING,
                exposureCompensation = 0,
            )
        }

        val builder = buildLiveRequestBuilder(device) ?: return
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(lockedRegion))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(lockedRegion))
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        safeSessionOp("focusTap capture") { session.capture(builder.build(), afStateCallback, backgroundHandler) }

        val runnable = Runnable {
            lockedRegion = null
            _state.update { it.copy(focusIndicator = null) }
            refreshPreviewRepeating()
        }
        pendingRevertRunnable = runnable
        backgroundHandler?.postDelayed(runnable, 2500)
    }

    /** Long-press: focuses and locks exposure at the point until the user taps elsewhere. */
    fun focusLongPressLock(xInView: Float, yInView: Float) {
        if (isHighSpeedRecording) return
        val prof = profile ?: return
        val device = cameraDevice ?: return
        val session = stillSession ?: return
        if (previewWidth == 0 || previewHeight == 0) return

        pendingRevertRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingRevertRunnable = null
        lockedRegion = meteringRectForTap(
            xInView, yInView, previewWidth, previewHeight,
            prof.activeArraySize, prof.sensorOrientation,
            prof.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            _state.value.zoomRatio,
        )
        _state.update {
            it.copy(
                focusIndicator = FocusIndicator(xInView, yInView, locked = true),
                focusState = FocusState.SEARCHING,
                exposureCompensation = 0,
            )
        }

        val builder = buildLiveRequestBuilder(device) ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        safeSessionOp("focusLongPressLock capture") { session.capture(builder.build(), afStateCallback, backgroundHandler) }

        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        safeSessionOp("focusLongPressLock setRepeatingRequest") {
            session.setRepeatingRequest(builder.build(), afStateCallback, backgroundHandler)
        }
    }

    /** While locked, a vertical drag adjusts exposure compensation (brightness). */
    fun adjustLockedExposure(deltaSteps: Int) {
        val s = _state.value
        if (s.focusIndicator?.locked != true) return
        val newValue = (s.exposureCompensation + deltaSteps)
            .coerceIn(s.minExposureCompensation, s.maxExposureCompensation)
        if (newValue == s.exposureCompensation) return
        _state.update { it.copy(exposureCompensation = newValue) }
        refreshPreviewRepeating()
    }

    /** Tapping elsewhere while locked clears the lock and reverts to continuous AF/AE. */
    fun clearFocusLock() {
        pendingRevertRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingRevertRunnable = null
        lockedRegion = null
        _state.update { it.copy(focusIndicator = null, exposureCompensation = 0) }
        refreshPreviewRepeating()
    }

    // ----- Photo capture -----

    suspend fun capturePhoto(): CapturedMedia {
        val session = stillSession ?: error("Camera not ready")
        val device = cameraDevice ?: error("Camera not ready")
        val prof = profile ?: error("Camera not ready")

        // A real camera flash needs a brief metering pre-flash to figure out the right power
        // for the actual shot - skipping straight to a full-power strobe (what this app used to
        // do) is exactly what blows highlights out on anything reasonably close. This mirrors
        // what every stock camera app does before a flash-lit still capture.
        if (_state.value.flashMode != FlashMode.OFF && prof.hasFlash) {
            runAePrecapture(session, device)
        }

        return performStillCapture(session, device, prof)
    }

    /**
     * Triggers CONTROL_AE_PRECAPTURE_TRIGGER_START and waits (with a timeout, since not every
     * HAL reports convergence the same way) for CONTROL_AE_STATE to settle before the real
     * flash-lit capture proceeds.
     */
    private suspend fun runAePrecapture(session: CameraCaptureSession, device: CameraDevice) {
        val builder = buildLiveRequestBuilder(device) ?: return
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val settled = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                if (settled && deferred.isActive) deferred.complete(Unit)
            }
        }

        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        safeSessionOp("ae precapture trigger") { session.capture(builder.build(), callback, backgroundHandler) }

        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        safeSessionOp("ae precapture repeating") { session.setRepeatingRequest(builder.build(), callback, backgroundHandler) }

        kotlinx.coroutines.withTimeoutOrNull(1200) { deferred.await() }
    }

    private suspend fun performStillCapture(
        session: CameraCaptureSession,
        device: CameraDevice,
        prof: CameraProfile,
    ): CapturedMedia = suspendCancellableCoroutine { cont ->
        val jpeg = jpegReader
        if (jpeg == null) {
            cont.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        val s = _state.value
        val wantsRaw = s.rawEnabled && rawReader != null
        val raw = if (wantsRaw) rawReader else null
        val displayName = "RAW_${System.currentTimeMillis()}"

        var jpegUri: android.net.Uri? = null
        var rawUri: android.net.Uri? = null
        var captureResultHolder: TotalCaptureResult? = null
        var rawImageHolder: android.media.Image? = null
        var finished = false

        fun maybeFinish() {
            if (finished) return
            if (jpegUri != null && (raw == null || rawUri != null)) {
                finished = true
                jpeg.setOnImageAvailableListener(null, null)
                raw?.setOnImageAvailableListener(null, null)
                if (cont.isActive) {
                    cont.resume(CapturedMedia(jpegUri!!, isVideo = false, timestampMs = System.currentTimeMillis()))
                }
            }
        }

        jpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            jpegUri = PhotoCapture.saveJpeg(appContext, image, displayName)
            image.close()
            maybeFinish()
        }, backgroundHandler)

        if (raw != null) {
            raw.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val result = captureResultHolder
                if (result != null) {
                    rawUri = PhotoCapture.saveRaw(appContext, image, cameraManager.getCameraCharacteristics(prof.cameraId), result, displayName)
                    image.close()
                    maybeFinish()
                } else {
                    rawImageHolder = image
                }
            }, backgroundHandler)
        }

        val afRegions = lockedRegion?.let { arrayOf(it) }
        val request = PhotoCapture.buildCaptureRequest(
            device, jpeg.surface, raw?.surface,
            jpegOrientation = jpegOrientationDegrees(prof),
            flashMode = s.flashMode,
            zoomRatio = s.zoomRatio,
            exposureCompensation = s.exposureCompensation,
            afRegions = afRegions,
        )

        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                captureResultHolder = result
                val pendingRawImage = rawImageHolder
                if (raw != null && pendingRawImage != null) {
                    rawUri = PhotoCapture.saveRaw(appContext, pendingRawImage, cameraManager.getCameraCharacteristics(prof.cameraId), result, displayName)
                    pendingRawImage.close()
                    maybeFinish()
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure,
            ) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Falha ao capturar foto (reason=${failure.reason})"))
            }
        }, backgroundHandler)
    }

    // ----- Video capture -----

    suspend fun startVideoRecording() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val prof = profile ?: return
        val s = _state.value
        val videoOption = s.selectedVideoSize ?: prof.videoSizes.firstOrNull() ?: return

        stillSession?.close()
        stillSession = null

        val tempFile = VideoRecorder.tempOutputFile(appContext)
        recordingFile = tempFile
        val mr = VideoRecorder.create(
            appContext, tempFile, videoOption, s.selectedFps,
            orientationHintDegrees = videoOrientationDegrees(prof),
        )
        recorder = mr

        val recorderSurface = mr.surface
        val useHighSpeed = videoOption.isHighSpeed(s.selectedFps)
        activeRecorderSurface = recorderSurface
        activeRecordingFpsRange = if (useHighSpeed) {
            android.util.Range(s.selectedFps, s.selectedFps)
        } else {
            CameraCapabilities.bestFpsRange(prof, s.selectedFps)
        }
        isHighSpeedRecording = useHighSpeed

        if (useHighSpeed) {
            // A constrained high-speed session requires every surface's buffer size to match
            // a size from getHighSpeedVideoSizes() - not just the recorder, the preview too.
            // The UI's TextureView transform reads previewBufferSize from state, so it must be
            // updated to match or the preview looks stretched while this is active.
            retainedTexture?.setDefaultBufferSize(videoOption.size.width, videoOption.size.height)
            _state.update { it.copy(previewBufferSize = videoOption.size) }
            val highSpeedSession = createHighSpeedSession(device, listOf(preview, recorderSurface))
            stillSession = highSpeedSession
            val requestBuilder = buildLiveRequestBuilder(device)
                ?: error("Preview surface missing for high-speed recording")
            val burst = highSpeedSession.createHighSpeedRequestList(requestBuilder.build())
            highSpeedSession.setRepeatingBurst(burst, null, backgroundHandler)
        } else {
            val session = createSession(device, listOf(preview, recorderSurface))
            stillSession = session
            startPreviewRepeating(session)
        }

        mr.start()
        _state.update { it.copy(isRecording = true, isHighSpeedRecording = useHighSpeed) }
    }

    suspend fun stopVideoRecording(): CapturedMedia {
        val mr = recorder ?: error("Not recording")
        val tempFile = recordingFile ?: error("No recording file")
        val wasHighSpeed = isHighSpeedRecording

        stillSession?.stopRepeating()
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder.stop() failed, discarding clip", e)
        }
        mr.release()
        recorder = null
        activeRecorderSurface = null
        activeRecordingFpsRange = null
        isHighSpeedRecording = false

        val uri = MediaStoreSaver.saveVideo(appContext, tempFile, "RAWCAM_${System.currentTimeMillis()}")
        recordingFile = null

        stillSession?.close()

        if (wasHighSpeed) {
            val restoreSize = normalPreviewSize
            if (restoreSize != null) {
                retainedTexture?.setDefaultBufferSize(restoreSize.width, restoreSize.height)
                _state.update { it.copy(previewBufferSize = restoreSize) }
            }
        }

        val device = cameraDevice
        if (device != null) {
            createStillSession(device)
        }
        _state.update { it.copy(isRecording = false, isHighSpeedRecording = false) }
        return CapturedMedia(uri, isVideo = true, timestampMs = System.currentTimeMillis())
    }

    fun setLastCapture(media: CapturedMedia) {
        _state.update { it.copy(lastCapture = media) }
    }

    fun deleteCapture(media: CapturedMedia) {
        MediaStoreSaver.delete(appContext, media.uri)
        _state.update { if (it.lastCapture?.uri == media.uri) it.copy(lastCapture = null) else it }
    }

    // ----- Teardown -----

    private fun closeCameraInternal(keepPreview: Boolean) {
        stillSession?.close()
        stillSession = null
        cameraDevice?.close()
        cameraDevice = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        if (!keepPreview) {
            previewSurface = null
        }
    }

    fun release() {
        closeCameraInternal(keepPreview = false)
        retainedTexture = null
        controllerScope.cancel()
    }

    // ----- Helpers -----

    private fun choosePreviewSize(prof: CameraProfile, viewWidth: Int, viewHeight: Int): Size {
        val target = prof.jpegSizes.firstOrNull() ?: Size(1920, 1080)
        val targetAspect = target.width.toFloat() / target.height
        val map = cameraManager.getCameraCharacteristics(prof.cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val choices = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: listOf(Size(1920, 1080))
        return choices
            .filter { kotlin.math.abs(it.width.toFloat() / it.height - targetAspect) < 0.12f }
            .ifEmpty { choices }
            .filter { it.width <= 1920 }
            .ifEmpty { choices }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: choices.first()
    }

    private fun jpegOrientationDegrees(prof: CameraProfile): Int {
        // The official Android formula is (sensorOrientation +/- deviceOrientation + 360) % 360,
        // with the sign flipped for the front camera vs. back. Since the activity is locked to
        // portrait (device orientation relative to natural = 0), that term drops out entirely
        // and BOTH cameras reduce to the same thing: sensorOrientation itself. The previous
        // "360 - sensorOrientation" front-camera special case was wrong - it applied a spurious
        // 180 degree rotation on top of the fixed 0 term, which is exactly what made front
        // camera photos come out upside-down/rotated ("invertida").
        return prof.sensorOrientation % 360
    }

    private fun videoOrientationDegrees(prof: CameraProfile): Int = jpegOrientationDegrees(prof)

    private fun meteringRectForTap(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        activeArray: Rect,
        sensorOrientation: Int,
        facingFront: Boolean,
        zoomRatio: Float,
    ): MeteringRectangle {
        val nx = (x / viewWidth).coerceIn(0f, 1f)
        val ny = (y / viewHeight).coerceIn(0f, 1f)

        val (rx, ry) = when (sensorOrientation) {
            90 -> ny to (1 - nx)
            270 -> (1 - ny) to nx
            180 -> (1 - nx) to (1 - ny)
            else -> nx to ny
        }
        val mirroredX = if (facingFront) 1 - rx else rx

        // CONTROL_AF_REGIONS/CONTROL_AE_REGIONS are always expressed in full, un-zoomed active
        // array coordinates - the framework does not remap them for you based on
        // CONTROL_ZOOM_RATIO. So a tap on the current (possibly zoomed-in) preview only
        // corresponds to a fraction of the full sensor once we first shrink that mapping down
        // to whatever crop is actually visible right now. Skipping this (as the code used to)
        // meant every tap effectively landed near the sensor's center once zoomed in - which is
        // exactly the "can't focus on the object in the background" symptom when the object
        // was framed by zooming in on it.
        val z = zoomRatio.coerceAtLeast(0.01f)
        val visibleWidth = activeArray.width() / z
        val visibleHeight = activeArray.height() / z
        val visibleLeft = activeArray.left + (activeArray.width() - visibleWidth) / 2f
        val visibleTop = activeArray.top + (activeArray.height() - visibleHeight) / 2f

        val sensorX = (visibleLeft + mirroredX * visibleWidth).toInt()
        val sensorY = (visibleTop + ry * visibleHeight).toInt()
        // Keep the metering box a consistent ~20% of the currently visible (zoomed) frame,
        // rather than ~20% of the whole sensor - otherwise it balloons past the entire visible
        // frame at high zoom and just meters "the middle of everything" instead of the tapped spot.
        val halfSize = (0.1f * minOf(visibleWidth, visibleHeight)).toInt().coerceAtLeast(1)

        val rect = Rect(
            (sensorX - halfSize).coerceIn(activeArray.left, activeArray.right),
            (sensorY - halfSize).coerceIn(activeArray.top, activeArray.bottom),
            (sensorX + halfSize).coerceIn(activeArray.left, activeArray.right),
            (sensorY + halfSize).coerceIn(activeArray.top, activeArray.bottom),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }
}
