package com.olhosdavo.geminilive.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Centraliza os recursos de acessibilidade sensorial:
 * - Vibração tátil (toque curto de confirmação e vibração dupla quando a câmera abre)
 * - Síntese de voz (TTS em Português do Brasil)
 */
class FeedbackHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    var isTtsReady: Boolean = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("pt", "BR"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun speak(message: String) {
        if (isTtsReady) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "olhos_da_vo_tts")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        fun vibrateShort(context: Context) {
            try {
                val vibrator = getVibrator(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(90L, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(90L)
                }
            } catch (_: Exception) {
            }
        }

        fun vibrateCameraReady(context: Context) {
            try {
                val vibrator = getVibrator(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Vibração dupla curta confirmando que a câmera do Live abriu
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 180), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300L)
                }
            } catch (_: Exception) {
            }
        }

        private fun getVibrator(context: Context): Vibrator {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }
    }
}
