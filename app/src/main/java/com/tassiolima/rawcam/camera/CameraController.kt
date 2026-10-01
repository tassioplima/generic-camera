package com.tassiolima.rawcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraController"
private const val RECONNECT_ERROR = "Não foi possível reconectar a câmera. Toque para tentar de novo."

/**
 * Owns the Camera2 pipeline end to end: opening the device, keeping a "still" session alive
 * for photo capture (JPEG + optional RAW), a Camera2 Extensions session for night photos, and
 * swapping in a recording session on demand for video/timelapse.
 *
 * Threading: every public method and every field below is touched on the main thread only.
 * Hardware callbacks arrive on [backgroundHandler]; anything in them that has to mutate
 * controller fields hops back to main first (see [onMain]). Only [_state] (thread-safe) and
 * capture-local variables are written from the camera thread.
 */
class CameraController(private val appContext: Context) {

    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraExecutor = Executor { r -> backgroundHandler?.post(r) ?: r.run() }

    private var cameraDevice: CameraDevice? = null
    private var stillSession: CameraCaptureSession? = null
    private var extensionSession: CameraExtensionSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var nightJpegReader: ImageReader? = null

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    private var previewSurface: Surface? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var normalPreviewSize: Size? = null

    // Set only while a recording is in progress. Every repeating/one-off request built while
    // this is non-null must target the recorder surface too, or the video silently freezes
    // (audio and the on-screen timer keep going, but no more frames reach the encoder).
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

    // Physical rotation of the phone (0/90/180/270, from an OrientationEventListener). The
    // activity is portrait-locked, but people still turn the phone sideways to shoot - the
    // saved JPEG/video must be rotated for how it was actually held, like any stock camera.
    private var deviceOrientation = 0

    // Set by pauseCamera(), cleared on the next open - so a device disconnect that arrives
    // while we're backgrounded doesn't trigger a reconnect loop.
    private var isPaused = false

    private var captureInProgress = false

    private val settingsStore = SettingsStore(appContext)

    private val _state = MutableStateFlow(
        CameraUiState(
            photoAspectRatio = settingsStore.photoAspectRatio,
            gridEnabled = settingsStore.gridEnabled,
            rawEnabled = settingsStore.rawEnabled,
            shutterSoundEnabled = settingsStore.shutterSoundEnabled,
            shutterFlashEnabled = settingsStore.shutterFlashEnabled,
            nightVideoEnabled = settingsStore.nightVideoEnabled,
            timelapseSpeed = settingsStore.timelapseSpeed.takeIf { it in TIMELAPSE_SPEEDS } ?: 10,
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

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Every call into a live CameraCaptureSession can throw if the HAL rejects it or the
     * session already died underneath us - observed in practice when zooming across a
     * physical-lens boundary while a MediaRecorder surface is bound. Catch it, log it, and
     * try to recover the camera instead of taking the process down.
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleRecovery() }
            return
        }
        if (recoveryScheduled || isPaused) return
        recoveryScheduled = true

        val texture = retainedTexture
        val cameraId = currentCameraId()
        if (texture == null || cameraId == null) {
            recoveryScheduled = false
            return
        }

        _state.update { it.copy(errorMessage = null) }

        // Salvage whatever was recorded so far instead of silently dropping the clip.
        finishRecorder(saveClip = true)
        closeSessions()
        cameraDevice?.close()
        cameraDevice = null
        _state.update { it.copy(ready = false) }

