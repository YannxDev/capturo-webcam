package io.github.yannick.capturowebcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.yannick.capturowebcam.data.SettingsStore
import io.github.yannick.capturowebcam.data.UrlInfo
import kotlin.random.Random

@Composable
internal fun PreviewCard(
    preview: ImageBitmap?,
    running: Boolean,
    starting: Boolean,
    clients: Int,
    cameraLabel: String,
    height: Int,
    compact: Boolean = false,
) {
    Card(
        modifier = if (compact) {
            Modifier
                .fillMaxWidth()
                .height(190.dp)
        } else {
            Modifier.fillMaxWidth()
        },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF08080C)),
    ) {
        Box(
            modifier = if (compact) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            },
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "Aperçu de la caméra",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = if (running || starting) {
                            Icons.Rounded.Videocam
                        } else {
                            Icons.Rounded.VideocamOff
                        },
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(42.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (running || starting) {
                            "Connexion à la caméra…"
                        } else {
                            "Aperçu en pause"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (running) {
                LiveBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (cameraLabel.isNotEmpty()) InfoPill(cameraLabel)
                    if (clients > 0) {
                        InfoPill(if (clients == 1) "1 client" else "$clients clients")
                    }
                }
            }

            if (preview != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                ) {
                    InfoPill(resolutionLabel(height))
                }
            }
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Surface(
        modifier = modifier,
        color = Color(0xFFE5484D),
        shape = RoundedCornerShape(50),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "EN DIRECT",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
internal fun PrimaryAction(
    running: Boolean,
    starting: Boolean,
    stopping: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val busy = starting || stopping
    Button(
        onClick = { if (running) onStop() else onStart() },
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = if (running) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (starting) "Démarrage…" else "Arrêt…",
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(
                imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (running) "Arrêter la diffusion" else "Démarrer la diffusion",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun ConnectionCard(
    urls: List<UrlInfo>,
    running: Boolean,
    onRefresh: () -> Unit,
) {
    var showQr by remember { mutableStateOf(false) }
    SectionCard(
        title = "Connexion",
        icon = Icons.Rounded.Wifi,
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showQr = true },
                    enabled = urls.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = "Afficher le QR")
                }
                IconButton(onClick = onRefresh, enabled = running) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Rafraîchir les adresses")
                }
            }
        },
    ) {
        if (urls.isEmpty()) {
            Text(
                text = "Démarre la diffusion pour obtenir l'adresse à saisir dans Capturo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            urls.forEach { url ->
                AddressRow(url)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Dans Capturo : Caméra → Source → Caméra réseau (URL), puis Détecter. Wi-Fi ou USB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showQr && urls.isNotEmpty()) {
        val target = urls.first().video
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Scanner l'adresse") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(
                        data = target,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = target,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQr = false }) { Text("Fermer") }
            },
        )
    }
}

