package com.olhosdavo.geminilive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

class GeminiLiveAccessibilityService : AccessibilityService() {

    private enum class Phase {
        IDLE,
        STEP_1_OPEN_LIVE,
        STEP_2_OPEN_CAMERA
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager

    private var currentPhase: Phase = Phase.IDLE
    private var sessionStartTime: Long = 0L
    private var liveClickedTime: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            // Timeout total de segurança de 12 segundos para nunca travar o celular
            if (now - sessionStartTime > 12_000L) {
                stopAutomation()
                return
            }

            processCurrentStep(now)

            if (currentPhase != Phase.IDLE) {
                handler.postDelayed(this, 350L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PrefsManager(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentPhase == Phase.IDLE) return
        processCurrentStep(System.currentTimeMillis())
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    override fun onDestroy() {
        stopAutomation()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun startAutomationSession() {
        prefs = PrefsManager(this)
        handler.removeCallbacks(pollRunnable)

        currentPhase = Phase.STEP_1_OPEN_LIVE
        sessionStartTime = System.currentTimeMillis()
        liveClickedTime = 0L

        launchOfficialGeminiApp()

        // Inicia verificação periódica a cada 350ms após 700ms da abertura do Gemini
        handler.postDelayed(pollRunnable, 700L)
    }

    private fun stopAutomation() {
        currentPhase = Phase.IDLE
        handler.removeCallbacks(pollRunnable)
    }

    private fun processCurrentStep(now: Long) {
        val rootNodes = getAllRootNodes()
        if (rootNodes.isEmpty()) return

        // Verifica se estamos ainda na tela do nosso próprio app (aguarda o Gemini abrir)
        val firstPkg = rootNodes.firstOrNull()?.packageName?.toString() ?: ""
        if (firstPkg == packageName && (now - sessionStartTime) < 1800L) {
            return
        }

        when (currentPhase) {
            Phase.STEP_1_OPEN_LIVE -> {
                // 1) Verifica se JÁ estamos dentro da tela do Gemini Live
                if (isAlreadyInsideGeminiLive(rootNodes)) {
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now - 800L
                    return
                }

                // 2) Procura o botão "Live" / "Conversar no Live" por texto ou descrição
                val liveNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = LIVE_BUTTON_KEYWORDS,
                    excludeKeywords = emptyList()
                )

                if (liveNode != null) {
                    clickNodeOrTapBounds(liveNode)
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                    return
                }

                // 3) Fallback por coordenada se após 2.0 segundos o botão Live não foi achado por texto
                if (prefs.useCoordinateFallback && (now - sessionStartTime) >= 2000L) {
                    tapScreenPercent(prefs.liveButtonXPercent, prefs.liveButtonYPercent)
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                }
            }

            Phase.STEP_2_OPEN_CAMERA -> {
                val elapsedSinceLive = now - liveClickedTime
                // Aguarda pelo menos 1.1s para a tela do Gemini Live carregar os botões inferiores
                if (elapsedSinceLive < 1100L) return

                // Se a câmera já estiver ligada ("Desativar câmera"), encerra com sucesso!
                val alreadyOnNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_ALREADY_ON_KEYWORDS,
                    excludeKeywords = emptyList()
                )
                if (alreadyOnNode != null) {
                    vibrateSuccess()
                    stopAutomation()
                    return
                }

                // 1) Procura o botão de ligar a Câmera dentro do Gemini Live
                val cameraNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_BUTTON_KEYWORDS,
                    excludeKeywords = CAMERA_EXCLUDE_KEYWORDS
                )

                if (cameraNode != null) {
                    clickNodeOrTapBounds(cameraNode)
                    vibrateSuccess()
                    stopAutomation()
                    return
                }

                // 2) Fallback por coordenada após o tempo configurado (ex: 2.2s após abrir o Live)
                if (prefs.useCoordinateFallback && elapsedSinceLive >= prefs.cameraDelayMs) {
                    tapScreenPercent(prefs.cameraButtonXPercent, prefs.cameraButtonYPercent)
                    vibrateSuccess()
                    stopAutomation()
                }
            }

            Phase.IDLE -> Unit
        }
    }

