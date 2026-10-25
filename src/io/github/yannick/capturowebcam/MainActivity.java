package io.github.yannick.capturowebcam;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capturo Webcam : interface de contrôle du service de diffusion MJPEG.
 * L'interface s'adapte au portrait et au paysage ; le service continue de
 * diffuser pendant la rotation.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 10;
    private static final String ACTION_TETHER_SETTINGS =
            "android.settings.TETHER_SETTINGS";

    private ImageView previewView;
    private TextView statusView;
    private TextView urlView;
    private TextView clientView;
    private TextView cameraLabelView;
    private TextView audioHintView;
    private Button toggleButton;
    private Button flipButton;
    private Spinner resolutionSpinner;
    private Spinner qualitySpinner;
    private Switch audioSwitch;

    private WebcamService service;
    private boolean bound;
    private boolean userStopped;
    private boolean permissionRequested;
    private boolean syncing;
    private int pollCount;
    private String lastStatusText;
    private boolean lastStatusOnline;
    private String lastCameraText;
    private int lastClientCount = -1;
    private boolean resolutionTouched;
    private boolean qualityTouched;
    private SharedPreferences prefs;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService previewExecutor =
            Executors.newSingleThreadExecutor();
    private byte[] lastPreviewFrame;
    private Bitmap previewBitmap;
    private boolean previewDecoding;

    private final int[] resolutionHeights = {480, 720, 1080, 1440, 2160};
    private final String[] resolutionLabels = {
            "640 × 480", "1280 × 720", "1920 × 1080",
            "2560 × 1440", "3840 × 2160 (4K)"
    };
    private final int[] qualityValues = {70, 80, 90};
    private final String[] qualityLabels = {
            "70 · fluide", "80 · équilibrée", "90 · maximale"
    };

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            updateFromService();
            uiHandler.postDelayed(this, 100);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((WebcamService.LocalBinder) binder).getService();
            bound = true;
            if (!service.isStreaming() && !userStopped && hasCameraPermission()) {
                startStreaming();
            }
            syncControlsFromService();
            updateFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            updateFromService();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(WebcamService.PREFS, MODE_PRIVATE);
        buildUi();
        if (!hasCameraPermission()) {
            requestPermissions();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, WebcamService.class), connection,
                BIND_AUTO_CREATE);
        uiHandler.post(poller);
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(poller);
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(poller);
        previewExecutor.shutdownNow();
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUi();
        syncControlsFromService();
        updateFromService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean cameraGranted = false;
        boolean audioGranted = false;
        for (int index = 0; index < permissions.length; index++) {
            boolean granted = index < grantResults.length
                    && grantResults[index] == PackageManager.PERMISSION_GRANTED;
            if (Manifest.permission.CAMERA.equals(permissions[index])) {
                cameraGranted = granted;
            } else if (Manifest.permission.RECORD_AUDIO.equals(permissions[index])) {
                audioGranted = granted;
            }
        }
        if (!audioGranted) {
            prefs.edit().putBoolean(WebcamService.PREF_AUDIO, false).apply();
            syncing = true;
            if (audioSwitch != null) {
                audioSwitch.setChecked(false);
            }
            syncing = false;
        }
        if (cameraGranted) {
            startStreaming();
        } else {
            setStatus("● Permission caméra refusée", false);
        }
        updateAudioHint();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(landscape ? 12 : 14);
        root.setPadding(pad, pad, pad, pad);

        FrameLayout previewHolder = new FrameLayout(this);
        previewHolder.setBackgroundColor(0xFF101014);
        previewView = new ImageView(this);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setBackgroundColor(0xFF101014);
        previewHolder.addView(previewView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewParams;
        if (landscape) {
            previewParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            previewParams.rightMargin = dp(16);
        } else {
            previewParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f);
            previewParams.bottomMargin = dp(10);
        }
        root.addView(previewHolder, previewParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int panelPad = dp(landscape ? 8 : 6);
        panel.setPadding(panelPad, dp(4), panelPad, dp(12));
        scroll.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams panelParams = landscape
                ? new LinearLayout.LayoutParams(
                        dp(360), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
        root.addView(scroll, panelParams);

        TextView title = new TextView(this);
        title.setText("Capturo Webcam");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        panel.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Caméra réseau et micro pour Capturo");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFFB6B6BE);
        panel.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(17);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, dp(10), 0, dp(2));
        statusView.setMaxLines(3);
        panel.addView(statusView);

        clientView = new TextView(this);
        clientView.setTextSize(12);
        clientView.setTextColor(0xFFB6B6BE);
        panel.addView(clientView);

        cameraLabelView = new TextView(this);
        cameraLabelView.setTextSize(12);
        cameraLabelView.setTextColor(0xFFB6B6BE);
        cameraLabelView.setPadding(0, dp(8), 0, 0);
        panel.addView(cameraLabelView);

        panel.addView(smallLabel("Résolution"), margins(0, dp(8), 0, 0));
        resolutionSpinner = new Spinner(this);
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, resolutionLabels);
        resolutionAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resolutionAdapter);
        resolutionSpinner.setSelection(indexForHeight(
                prefs.getInt(WebcamService.PREF_HEIGHT,
                        WebcamService.DEFAULT_HEIGHT)));
        resolutionSpinner.setOnTouchListener((view, event) -> {
            resolutionTouched = true;
            return false;
        });
        resolutionSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent,
                                               android.view.View view,
                                               int position, long id) {
                        if (syncing || !resolutionTouched) {
                            return;
                        }
                        resolutionTouched = false;
                        int height = resolutionHeights[position];
                        prefs.edit()
                                .putInt(WebcamService.PREF_HEIGHT, height)
                                .apply();
                        if (service != null) {
                            service.setRequestedHeight(height);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
        panel.addView(resolutionSpinner, margins(0, 0, 0, dp(6)));

        panel.addView(smallLabel("Qualité JPEG"), margins(0, dp(2), 0, 0));
        qualitySpinner = new Spinner(this);
        ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, qualityLabels);
        qualityAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(qualityAdapter);
        qualitySpinner.setSelection(indexForQuality(
                prefs.getInt(WebcamService.PREF_QUALITY,
                        WebcamService.DEFAULT_JPEG_QUALITY)));
        qualitySpinner.setOnTouchListener((view, event) -> {
            qualityTouched = true;
            return false;
        });
        qualitySpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent,
                                               android.view.View view,
                                               int position, long id) {
                        if (syncing || !qualityTouched) {
                            return;
                        }
                        qualityTouched = false;
                        int quality = qualityValues[position];
                        prefs.edit()
                                .putInt(WebcamService.PREF_QUALITY, quality)
                                .apply();
                        if (service != null) {
                            service.setJpegQuality(quality);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
        panel.addView(qualitySpinner, margins(0, 0, 0, dp(6)));

        Button fluidButton = new Button(this);
        fluidButton.setText("Mode fluide (640 × 480, qualité 70)");
        fluidButton.setAllCaps(false);
        fluidButton.setOnClickListener(v -> applyFluidMode());
        panel.addView(fluidButton, margins(0, 0, 0, dp(6)));

        audioSwitch = new Switch(this);
        audioSwitch.setText("Micro du téléphone");
        audioSwitch.setTextSize(14);
        audioSwitch.setTextColor(Color.WHITE);
        audioSwitch.setChecked(hasAudioPermission()
                && prefs.getBoolean(WebcamService.PREF_AUDIO, true));
        audioSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncing) {
                return;
            }
            if (checked && !hasAudioPermission()) {
                syncing = true;
                audioSwitch.setChecked(false);
                syncing = false;
                requestPermissions();
                return;
            }
            prefs.edit().putBoolean(WebcamService.PREF_AUDIO, checked).apply();
            if (service != null) {
                service.setAudioEnabled(checked);
            }
            updateUrl();
            updateAudioHint();
        });
        panel.addView(audioSwitch, margins(0, dp(2), 0, dp(2)));

        audioHintView = new TextView(this);
        audioHintView.setText("Autorise le micro dans Android pour activer le son.");
        audioHintView.setTextSize(11);
        audioHintView.setTextColor(0xFFF6D32D);
        audioHintView.setPadding(0, 0, 0, dp(4));
        panel.addView(audioHintView);

        flipButton = new Button(this);
        flipButton.setText("Passer à la caméra avant");
        flipButton.setAllCaps(false);
        flipButton.setOnClickListener(v -> flipCamera());
        panel.addView(flipButton, margins(0, dp(4), 0, dp(6)));

        Button usbButton = new Button(this);
        usbButton.setText("Partage USB…");
        usbButton.setAllCaps(false);
        usbButton.setOnClickListener(v -> openTetheringSettings());
        panel.addView(usbButton, margins(0, 0, 0, dp(6)));

        toggleButton = new Button(this);
        toggleButton.setText("Démarrer le serveur");
        toggleButton.setAllCaps(false);
        toggleButton.setOnClickListener(v -> toggleServer());
        panel.addView(toggleButton, margins(0, 0, 0, dp(10)));

        TextView urlLabel = new TextView(this);
        urlLabel.setText("Adresse Capturo (détection automatique Wi-Fi/USB)");
        urlLabel.setTextSize(12);
        urlLabel.setTextColor(0xFFB6B6BE);
        panel.addView(urlLabel);

        urlView = new TextView(this);
        urlView.setTextSize(12);
        urlView.setTextColor(0xFF62A0EA);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(0, dp(4), 0, dp(8));
        panel.addView(urlView);

        TextView info = new TextView(this);
        info.setText("Tournez le téléphone : l'interface et l'image s'adaptent "
                + "au portrait comme au paysage.\n\n"
                + "Dans Capturo : Caméra → Source → Caméra réseau, puis "
                + "cliquez sur « Détecter » : Capturo trouve le téléphone en "
                + "Wi-Fi ou en USB. Pour le son, activez « Micro du téléphone » "
                + "dans Capturo (/audio).\n\n"
                + "USB : branchez le câble, activez le partage de connexion USB "
                + "dans Android, puis cliquez sur « Détecter » dans Capturo. "
                + "L'USB est plus rapide et plus stable que le Wi-Fi.\n\n"
                + "Si l'image saccade : choisissez la qualité 70 ou 640 × 480, "
                + "ou passez en USB. La 4K demande beaucoup de débit. Le service "
                + "continue de diffuser quand l'écran se verrouille.");
        info.setTextSize(11);
        info.setTextColor(0xFF8A8A92);
        panel.addView(info);

        lastPreviewFrame = null;
        previewBitmap = null;
        lastStatusText = null;
        lastCameraText = null;
        lastClientCount = -1;
        setContentView(root);
        updateAudioHint();
        updateUrl();
    }

    private TextView smallLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(0xFFB6B6BE);
        return view;
    }

    private LinearLayout.LayoutParams margins(
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateFromService() {
        if (service == null) {
            setStatus("● Arrêté", false);
            if (toggleButton != null) {
                toggleButton.setText("Démarrer le serveur");
            }
            updateClientCount(0);
            updateUrl();
            return;
        }

        boolean running = service.isStreaming();
        String error = service.getLastError();
        if (running) {
            setStatus("● En ligne", true);
        } else if (error != null && !error.isEmpty()) {
            setStatus("● " + error, false);
        } else {
            setStatus("● Arrêté", false);
        }
        if (toggleButton != null) {
            toggleButton.setText(running
                    ? "Arrêter le serveur" : "Démarrer le serveur");
        }
        updateClientCount(service.getClientCount());
        pollCount++;
        if (pollCount % 20 == 0) {
            updateUrl();
        }

        int facing = service.getLensFacing();
        boolean back = facing == CameraCharacteristics.LENS_FACING_BACK;
        if (flipButton != null) {
            flipButton.setText(back
                    ? "Passer à la caméra avant"
                    : "Passer à la caméra arrière");
        }
        if (cameraLabelView != null) {
            String cameraText = (back ? "Caméra arrière" : "Caméra avant")
                    + " · " + heightLabel(service.getRequestedHeight())
                    + " · qualité " + service.getJpegQuality();
            if (!cameraText.equals(lastCameraText)) {
                cameraLabelView.setText(cameraText);
                lastCameraText = cameraText;
            }
        }

        if (running && !service.isAudioEnabled()
                && audioSwitch != null && audioSwitch.isChecked()
                && !hasAudioPermission()) {
            syncing = true;
            audioSwitch.setChecked(false);
            syncing = false;
        }

        byte[] frame = service.getLatestFrame();
        if (frame != null && frame != lastPreviewFrame && !previewDecoding) {
            lastPreviewFrame = frame;
            previewDecoding = true;
            int sampleSize = service.getRequestedHeight() >= 1440 ? 4 : 2;
            previewExecutor.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                Bitmap bitmap = BitmapFactory.decodeByteArray(
                        frame, 0, frame.length, options);
                uiHandler.post(() -> {
                    previewDecoding = false;
                    if (bitmap == null) {
                        return;
                    }
                    if (previewView == null) {
                        bitmap.recycle();
                        return;
                    }
                    previewView.setImageBitmap(bitmap);
                    if (previewBitmap != null) {
                        previewBitmap.recycle();
                    }
                    previewBitmap = bitmap;
                });
            });
        }
        updateAudioHint();
    }

    private String heightLabel(int height) {
        if (height >= 2160) {
            return "4K";
        }
        if (height >= 1440) {
            return "1440p";
        }
        if (height >= 1080) {
            return "1080p";
        }
        if (height >= 720) {
            return "720p";
        }
        return "480p";
    }

    private void syncControlsFromService() {
        if (service == null) {
            return;
        }
        syncing = true;
        resolutionSpinner.setSelection(
                indexForHeight(service.getRequestedHeight()));
        qualitySpinner.setSelection(
                indexForQuality(service.getJpegQuality()));
        if (audioSwitch != null) {
            audioSwitch.setChecked(service.isAudioEnabled());
        }
        syncing = false;
    }

    private int indexForHeight(int height) {
        for (int index = 0; index < resolutionHeights.length; index++) {
            if (resolutionHeights[index] == height) {
                return index;
            }
        }
        return indexForHeight(WebcamService.DEFAULT_HEIGHT);
    }

    private int indexForQuality(int quality) {
        for (int index = 0; index < qualityValues.length; index++) {
            if (qualityValues[index] == quality) {
                return index;
            }
        }
        return 1;
    }

    private void setStatus(String text, boolean online) {
        if (statusView == null) {
            return;
        }
        if (text.equals(lastStatusText) && online == lastStatusOnline) {
            return;
        }
        statusView.setText(text);
        statusView.setTextColor(online ? 0xFF8FF0A4 : 0xFFF6D32D);
        lastStatusText = text;
        lastStatusOnline = online;
    }

    private void updateClientCount(int count) {
        if (clientView == null || count == lastClientCount) {
            return;
        }
        lastClientCount = count;
        if (count <= 0) {
            clientView.setText("Aucune connexion active");
        } else {
            clientView.setText(count + " connexion"
                    + (count > 1 ? "s" : "") + " active"
                    + (count > 1 ? "s" : ""));
        }
    }

    private void updateAudioHint() {
        if (audioHintView == null) {
            return;
        }
        audioHintView.setVisibility(
                hasAudioPermission() ? android.view.View.GONE
                        : android.view.View.VISIBLE);
    }

    private void updateUrl() {
        if (urlView == null) {
            return;
        }
        List<String> urls = NetUtils.urls(WebcamService.PORT);
        if (urls.isEmpty()) {
            urlView.setText("Aucune adresse réseau.\n"
                    + "Activez le Wi-Fi ou le partage de connexion USB.");
            return;
        }
        boolean audio = audioSwitch != null && audioSwitch.isChecked();
        StringBuilder builder = new StringBuilder();
        for (String entry : urls) {
            int marker = entry.indexOf("/video");
            if (marker < 0) {
                continue;
            }
            String suffix = "";
            int paren = entry.indexOf("   (");
            if (paren >= 0) {
                suffix = entry.substring(paren);
            }
            String base = entry.substring(0, marker);
            builder.append("Vidéo : ").append(base).append("/video")
                    .append(suffix).append('\n');
            if (audio) {
                builder.append("Son   : ").append(base).append("/audio")
                        .append(suffix).append('\n');
            }
        }
        urlView.setText(builder.toString().trim());
    }

    // -------------------------------------------------------------- actions

    private void applyFluidMode() {
        int height = 480;
        int quality = 70;
        prefs.edit()
                .putInt(WebcamService.PREF_HEIGHT, height)
                .putInt(WebcamService.PREF_QUALITY, quality)
                .apply();
        syncing = true;
        resolutionSpinner.setSelection(indexForHeight(height));
        qualitySpinner.setSelection(indexForQuality(quality));
        syncing = false;
        if (service != null) {
            service.setProfile(height, quality);
        }
        updateFromService();
    }

    private void openTetheringSettings() {
        try {
            startActivity(new Intent(ACTION_TETHER_SETTINGS));
        } catch (Exception error) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void toggleServer() {
        if (service != null && service.isStreaming()) {
            userStopped = true;
            service.stopStreaming();
            uiHandler.postDelayed(this::updateFromService, 300);
        } else {
            startStreaming();
        }
    }

    private void startStreaming() {
        if (!hasCameraPermission()) {
            requestPermissions();
            return;
        }
        userStopped = false;
        Intent intent = new Intent(this, WebcamService.class)
                .setAction(WebcamService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        uiHandler.postDelayed(this::updateFromService, 400);
    }

    private void flipCamera() {
        if (service == null) {
            return;
        }
        int facing = service.getLensFacing()
                == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        service.setLensFacing(facing);
        uiHandler.postDelayed(this::updateFromService, 300);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (permissionRequested) {
            return;
        }
        permissionRequested = true;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQUEST_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, REQUEST_PERMISSIONS);
        }
    }
}
