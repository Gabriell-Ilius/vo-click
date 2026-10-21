package com.olhosdavo.geminilive.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as configurações salvas no aparelho (modo de abertura, voz e tempo de conexão do Live).
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoLaunchOnOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAUNCH, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LAUNCH, value).apply()

    var voiceFeedback: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, value).apply()

    // Tempo de espera em décimos de segundo para o modo Live conectar antes de acionar o botão virtual da câmera (padrão 25 = 2.5s)
    var cameraDelayTenths: Int
        get() = prefs.getInt(KEY_CAMERA_DELAY_TENTHS, DEFAULT_DELAY_TENTHS)
        set(value) = prefs.edit().putInt(KEY_CAMERA_DELAY_TENTHS, value).apply()

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(15, 60) * 100L)

    companion object {
        private const val PREFS_NAME = "olhos_da_vo_prefs"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_open"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_CAMERA_DELAY_TENTHS = "camera_delay_tenths"

        const val DEFAULT_DELAY_TENTHS = 25
    }
}