@Composable
private fun QrCode(data: String, modifier: Modifier = Modifier) {
    val bitmap = remember(data) {
        runCatching {
            val matrix = QRCodeWriter().encode(
                data,
                BarcodeFormat.QR_CODE,
                512,
                512,
            )
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            val black = Color.Black.toArgb()
            val white = Color.White.toArgb()
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) black else white
                }
            }
            Bitmap.createBitmap(
                pixels,
                width,
                height,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR de l'adresse",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun AddressRow(url: UrlInfo) {
    val context = LocalContext.current
    val isUsb = url.label == "USB"
    val containerColor = if (isUsb) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onContainerColor = if (isUsb) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = containerColor, shape = RoundedCornerShape(8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isUsb) Icons.Rounded.Usb else Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = onContainerColor,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = url.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainerColor,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = url.video,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (url.interfaceName.isNotEmpty()) {
                Text(
                    text = url.interfaceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("Capturo Webcam", url.video),
                )
                Toast.makeText(context, "Adresse copiée", Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copier l'adresse")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraCard(
    height: Int,
    quality: Int,
    audio: Boolean,
    facing: Int,
    fps: Int,
    torch: Boolean,
    torchAvailable: Boolean,
    running: Boolean,
    onProfile: (Int, Int) -> Unit,
    onAudio: (Boolean) -> Unit,
    onFacing: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onTorch: (Boolean) -> Unit,
) {
    SectionCard(title = "Caméra", icon = Icons.Rounded.CameraAlt) {
        Text("Résolution", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                480 to "480p",
                720 to "720p",
                1080 to "1080p",
                1440 to "1440p",
                2160 to "4K",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = height == value,
                    onClick = { onProfile(value, quality) },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Images par seconde", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsStore.FPS_CHOICES.forEach { value ->
                FilterChip(
                    selected = fps == value,
                    onClick = { onFps(value) },
                    label = { Text("$value i/s") },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Qualité JPEG", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                70 to "Fluide",
                80 to "Équilibrée",
                90 to "Maximale",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = quality == value,
                    onClick = { onProfile(height, value) },
                    label = { Text("$label · $value") },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onProfile(480, 70) },
            enabled = height != 480 || quality != 70,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Mode fluide")
        }
        Text(
            text = "640 × 480, qualité 70 : idéal si l'image saccade.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Caméra", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (facing == SettingsStore.LENS_FRONT) "Avant" else "Arrière",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = facing == SettingsStore.LENS_BACK,
                    onClick = { onFacing(SettingsStore.LENS_BACK) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("Arrière") },
                )
                SegmentedButton(
                    selected = facing == SettingsStore.LENS_FRONT,
                    onClick = { onFacing(SettingsStore.LENS_FRONT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("Avant") },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = null,
                tint = if (torchAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Torche", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = when {
                        torchAvailable && torch -> "Allumée"
                        torchAvailable -> "Éteinte"
                        else -> "Indisponible sur cette caméra"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = torch && torchAvailable,
                onCheckedChange = onTorch,
                enabled = torchAvailable && running,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (audio) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Micro du téléphone", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (audio) "PCM 48 kHz mono sur /audio" else "Désactivé",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = audio, onCheckedChange = onAudio)
        }

        if (running) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Les changements redémarrent brièvement la caméra.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TipsCard() {
    SectionCard(title = "Astuces", icon = Icons.Rounded.Lightbulb) {
        TipRow("Wi-Fi", "Pour la 4K, préfère l'USB ou un Wi-Fi 5 GHz.")
        TipRow(
            "USB",
            "Active le partage de connexion USB dans les réglages Android, " +
                "ou utilise adb forward tcp:8080 tcp:8080.",
        )
        TipRow(
            "Capturo",
            "Clique sur Détecter : l'adresse Wi-Fi/USB est remplie automatiquement.",
        )
        TipRow(
            "Batterie",
            "La diffusion continue chauffe le téléphone ; baisse la résolution si besoin.",
        )
    }
}

@Composable
private fun TipRow(title: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    icon: ImageVector,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                action?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsSheet(
    themeMode: Int,
    accentIndex: Int,
    token: String,
    onThemeModeChange: (Int) -> Unit,
    onAccentChange: (Int) -> Unit,
    onTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = "Apparence",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text("Thème", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeChip(
                    label = "Auto",
                    icon = Icons.Rounded.BrightnessAuto,
                    selected = themeMode == SettingsStore.THEME_AUTO,
                ) { onThemeModeChange(SettingsStore.THEME_AUTO) }
                ThemeChip(
                    label = "Clair",
                    icon = Icons.Rounded.LightMode,
                    selected = themeMode == SettingsStore.THEME_LIGHT,
                ) { onThemeModeChange(SettingsStore.THEME_LIGHT) }
                ThemeChip(
                    label = "Sombre",
                    icon = Icons.Rounded.DarkMode,
                    selected = themeMode == SettingsStore.THEME_DARK,
                ) { onThemeModeChange(SettingsStore.THEME_DARK) }
            }

            Spacer(Modifier.height(20.dp))
            Text("Couleur d'accent", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentChoices.forEachIndexed { index, choice ->
                    AccentDot(
                        choice = choice,
                        selected = accentIndex == index,
                        onClick = { onAccentChange(index) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))

            Text(
                text = "Accès au flux",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Un jeton protège /video et /audio. Laisse vide pour " +
                    "un accès libre sur le réseau local. L'adresse et le QR " +
                    "sont mis à jour automatiquement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Jeton d'accès") },
                placeholder = { Text("Aucun (accès libre)") },
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        onClick = {
                            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                            onTokenChange(
                                buildString {
                                    repeat(8) {
                                        append(alphabet[Random.nextInt(alphabet.length)])
                                    }
                                },
                            )
                        },
                    ) { Text("Générer") }
                },
            )
            if (token.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Le QR de la page Connexion contient déjà ?token=…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))

            Text(
                text = "À propos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Capturo Webcam 2.1 · MJPEG + PCM 48 kHz mono. " +
                    "Diffusion locale, sans module noyau ni root.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pour l'USB, active le partage de connexion Android ou " +
                    "utilise adb forward. Capturo détecte le téléphone en Wi-Fi " +
                    "comme en USB.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Fermer")
            }
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
    )
}

@Composable
private fun AccentDot(
    choice: AccentChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(choice.light)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = readableOn(choice.light),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = choice.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun resolutionLabel(height: Int): String = when (height) {
    2160 -> "4K"
    1440 -> "1440p"
    1080 -> "1080p"
    720 -> "720p"
    else -> "480p"
}
