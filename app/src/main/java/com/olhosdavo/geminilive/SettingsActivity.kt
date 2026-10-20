package com.olhosdavo.geminilive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLiveCoordsLabel: TextView
    private lateinit var tvCameraCoordsLabel: TextView
    private lateinit var tvDelayLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvLiveCoordsLabel = findViewById(R.id.tvLiveCoordsLabel)
        tvCameraCoordsLabel = findViewById(R.id.tvCameraCoordsLabel)
        tvDelayLabel = findViewById(R.id.tvDelayLabel)

        val btnOpenAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnOpenAppDetails = findViewById<Button>(R.id.btnOpenAppDetails)
        val cbAutoLaunchOnOpen = findViewById<CheckBox>(R.id.cbAutoLaunchOnOpen)
        val cbVoiceFeedback = findViewById<CheckBox>(R.id.cbVoiceFeedback)
        val cbUseCoordinateFallback = findViewById<CheckBox>(R.id.cbUseCoordinateFallback)

        val sbLiveX = findViewById<SeekBar>(R.id.sbLiveX)
        val sbLiveY = findViewById<SeekBar>(R.id.sbLiveY)
        val sbCameraX = findViewById<SeekBar>(R.id.sbCameraX)
        val sbCameraY = findViewById<SeekBar>(R.id.sbCameraY)
        val sbCameraDelay = findViewById<SeekBar>(R.id.sbCameraDelay)

        val btnTestNow = findViewById<Button>(R.id.btnTestNow)
        val btnCloseSettings = findViewById<Button>(R.id.btnCloseSettings)

        // Carrega estados salvos
        cbAutoLaunchOnOpen.isChecked = prefs.autoLaunchOnOpen
        cbVoiceFeedback.isChecked = prefs.voiceFeedback
        cbUseCoordinateFallback.isChecked = prefs.useCoordinateFallback

        sbLiveX.progress = prefs.liveButtonXPercent
        sbLiveY.progress = prefs.liveButtonYPercent
        sbCameraX.progress = prefs.cameraButtonXPercent
        sbCameraY.progress = prefs.cameraButtonYPercent
        sbCameraDelay.progress = prefs.cameraDelayTenths

        updateLabels()

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenAppDetails.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        cbAutoLaunchOnOpen.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoLaunchOnOpen = isChecked
        }

        cbVoiceFeedback.setOnCheckedChangeListener { _, isChecked ->
            prefs.voiceFeedback = isChecked
        }

        cbUseCoordinateFallback.setOnCheckedChangeListener { _, isChecked ->
            prefs.useCoordinateFallback = isChecked
        }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.liveButtonXPercent = sbLiveX.progress.coerceIn(5, 95)
                prefs.liveButtonYPercent = sbLiveY.progress.coerceIn(5, 98)
                prefs.cameraButtonXPercent = sbCameraX.progress.coerceIn(5, 95)
                prefs.cameraButtonYPercent = sbCameraY.progress.coerceIn(5, 98)
                prefs.cameraDelayTenths = sbCameraDelay.progress.coerceIn(10, 50)
                updateLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbLiveX.setOnSeekBarChangeListener(seekListener)
        sbLiveY.setOnSeekBarChangeListener(seekListener)
        sbCameraX.setOnSeekBarChangeListener(seekListener)
        sbCameraY.setOnSeekBarChangeListener(seekListener)
        sbCameraDelay.setOnSeekBarChangeListener(seekListener)

        btnTestNow.setOnClickListener {
            val service = GeminiLiveAccessibilityService.instance
            if (!GeminiLiveAccessibilityService.isServiceEnabled(this) || service == null) {
                Toast.makeText(
                    this,
                    "Ative primeiro o Serviço de Acessibilidade no Passo 1!",
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
        val enabled = GeminiLiveAccessibilityService.isServiceEnabled(this)
        if (enabled) {
            tvServiceStatus.text = "1. Serviço de Acessibilidade: ATIVADO ✅"
            tvServiceStatus.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvServiceStatus.text = "1. Serviço de Acessibilidade: DESATIVADO ❌"
            tvServiceStatus.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun updateLabels() {
        tvLiveCoordsLabel.text =
            "Posição do botão LIVE (X: ${prefs.liveButtonXPercent}%, Y: ${prefs.liveButtonYPercent}%)"
        tvCameraCoordsLabel.text =
            "Posição do botão CÂMERA no Live (X: ${prefs.cameraButtonXPercent}%, Y: ${prefs.cameraButtonYPercent}%)"
        val seconds = prefs.cameraDelayTenths / 10.0
        tvDelayLabel.text = String.format(
            Locale.getDefault(),
            "Tempo de espera para abrir a câmera: %.1f segundos",
            seconds
        )
    }
}
