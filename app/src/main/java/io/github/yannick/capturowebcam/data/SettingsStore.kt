package io.github.yannick.capturowebcam.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Préférences partagées entre l'interface Compose et le service.
 * Les clés sont les mêmes que dans l'ancienne version Java afin de
 * conserver les réglages de l'utilisateur lors de la mise à jour.
 */
object SettingsStore {

    const val LENS_BACK = 0
    const val LENS_FRONT = 1

    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val HEIGHT_FLUID = 480
    const val HEIGHT_HD = 720
    const val HEIGHT_FULL_HD = 1080
    const val HEIGHT_QHD = 1440
    const val HEIGHT_4K = 2160

    val HEIGHTS = listOf(
        HEIGHT_FLUID,
        HEIGHT_HD,
        HEIGHT_FULL_HD,
        HEIGHT_QHD,
        HEIGHT_4K,
    )

    val FPS_CHOICES = listOf(15, 24, 30, 60)

    private const val PREFS = "capturo"
    private const val KEY_HEIGHT = "height"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUDIO = "audio"
    private const val KEY_LENS = "lens_facing"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_FPS = "fps"
    private const val KEY_TOKEN = "access_token"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun height(): Int {
        val value = prefs.getInt(KEY_HEIGHT, HEIGHT_HD)
        return if (value in HEIGHTS) value else HEIGHT_HD
    }

    fun quality(): Int = prefs.getInt(KEY_QUALITY, 80).coerceIn(1, 100)

    fun audio(): Boolean = prefs.getBoolean(KEY_AUDIO, true)

    fun lensFacing(): Int = prefs.getInt(KEY_LENS, LENS_BACK)

    fun theme(): Int = prefs.getInt(KEY_THEME, THEME_AUTO).coerceIn(0, 2)

    fun accent(): Int = prefs.getInt(KEY_ACCENT, 0).coerceAtLeast(0)

    fun fps(): Int {
        val value = prefs.getInt(KEY_FPS, 30)
        return if (value in FPS_CHOICES) value else 30
    }

    /** Jeton exigé par le serveur MJPEG ; vide = accès libre sur le LAN. */
    fun token(): String = prefs.getString(KEY_TOKEN, "").orEmpty().trim()

    fun setProfile(height: Int, quality: Int) {
        prefs.edit()
            .putInt(KEY_HEIGHT, height)
            .putInt(KEY_QUALITY, quality.coerceIn(1, 100))
            .apply()
    }

    fun setFps(fps: Int) {
        val safe = if (fps in FPS_CHOICES) fps else 30
        prefs.edit().putInt(KEY_FPS, safe).apply()
    }

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun setAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO, enabled).apply()
    }

    fun setLensFacing(facing: Int) {
        prefs.edit().putInt(KEY_LENS, facing).apply()
    }

    fun setTheme(mode: Int) {
        prefs.edit().putInt(KEY_THEME, mode.coerceIn(0, 2)).apply()
    }

    fun setAccent(index: Int) {
        prefs.edit().putInt(KEY_ACCENT, index.coerceAtLeast(0)).apply()
    }
}
