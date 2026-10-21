package com.olhosdavo.geminilive.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia as configurações salvas no aparelho usando commit() síncrono para garantir
 * que as coordenadas calibradas dos Alvos 1 e 2 sejam sempre gravadas imediatamente no disco.
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

    /**
     * Salva todas as coordenadas dos Alvos 1 e 2 de forma atômica e síncrona (commit = true).
     */
    fun saveCalibration(liveX: Int, liveY: Int, cameraX: Int, cameraY: Int): Boolean {
        return prefs.edit()
            .putInt(KEY_LIVE_PX_X, liveX)
            .putInt(KEY_LIVE_PX_Y, liveY)
            .putInt(KEY_CAMERA_PX_X, cameraX)
            .putInt(KEY_CAMERA_PX_Y, cameraY)
            .putBoolean(KEY_HAS_CALIBRATION, true)
            .commit()
    }

    fun resetCalibration() {
        prefs.edit()
            .putBoolean(KEY_HAS_CALIBRATION, false)
            .putInt(KEY_LIVE_PX_X, -1)
            .putInt(KEY_LIVE_PX_Y, -1)
            .putInt(KEY_CAMERA_PX_X, -1)
            .putInt(KEY_CAMERA_PX_Y, -1)
            .commit()
    }

    // Tempo de espera em décimos de segundo para o modo Live conectar antes de tocar na câmera (padrão 40 = 4.0s)
    var cameraDelayTenths: Int
        get() = prefs.getInt(KEY_CAMERA_DELAY_TENTHS, DEFAULT_DELAY_TENTHS)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_DELAY_TENTHS, value).commit()
        }

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(15, 80) * 100L)

    companion object {
        private const val PREFS_NAME = "olhos_da_vo_prefs"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_open"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_HAS_CALIBRATION = "has_visual_calibration"
        private const val KEY_LIVE_PX_X = "live_px_x"
        private const val KEY_LIVE_PX_Y = "live_px_y"
        private const val KEY_CAMERA_PX_X = "camera_px_x"
        private const val KEY_CAMERA_PX_Y = "camera_px_y"
        private const val KEY_CAMERA_DELAY_TENTHS = "camera_delay_tenths"

        // 4.0 segundos de espera padrão (tempo para o Gemini Live abrir e conectar antes do clique na câmera)
        const val DEFAULT_DELAY_TENTHS = 40
    }
}
