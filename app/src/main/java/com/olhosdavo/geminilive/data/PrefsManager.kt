package com.olhosdavo.geminilive.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as configurações salvas no aparelho usando commit() síncrono.
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoLaunchOnOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAUNCH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_LAUNCH, value).commit()
        }

    var voiceFeedback: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FEEDBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, value).commit()
        }

    var hasVisualCalibration: Boolean
        get() = prefs.getBoolean(KEY_HAS_CALIBRATION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HAS_CALIBRATION, value).commit()
        }

    var liveButtonXPx: Int
        get() = prefs.getInt(KEY_LIVE_PX_X, -1)
        set(value) {
            prefs.edit().putInt(KEY_LIVE_PX_X, value).commit()
        }

    var liveButtonYPx: Int
        get() = prefs.getInt(KEY_LIVE_PX_Y, -1)
        set(value) {
            prefs.edit().putInt(KEY_LIVE_PX_Y, value).commit()
        }

    var cameraButtonXPx: Int
        get() = prefs.getInt(KEY_CAMERA_PX_X, -1)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_PX_X, value).commit()
        }

    var cameraButtonYPx: Int
        get() = prefs.getInt(KEY_CAMERA_PX_Y, -1)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_PX_Y, value).commit()
        }

    fun saveCalibration(liveX: Int, liveY: Int, cameraX: Int, cameraY: Int): Boolean {
        return prefs.edit()
            .putInt(KEY_LIVE_PX_X, liveX)
            .putInt(KEY_LIVE_PX_Y, liveY)
            .putInt(KEY_CAMERA_PX_X, cameraX)
            .putInt(KEY_CAMERA_PX_Y, cameraY)
            .putBoolean(KEY_HAS_CALIBRATION, true)
            .commit()
    }

    // Tempo 1: Espera para o Gemini abrir antes de dar o Clique 1 no botão Live (padrão 30 = 3.0s)
    var liveDelayTenths: Int
        get() = prefs.getInt(KEY_LIVE_DELAY_TENTHS_V2, DEFAULT_LIVE_DELAY_TENTHS)
        set(value) {
            prefs.edit().putInt(KEY_LIVE_DELAY_TENTHS_V2, value).commit()
        }

    val liveDelayMs: Long
        get() = (liveDelayTenths.coerceIn(10, 100) * 100L)

    // Tempo 2: Espera após o Clique 1 para o modo Live conectar antes de dar o Clique 2 na Câmera (padrão 55 = 5.5s)
    var cameraDelayTenths: Int
        get() = prefs.getInt(KEY_CAMERA_DELAY_TENTHS_V2, DEFAULT_CAMERA_DELAY_TENTHS)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_DELAY_TENTHS_V2, value).commit()
        }

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(15, 120) * 100L)

    companion object {
        private const val PREFS_NAME = "olhos_da_vo_prefs"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_open"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_HAS_CALIBRATION = "has_visual_calibration"
        private const val KEY_LIVE_PX_X = "live_px_x"
        private const val KEY_LIVE_PX_Y = "live_px_y"
        private const val KEY_CAMERA_PX_X = "camera_px_x"
        private const val KEY_CAMERA_PX_Y = "camera_px_y"

        // Chaves novas (_v2) para não puxar o valor antigo de 2.2s salvo nas primeiras versões
        private const val KEY_LIVE_DELAY_TENTHS_V2 = "live_delay_tenths_v2"
        private const val KEY_CAMERA_DELAY_TENTHS_V2 = "camera_delay_tenths_v2"

        const val DEFAULT_LIVE_DELAY_TENTHS = 30   // 3.0 segundos para o Clique 1 (Live)
        const val DEFAULT_CAMERA_DELAY_TENTHS = 55 // 5.5 segundos após o Clique 1 para o Clique 2 (Câmera)
    }
}
