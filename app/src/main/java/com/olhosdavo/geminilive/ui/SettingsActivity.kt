package com.olhosdavo.geminilive.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.olhosdavo.geminilive.R
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.service.GeminiLiveAccessibilityService
import java.util.Locale

/**
 * Painel de Configuração com Calibrador Visual Flutuante (Alvos 1 e 2) e Design Material 3.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvCalibrationStatus: TextView
    private lateinit var tvDelayLabel: TextView
    private lateinit var btnResetCalibration: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvCalibrationStatus = findViewById(R.id.tvCalibrationStatus)
        tvDelayLabel = findViewById(R.id.tvDelayLabel)
        btnResetCalibration = findViewById(R.id.btnResetCalibration)

        val btnOpenAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnOpenAppDetails = findViewById<Button>(R.id.btnOpenAppDetails)
        val btnVisualCalibrate = findViewById<Button>(R.id.btnVisualCalibrate)
        val btnDelayMinus = findViewById<Button>(R.id.btnDelayMinus)
        val btnDelayPlus = findViewById<Button>(R.id.btnDelayPlus)
        val btnPinWidget = findViewById<Button>(R.id.btnPinWidget)

        val swAutoLaunchOnOpen = findViewById<SwitchMaterial>(R.id.swAutoLaunchOnOpen)
        val swVoiceFeedback = findViewById<SwitchMaterial>(R.id.swVoiceFeedback)

        val btnTestNow = findViewById<Button>(R.id.btnTestNow)
        val btnCloseSettings = findViewById<Button>(R.id.btnCloseSettings)

        swAutoLaunchOnOpen.isChecked = prefs.autoLaunchOnOpen
        swVoiceFeedback.isChecked = prefs.voiceFeedback

        updateStatusLabels()

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenAppDetails.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        // Abre os 2 alvos flutuantes sobre o Gemini para calibrar arrastando com o dedo!
        btnVisualCalibrate.setOnClickListener {
            val service = GeminiLiveAccessibilityService.instance
            if (!GeminiLiveAccessibilityService.isServiceEnabled(this) || service == null) {
                Toast.makeText(
                    this,
                    "Ative primeiro o Serviço de Acessibilidade no Card 1!",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            service.startVisualCalibrationMode()
        }

        btnResetCalibration.setOnClickListener {
            prefs.hasVisualCalibration = false
            prefs.liveButtonXPx = -1
            prefs.liveButtonYPx = -1
            prefs.cameraButtonXPx = -1
            prefs.cameraButtonYPx = -1
            updateStatusLabels()
            Toast.makeText(
                this,
                "Calibração resetada para o modo Automático.",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnDelayMinus.setOnClickListener {
            prefs.cameraDelayTenths = (prefs.cameraDelayTenths - 3).coerceIn(15, 60)
            updateStatusLabels()
        }

        btnDelayPlus.setOnClickListener {
            prefs.cameraDelayTenths = (prefs.cameraDelayTenths + 3).coerceIn(15, 60)
            updateStatusLabels()
        }

        btnPinWidget.setOnClickListener {
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val myProvider = ComponentName(this, OlhosDaVoWidgetProvider::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(myProvider, null, null)
            } else {
                Toast.makeText(
                    this,
                    "Segure o dedo na tela inicial e escolha Widgets -> Olhos da Vó",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        swAutoLaunchOnOpen.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoLaunchOnOpen = isChecked
        }

        swVoiceFeedback.setOnCheckedChangeListener { _, isChecked ->
            prefs.voiceFeedback = isChecked
        }

        btnTestNow.setOnClickListener {
            val service = GeminiLiveAccessibilityService.instance
            if (!GeminiLiveAccessibilityService.isServiceEnabled(this) || service == null) {
                Toast.makeText(
                    this,
                    "Ative primeiro o Serviço de Acessibilidade no Card 1!",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            service.startAutomationSession()
        }

        btnCloseSettings.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusLabels()
    }

    private fun updateStatusLabels() {
        val enabled = GeminiLiveAccessibilityService.isServiceEnabled(this)
        if (enabled) {
            tvServiceStatus.text = "1. Acessibilidade: ATIVADA ✅"
            tvServiceStatus.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            tvServiceStatus.text = "1. Acessibilidade: DESATIVADA ❌"
            tvServiceStatus.setTextColor(Color.parseColor("#F87171"))
        }

        if (prefs.hasVisualCalibration && prefs.cameraButtonXPx > 0) {
            tvCalibrationStatus.text = "✅ Calibrado na tela: Alvo 1 (${prefs.liveButtonXPx}, ${prefs.liveButtonYPx}) • Alvo 2 (${prefs.cameraButtonXPx}, ${prefs.cameraButtonYPx})"
            tvCalibrationStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnResetCalibration.visibility = View.VISIBLE
        } else {
            tvCalibrationStatus.text = "Modo atual: Detecção Automática dos botões"
            tvCalibrationStatus.setTextColor(Color.parseColor("#38BDF8"))
            btnResetCalibration.visibility = View.GONE
        }

        val seconds = prefs.cameraDelayTenths / 10.0
        tvDelayLabel.text = String.format(
            Locale.getDefault(),
            "Espera para clicar na câmera: %.1fs",
            seconds
        )
    }
}