    private fun getAllRootNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { window ->
                window.root?.let { result.add(it) }
            }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) {
            rootInActiveWindow?.let { result.add(it) }
        }
        return result
    }

    private fun isAlreadyInsideGeminiLive(roots: List<AccessibilityNodeInfo>): Boolean {
        val liveScreenIndicators = listOf(
            "ativar camera",
            "desativar camera",
            "turn on camera",
            "turn off camera",
            "compartilhar tela",
            "share screen",
            "encerrar modo live",
            "sair do modo live"
        )
        return findBestMatchingNode(roots, liveScreenIndicators, emptyList()) != null
    }

    private fun findBestMatchingNode(
        roots: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        excludeKeywords: List<String>
    ): AccessibilityNodeInfo? {
        for (root in roots) {
            val found = searchNodeTree(root, keywords, excludeKeywords)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeTree(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        excludeKeywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val contentDesc = normalizeText(node.contentDescription?.toString())
        val text = normalizeText(node.text?.toString())
        val viewId = normalizeText(node.viewIdResourceName?.substringAfterLast('/'))

        val combined = "$contentDesc | $text | $viewId"

        if (combined.length > 4) {
            val isExcluded = excludeKeywords.any { ex -> combined.contains(ex) }
            if (!isExcluded) {
                for (kw in keywords) {
                    if (contentDesc == kw || text == kw ||
                        contentDesc.contains(kw) || text.contains(kw) || viewId.contains(kw)
                    ) {
                        return node
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = searchNodeTree(child, keywords, excludeKeywords)
            if (match != null) return match
        }
        return null
    }

    private fun clickNodeOrTapBounds(node: AccessibilityNodeInfo) {
        // Tenta clicar no próprio nó ou em algum pai clicável
        var current: AccessibilityNodeInfo? = node
        var clicked = false
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) break
            }
            current = current.parent
            depth++
        }

        // Também executa toque físico simulado no centro do botão caso seja um componente Compose
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!clicked && !rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
    }

    private fun tapScreenPercent(xPercent: Int, yPercent: Int) {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * (xPercent.coerceIn(1, 99) / 100f))
        val y = (metrics.heightPixels * (yPercent.coerceIn(1, 99) / 100f))
        tapCoordinates(x, y)
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 90L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun launchOfficialGeminiApp() {
        val pm = packageManager

        // 1. Tenta abrir o app oficial do Google Gemini (com.google.android.apps.bard)
        val bardIntent = pm.getLaunchIntentForPackage("com.google.android.apps.bard")
        if (bardIntent != null) {
            bardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(bardIntent)
            return
        }

        // 2. Tenta abrir o Assistente Digital padrão (quando o Gemini é o assistente do Android)
        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (assistIntent.resolveActivity(pm) != null) {
            startActivity(assistIntent)
            return
        }

        // 3. Fallback para Comando de Voz / App do Google
        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (voiceIntent.resolveActivity(pm) != null) {
            startActivity(voiceIntent)
            return
        }

        val googleAppIntent = pm.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
        if (googleAppIntent != null) {
            googleAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(googleAppIntent)
            return
        }

        Toast.makeText(
            this,
            "Instale o aplicativo Google Gemini na Play Store.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Vibração dupla curta confirmando que a câmera do Live abriu
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 180), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        } catch (_: Exception) {
        }
    }

    private fun normalizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").trim()
    }

    companion object {
        @Volatile
        var instance: GeminiLiveAccessibilityService? = null
            private set

        private val LIVE_BUTTON_KEYWORDS = listOf(
            "conversar no live",
            "entrar no modo live",
            "iniciar o gemini live",
            "gemini live",
            "modo live",
            "go live",
            "live"
        )

        private val CAMERA_BUTTON_KEYWORDS = listOf(
            "ativar camera",
            "ligar camera",
            "abrir camera",
            "usar camera",
            "iniciar video",
            "turn on camera",
            "start camera",
            "camera",
            "video"
        )

        private val CAMERA_ALREADY_ON_KEYWORDS = listOf(
            "desativar camera",
            "desligar camera",
            "turn off camera",
            "stop camera"
        )

        private val CAMERA_EXCLUDE_KEYWORDS = listOf(
            "desativar camera",
            "desligar camera",
            "turn off camera",
            "trocar camera",
            "alternar camera",
            "switch camera"
        )

        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, GeminiLiveAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
