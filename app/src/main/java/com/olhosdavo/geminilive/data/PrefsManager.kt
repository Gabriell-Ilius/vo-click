package com.olhosdavo.geminilive.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as configurações salvas no aparelho (modo de abertura, voz e posições exatas dos alvos 1 e 2).
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

    var hasVisualCalibration: Boolean
        get() = prefs.getBoolean(KEY_HAS_CALIBRATION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_CALIBRATION, value).apply()

    // Coordenadas em pixels exatos da tela (salvas pelo Calibrador Visual de Alvos)
    var liveButtonXPx: Int
        get() = prefs.getInt(KEY_LIVE_PX_X, -1)
        set(value) = prefs.edit().putInt(KEY_LIVE_PX_X, value).apply()

    var liveButtonYPx: Int
        get() = prefs.getInt(KEY_LIVE_PX_Y, -1)
        set(value) = prefs.edit().putInt(KEY_LIVE_PX_Y, value).apply()

    var cameraButtonXPx: Int
        get() = prefs.getInt(KEY_CAMERA_PX_X, -1)
        set(value) = prefs.edit().putInt(KEY_CAMERA_PX_X, value).apply()

    var cameraButtonYPx: Int
        get() = prefs.getInt(KEY_CAMERA_PX_Y, -1)
        set(value) = prefs.edit().putInt(KEY_CAMERA_PX_Y, value).apply()

    // Porcentagens padrão caso ainda não tenha usado o Calibrador Visual
    var liveButtonXPercent: Int
        get() = prefs.getInt(KEY_LIVE_BTN_X, DEFAULT_LIVE_X)
        set(value) = prefs.edit().putInt(KEY_LIVE_BTN_X, value).apply()

    var liveButtonYPercent: Int
        get() = prefs.getInt(KEY_LIVE_BTN_Y, DEFAULT_LIVE_Y)
        set(value) = prefs.edit().putInt(KEY_LIVE_BTN_Y, value).apply()

    var cameraButtonXPercent: Int
        get() = prefs.getInt(KEY_CAMERA_BTN_X, DEFAULT_CAMERA_X)
        set(value) = prefs.edit().putInt(KEY_CAMERA_BTN_X, value).apply()

    var cameraButtonYPercent: Int
        get() = prefs.getInt(KEY_CAMERA_BTN_Y, DEFAULT_CAMERA_Y)
        set(value) = prefs.edit().putInt(KEY_CAMERA_BTN_Y, value).apply()

    // Tempo de espera em décimos de segundo para o modo Live abrir antes de tocar na câmera (padrão 28 = 2.8s)
    var cameraDelayTenths: Int
        get() = prefs.getInt(KEY_CAMERA_DELAY_TENTHS, DEFAULT_DELAY_TENTHS)
        set(value) = prefs.edit().putInt(KEY_CAMERA_DELAY_TENTHS, value).apply()

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(15, 60) * 100L)

    companion object {
        private const val PREFS_NAME = "olhos_da_vo_prefs"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_open"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_HAS_CALIBRATION = "has_visual_calibration"
        private const val KEY_LIVE_PX_X = "live_px_x"
        private const val KEY_LIVE_PX_Y = "live_px_y"
        private const val KEY_CAMERA_PX_X = "camera_px_x"
        private const val KEY_CAMERA_PX_Y = "camera_px_y"
        private const val KEY_LIVE_BTN_X = "live_btn_x"
        private const val KEY_LIVE_BTN_Y = "live_btn_y"
        private const val KEY_CAMERA_BTN_X = "camera_btn_x"
        private const val KEY_CAMERA_BTN_Y = "camera_btn_y"
        private const val KEY_CAMERA_DELAY_TENTHS = "camera_delay_tenths"

        const val DEFAULT_LIVE_X = 88
        const val DEFAULT_LIVE_Y = 91
        const val DEFAULT_CAMERA_X = 22
        const val DEFAULT_CAMERA_Y = 86
        const val DEFAULT_DELAY_TENTHS = 28
    }
}