        mainHandler.postDelayed({
            controllerScope.launch {
                recoveryScheduled = false
                if (isPaused) return@launch
                if (!openCameraInternal(cameraId, texture, previewWidth, previewHeight)) {
                    _state.update { s -> s.copy(errorMessage = RECONNECT_ERROR) }
                }
            }
        }, 350)
    }

    /** Lets the UI offer a manual "tap to retry" after a recovery attempt gives up. */
    fun retryOpenCamera() {
        val texture = retainedTexture ?: return
        val cameraId = currentCameraId() ?: return
        _state.update { it.copy(errorMessage = null) }
        controllerScope.launch {
            if (!openCameraInternal(cameraId, texture, previewWidth, previewHeight)) {
                _state.update { s -> s.copy(errorMessage = RECONNECT_ERROR) }
            }
        }
    }

    private fun currentCameraId(): String? =
        (if (_state.value.facingBack) backCameraId else frontCameraId) ?: backCameraId ?: frontCameraId

    /** Called once the TextureView's SurfaceTexture is ready. Reopens whichever side was in use. */
    suspend fun onPreviewSurfaceAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        backCameraId = CameraCapabilities.findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_BACK)
        frontCameraId = CameraCapabilities.findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_FRONT)
        _state.update { it.copy(hasFrontCamera = frontCameraId != null) }

        val cameraId = currentCameraId() ?: run {
            _state.update { it.copy(errorMessage = "Nenhuma câmera encontrada neste aparelho") }
            return
        }
        isPaused = false
        if (!openCameraInternal(cameraId, texture, width, height)) {
            _state.update { it.copy(errorMessage = RECONNECT_ERROR) }
        }
    }

    /**
     * The TextureView is going away (e.g. the review screen replaced the camera screen). Its
     * SurfaceTexture is about to be released, so the camera must stop streaming into it now -
     * and must not try to reopen with it later, that's a guaranteed "Surface was abandoned".
     */
    fun onPreviewSurfaceDestroyed() {
        finishRecorder(saveClip = true)
        cancelFocusRevert()
        closeSessions()
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        retainedTexture = null
        _state.update { it.copy(ready = false) }
    }

    suspend fun switchCamera() {
        if (isOpening || recorder != null || captureInProgress) return
        val current = _state.value
        val targetFacingBack = !current.facingBack
        val targetId = if (targetFacingBack) backCameraId else frontCameraId
        if (targetId == null) return
        val tex = retainedTexture ?: return
        closeCameraInternal(keepPreview = true)
        if (!openCameraInternal(targetId, tex, previewWidth, previewHeight)) {
            _state.update { it.copy(errorMessage = RECONNECT_ERROR) }
        }
    }

    private var retainedTexture: SurfaceTexture? = null

    fun setDeviceOrientation(degrees: Int) {
        deviceOrientation = degrees
    }

    /** Releases the camera device while the app is backgrounded, without forgetting the surface. */
    fun pauseCamera() {
        isPaused = true
        cancelFocusRevert()
        closeSessions()
        cameraDevice?.close()
        cameraDevice = null
        _state.update { it.copy(ready = false, nightCaptureProgress = null) }
    }

    /** Reopens the camera when the app returns to the foreground, if it isn't already open. */
    suspend fun resumeCamera() {
        isPaused = false
        if (cameraDevice != null || isOpening) return
        val texture = retainedTexture ?: return
        val id = currentCameraId() ?: return
        if (!openCameraInternal(id, texture, previewWidth, previewHeight)) {
            _state.update { it.copy(errorMessage = RECONNECT_ERROR) }
        }
    }

    // onResume() and the TextureView's onSurfaceTextureAvailable can both race to open the
    // camera on first launch; without this guard the second call's openCamera() would collide
    // with the first's in-flight one and leave the preview black.
    private var isOpening = false

    /** Returns false (after logging) instead of throwing, so no open path can crash the app. */
    private suspend fun openCameraInternal(cameraId: String, texture: SurfaceTexture, width: Int, height: Int): Boolean {
        if (isOpening) return true
        isOpening = true
        return try {
            openCameraInternalLocked(cameraId, texture, width, height)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Opening camera $cameraId failed", e)
            closeSessions()
            cameraDevice?.close()
            cameraDevice = null
            false
        } finally {
            isOpening = false
        }
    }

    private suspend fun openCameraInternalLocked(cameraId: String, texture: SurfaceTexture, width: Int, height: Int) {
        // Guard against being called while a device from a previous open is still around
        // (e.g. a stray resume before pause finished) - Camera2 refuses a second open otherwise.
        closeSessions()
        cameraDevice?.close()
        cameraDevice = null
        activeRecorderSurface = null
        activeRecordingFpsRange = null
        isHighSpeedRecording = false
        _state.update { it.copy(ready = false, errorMessage = null) }

        retainedTexture = texture
        previewWidth = width
        previewHeight = height

        cancelFocusRevert()
        lockedRegion = null

        val prof = CameraCapabilities.loadProfile(cameraManager, cameraId)
        profile = prof

        val previewSize = choosePreviewSize(prof)
        normalPreviewSize = previewSize
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(texture)

        jpegReader?.close()
        rawReader?.close()

        val availableRatios = CameraCapabilities.availableAspectRatios(prof)
        val chosenRatio = _state.value.photoAspectRatio.takeIf { it in availableRatios }
            ?: availableRatios.firstOrNull() ?: PhotoAspectRatio.RATIO_16_9
        val jpegSize = CameraCapabilities.bestJpegSize(prof, chosenRatio)
            ?: prof.jpegSizes.firstOrNull() ?: previewSize
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)

        rawReader = if (prof.supportsRaw && prof.rawSize != null) {
            ImageReader.newInstance(prof.rawSize.width, prof.rawSize.height, ImageFormat.RAW_SENSOR, 2)
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
                rawEnabled = settingsStore.rawEnabled && prof.supportsRaw,
                videoSizeOptions = prof.videoSizes,
                selectedVideoSize = restoredVideoSize,
                selectedFps = restoredFps,
                minZoomRatio = prof.zoomRatioRange.lower,
                maxZoomRatio = prof.zoomRatioRange.upper,
                // Always back to 1x on a fresh open (launch, resume-from-background, camera
                // switch) - a zoom left over from before the app was backgrounded shouldn't
                // silently carry over.
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
                nightExtensionSupported = prof.nightExtensionSupported,
                nightCaptureProgress = null,
            )
        }

        val device = openDevice(cameraId)
        cameraDevice = device
        isPaused = false
        configureSessionForMode(device)
        _state.update { it.copy(ready = true) }

        if (_state.value.lastCapture == null) {
            val recent = withContext(Dispatchers.IO) { MediaStoreSaver.findMostRecentCapture(appContext) }
            if (recent != null) {
                _state.update { if (it.lastCapture == null) it.copy(lastCapture = recent) else it }
            }
        }
    }

    private suspend fun openDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera disconnected while opening"))
                    } else {
                        onDeviceLost(camera)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera error code $error"))
                    } else {
                        Log.w(TAG, "Camera device error $error after open")
                        onDeviceLost(camera)
                    }
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    /**
     * The device died after it was already open (another app with higher priority took the
     * camera, or a HAL error). Drop our reference and reconnect - unless we're backgrounded, in
     * which case onResume will reopen it anyway.
     */
    private fun onDeviceLost(camera: CameraDevice) = onMain {
        if (cameraDevice !== camera) return@onMain
        cameraDevice = null
        stillSession = null
        extensionSession = null
        _state.update { it.copy(ready = false) }
        scheduleRecovery()
    }

    // ----- Sessions -----

    private fun wantsNightSession(): Boolean =
        _state.value.mode == CameraMode.NIGHT && profile?.nightExtensionSupported == true

    private suspend fun configureSessionForMode(device: CameraDevice) {
        if (wantsNightSession()) createNightSession(device) else createStillSession(device)
    }

    private fun closeSessions() {
        stillSession?.let { s -> runCatching { s.close() } }
        stillSession = null
        extensionSession?.let { s -> runCatching { s.close() } }
        extensionSession = null
        nightJpegReader?.close()
        nightJpegReader = null
    }

    private suspend fun createStillSession(device: CameraDevice) {
        val preview = previewSurface ?: return
        // Coming back from night mode (whose extension dictates its own preview size) or a
        // high-speed recording: put the preview buffer back to its normal size first.
        normalPreviewSize?.let { size ->
            if (_state.value.previewBufferSize != size) {
                retainedTexture?.setDefaultBufferSize(size.width, size.height)
                _state.update { it.copy(previewBufferSize = size) }
            }
        }
        val surfaces = listOfNotNull(preview, jpegReader?.surface, rawReader?.surface)
        val session = createSession(device, surfaces)
        stillSession = session
        startPreviewRepeating(session)
    }

    private suspend fun createSession(device: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
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
                    } else {
                        session.close()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("High-speed session configuration failed"))
                }
            }, backgroundHandler)
        }

    // ----- Night (Camera2 Extensions) -----

    /**
     * Night photos go through the vendor's own multi-frame night pipeline via Camera2
     * Extensions (EXTENSION_NIGHT) - the same burst-merge-denoise processing the stock camera
     * uses, which no app can replicate from single frames. It runs on its own session type
     * with its own supported preview/JPEG sizes, so switching into/out of night mode means
     * swapping sessions.
     */
    private suspend fun createNightSession(device: CameraDevice) {
        val prof = profile ?: return
        val preview = previewSurface ?: return
        val texture = retainedTexture ?: return
        val ext = cameraManager.getCameraExtensionCharacteristics(prof.cameraId)
        val night = CameraExtensionCharacteristics.EXTENSION_NIGHT

        val jpegSizes = ext.getExtensionSupportedSizes(night, ImageFormat.JPEG)
        val ratio = _state.value.photoAspectRatio.ratio
        val jpegSize = jpegSizes
            .filter { kotlin.math.abs(it.width.toFloat() / it.height - ratio) < 0.05f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: error("Night extension has no JPEG sizes")
        val jpegAspect = jpegSize.width.toFloat() / jpegSize.height

        val previewSizes = ext.getExtensionSupportedSizes(night, SurfaceTexture::class.java)
        val previewSize = previewSizes
            .filter { kotlin.math.abs(it.width.toFloat() / it.height - jpegAspect) < 0.05f && it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
            ?: previewSizes.firstOrNull()
            ?: error("Night extension has no preview sizes")

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        _state.update { it.copy(previewBufferSize = previewSize) }

        nightJpegReader?.close()
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        nightJpegReader = reader

        val session = suspendCancellableCoroutine<CameraExtensionSession> { cont ->
            val config = ExtensionSessionConfiguration(
                night,
                listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)),
                cameraExecutor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(session: CameraExtensionSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraExtensionSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Night session configuration failed"))
                    }
                },
            )
            try {
                device.createExtensionSession(config)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
        extensionSession = session
        nightRequestKeys = runCatching { ext.getAvailableCaptureRequestKeys(night) }.getOrDefault(emptySet())
        startNightRepeating(session)
    }

    // Extension sessions only honour the keys the vendor lists; set nothing else.
    private var nightRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()

    private fun <T> CaptureRequest.Builder.setIfNight(key: CaptureRequest.Key<T>, value: T) {
        if (key in nightRequestKeys) set(key, value)
    }

    private fun applyNightParams(builder: CaptureRequest.Builder) {
        val s = _state.value
        builder.setIfNight(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)
        builder.setIfNight(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.exposureCompensation)
        builder.setIfNight(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.setIfNight(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        // Continuous AF honours AF regions on its own, so a tap can steer focus without an
        // AF trigger (triggers in a repeating extension request aren't well-defined).
        builder.setIfNight(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        lockedRegion?.let { region ->
            builder.setIfNight(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.setIfNight(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private val nightPreviewCallback = object : CameraExtensionSession.ExtensionCaptureCallback() {
        override fun onCaptureResultAvailable(
            session: CameraExtensionSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            updateFocusStateFrom(result)
        }
    }

    private fun startNightRepeating(session: CameraExtensionSession) {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        safeSessionOp("night setRepeatingRequest") {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            applyNightParams(builder)
            session.setRepeatingRequest(builder.build(), cameraExecutor, nightPreviewCallback)
        }
    }

    private suspend fun performNightCapture(
        session: CameraExtensionSession,
        device: CameraDevice,
        prof: CameraProfile,
    ): CapturedMedia {
        val reader = nightJpegReader ?: error("Camera not ready")
        val displayName = "NIGHT_${System.currentTimeMillis()}"
        _state.update { it.copy(nightCaptureProgress = -1) }
        try {
            return suspendCancellableCoroutine { cont ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val uri = PhotoCapture.saveJpeg(appContext, image, displayName)
                        if (cont.isActive) cont.resume(CapturedMedia(uri, isVideo = false, timestampMs = System.currentTimeMillis()))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        image.close()
                        r.setOnImageAvailableListener(null, null)
                    }
                }, backgroundHandler)

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                applyNightParams(builder)
                builder.setIfNight(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees(prof))
                builder.setIfNight(CaptureRequest.JPEG_QUALITY, 100.toByte())

                val callback = object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureProcessProgressed(
                        session: CameraExtensionSession,
                        request: CaptureRequest,
                        progress: Int,
                    ) {
                        _state.update { it.copy(nightCaptureProgress = progress.coerceIn(0, 100)) }
                    }

                    override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Falha na foto noturna"))
                    }

                    override fun onCaptureSequenceAborted(session: CameraExtensionSession, sequenceId: Int) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Foto noturna cancelada"))
                    }
                }
                try {
                    session.capture(builder.build(), cameraExecutor, callback)
                } catch (e: Exception) {
                    reader.setOnImageAvailableListener(null, null)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } finally {
            _state.update { it.copy(nightCaptureProgress = null) }
        }
    }

    // ----- Live preview requests -----

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
        safeSessionOp("setRepeatingRequest") {
            val builder = buildLiveRequestBuilder(device) ?: return@safeSessionOp
            session.setRepeatingRequest(builder.build(), afStateCallback, backgroundHandler)
        }
    }

    private fun updateFocusStateFrom(result: CaptureResult) {
        if (_state.value.focusIndicator == null) return
        val newState = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            -> FocusState.FOCUSED
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> FocusState.FAILED
            null -> return
            else -> FocusState.SEARCHING
        }
        _state.update { if (it.focusState != newState) it.copy(focusState = newState) else it }
    }

    /**
     * Only meaningful while a focus reticle is on screen - reads CONTROL_AF_STATE off every
     * frame so the reticle can show "searching" vs. actually landed vs. failed.
     */
    private val afStateCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            updateFocusStateFrom(result)
        }
    }

    /** Night video applies to video + timelapse, but never to a constrained high-speed mode. */
    private fun nightVideoActive(s: CameraUiState): Boolean {
        if (!s.mode.isVideo || !s.nightVideoEnabled) return false
        if (s.mode == CameraMode.VIDEO && s.selectedVideoSize?.isHighSpeed(s.selectedFps) == true) return false
        return true
    }

    private fun applyLiveParams(builder: CaptureRequest.Builder) {
        val s = _state.value
        val prof = profile
        val night = nightVideoActive(s)

        if (night) {
            // Low-light video: denoising is what makes a dark clip watchable, and letting AE
            // stretch each frame's exposure (fps floor dropped to e.g. 15) roughly doubles the
            // light gathered per frame.
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            if (prof != null) {
                val fps = if (s.mode == CameraMode.TIMELAPSE) VideoRecorder.TIMELAPSE_PLAYBACK_FPS else s.selectedFps
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, CameraCapabilities.nightFpsRange(prof, fps))
            }
        } else {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        if (prof?.supportsOis == true) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.exposureCompensation)

        // The live/repeating stream never carries a flash-firing AE mode (ON_ALWAYS_FLASH /
        // ON_AUTO_FLASH) - several HALs interpret that literally per frame and strobe the LED
        // on every preview frame. The real flash only fires during precapture + the still
        // request. With flash ON we just light a steady, dimmed focus-assist torch.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        when (s.flashMode) {
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            FlashMode.ON -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
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
        extensionSession?.let { startNightRepeating(it); return }
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
        if (recorder != null) return
        settingsStore.saveVideoSettings(sizeOption.size.width, sizeOption.size.height, fps)
        _state.update { it.copy(selectedVideoSize = sizeOption, selectedFps = fps) }
        refreshPreviewRepeating()
    }

    fun setNightVideoEnabled(enabled: Boolean) {
        settingsStore.nightVideoEnabled = enabled
        _state.update { it.copy(nightVideoEnabled = enabled) }
        refreshPreviewRepeating()
    }

    fun setTimelapseSpeed(speed: Int) {
        if (recorder != null || speed !in TIMELAPSE_SPEEDS) return
        settingsStore.timelapseSpeed = speed
        _state.update { it.copy(timelapseSpeed = speed) }
    }

    suspend fun setMode(mode: CameraMode) {
        if (recorder != null || captureInProgress) return
        if (_state.value.mode == mode) return
        clearFocusLockSilently()
        _state.update { it.copy(mode = mode) }
        reconfigureForModeIfNeeded()
        // Night-video params depend on the mode too (e.g. Foto -> Vídeo with night video on).
        refreshPreviewRepeating()
    }

    private var reconfiguring = false

    /**
     * Swaps between the regular session and the night extension session to match the current
     * mode. Loops until they agree, so rapid mode taps while a swap is in flight still end up
     * on the right session.
     */
    private suspend fun reconfigureForModeIfNeeded() {
        if (reconfiguring || isOpening) return
        reconfiguring = true
        try {
            while (true) {
                val device = cameraDevice ?: return
                val wantNight = wantsNightSession()
                val haveNight = extensionSession != null
                val haveAny = stillSession != null || extensionSession != null
                if (haveAny && wantNight == haveNight) return
                closeSessions()
                configureSessionForMode(device)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Session reconfiguration failed", e)
            if (_state.value.mode == CameraMode.NIGHT) {
                // Night extension refused to start - degrade to the single-frame night scene
                // fallback on the regular session instead of leaving a dead preview.
                profile = profile?.copy(nightExtensionSupported = false)
                _state.update { it.copy(nightExtensionSupported = false) }
                cameraDevice?.let { d -> runCatching { createStillSession(d) } }
            } else {
                scheduleRecovery()
            }
        } finally {
            reconfiguring = false
        }
    }

    suspend fun setPhotoAspectRatio(ratio: PhotoAspectRatio) {
        if (recorder != null || captureInProgress || isOpening) return
        val prof = profile ?: return
        val device = cameraDevice ?: return
        if (ratio !in _state.value.availableAspectRatios) return
        val newSize = CameraCapabilities.bestJpegSize(prof, ratio) ?: return

        closeSessions()
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(newSize.width, newSize.height, ImageFormat.JPEG, 2)
        settingsStore.photoAspectRatio = ratio
        _state.update { it.copy(photoAspectRatio = ratio) }
        try {
            configureSessionForMode(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recreating session for aspect ratio failed", e)
            scheduleRecovery()
        }
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
    private var focusRevertJob: Job? = null

    private fun cancelFocusRevert() {
        focusRevertJob?.cancel()
        focusRevertJob = null
    }

    private fun meteringRectForCurrentPreview(xInView: Float, yInView: Float): MeteringRectangle? {
        val prof = profile ?: return null
        if (previewWidth == 0 || previewHeight == 0) return null
        return meteringRectForTap(
            xInView, yInView, previewWidth, previewHeight,
            _state.value.previewBufferSize,
            prof.activeArraySize, prof.sensorOrientation,
            prof.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            _state.value.zoomRatio,
        )
    }

    /** Quick tap: focuses at the point, then reverts to continuous AF/AE shortly after. */
    fun focusTap(xInView: Float, yInView: Float) {
        if (isHighSpeedRecording || captureInProgress) return
        val device = cameraDevice ?: return
        val region = meteringRectForCurrentPreview(xInView, yInView) ?: return

        cancelFocusRevert()
        lockedRegion = region
        _state.update {
            it.copy(
                focusIndicator = FocusIndicator(xInView, yInView, locked = false),
                focusState = FocusState.SEARCHING,
                exposureCompensation = 0,
            )
        }

        val night = extensionSession
        if (night != null) {
            startNightRepeating(night)
        } else {
            val session = stillSession ?: return
            safeSessionOp("focusTap capture") {
                val builder = buildLiveRequestBuilder(device) ?: return@safeSessionOp
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), afStateCallback, backgroundHandler)
            }
        }

        focusRevertJob = controllerScope.launch {
            delay(2500)
            lockedRegion = null
            _state.update { it.copy(focusIndicator = null) }
            refreshPreviewRepeating()
        }
    }

    /** Long-press: focuses and locks exposure at the point until the user taps elsewhere. */
    fun focusLongPressLock(xInView: Float, yInView: Float) {
        if (isHighSpeedRecording || captureInProgress) return
        val device = cameraDevice ?: return
        val region = meteringRectForCurrentPreview(xInView, yInView) ?: return

        cancelFocusRevert()
        lockedRegion = region
        _state.update {
            it.copy(
                focusIndicator = FocusIndicator(xInView, yInView, locked = true),
                focusState = FocusState.SEARCHING,
                exposureCompensation = 0,
            )
        }

        val night = extensionSession
        if (night != null) {
            startNightRepeating(night)
            return
        }
        val session = stillSession ?: return
        safeSessionOp("focusLongPressLock") {
            val builder = buildLiveRequestBuilder(device) ?: return@safeSessionOp
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), afStateCallback, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
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
        clearFocusLockSilently()
        refreshPreviewRepeating()
    }

    private fun clearFocusLockSilently() {
        cancelFocusRevert()
        lockedRegion = null
        _state.update { it.copy(focusIndicator = null, exposureCompensation = 0) }
    }

    // ----- Photo capture -----

    suspend fun capturePhoto(): CapturedMedia {
        if (captureInProgress) error("Captura em andamento")
        val device = cameraDevice ?: error("Camera not ready")
        val prof = profile ?: error("Camera not ready")
        captureInProgress = true
        try {
            extensionSession?.let { return performNightCapture(it, device, prof) }

            val session = stillSession ?: error("Camera not ready")
            // A real camera flash needs a metering pre-flash to figure out the right power for
            // the actual shot - skipping straight to a full-power strobe blows highlights out.
            val flash = _state.value.flashMode
            val usedPrecapture = (flash == FlashMode.ON || flash == FlashMode.AUTO) && prof.hasFlash &&
                _state.value.mode != CameraMode.NIGHT
            if (usedPrecapture) runAePrecapture(session, device, flash)

            return try {
                performStillCapture(session, device, prof)
            } finally {
                if (usedPrecapture) refreshPreviewRepeating()
            }
        } finally {
            captureInProgress = false
        }
    }

    /**
     * Runs the AE precapture metering sequence with the flash AE mode active, so the HAL
     * actually fires its pre-flash and computes the right power for the real shot. Waits until
     * AE has gone through PRECAPTURE (or a handful of frames, for HALs that never report it)
     * and settled, with a timeout as the last resort.
     */
    private suspend fun runAePrecapture(session: CameraCaptureSession, device: CameraDevice, flash: FlashMode) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            var sawPrecapture = false
            var frames = 0
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                frames++
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                val settled = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                if (settled && (sawPrecapture || frames >= 6) && deferred.isActive) deferred.complete(Unit)
            }
        }

        safeSessionOp("ae precapture") {
            val builder = buildLiveRequestBuilder(device) ?: return@safeSessionOp
            builder.set(CaptureRequest.CONTROL_AE_MODE, flash.toAeMode())
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(builder.build(), callback, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), callback, backgroundHandler)
        }

        kotlinx.coroutines.withTimeoutOrNull(1500) { deferred.await() }
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
        val nightFallback = s.mode == CameraMode.NIGHT
        // RAW in the night fallback would just be a single dark frame - skip it there.
        val raw = if (s.rawEnabled && !nightFallback) rawReader else null
        val displayName = (if (nightFallback) "NIGHT_" else "RAW_") + System.currentTimeMillis()

        // Everything below is only touched from backgroundHandler callbacks.
        var jpegUri: android.net.Uri? = null
        var rawDone = raw == null
        var captureResultHolder: TotalCaptureResult? = null
        var rawImageHolder: android.media.Image? = null
        var finished = false

        fun finishWith(error: Throwable?) {
            if (finished) return
            if (error == null && (jpegUri == null || !rawDone)) return
            finished = true
            jpeg.setOnImageAvailableListener(null, null)
            raw?.setOnImageAvailableListener(null, null)
            rawImageHolder?.close()
            rawImageHolder = null
            if (!cont.isActive) return
            if (error != null) {
                cont.resumeWithException(error)
            } else {
                cont.resume(CapturedMedia(jpegUri!!, isVideo = false, timestampMs = System.currentTimeMillis()))
            }
        }

        fun saveRaw(image: android.media.Image, result: TotalCaptureResult) {
            try {
                val chars = PhotoCapture.dngCharacteristics(cameraManager, prof.cameraId, result, image)
                runCatching { PhotoCapture.saveRaw(appContext, image, chars, result, displayName) }
                    .recoverCatching {
                        // Physical-lens metadata didn't fit this frame - fall back to the
                        // logical camera's characteristics rather than losing the DNG.
                        PhotoCapture.saveRaw(appContext, image, cameraManager.getCameraCharacteristics(prof.cameraId), result, displayName)
                    }
                    .onFailure { Log.e(TAG, "Saving DNG failed", it) }
            } finally {
                image.close()
                rawDone = true
            }
            finishWith(null)
        }

        jpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                jpegUri = PhotoCapture.saveJpeg(appContext, image, displayName)
            } catch (e: Exception) {
                Log.e(TAG, "Saving JPEG failed", e)
                finishWith(e)
                return@setOnImageAvailableListener
            } finally {
                image.close()
            }
            finishWith(null)
        }, backgroundHandler)

        if (raw != null) {
            raw.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val result = captureResultHolder
                if (result != null) saveRaw(image, result) else rawImageHolder = image
            }, backgroundHandler)
        }

        val request = try {
            PhotoCapture.buildCaptureRequest(
                device, jpeg.surface, raw?.surface,
                jpegOrientation = jpegOrientationDegrees(prof),
                flashMode = if (nightFallback) FlashMode.OFF else s.flashMode,
                zoomRatio = s.zoomRatio,
                exposureCompensation = s.exposureCompensation,
                afRegions = lockedRegion?.let { arrayOf(it) },
                ois = prof.supportsOis,
                nightSceneFallback = nightFallback && prof.supportsNightSceneMode,
            )
        } catch (e: Exception) {
            finishWith(e)
            return@suspendCancellableCoroutine
        }

        try {
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    captureResultHolder = result
                    val pendingRawImage = rawImageHolder
                    if (raw != null && pendingRawImage != null) {
                        rawImageHolder = null
                        saveRaw(pendingRawImage, result)
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    finishWith(IllegalStateException("Falha ao capturar foto (reason=${failure.reason})"))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            jpeg.setOnImageAvailableListener(null, null)
            raw?.setOnImageAvailableListener(null, null)
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    // ----- Video capture -----

    suspend fun startVideoRecording() {
        if (recorder != null || isOpening) return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val prof = profile ?: return
        val s = _state.value
        val videoOption = s.selectedVideoSize ?: prof.videoSizes.firstOrNull() ?: return
        val timelapse = s.mode == CameraMode.TIMELAPSE
        val fps = if (timelapse) VideoRecorder.TIMELAPSE_PLAYBACK_FPS else s.selectedFps
        val useHighSpeed = !timelapse && videoOption.isHighSpeed(fps)

        clearFocusLockSilently()
        closeSessions()

        val tempFile = VideoRecorder.tempOutputFile(appContext)
        try {
            val orientation = videoOrientationDegrees(prof)
            val mr = if (timelapse) {
                VideoRecorder.createTimelapse(appContext, tempFile, videoOption, s.timelapseSpeed, orientation)
            } else {
                VideoRecorder.create(appContext, tempFile, videoOption, fps, orientation)
            }
            recorder = mr
            recordingFile = tempFile

            val recorderSurface = mr.surface
            activeRecorderSurface = recorderSurface
            activeRecordingFpsRange = when {
                useHighSpeed -> android.util.Range(fps, fps)
                nightVideoActive(s) -> CameraCapabilities.nightFpsRange(prof, fps)
                else -> CameraCapabilities.bestFpsRange(prof, fps)
            }
            isHighSpeedRecording = useHighSpeed

            if (useHighSpeed) {
                // A constrained high-speed session requires every surface's buffer size to
                // match a size from getHighSpeedVideoSizes() - the preview too. The UI's
                // TextureView transform reads previewBufferSize, so keep that in sync.
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
        } catch (e: CancellationException) {
            finishRecorder(saveClip = false)
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Starting recording failed", e)
            // Put the camera back into a usable photo/preview state instead of leaving a
            // closed session behind (frozen preview, every photo failing "not ready").
            finishRecorder(saveClip = false)
            tempFile.delete()
            closeSessions()
            cameraDevice?.let { d ->
                try {
                    configureSessionForMode(d)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.e(TAG, "Restoring preview after failed recording start", e2)
                    scheduleRecovery()
                }
            }
            throw e
        }
    }

    /**
     * Stops the recorder and resets all recording state synchronously (no suspension), then
     * saves the clip on IO in the background if asked to. Returns the temp file when there's a
     * valid clip to save, or null when there was nothing/it was discarded.
     */
    private fun stopRecorderSync(): File? {
        val mr = recorder ?: return null
        val tempFile = recordingFile
        val wasHighSpeed = isHighSpeedRecording

        stillSession?.let { s -> runCatching { s.stopRepeating() } }
        val stoppedCleanly = try {
            mr.stop()
            true
        } catch (e: RuntimeException) {
            // Typically "stop failed" because no frame ever reached the encoder (very short
            // clip, or a timelapse stopped before its first frame) - the file is unplayable.
            Log.w(TAG, "MediaRecorder.stop() failed, discarding clip", e)
            false
        }
        runCatching { mr.release() }
        recorder = null
        recordingFile = null
        activeRecorderSurface = null
        activeRecordingFpsRange = null
        isHighSpeedRecording = false

        if (wasHighSpeed) {
            normalPreviewSize?.let { size ->
                retainedTexture?.setDefaultBufferSize(size.width, size.height)
                _state.update { it.copy(previewBufferSize = size) }
            }
        }
        _state.update { it.copy(isRecording = false, isHighSpeedRecording = false) }

        if (!stoppedCleanly || tempFile == null || !tempFile.exists() || tempFile.length() == 0L) {
            tempFile?.delete()
            return null
        }
        return tempFile
    }

    /** Fire-and-forget variant for teardown paths (pause, surface lost, recovery). */
    private fun finishRecorder(saveClip: Boolean) {
        val file = stopRecorderSync() ?: return
        if (!saveClip) {
            file.delete()
            return
        }
        controllerScope.launch {
            runCatching { saveClipToGallery(file) }
                .onSuccess { setLastCapture(it) }
                .onFailure { Log.e(TAG, "Saving recording during teardown failed", it) }
        }
    }

    private suspend fun saveClipToGallery(file: File): CapturedMedia {
        val uri = withContext(Dispatchers.IO) {
            MediaStoreSaver.saveVideo(appContext, file, "RAWCAM_${System.currentTimeMillis()}")
        }
        return CapturedMedia(uri, isVideo = true, timestampMs = System.currentTimeMillis())
    }

    /**
     * Stops and saves the current clip. With [reopenPreview] false (app going to background)
     * the camera is released immediately, before any suspension point, so a quick resume
     * can't race with it. Returns null if the clip had to be discarded.
     */
    suspend fun stopVideoRecording(reopenPreview: Boolean = true): CapturedMedia? {
        if (recorder == null) return null
        val file = stopRecorderSync()
        closeSessions()

        if (reopenPreview) {
            cameraDevice?.let { device ->
                try {
                    configureSessionForMode(device)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Restoring preview after recording failed", e)
                    scheduleRecovery()
                }
            }
        } else {
            pauseCamera()
        }

        return file?.let { saveClipToGallery(it) }
    }

    fun setLastCapture(media: CapturedMedia) {
        _state.update { it.copy(lastCapture = media) }
    }

    suspend fun deleteCapture(media: CapturedMedia) {
        withContext(Dispatchers.IO) { MediaStoreSaver.delete(appContext, media.uri) }
        if (_state.value.lastCapture?.uri == media.uri) {
            // Fall back to the next-newest capture so the bubble doesn't vanish while older
            // shots still exist.
            val next = withContext(Dispatchers.IO) { MediaStoreSaver.findMostRecentCapture(appContext) }
            _state.update { if (it.lastCapture?.uri == media.uri) it.copy(lastCapture = next) else it }
        }
    }

    // ----- Teardown -----

    private fun closeCameraInternal(keepPreview: Boolean) {
        closeSessions()
        cameraDevice?.close()
        cameraDevice = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        if (!keepPreview) {
            previewSurface?.release()
            previewSurface = null
        }
    }

    fun release() {
        finishRecorder(saveClip = false)
        closeCameraInternal(keepPreview = false)
        retainedTexture = null
        controllerScope.cancel()
    }

    // ----- Helpers -----

    private fun choosePreviewSize(prof: CameraProfile): Size {
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

    /**
     * Standard Camera2 formula: (sensorOrientation ± deviceOrientation + 360) % 360, with the
     * device term negated for the front camera (it's mirrored relative to how the phone turns).
     * In plain portrait (deviceOrientation 0) both cameras reduce to sensorOrientation itself.
     */
    private fun jpegOrientationDegrees(prof: CameraProfile): Int {
        val front = prof.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val device = if (front) -deviceOrientation else deviceOrientation
        return (prof.sensorOrientation + device + 360) % 360
    }

    private fun videoOrientationDegrees(prof: CameraProfile): Int = jpegOrientationDegrees(prof)

    private fun meteringRectForTap(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        bufferSize: Size?,
        activeArray: Rect,
        sensorOrientation: Int,
        facingFront: Boolean,
        zoomRatio: Float,
    ): MeteringRectangle {
        // The preview is center-cropped to fill the view (applyPreviewTransform), so part of
        // the buffer is off-screen. Undo that crop first, so a tap near the screen edge maps to
        // the matching point in the frame rather than being squeezed toward the middle.
        var nx = (x / viewWidth).coerceIn(0f, 1f)
        var ny = (y / viewHeight).coerceIn(0f, 1f)
        if (bufferSize != null && bufferSize.width > 0 && bufferSize.height > 0) {
            val swapped = sensorOrientation % 180 != 0
            val bw = (if (swapped) bufferSize.height else bufferSize.width).toFloat()
            val bh = (if (swapped) bufferSize.width else bufferSize.height).toFloat()
            val scale = maxOf(viewWidth / bw, viewHeight / bh)
            val shownW = bw * scale
            val shownH = bh * scale
            nx = ((x - (viewWidth - shownW) / 2f) / shownW).coerceIn(0f, 1f)
            ny = ((y - (viewHeight - shownH) / 2f) / shownH).coerceIn(0f, 1f)
        }

        // The front preview is shown mirrored, so un-mirror in SCREEN space (before rotating
        // into sensor space) - mirroring after the rotation flips the wrong axis.
        if (facingFront) nx = 1f - nx

        val (rx, ry) = when (sensorOrientation) {
            90 -> ny to (1 - nx)
            270 -> (1 - ny) to nx
            180 -> (1 - nx) to (1 - ny)
            else -> nx to ny
        }

        // CONTROL_AF_REGIONS/CONTROL_AE_REGIONS are always expressed in full, un-zoomed active
        // array coordinates - the framework does not remap them for CONTROL_ZOOM_RATIO. So a
        // tap on a zoomed-in preview only covers the visible crop of the sensor.
        val z = zoomRatio.coerceAtLeast(0.01f)
        val visibleWidth = activeArray.width() / z
        val visibleHeight = activeArray.height() / z
        val visibleLeft = activeArray.left + (activeArray.width() - visibleWidth) / 2f
        val visibleTop = activeArray.top + (activeArray.height() - visibleHeight) / 2f

        val sensorX = (visibleLeft + rx * visibleWidth).toInt()
        val sensorY = (visibleTop + ry * visibleHeight).toInt()
        // Keep the metering box a consistent ~20% of the currently visible (zoomed) frame.
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
