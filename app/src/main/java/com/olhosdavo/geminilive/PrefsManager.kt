package com.olhosdavo.geminilive

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("olhos_da_vo_prefs", Context.MODE_PRIVATE)

    var autoLaunchOnOpen: Boolean
        get() = prefs.getBoolean("auto_launch_on_open", false)
        set(value) = prefs.edit().putBoolean("auto_launch_on_open", value).apply()

    var voiceFeedback: Boolean
        get() = prefs.getBoolean("voice_feedback", true)
        set(value) = prefs.edit().putBoolean("voice_feedback", value).apply()

    var useCoordinateFallback: Boolean
        get() = prefs.getBoolean("use_coord_fallback", true)
        set(value) = prefs.edit().putBoolean("use_coord_fallback", value).apply()

    // Posição padrão do botão Gemini Live (canto inferior direito da barra de entrada: X=88%, Y=91%)
    var liveButtonXPercent: Int
        get() = prefs.getInt("live_btn_x", 88)
        set(value) = prefs.edit().putInt("live_btn_x", value).apply()

    var liveButtonYPercent: Int
        get() = prefs.getInt("live_btn_y", 91)
        set(value) = prefs.edit().putInt("live_btn_y", value).apply()

    // Posição padrão do botão de Câmera dentro do Gemini Live (barra inferior esquerda: X=22%, Y=89%)
    var cameraButtonXPercent: Int
        get() = prefs.getInt("camera_btn_x", 22)
        set(value) = prefs.edit().putInt("camera_btn_x", value).apply()

    var cameraButtonYPercent: Int
        get() = prefs.getInt("camera_btn_y", 89)
        set(value) = prefs.edit().putInt("camera_btn_y", value).apply()

    // Tempo de espera em décimos de segundo antes de usar o clique na câmera (padrão 22 = 2.2s)
    var cameraDelayTenths: Int
        get() = prefs.getInt("camera_delay_tenths", 22)
        set(value) = prefs.edit().putInt("camera_delay_tenths", value).apply()

    val cameraDelayMs: Long
        get() = (cameraDelayTenths.coerceIn(10, 50) * 100L)
}
