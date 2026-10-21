package com.olhosdavo.geminilive.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Responsável por encerrar processos anteriores do Gemini em segundo plano e abrir
 * o aplicativo oficial do Google Gemini no Android.
 */
object GeminiLauncher {

    const val PACKAGE_GEMINI = "com.google.android.apps.bard"
    const val PACKAGE_GOOGLE_APP = "com.google.android.googlequicksearchbox"

    fun killGeminiBackgroundProcesses(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(PACKAGE_GEMINI)
            am.killBackgroundProcesses(PACKAGE_GOOGLE_APP)
        } catch (_: Exception) {
        }
    }

    fun launchOfficialGemini(context: Context) {
        val pm = context.packageManager

        // 1. Tenta abrir o app oficial dedicado do Google Gemini
        val bardIntent = pm.getLaunchIntentForPackage(PACKAGE_GEMINI)
        if (bardIntent != null) {
            bardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(bardIntent)
            return
        }

        // 2. Tenta abrir o Assistente Digital padrão (quando o Gemini é definido como assistente)
        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (assistIntent.resolveActivity(pm) != null) {
            context.startActivity(assistIntent)
            return
        }

        // 3. Fallback para Comando de Voz do Android
        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (voiceIntent.resolveActivity(pm) != null) {
            context.startActivity(voiceIntent)
            return
        }

        // 4. Fallback para o aplicativo principal do Google
        val googleAppIntent = pm.getLaunchIntentForPackage(PACKAGE_GOOGLE_APP)
        if (googleAppIntent != null) {
            googleAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(googleAppIntent)
            return
        }

        Toast.makeText(
            context,
            "Instale o aplicativo oficial Google Gemini na Play Store.",
            Toast.LENGTH_LONG
        ).show()
    }
}
