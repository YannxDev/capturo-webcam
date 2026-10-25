package io.github.yannick.capturowebcam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.yannick.capturowebcam.MainActivity
import io.github.yannick.capturowebcam.data.SettingsStore
import io.github.yannick.capturowebcam.data.WebcamBus
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

/**
 * Service de premier plan : maintient la caméra et le serveur MJPEG actifs
 * même quand l'écran se verrouille. Le micro est diffusé en PCM 48 kHz mono.
 */
class WebcamService : Service() {

    companion object {
        const val ACTION_START = "io.github.yannick.capturowebcam.action.START"
        const val ACTION_STOP = "io.github.yannick.capturowebcam.action.STOP"
        const val ACTION_SET_PROFILE = "io.github.yannick.capturowebcam.action.SET_PROFILE"
        const val ACTION_SET_AUDIO = "io.github.yannick.capturowebcam.action.SET_AUDIO"
        const val ACTION_SET_FACING = "io.github.yannick.capturowebcam.action.SET_FACING"
        const val ACTION_SET_FPS = "io.github.yannick.capturowebcam.action.SET_FPS"
        const val ACTION_SET_TORCH = "io.github.yannick.capturowebcam.action.SET_TORCH"

        const val EXTRA_HEIGHT = "height"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_FACING = "facing"
        const val EXTRA_FPS = "fps"
        const val EXTRA_TORCH = "torch"

        const val PORT = 8080
        const val AUDIO_RATE = 48000

        private const val TAG = "CapturoWebcam"
        private const val CHANNEL_ID = "capturo_webcam"
        private const val NOTIFICATION_ID = 42

        private const val INTERVAL_4K_NS = 150_000_000L
        private const val INTERVAL_1080_NS = 100_000_000L
        private const val INTERVAL_720_NS = 50_000_000L
        private const val MAX_CAMERA_RETRIES = 6
    }

    private var cameraManager: CameraManager? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var server: MjpegServer? = null
    private var discovery: DiscoveryResponder? = null
    private var displayManager: DisplayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var requestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    @Volatile
    private var started = false

    @Volatile
    private var cameraGeneration = 0

    @Volatile
    private var cameraRetries = 0

    @Volatile
    private var lensFacing = SettingsStore.LENS_BACK

    /**
     * Facing physique réellement sélectionné. Certains HAL (dont le TECNO
     * KM6) inversent LENS_FACING ; on s'en sert pour la rotation et les logs.
     */
    @Volatile
    private var physicalFacing = SettingsStore.LENS_BACK

    @Volatile
    private var requestedHeight = SettingsStore.HEIGHT_HD

    @Volatile
    private var requestedFps = SettingsStore.fps()

    @Volatile
    private var jpegQuality = 80

    @Volatile
    private var captureHeight = 0

    @Volatile
    private var audioEnabled = true

    @Volatile
    private var audioRunning = false

    @Volatile
    private var audioGeneration = 0

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var torchEnabled = false

    @Volatile
    private var torchAvailable = false

    private var wakeLock: PowerManager.WakeLock? = null

    private var audioThread: Thread? = null

    private var rotationDegrees = 0
    private var lastDisplayRotation = -1
    private var lastFrameNs = 0L
    private var frameCount = 0

    private val rotationRestart = Runnable { restartCameraInternal() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !started) return
            val rotation = displayRotation()
            if (rotation == lastDisplayRotation) return
            lastDisplayRotation = rotation
            Log.i(TAG, "rotation d'écran -> ${rotation * 90}°")
            cameraHandler?.removeCallbacks(rotationRestart)
            cameraHandler?.postDelayed(rotationRestart, 350)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        WebcamBus.running.value = false
        refreshFromSettings()

