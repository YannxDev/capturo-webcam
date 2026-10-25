package io.github.yannick.capturowebcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Service de premier plan : maintient la caméra et le serveur MJPEG actifs
 * même quand l'écran se verrouille ou que l'application passe en arrière-plan.
 * Le micro du téléphone est diffusé en PCM 48 kHz mono sur /audio.
 */
public class WebcamService extends Service {

    public static final String ACTION_START =
            "io.github.yannick.capturowebcam.action.START";
    public static final String ACTION_STOP =
            "io.github.yannick.capturowebcam.action.STOP";

    public static final int PORT = 8080;
    public static final int AUDIO_RATE = 48000;
    public static final int DEFAULT_JPEG_QUALITY = 70;
    public static final int DEFAULT_HEIGHT = 720;

    public static final String PREFS = "capturo";
    public static final String PREF_HEIGHT = "height";
    public static final String PREF_QUALITY = "quality";
    public static final String PREF_AUDIO = "audio";
    public static final String PREF_LENS = "lens_facing";

    private static final String TAG = "CapturoWebcam";
    private static final String CHANNEL_ID = "capturo_webcam";
    private static final int NOTIFICATION_ID = 42;
    /** Limite de débit selon la résolution choisie. */
    private static final long INTERVAL_4K_NS = 150_000_000L;
    private static final long INTERVAL_1080_NS = 100_000_000L;
    private static final long INTERVAL_720_NS = 50_000_000L;

