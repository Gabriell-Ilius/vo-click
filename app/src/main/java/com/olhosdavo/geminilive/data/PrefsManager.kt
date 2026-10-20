package com.olhosdavo.geminilive.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as configurações salvas no aparelho (modo de abertura, voz e coordenadas de fallback).
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

    var useCoordinateFallback: Boolean
        get() = prefs.getBoolean(KEY_USE_COORD_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_COORD_FALLBACK, value).apply()

    // Posição padrão do botão Gemini Live (canto inferior direito da barra de entrada: X=88%, Y=91%)
    var liveButtonXPercent: Int
        get() = prefs.getInt(KEY_LIVE_BTN_X, DEFAULT_LIVE_X)
        set(value) = prefs.edit().putInt(KEY_LIVE_BTN_X, value).apply()

    var liveButtonYPercent: Int
        get() = prefs.getInt(KEY_LIVE_BTN_Y, DEFAULT_LIVE_Y)
        set(value) = prefs.edit().putInt(KEY_LIVE_BTN_Y, value).apply()

    // Posição padrão do botão de Câmera dentro do Gemini Live (barra inferior esquerda: X=22%, Y=89%)
    var cameraButtonXPercent: Int
        get() = prefs.getInt(KEY_CAMERA_BTN_X, DEFAULT_CAMERA_X)
        set(value) = prefs.edit().putInt(KEY_CAMERA_BTN_X, value).apply()

    var cameraButtonYPercent: Int
        get() = prefs.getInt(KEY_CAMERA_BTN_Y, DEFAULT_CAMERA_Y)
        set(value) = prefs.edit().putInt(KEY_CAMERA_BTN_Y, value).apply()

    // Tempo de espera em décimos de segundo antes de usar o clique na câmera (padrão 22 = 2.2s)
    var cameraDelayTenths: Int
        get() = prefs.getInt(KEY_CAMERA_DELAY_TENTHS, DEFAULT_DELAY_TENTHS)
        set(value) = prefs.edit().putInt(KEY_CAMERA_DELAY_TENTHS, value).apply()

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(10, 50) * 100L)

    companion object {
        private const val PREFS_NAME = "olhos_da_vo_prefs"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_open"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_USE_COORD_FALLBACK = "use_coord_fallback"
        private const val KEY_LIVE_BTN_X = "live_btn_x"
        private const val KEY_LIVE_BTN_Y = "live_btn_y"
        private const val KEY_CAMERA_BTN_X = "camera_btn_x"
        private const val KEY_CAMERA_BTN_Y = "camera_btn_y"
        private const val KEY_CAMERA_DELAY_TENTHS = "camera_delay_tenths"

        const val DEFAULT_LIVE_X = 88
        const val DEFAULT_LIVE_Y = 91
        const val DEFAULT_CAMERA_X = 22
        const val DEFAULT_CAMERA_Y = 89
        const val DEFAULT_DELAY_TENTHS = 22
    }
}
