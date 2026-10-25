package io.github.yannick.capturowebcam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.yannick.capturowebcam.data.SettingsStore
import io.github.yannick.capturowebcam.data.WebcamBus
import io.github.yannick.capturowebcam.service.NetUtils
import io.github.yannick.capturowebcam.service.WebcamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebcamScreen(
    themeMode: Int,
    accentIndex: Int,
    onThemeModeChange: (Int) -> Unit,
    onAccentChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val running by WebcamBus.running.collectAsStateWithLifecycle()
    val clients by WebcamBus.clients.collectAsStateWithLifecycle()
    val error by WebcamBus.error.collectAsStateWithLifecycle()
    val urls by WebcamBus.urls.collectAsStateWithLifecycle()
    val cameraLabel by WebcamBus.cameraLabel.collectAsStateWithLifecycle()
    val serviceStatus by WebcamBus.status.collectAsStateWithLifecycle()
    val busHeight by WebcamBus.height.collectAsStateWithLifecycle()
    val busQuality by WebcamBus.quality.collectAsStateWithLifecycle()
    val busAudio by WebcamBus.audio.collectAsStateWithLifecycle()
    val busFacing by WebcamBus.lensFacing.collectAsStateWithLifecycle()
    val busFps by WebcamBus.fps.collectAsStateWithLifecycle()
    val busTorch by WebcamBus.torch.collectAsStateWithLifecycle()
    val busTorchAvailable by WebcamBus.torchAvailable.collectAsStateWithLifecycle()

    var height by remember { mutableIntStateOf(SettingsStore.height()) }
    var quality by remember { mutableIntStateOf(SettingsStore.quality()) }
    var audio by remember { mutableStateOf(SettingsStore.audio()) }
    var facing by remember { mutableIntStateOf(SettingsStore.lensFacing()) }
    var fps by remember { mutableIntStateOf(SettingsStore.fps()) }
    var torch by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(SettingsStore.token()) }

    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    fun startServiceNow() {
        starting = true
        stopping = false
        WebcamBus.clearError()
        WebcamBus.status.value = "Démarrage…"
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WebcamService::class.java)
                    .setAction(WebcamService.ACTION_START),
            )
        } catch (problem: Exception) {
            starting = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Impossible de démarrer : ${problem.message}",
                )
            }
        }
    }

    fun stopServiceNow() {
        stopping = true
        val intent = Intent(context, WebcamService::class.java)
            .setAction(WebcamService.ACTION_STOP)
        try {
            context.startService(intent)
        } catch (_: Exception) {
            context.stopService(Intent(context, WebcamService::class.java))
        }
    }

    fun sendProfile(newHeight: Int, newQuality: Int) {
        if (!running) return
        context.startService(
            Intent(context, WebcamService::class.java)
                .setAction(WebcamService.ACTION_SET_PROFILE)
                .putExtra(WebcamService.EXTRA_HEIGHT, newHeight)
                .putExtra(WebcamService.EXTRA_QUALITY, newQuality),
        )
    }

    fun sendAudio(enabled: Boolean) {
        if (!running) return
        context.startService(
            Intent(context, WebcamService::class.java)
                .setAction(WebcamService.ACTION_SET_AUDIO)
                .putExtra(WebcamService.EXTRA_AUDIO, enabled),
        )
    }

    fun sendFacing(newFacing: Int) {
        if (!running) return
        context.startService(
            Intent(context, WebcamService::class.java)
                .setAction(WebcamService.ACTION_SET_FACING)
                .putExtra(WebcamService.EXTRA_FACING, newFacing),
        )
    }

    fun sendFps(newFps: Int) {
        if (!running) return
        context.startService(
            Intent(context, WebcamService::class.java)
                .setAction(WebcamService.ACTION_SET_FPS)
                .putExtra(WebcamService.EXTRA_FPS, newFps),
        )
    }

    fun sendTorch(enabled: Boolean) {
        if (!running) return
        context.startService(
            Intent(context, WebcamService::class.java)
                .setAction(WebcamService.ACTION_SET_TORCH)
                .putExtra(WebcamService.EXTRA_TORCH, enabled),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            starting = false
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Autorise la caméra pour démarrer la diffusion.",
                    actionLabel = "Réglages",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openAppSettings(context)
                }
            }
            return@rememberLauncherForActivityResult
        }
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        if (audio && !audioGranted) {
            audio = false
            SettingsStore.setAudio(false)
            WebcamBus.audio.value = false
        }
        startServiceNow()
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            audio = true
            SettingsStore.setAudio(true)
            WebcamBus.audio.value = true
            sendAudio(true)
        } else {
            audio = false
            SettingsStore.setAudio(false)
            WebcamBus.audio.value = false
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Autorise le micro dans les réglages Android.",
                    actionLabel = "Réglages",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openAppSettings(context)
                }
            }
        }
    }

    fun requestStart() {
        if (starting || running) return
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.CAMERA)
            }
            if (audio && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startServiceNow()
        }
    }

    fun changeProfile(newHeight: Int, newQuality: Int) {
        if (height == newHeight && quality == newQuality) return
        height = newHeight
        quality = newQuality
        SettingsStore.setProfile(newHeight, newQuality)
        WebcamBus.height.value = newHeight
        WebcamBus.quality.value = newQuality
        sendProfile(newHeight, newQuality)
    }

    fun changeAudio(enabled: Boolean) {
        if (enabled && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        audio = enabled
        SettingsStore.setAudio(enabled)
        WebcamBus.audio.value = enabled
        sendAudio(enabled)
    }

    fun changeFacing(newFacing: Int) {
        if (facing == newFacing) return
        facing = newFacing
        SettingsStore.setLensFacing(newFacing)
        WebcamBus.lensFacing.value = newFacing
        sendFacing(newFacing)
    }

    fun changeFps(newFps: Int) {
        if (fps == newFps) return
        fps = newFps
        SettingsStore.setFps(newFps)
        WebcamBus.fps.value = newFps
        sendFps(newFps)
    }

    fun changeTorch(enabled: Boolean) {
        torch = enabled
        WebcamBus.torch.value = enabled
        sendTorch(enabled)
    }

    fun changeToken(value: String) {
        token = value.trim()
        SettingsStore.setToken(token)
        if (running) {
            WebcamBus.urls.value = NetUtils.urls(WebcamService.PORT, token)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WebcamBus.frames.collectLatest { bytes ->
                val bitmap = withContext(Dispatchers.Default) { decodePreview(bytes) }
                if (bitmap != null) preview = bitmap.asImageBitmap()
            }
        }
    }

    LaunchedEffect(running) {
        if (running) {
            starting = false
            stopping = false
        } else {
            stopping = false
            if (!starting) preview = null
        }
    }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        starting = false
        stopping = false
        val action = if (message.contains("autoris", true) ||
            message.contains("refus", true) ||
            message.contains("permission", true)
        ) {
            "Réglages"
        } else {
            null
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = action,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        WebcamBus.clearError()
        if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
    }

    LaunchedEffect(starting) {
        if (starting) {
            delay(12_000)
            if (!running) starting = false
        }
    }

    LaunchedEffect(busHeight, running) {
        if (running && busHeight != height) height = busHeight
    }
    LaunchedEffect(busQuality, running) {
        if (running && busQuality != quality) quality = busQuality
    }
    LaunchedEffect(busAudio, running) {
        if (running && busAudio != audio) audio = busAudio
    }
    LaunchedEffect(busFacing, running) {
        if (running && busFacing != facing) facing = busFacing
    }
    LaunchedEffect(busFps, running) {
        if (running && busFps != fps) fps = busFps
    }
    LaunchedEffect(busTorch) {
        torch = busTorch
    }
    LaunchedEffect(busTorchAvailable) {
        torchAvailable = busTorchAvailable
    }

    val statusText = when {
        error != null -> error ?: ""
        running -> {
            if (serviceStatus.startsWith("Reconnexion") ||
                serviceStatus.startsWith("Ouverture") ||
                serviceStatus.startsWith("Redémarrage")
            ) {
                serviceStatus
            } else {
                buildString {
                    append("Diffusion active")
                    if (cameraLabel.isNotEmpty()) append(" · ").append(cameraLabel)
                    if (clients > 0) {
                        append(" · ")
                        append(clients)
                        append(if (clients == 1) " client" else " clients")
                    }
                }
            }
        }
        starting -> "Démarrage…"
        stopping -> "Arrêt…"
        else -> "Prêt · MJPEG + PCM"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Capturo Webcam",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (running) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Réglages")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val landscape = maxWidth > maxHeight
            val contentPadding = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)

            if (landscape) {
                Row(
                    modifier = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PreviewCard(
                            preview = preview,
                            running = running,
                            starting = starting,
                            clients = clients,
                            cameraLabel = cameraLabel,
                            height = height,
                            compact = true,
                        )
                        PrimaryAction(
                            running = running,
                            starting = starting,
                            stopping = stopping,
                            onStart = { requestStart() },
                            onStop = { stopServiceNow() },
                        )
                        ConnectionCard(
                            urls = urls,
                            running = running,
                            onRefresh = {
                                WebcamBus.urls.value = NetUtils.urls(WebcamService.PORT, token)
                            },
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CameraCard(
                            height = height,
                            quality = quality,
                            audio = audio,
                            facing = facing,
                            fps = fps,
                            torch = torch,
                            torchAvailable = torchAvailable,
                            running = running,
                            onProfile = { h, q -> changeProfile(h, q) },
                            onAudio = { changeAudio(it) },
                            onFacing = { changeFacing(it) },
                            onFps = { changeFps(it) },
                            onTorch = { changeTorch(it) },
                        )
                        TipsCard()
                    }
                }
            } else {
                Column(
                    modifier = contentPadding.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PreviewCard(
                        preview = preview,
                        running = running,
                        starting = starting,
                        clients = clients,
                        cameraLabel = cameraLabel,
                        height = height,
                    )
                    PrimaryAction(
                        running = running,
                        starting = starting,
                        stopping = stopping,
                        onStart = { requestStart() },
                        onStop = { stopServiceNow() },
                    )
                    ConnectionCard(
                        urls = urls,
                        running = running,
                        onRefresh = {
                            WebcamBus.urls.value = NetUtils.urls(WebcamService.PORT, token)
                        },
                    )
                    CameraCard(
                        height = height,
                        quality = quality,
                        audio = audio,
                        facing = facing,
                        fps = fps,
                        torch = torch,
                        torchAvailable = torchAvailable,
                        running = running,
                        onProfile = { h, q -> changeProfile(h, q) },
                        onAudio = { changeAudio(it) },
                        onFacing = { changeFacing(it) },
                        onFps = { changeFps(it) },
                        onTorch = { changeTorch(it) },
                    )
                    TipsCard()
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            themeMode = themeMode,
            accentIndex = accentIndex,
            token = token,
            onThemeModeChange = onThemeModeChange,
            onAccentChange = onAccentChange,
            onTokenChange = { changeToken(it) },
            onDismiss = { showSettings = false },
        )
    }
}

private fun decodePreview(bytes: ByteArray, maxWidth: Int = 1280): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / sample > maxWidth) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