    public class LocalBinder extends Binder {
        public WebcamService getService() {
            return WebcamService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private MjpegServer server;
    private DiscoveryResponder discovery;
    private SharedPreferences prefs;
    private DisplayManager displayManager;

    private String cameraId;
    private CameraCharacteristics characteristics;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private volatile boolean streaming;
    private volatile String lastError;
    private volatile byte[] latestFrame;
    private volatile int clientCount;
    private volatile int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private volatile int requestedHeight = DEFAULT_HEIGHT;
    private volatile int jpegQuality = DEFAULT_JPEG_QUALITY;
    private volatile boolean audioEnabled = true;

    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private volatile int audioGeneration;

    private int rotationDegrees;
    private int lastDisplayRotation = -1;
    private long lastFrameNs;
    private int frameCount;

    private final Runnable rotationRestart = this::restartCameraInternal;

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY || !streaming) {
                        return;
                    }
                    int rotation = getDisplayRotation();
                    if (rotation == lastDisplayRotation) {
                        return;
                    }
                    Log.i(TAG, "rotation d'écran -> " + (rotation * 90) + "°");
                    lastDisplayRotation = rotation;
                    if (cameraHandler != null) {
                        cameraHandler.removeCallbacks(rotationRestart);
                        cameraHandler.postDelayed(rotationRestart, 350);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        requestedHeight = prefs.getInt(PREF_HEIGHT, DEFAULT_HEIGHT);
        jpegQuality = prefs.getInt(PREF_QUALITY, DEFAULT_JPEG_QUALITY);
        audioEnabled = prefs.getBoolean(PREF_AUDIO, true);
        lensFacing = prefs.getInt(
                PREF_LENS, CameraCharacteristics.LENS_FACING_BACK);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraThread = new HandlerThread("capturo-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        server = new MjpegServer(PORT, count -> clientCount = count);
        discovery = new DiscoveryResponder(PORT);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        lastDisplayRotation = getDisplayRotation();
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, cameraHandler);
        }
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStreaming();
            return START_NOT_STICKY;
        }
        try {
            startForegroundCompat();
        } catch (Exception error) {
            lastError = "Service caméra refusé : " + error.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        cameraHandler.post(this::startStreamingInternal);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopStreaming();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        stopAudioInternal();
        stopCameraInternal();
        if (discovery != null) {
            discovery.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ API locale

    public boolean isStreaming() {
        return streaming;
    }

    public String getLastError() {
        return lastError;
    }

    public int getClientCount() {
        return clientCount;
    }

    public byte[] getLatestFrame() {
        return latestFrame;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public int getRequestedHeight() {
        return requestedHeight;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public boolean isAudioEnabled() {
        return audioEnabled && hasAudioPermission();
    }

    public boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void setLensFacing(int facing) {
        if (lensFacing == facing) {
            return;
        }
        lensFacing = facing;
        if (prefs != null) {
            prefs.edit().putInt(PREF_LENS, facing).apply();
        }
        if (cameraHandler != null) {
            cameraHandler.post(this::restartCameraInternal);
        }
    }

    public void setRequestedHeight(int height) {
        if (requestedHeight == height) {
            return;
        }
        requestedHeight = height;
        if (prefs != null) {
            prefs.edit().putInt(PREF_HEIGHT, height).apply();
        }
        if (cameraHandler != null) {
            cameraHandler.post(this::restartCameraInternal);
        }
    }

    public void setJpegQuality(int quality) {
        int clamped = Math.max(1, Math.min(100, quality));
        if (jpegQuality == clamped) {
            return;
        }
        jpegQuality = clamped;
        if (prefs != null) {
            prefs.edit().putInt(PREF_QUALITY, clamped).apply();
        }
        if (cameraHandler != null) {
            cameraHandler.post(this::restartCameraInternal);
        }
    }

    public void setProfile(int height, int quality) {
        int clamped = Math.max(1, Math.min(100, quality));
        boolean changed = requestedHeight != height || jpegQuality != clamped;
        requestedHeight = height;
        jpegQuality = clamped;
        if (prefs != null) {
            prefs.edit()
                    .putInt(PREF_HEIGHT, height)
                    .putInt(PREF_QUALITY, clamped)
                    .apply();
        }
        if (changed && cameraHandler != null) {
            cameraHandler.post(this::restartCameraInternal);
        }
    }

    public void setAudioEnabled(boolean enabled) {
        if (audioEnabled == enabled) {
            return;
        }
        audioEnabled = enabled;
        if (prefs != null) {
            prefs.edit().putBoolean(PREF_AUDIO, enabled).apply();
        }
        if (cameraHandler == null) {
            return;
        }
        cameraHandler.post(() -> {
            if (!streaming) {
                return;
            }
            if (enabled && hasAudioPermission()) {
                try {
                    refreshForegroundTypes();
                    startAudioInternal();
                } catch (Exception error) {
                    audioEnabled = false;
                    stopAudioInternal();
                    lastError = "Micro indisponible : " + error.getMessage();
                }
            } else {
                stopAudioInternal();
                refreshForegroundTypes();
            }
        });
    }

    public void stopStreaming() {
        if (cameraHandler != null) {
            cameraHandler.post(this::stopStreamingInternal);
        } else {
            stopStreamingInternal();
        }
    }

    // --------------------------------------------------------------- caméra

    private void startStreamingInternal() {
        if (streaming || cameraDevice != null) {
            return;
        }
        lastError = null;
        try {
            server.start();
        } catch (IOException error) {
            fail("Port " + PORT + " indisponible : " + error.getMessage());
            return;
        }
        if (discovery != null) {
            discovery.start();
        }

        if (audioEnabled && hasAudioPermission()) {
            startAudioInternal();
        } else {
            audioEnabled = false;
            server.setAudioEnabled(false);
        }

        lastDisplayRotation = getDisplayRotation();
        openCamera();
    }

    private void restartCameraInternal() {
        if (!streaming) {
            return;
        }
        stopCameraInternal();
        openCamera();
    }

    private void openCamera() {
        try {
            cameraId = findCameraId(lensFacing);
            if (cameraId == null) {
                fail("Aucune caméra trouvée");
                return;
            }
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail("Caméra non compatible");
                return;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                fail("Format JPEG indisponible");
                return;
            }
            Size captureSize = chooseSize(sizes, requestedHeight);
            rotationDegrees = computeRotation(characteristics, lensFacing);
            frameCount = 0;
            Log.i(TAG, "caméra " + (lensFacing == CameraCharacteristics.LENS_FACING_FRONT ? "avant" : "arrière")
                    + " · " + captureSize.getWidth() + "x" + captureSize.getHeight()
                    + " · qualité " + jpegQuality
                    + " · rotation " + rotationDegrees + "°"
                    + " · audio " + audioEnabled);

            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            cameraManager.openCamera(cameraId, stateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException error) {
            fail("Erreur caméra : " + error.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    fail("Caméra déconnectée");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    fail("Erreur caméra " + error);
                }
            };

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) {
            return;
        }
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null || imageReader == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(imageReader.getSurface());
                                builder.set(CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO);
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                builder.set(CaptureRequest.JPEG_QUALITY,
                                        (byte) jpegQuality);
                                builder.set(CaptureRequest.JPEG_ORIENTATION,
                                        rotationDegrees);
                                Range<Integer> fpsRange = chooseFpsRange();
                                if (fpsRange != null) {
                                    builder.set(
                                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                            fpsRange);
                                }
                                session.setRepeatingRequest(
                                        builder.build(), null, cameraHandler);
                                streaming = true;
                            } catch (CameraAccessException | IllegalStateException error) {
                                fail("Erreur de capture : " + error.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("Configuration caméra impossible");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException error) {
            fail("Erreur caméra : " + error.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = System.nanoTime();
            long minInterval = minFrameIntervalNs();
            if (minInterval > 0 && now - lastFrameNs < minInterval) {
                return;
            }
            lastFrameNs = now;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            // L'encodeur JPEG de certains téléphones ignore JPEG_QUALITY et
            // produit des images énormes (plusieurs centaines de Ko pour du
            // 480p), ce qui sature le Wi-Fi. On ré-encode avec l'encodeur
            // Android, beaucoup plus compact, pour un flux fluide.
            byte[] encoded = reencode(data);
            latestFrame = encoded;
            server.publish(encoded);
            frameCount++;
            if (frameCount % 30 == 0) {
                Log.i(TAG, "images " + frameCount + " · JPEG "
                        + data.length + " → " + encoded.length + " o");
            }
        } catch (Exception ignored) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private byte[] reencode(byte[] data) {
        // La 4K est trop lourde à décoder/ré-encoder sur le téléphone.
        if (requestedHeight >= 2160) {
            return data;
        }
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) {
                return data;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output);
            byte[] encoded = output.toByteArray();
            return encoded.length > 0 ? encoded : data;
        } catch (Exception error) {
            return data;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private long minFrameIntervalNs() {
        int height = requestedHeight;
        if (height >= 2160) {
            return INTERVAL_4K_NS;
        }
        if (height >= 1080) {
            return INTERVAL_1080_NS;
        }
        if (height >= 720) {
            return INTERVAL_720_NS;
        }
        return 0L;
    }

    private Range<Integer> chooseFpsRange() {
        if (characteristics == null) {
            return null;
        }
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range.getUpper() == 30
                    && (best == null || range.getLower() > best.getLower())) {
                best = range;
            }
        }
        if (best == null) {
            for (Range<Integer> range : ranges) {
                if (best == null || range.getUpper() > best.getUpper()) {
                    best = range;
                }
            }
        }
        Log.i(TAG, "cadence cible " + best);
        return best;
    }

    private void stopStreamingInternal() {
        stopAudioInternal();
        stopCameraInternal();
        if (discovery != null) {
            discovery.stop();
        }
        if (server != null) {
            server.stop();
        }
        streaming = false;
        latestFrame = null;
        lastError = null;
        stopForegroundCompat();
        stopSelf();
    }

    private void stopCameraInternal() {
        if (imageReader != null) {
            try {
                imageReader.setOnImageAvailableListener(null, null);
            } catch (Exception ignored) {
            }
        }
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
    }

    // ---------------------------------------------------------------- audio

    private void startAudioInternal() {
        if (audioRunning || !audioEnabled) {
            return;
        }
        if (!hasAudioPermission()) {
            audioEnabled = false;
            if (server != null) {
                server.setAudioEnabled(false);
            }
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = 4096;
        }
        int bufferSize = Math.max(minBuffer * 2, 8192);
        AudioRecord record = createAudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, bufferSize);
        if (record == null) {
            record = createAudioRecord(
                    MediaRecorder.AudioSource.MIC, bufferSize);
        }
        if (record == null) {
            audioEnabled = false;
            if (server != null) {
                server.setAudioEnabled(false);
            }
            lastError = "Micro du téléphone indisponible";
            return;
        }

        final AudioRecord activeRecord = record;
        audioRecord = activeRecord;
        audioRunning = true;
        int generation = ++audioGeneration;
        Log.i(TAG, "micro démarré · " + AUDIO_RATE + " Hz mono");
        if (server != null) {
            server.setAudioEnabled(true);
        }
        audioThread = new Thread(
                () -> audioLoop(activeRecord, generation), "capturo-audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private AudioRecord createAudioRecord(int source, int bufferSize) {
        try {
            AudioRecord record = new AudioRecord(
                    source,
                    AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                return record;
            }
            record.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void audioLoop(AudioRecord record, int generation) {
        try {
            record.startRecording();
            byte[] buffer = new byte[1920]; // 20 ms à 48 kHz
            while (audioRunning && generation == audioGeneration) {
                int read = record.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    if (server != null) {
                        server.publishAudio(chunk);
                    }
                } else if (read < 0) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
        }
    }

    private void stopAudioInternal() {
        audioRunning = false;
        audioGeneration++;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
        audioThread = null;
        if (server != null) {
            server.setAudioEnabled(false);
        }
    }

    private void fail(String message) {
        lastError = message;
        streaming = false;
        stopAudioInternal();
        stopCameraInternal();
        if (discovery != null) {
            discovery.stop();
        }
        if (server != null) {
            server.stop();
        }
        stopForegroundCompat();
        stopSelf();
    }

    // ----------------------------------------------------------- utilitaires

    private String findCameraId(int facing) throws CameraAccessException {
        String[] ids = cameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(id);
            Integer lens = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lens != null && lens == facing) {
                return id;
            }
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private static Size chooseSize(Size[] sizes, int targetHeight) {
        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size size : sizes) {
            if (size.getWidth() > 3840 || size.getHeight() > 2160) {
                continue;
            }
            int score = Math.abs(size.getHeight() - targetHeight);
            if (size.getWidth() * 9 == size.getHeight() * 16) {
                score -= 10;
            }
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best != null ? best : sizes[0];
    }

    private int getDisplayRotation() {
        if (displayManager != null) {
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                return display.getRotation();
            }
        }
        WindowManager windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager != null) {
            return windowManager.getDefaultDisplay().getRotation();
        }
        return Surface.ROTATION_0;
    }

    private int computeRotation(CameraCharacteristics characteristics, int facing) {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensor == null) {
            return 0;
        }
        int deviceDegrees = getDisplayRotation() * 90;
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensor + deviceDegrees) % 360;
        }
        return (sensor - deviceDegrees + 360) % 360;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Capturo Webcam", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Diffusion de la caméra vers Capturo");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private int foregroundTypes() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if (Build.VERSION.SDK_INT >= 30
                && audioEnabled
                && hasAudioPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return types;
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, foregroundTypes());
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshForegroundTypes() {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes());
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String text = audioRunning
                ? "Flux MJPEG + micro — port " + PORT
                : "Flux MJPEG — port " + PORT;
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Capturo Webcam")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }
}
