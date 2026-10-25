package io.github.yannick.capturowebcam.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Adresse HTTP utilisable par Capturo pour joindre le téléphone. */
data class UrlInfo(
    val label: String,
    val video: String,
    val audio: String,
    val interfaceName: String,
)

/**
 * État partagé entre le service (producteur) et l'interface Compose.
 * Le service tourne dans le même processus : pas besoin de binder.
 */
object WebcamBus {

    val running = MutableStateFlow(false)
    val starting = MutableStateFlow(false)
    val status = MutableStateFlow("Arrêté")
    val error = MutableStateFlow<String?>(null)

    val clients = MutableStateFlow(0)
    val cameraLabel = MutableStateFlow("")
    val urls = MutableStateFlow<List<UrlInfo>>(emptyList())

    val height = MutableStateFlow(720)
    val quality = MutableStateFlow(80)
    val audio = MutableStateFlow(true)
    val lensFacing = MutableStateFlow(0)
    val fps = MutableStateFlow(30)
    val torch = MutableStateFlow(false)
    val torchAvailable = MutableStateFlow(false)

    /** Une seule image en attente : l'aperçu rate volontairement les anciennes. */
    val frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun publishFrame(jpeg: ByteArray) {
        frames.tryEmit(jpeg)
    }

    fun clearError() {
        error.value = null
    }
}