        cameraManager = getSystemService(CameraManager::class.java)
        val thread = HandlerThread("capturo-camera").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        server = MjpegServer(
            PORT,
            { count -> WebcamBus.clients.value = count },
            { SettingsStore.token() },
        )
        discovery = DiscoveryResponder(PORT) { SettingsStore.token() }
        displayManager = getSystemService(DisplayManager::class.java)
        lastDisplayRotation = displayRotation()
        // Le callback de rotation doit rester réactif même quand le thread
        // caméra ré-encode une image 1080p/4K.
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                cameraHandler?.post { stopStreamingInternal() }
                return START_NOT_STICKY
            }

            ACTION_SET_PROFILE -> {
                val height = intent?.getIntExtra(EXTRA_HEIGHT, requestedHeight)
                    ?: requestedHeight
                val quality = intent?.getIntExtra(EXTRA_QUALITY, jpegQuality)
                    ?: jpegQuality
                requestedHeight = height
                jpegQuality = quality.coerceIn(1, 100)
                SettingsStore.setProfile(requestedHeight, jpegQuality)
                WebcamBus.height.value = requestedHeight
                WebcamBus.quality.value = jpegQuality
                if (started) {
                    cameraHandler?.post { restartCameraInternal() }
                } else {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_SET_AUDIO -> {
                val enabled = intent?.getBooleanExtra(EXTRA_AUDIO, audioEnabled)
                    ?: audioEnabled
                if (started) {
                    cameraHandler?.post { setAudioEnabledInternal(enabled) }
                } else {
                    audioEnabled = enabled
                    audioRunning = false
                    SettingsStore.setAudio(enabled)
                    WebcamBus.audio.value = enabled && hasAudioPermission()
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_SET_FACING -> {
                val facing = intent?.getIntExtra(EXTRA_FACING, lensFacing)
                    ?: lensFacing
                lensFacing = facing
                SettingsStore.setLensFacing(facing)
                WebcamBus.lensFacing.value = facing
                if (started) {
                    cameraHandler?.post { restartCameraInternal() }
                } else {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_SET_FPS -> {
                val fps = intent?.getIntExtra(EXTRA_FPS, requestedFps)
                    ?: requestedFps
                requestedFps = if (fps in SettingsStore.FPS_CHOICES) fps else 30
                SettingsStore.setFps(requestedFps)
                WebcamBus.fps.value = requestedFps
                updateNotification()
                if (started) {
                    cameraHandler?.post { restartCameraInternal() }
                } else {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_SET_TORCH -> {
                val enabled = intent?.getBooleanExtra(EXTRA_TORCH, torchEnabled)
                    ?: torchEnabled
                setTorchInternal(enabled)
                if (!started) stopSelf(startId)
                return START_NOT_STICKY
            }

            else -> {
                refreshFromSettings()
                if (audioEnabled && !hasAudioPermission()) {
                    audioEnabled = false
                    SettingsStore.setAudio(false)
                    WebcamBus.audio.value = false
                }
                try {
                    startForegroundCompat()
                } catch (error: Exception) {
                    Log.e(TAG, "Service caméra refusé", error)
                    WebcamBus.error.value = "Service caméra refusé : ${error.message}"
                    WebcamBus.running.value = false
                    WebcamBus.starting.value = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (started) {
                    WebcamBus.starting.value = false
                    updateNotification()
                } else {
                    WebcamBus.error.value = null
                    WebcamBus.starting.value = true
                    WebcamBus.status.value = "Démarrage…"
                    cameraHandler?.post { startStreamingInternal() }
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        cameraHandler?.post { stopStreamingInternal() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        started = false
        cameraGeneration++
        stopAudioInternal()
        stopCameraInternal()
        setTorchInternal(false)
        releaseWakeLock()
        discovery?.stop()
        server?.stop()
        cameraThread?.quitSafely()
        WebcamBus.running.value = false
        WebcamBus.starting.value = false
        WebcamBus.clients.value = 0
        WebcamBus.urls.value = emptyList()
        WebcamBus.cameraLabel.value = ""
        stopForegroundCompat()
        super.onDestroy()
    }

    // --------------------------------------------------------------- démarrage

    private fun refreshFromSettings() {
        requestedHeight = SettingsStore.height()
        requestedFps = SettingsStore.fps()
        jpegQuality = SettingsStore.quality()
        audioEnabled = SettingsStore.audio()
        lensFacing = SettingsStore.lensFacing()
        WebcamBus.height.value = requestedHeight
        WebcamBus.fps.value = requestedFps
        WebcamBus.quality.value = jpegQuality
        WebcamBus.audio.value = audioEnabled && hasAudioPermission()
        WebcamBus.lensFacing.value = lensFacing
    }

    private fun startStreamingInternal() {
        if (started) return
        refreshFromSettings()
        if (audioEnabled && !hasAudioPermission()) {
            audioEnabled = false
            SettingsStore.setAudio(false)
            WebcamBus.audio.value = false
        }

        WebcamBus.error.value = null
        WebcamBus.starting.value = false
        WebcamBus.running.value = true
        WebcamBus.status.value = "Démarrage…"
        WebcamBus.cameraLabel.value = ""
        started = true
        WebcamBus.urls.value = NetUtils.urls(PORT, SettingsStore.token())
        acquireWakeLock()

        val httpServer = server
        if (httpServer == null) {
            fail("Serveur vidéo indisponible")
            return
        }
        try {
            httpServer.start()
        } catch (error: IOException) {
            fail("Port $PORT indisponible : ${error.message}")
            return
        }

        discovery?.start()
        if (audioEnabled) {
            startAudioInternal()
        } else {
            httpServer.setAudioEnabled(false)
        }

        lastDisplayRotation = displayRotation()
        cameraRetries = 0
        openCamera(++cameraGeneration)
        updateNotification()
    }

    private fun stopStreamingInternal() {
        started = false
        cameraGeneration++
        stopAudioInternal()
        stopCameraInternal()
        setTorchInternal(false)
        releaseWakeLock()
        discovery?.stop()
        server?.stop()

        WebcamBus.running.value = false
        WebcamBus.starting.value = false
        WebcamBus.clients.value = 0
        WebcamBus.urls.value = emptyList()
        WebcamBus.cameraLabel.value = ""
        WebcamBus.status.value = "Arrêté"

        mainHandler.post {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        started = false
        cameraGeneration++
        WebcamBus.error.value = message
        WebcamBus.running.value = false
        WebcamBus.starting.value = false
        WebcamBus.clients.value = 0
        WebcamBus.urls.value = emptyList()
        WebcamBus.cameraLabel.value = ""
        WebcamBus.status.value = message

        stopAudioInternal()
        stopCameraInternal()
        setTorchInternal(false)
        releaseWakeLock()
        discovery?.stop()
        server?.stop()

        mainHandler.post {
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ caméra

    private fun openCamera(generation: Int) {
        if (!started || generation != cameraGeneration) return
        val manager = cameraManager ?: run {
            scheduleCameraRestart("caméra indisponible")
            return
        }
        val handler = cameraHandler ?: run {
            fail("Caméra indisponible")
            return
        }
        try {
            val selection = selectCamera(manager, lensFacing) ?: run {
                scheduleCameraRestart("aucune caméra")
                return
            }
            val id = selection.first
            physicalFacing = selection.second
            cameraId = id
            val chars = manager.getCameraCharacteristics(id)
            characteristics = chars
            updateTorchAvailability()
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    fail("Caméra non compatible")
                    return
                }
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) {
                fail("Format JPEG indisponible")
                return
            }
            val captureSize = chooseSize(sizes, requestedHeight)
            rotationDegrees = computeRotation(chars, physicalFacing)
            captureHeight = captureSize.height
            frameCount = 0
            lastFrameNs = 0
            WebcamBus.cameraLabel.value =
                "${captureSize.width}×${captureSize.height} · ${facingLabel(physicalFacing)}"
            WebcamBus.status.value = "Ouverture de la caméra…"
            val declaredFacing = chars.get(CameraCharacteristics.LENS_FACING)
            Log.i(
                TAG,
                "caméra ${facingLabel(physicalFacing)} · ID $id" +
                    " · demandé ${requestedFacingLabel(lensFacing)}" +
                    " · déclaré ${facingLabel(declaredFacing ?: -1)}" +
                    " · flash ${chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true}" +
                    " · ${captureSize.width}x${captureSize.height}" +
                    " · qualité $jpegQuality · rotation $rotationDegrees°" +
                    " · audio $audioEnabled",
            )

            val reader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.JPEG,
                3,
            )
            reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
            imageReader = reader

            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!started || generation != cameraGeneration) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createCaptureSession(generation)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        if (started && generation == cameraGeneration) {
                            scheduleCameraRestart("caméra déconnectée")
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        if (started && generation == cameraGeneration) {
                            scheduleCameraRestart("erreur caméra $error")
                        }
                    }
                },
                handler,
            )
        } catch (error: CameraAccessException) {
            scheduleCameraRestart("accès caméra : ${error.message}")
        } catch (error: SecurityException) {
            fail("Autorisation caméra manquante")
        } catch (error: IllegalArgumentException) {
            fail("Caméra incompatible : ${error.message}")
        }
    }

    private fun createCaptureSession(generation: Int) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val handler = cameraHandler ?: return
        if (!started || generation != cameraGeneration) return

        try {
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!started ||
                            generation != cameraGeneration ||
                            cameraDevice == null ||
                            imageReader == null
                        ) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            val builder = device.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW,
                            )
                            builder.addTarget(reader.surface)
                            builder.set(
                                CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_AUTO,
                            )
                            chooseAfMode(characteristics)?.let { mode ->
                                builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
                            }
                            builder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON,
                            )
                            builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                            builder.set(CaptureRequest.JPEG_ORIENTATION, rotationDegrees)
                            chooseFpsRange(characteristics)?.let { range ->
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            }
                            requestBuilder = builder
                            session.setRepeatingRequest(builder.build(), null, handler)
                            cameraRetries = 0
                            applyTorch()
                            WebcamBus.status.value = "Diffusion active"
                            updateNotification()
                        } catch (error: CameraAccessException) {
                            scheduleCameraRestart("capture : ${error.message}")
                        } catch (error: IllegalStateException) {
                            scheduleCameraRestart("capture : ${error.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (generation == cameraGeneration) {
                            scheduleCameraRestart("configuration impossible")
                        }
                    }
                },
                handler,
            )
        } catch (error: CameraAccessException) {
            scheduleCameraRestart("session caméra : ${error.message}")
        } catch (error: IllegalStateException) {
            scheduleCameraRestart("session caméra : ${error.message}")
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = System.nanoTime()
            val interval = minFrameIntervalNs()
            if (interval > 0 && now - lastFrameNs < interval) return
            lastFrameNs = now

            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val encoded = reencode(data)
            server?.publish(encoded)
            WebcamBus.publishFrame(encoded)
            frameCount++
            if (frameCount % 30 == 0) {
                Log.i(TAG, "images $frameCount · JPEG ${data.size} → ${encoded.size} o")
            }
        } catch (_: Exception) {
            // Image corrompue ou lecteur fermé.
        } finally {
            image?.close()
        }
    }

    private fun reencode(data: ByteArray): ByteArray {
        // La 4K est trop lourde à décoder/ré-encoder sur le téléphone.
        val effectiveHeight = if (captureHeight > 0) captureHeight else requestedHeight
        if (effectiveHeight >= SettingsStore.HEIGHT_4K) return data
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            val decoded = bitmap ?: return data
            val output = ByteArrayOutputStream()
            decoded.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            val encoded = output.toByteArray()
            if (encoded.isNotEmpty()) encoded else data
        } catch (_: Exception) {
            data
        } catch (_: OutOfMemoryError) {
            data
        } finally {
            bitmap?.recycle()
        }
    }

    private fun minFrameIntervalNs(): Long {
        val effectiveHeight = if (captureHeight > 0) captureHeight else requestedHeight
        val cap = when {
            effectiveHeight >= SettingsStore.HEIGHT_4K -> INTERVAL_4K_NS
            effectiveHeight >= SettingsStore.HEIGHT_FULL_HD -> INTERVAL_1080_NS
            effectiveHeight >= SettingsStore.HEIGHT_HD -> INTERVAL_720_NS
            else -> 0L
        }
        val wanted = if (requestedFps > 0) 1_000_000_000L / requestedFps else 0L
        // La limite de résolution évite d'écraser le téléphone en 4K même
        // si l'utilisateur demande 60 i/s.
        return maxOf(cap, wanted)
    }

    private fun chooseFpsRange(chars: CameraCharacteristics?): Range<Int>? {
        val ranges = chars?.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        ) ?: return null
        if (ranges.isEmpty()) return null
        val target = requestedFps.coerceAtLeast(1)
        val exact = ranges.filter { it.upper == target }.maxByOrNull { it.lower }
        val below = ranges.filter { it.upper <= target }.maxByOrNull { it.upper }
        val best = exact
            ?: below
            ?: ranges.minByOrNull { abs(it.upper - target) }
            ?: return null
        Log.i(TAG, "cadence cible $best (demandé $target i/s)")
        return best
    }

    private fun chooseAfMode(chars: CameraCharacteristics?): Int? {
        val modes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return null
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun restartCameraInternal() {
        if (!started) return
        cameraRetries = 0
        WebcamBus.status.value = "Redémarrage de la caméra…"
        stopCameraInternal()
        openCamera(++cameraGeneration)
    }

    /**
     * Le HAL caméra de certains téléphones (MTK/HiOS) abandonne la session
     * après plusieurs minutes. On rouvre la caméra avec un délai croissant
     * au lieu d'arrêter la diffusion et le serveur HTTP.
     */
    private fun scheduleCameraRestart(reason: String) {
        if (!started) return
        cameraRetries++
        if (cameraRetries > MAX_CAMERA_RETRIES) {
            fail("Caméra indisponible : $reason")
            return
        }
        Log.w(TAG, "caméra : $reason · tentative $cameraRetries/$MAX_CAMERA_RETRIES")
        WebcamBus.status.value = "Reconnexion caméra… ($cameraRetries)"
        stopCameraInternal()
        cameraGeneration++
        val shift = (cameraRetries - 1).coerceIn(0, 3)
        val delayMs = (500L shl shift).coerceAtMost(4_000L)
        cameraHandler?.postDelayed(
            {
                if (started) openCamera(++cameraGeneration)
            },
            delayMs,
        )
    }

    private fun stopCameraInternal() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        requestBuilder = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
    }

    // ------------------------------------------------------------------- torche

    private fun updateTorchAvailability() {
        val available = characteristics?.get(
            CameraCharacteristics.FLASH_INFO_AVAILABLE,
        ) == true
        torchAvailable = available
        WebcamBus.torchAvailable.value = available
        if (!available && torchEnabled) {
            torchEnabled = false
            WebcamBus.torch.value = false
        }
    }

    private fun setTorchInternal(enabled: Boolean) {
        torchEnabled = enabled && torchAvailable
        WebcamBus.torch.value = torchEnabled
        applyTorch()
        updateNotification()
    }

    private fun applyTorch() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { applyTorchInternal() }
        } else {
            applyTorchInternal()
        }
    }

    /**
     * Sur beaucoup de téléphones, CameraManager.setTorchMode refuse la
     * commande tant que la caméra est ouverte (CAMERA_IN_USE). La torche
     * doit alors passer par FLASH_MODE_TORCH dans la requête de capture.
     */
    private fun applyTorchInternal() {
        val on = torchEnabled && torchAvailable
        val builder = requestBuilder
        val session = captureSession
        val handler = cameraHandler
        if (on && builder != null && session != null && handler != null) {
            try {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_TORCH,
                )
                session.setRepeatingRequest(builder.build(), null, handler)
                Log.i(TAG, "torche allumée (session)")
                return
            } catch (error: CameraAccessException) {
                Log.w(TAG, "torche par session refusée : ${error.message}")
            } catch (error: IllegalStateException) {
                Log.w(TAG, "torche par session impossible : ${error.message}")
            }
        }
        if (!on && builder != null && session != null && handler != null) {
            try {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_OFF,
                )
                session.setRepeatingRequest(builder.build(), null, handler)
                Log.i(TAG, "torche éteinte (session)")
                return
            } catch (error: CameraAccessException) {
                Log.w(TAG, "extinction par session refusée : ${error.message}")
            } catch (error: IllegalStateException) {
                Log.w(TAG, "extinction par session impossible : ${error.message}")
            }
        }
        val manager = cameraManager ?: return
        val id = cameraId ?: return
        try {
            manager.setTorchMode(id, on)
            Log.i(TAG, if (on) "torche allumée" else "torche éteinte")
        } catch (error: CameraAccessException) {
            Log.w(TAG, "torche indisponible : ${error.message}")
            torchEnabled = false
            WebcamBus.torch.value = false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "torche refusée : ${error.message}")
            torchEnabled = false
            WebcamBus.torch.value = false
        }
    }

    // ------------------------------------------------------------ veille

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val manager = getSystemService(PowerManager::class.java) ?: return
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Capturo:webcam",
            ).apply { setReferenceCounted(false) }
        }
        try {
            // Six heures : le service reste actif même écran éteint, sans
            // immobiliser le téléphone indéfiniment.
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
            Log.i(TAG, "veille partielle active")
        } catch (error: Exception) {
            Log.w(TAG, "veille partielle refusée : ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------- audio

    private fun setAudioEnabledInternal(enabled: Boolean) {
        audioEnabled = enabled
        SettingsStore.setAudio(enabled)
        if (enabled && !hasAudioPermission()) {
            audioEnabled = false
            SettingsStore.setAudio(false)
            WebcamBus.audio.value = false
            WebcamBus.error.value = "Autorisation micro manquante"
            return
        }
        WebcamBus.audio.value = enabled
        if (!started) return

        if (enabled) {
            refreshForegroundTypes()
            startAudioInternal()
        } else {
            stopAudioInternal()
            refreshForegroundTypes()
        }
    }

    private fun startAudioInternal() {
        if (audioRunning) return
        if (!audioEnabled || !hasAudioPermission()) {
            audioEnabled = false
            WebcamBus.audio.value = false
            server?.setAudioEnabled(false)
            return
        }

        var minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) minBuffer = 4096
        val bufferSize = maxOf(minBuffer * 2, 8192)

        val record = createAudioRecord(MediaRecorder.AudioSource.CAMCORDER, bufferSize)
            ?: createAudioRecord(MediaRecorder.AudioSource.MIC, bufferSize)
        if (record == null) {
            audioEnabled = false
            SettingsStore.setAudio(false)
            WebcamBus.audio.value = false
            WebcamBus.error.value = "Micro du téléphone indisponible"
            server?.setAudioEnabled(false)
            return
        }

        audioRecord = record
        audioRunning = true
        val generation = ++audioGeneration
        server?.setAudioEnabled(true)
        WebcamBus.audio.value = true
        Log.i(TAG, "micro démarré · $AUDIO_RATE Hz mono")
        audioThread = Thread({ audioLoop(record, generation) }, "capturo-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun createAudioRecord(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                source,
                AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun audioLoop(record: AudioRecord, generation: Int) {
        try {
            record.startRecording()
            val buffer = ByteArray(1920) // 20 ms à 48 kHz
            while (audioRunning && generation == audioGeneration) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    server?.publishAudio(buffer.copyOf(read))
                } else if (read < 0) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
    }

    private fun stopAudioInternal() {
        audioRunning = false
        audioGeneration++
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        audioRecord = null
        audioThread = null
        server?.setAudioEnabled(false)
    }

    // -------------------------------------------------------------- utilitaires

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private data class CameraInfo(
        val id: String,
        val declaredFacing: Int,
        val hasFlash: Boolean,
        val sensorArea: Long,
    )

    private fun cameraInfos(manager: CameraManager): List<CameraInfo> {
        return manager.cameraIdList.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                CameraInfo(
                    id = id,
                    declaredFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1,
                    hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                    sensorArea = if (size == null) {
                        0L
                    } else {
                        size.width.toLong() * size.height.toLong()
                    },
                )
            } catch (_: CameraAccessException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Certains téléphones (TECNO KM6 notamment) annoncent LENS_FACING à
     * l'envers : la caméra déclarée « arrière » n'a pas de flash et la caméra
     * déclarée « avant » en a un. On détecte cette inversion pour que les
     * boutons Avant/Arrière correspondent aux capteurs physiques.
     */
    private fun facingIsInverted(infos: List<CameraInfo>): Boolean {
        val back = infos.firstOrNull {
            it.declaredFacing == CameraCharacteristics.LENS_FACING_BACK
        } ?: return false
        val front = infos.firstOrNull {
            it.declaredFacing == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return false

        if (back.hasFlash != front.hasFlash) {
            // Le capteur arrière a presque toujours un flash, pas l'avant.
            return !back.hasFlash
        }
        // Flash identique (ou absent des deux côtés) : le capteur arrière est
        // généralement le plus grand.
        return back.sensorArea > 0 &&
            front.sensorArea > 0 &&
            back.sensorArea < front.sensorArea
    }

    private fun flipFacing(facing: Int): Int = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraCharacteristics.LENS_FACING_FRONT
        CameraCharacteristics.LENS_FACING_FRONT -> CameraCharacteristics.LENS_FACING_BACK
        else -> facing
    }

    private fun facingLabel(facing: Int): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "Avant"
        CameraCharacteristics.LENS_FACING_BACK -> "Arrière"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externe"
        else -> "Inconnue"
    }

    /**
     * Les constantes de l'application (0 = arrière, 1 = avant) sont
     * volontairement stables, mais Camera2 utilise l'inverse
     * (LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1). Sans cette conversion,
     * les boutons Avant et Arrière sont inversés.
     */
    private fun requestedFacingLabel(logicalFacing: Int): String = when (logicalFacing) {
        SettingsStore.LENS_BACK -> "Arrière"
        SettingsStore.LENS_FRONT -> "Avant"
        else -> "Inconnue"
    }

    private fun cameraFacing(logicalFacing: Int): Int = when (logicalFacing) {
        SettingsStore.LENS_BACK -> CameraCharacteristics.LENS_FACING_BACK
        SettingsStore.LENS_FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        else -> logicalFacing
    }

    /**
     * Renvoie l'identifiant de la caméra et son orientation physique réelle.
     */
    private fun selectCamera(
        manager: CameraManager,
        requestedFacing: Int,
    ): Pair<String, Int>? {
        val infos = cameraInfos(manager)
        if (infos.isEmpty()) return null

        val targetFacing = cameraFacing(requestedFacing)
        val inverted = facingIsInverted(infos)
        val declaredTarget = if (inverted) {
            flipFacing(targetFacing)
        } else {
            targetFacing
        }
        val selected = infos.firstOrNull {
            it.declaredFacing == declaredTarget
        } ?: infos.firstOrNull {
            it.declaredFacing == targetFacing
        } ?: infos.first()

        val physicalFacing = if (inverted) {
            flipFacing(selected.declaredFacing)
        } else {
            selected.declaredFacing
        }
        return selected.id to physicalFacing
    }

    private fun chooseSize(sizes: Array<Size>, targetHeight: Int): Size {
        var best: Size? = null
        var bestScore = Int.MAX_VALUE
        for (size in sizes) {
            if (size.width > 4096 || size.height > 2160) continue
            var score = abs(size.height - targetHeight)
            if (size.width * 9 == size.height * 16) score -= 10
            if (score < bestScore) {
                bestScore = score
                best = size
            }
        }
        return best ?: sizes[0]
    }

    private fun displayRotation(): Int {
        val display: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) return display.rotation
        @Suppress("DEPRECATION")
        val windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    private fun computeRotation(chars: CameraCharacteristics, facing: Int): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return 0
        val deviceDegrees = displayRotation() * 90
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + deviceDegrees) % 360
        } else {
            (sensor - deviceDegrees + 360) % 360
        }
    }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Capturo Webcam",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Diffusion de la caméra vers Capturo"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            audioEnabled &&
            hasAudioPermission()
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundTypes())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshForegroundTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes())
        } catch (error: Exception) {
            Log.w(TAG, "Type de service micro refusé", error)
            WebcamBus.error.value = "Micro refusé par Android : ${error.message}"
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebcamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val url = WebcamBus.urls.value.firstOrNull()?.video
        val text = buildString {
            append(url ?: "Flux caméra prêt")
            append(" · ").append(requestedFps).append(" i/s")
            if (audioRunning) append(" · micro")
            if (torchEnabled) append(" · torche")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Capturo Webcam")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        mainHandler.post {
            if (!started) return@post
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        }
    }
}
