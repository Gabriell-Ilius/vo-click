package com.olhosdavo.geminilive.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.util.CalibrationOverlayManager
import com.olhosdavo.geminilive.util.FeedbackHelper
import com.olhosdavo.geminilive.util.GeminiLauncher
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço de Acessibilidade que automatiza a abertura do Gemini Live com Câmera:
 * - Etapa 1 (STEP_1_OPEN_LIVE): Clica 1 vez no botão Live.
 * - Etapa 2 (STEP_2_OPEN_CAMERA): Aguarda o modo Live abrir e clica EXATAMENTE 1 VEZ no botão Câmera,
 *   encerrando imediatamente para nunca desligar a câmera com um segundo clique.
 */
class GeminiLiveAccessibilityService : AccessibilityService() {

    private enum class Phase {
        IDLE,
        STEP_1_OPEN_LIVE,
        STEP_2_OPEN_CAMERA
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager
    private var calibrationOverlay: CalibrationOverlayManager? = null

    private var currentPhase: Phase = Phase.IDLE
    private var sessionStartTime: Long = 0L
    private var liveClickedTime: Long = 0L
    private var liveScreenConfirmedTime: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            if (now - sessionStartTime > 18_000L) {
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
        calibrationOverlay?.hide()
        stopAutomation()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Abre o aplicativo do Gemini e exibe os 2 alvos flutuantes arrastáveis (1-LIVE e 2-CÂMERA)
     * para o familiar posicionar visualmente na tela.
     */
    fun startVisualCalibrationMode() {
        stopAutomation()
        prefs = PrefsManager(this)
        if (calibrationOverlay == null) {
            calibrationOverlay = CalibrationOverlayManager(this, prefs) { x, y ->
                tapCoordinates(x, y)
            }
        }
        GeminiLauncher.launchOfficialGemini(this)
        handler.postDelayed({
            calibrationOverlay?.show()
        }, 650L)
    }

    fun startAutomationSession() {
        calibrationOverlay?.hide()
        prefs = PrefsManager(this)
        handler.removeCallbacks(pollRunnable)

        currentPhase = Phase.STEP_1_OPEN_LIVE
        sessionStartTime = System.currentTimeMillis()
        liveClickedTime = 0L
        liveScreenConfirmedTime = 0L

        GeminiLauncher.launchOfficialGemini(this)

        handler.postDelayed(pollRunnable, 800L)
    }

    private fun stopAutomation() {
        currentPhase = Phase.IDLE
        handler.removeCallbacks(pollRunnable)
    }

    private fun processCurrentStep(now: Long) {
        val rootNodes = getExternalAppRootNodes()
        if (rootNodes.isEmpty()) return

        when (currentPhase) {
            Phase.STEP_1_OPEN_LIVE -> {
                // 1) Verifica se JÁ estamos dentro da tela do Gemini Live
                if (isAlreadyInsideGeminiLive(rootNodes)) {
                    transitionToStep2(now, alreadyConfirmed = true)
                    return
                }

                // 2) Se o familiar calibrou visualmente o Alvo 1 na tela, toca 1 única vez no Alvo 1 após 1.6s
                if (prefs.hasVisualCalibration && prefs.liveButtonXPx > 0 && (now - sessionStartTime) >= 1600L) {
                    tapCoordinates(prefs.liveButtonXPx.toFloat(), prefs.liveButtonYPx.toFloat())
                    transitionToStep2(now, alreadyConfirmed = false)
                    return
                }

                // 3) Procura o botão "Live" / "Conversar no Live" na janela do Gemini
                val liveNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = LIVE_BUTTON_KEYWORDS,
                    excludeKeywords = emptyList(),
                    requireBottomHalf = false
                )

                if (liveNode != null) {
                    clickOnce(liveNode)
                    transitionToStep2(now, alreadyConfirmed = false)
                    return
                }

                // 4) Fallback se após 2.4 segundos o botão Live não foi achado por texto
                if ((now - sessionStartTime) >= 2400L) {
                    tapSavedLivePosition()
                    transitionToStep2(now, alreadyConfirmed = false)
                }
            }

            Phase.STEP_2_OPEN_CAMERA -> {
                // Se a câmera já estiver ligada, vibra e encerra imediatamente
                val alreadyOnNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_ALREADY_ON_KEYWORDS,
                    excludeKeywords = emptyList(),
                    requireBottomHalf = false
                )
                if (alreadyOnNode != null) {
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                if (liveScreenConfirmedTime == 0L && isAlreadyInsideGeminiLive(rootNodes)) {
                    liveScreenConfirmedTime = now
                }

                val elapsedSinceLive = now - liveClickedTime
                // Aguarda o Gemini Live abrir e conectar antes de dar o clique único na câmera
                val requiredWait = if (liveScreenConfirmedTime > 0L) {
                    maxOf(1800L, prefs.cameraDelayMs - 600L)
                } else {
                    prefs.cameraDelayMs
                }
                if (elapsedSinceLive < requiredWait) return

                // 1) Se o familiar calibrou visualmente o Alvo 2 na tela, dá 1 ÚNICO TOQUE no Alvo 2 e encerra!
                if (prefs.hasVisualCalibration && prefs.cameraButtonXPx > 0) {
                    tapCoordinates(prefs.cameraButtonXPx.toFloat(), prefs.cameraButtonYPx.toFloat())
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 2) Procura o botão de ligar a Câmera na metade inferior da tela do Gemini Live
                val cameraNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_BUTTON_KEYWORDS,
                    excludeKeywords = CAMERA_EXCLUDE_KEYWORDS,
                    requireBottomHalf = true
                )

                if (cameraNode != null) {
                    clickOnce(cameraNode)
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 3) Verifica a altura Y dos outros botões da barra inferior do Live ("Compartilhar tela" / "Pausar")
                val siblingBarY = findLiveBottomBarCenterY(rootNodes)
                if (siblingBarY != null) {
                    val x = resources.displayMetrics.widthPixels * (prefs.cameraButtonXPercent / 100f)
                    tapCoordinates(x, siblingBarY.toFloat())
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 4) Fallback final (1 único toque na posição salva e encerra!)
                tapSavedCameraPosition()
                FeedbackHelper.vibrateCameraReady(this)
                stopAutomation()
            }

            Phase.IDLE -> Unit
        }
    }

    private fun transitionToStep2(now: Long, alreadyConfirmed: Boolean) {
        currentPhase = Phase.STEP_2_OPEN_CAMERA
        liveClickedTime = now
        liveScreenConfirmedTime = if (alreadyConfirmed) now else 0L
    }

    private fun getExternalAppRootNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: ""
                if (isAllowedTargetPackage(pkg)) {
                    result.add(root)
                }
            }
        } catch (_: Exception) {
        }

        if (result.isEmpty()) {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString() ?: ""
                if (isAllowedTargetPackage(pkg)) {
                    result.add(root)
                }
            }
        }
        return result
    }

    private fun isAllowedTargetPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == packageName) return false
        if (pkg == "com.android.systemui") return false
        if (pkg.contains("launcher") || pkg.contains("home")) return false
        return true
    }

    private fun isAlreadyInsideGeminiLive(roots: List<AccessibilityNodeInfo>): Boolean {
        val liveScreenIndicators = listOf(
            "ativar camera",
            "desativar camera",
            "ligar camera",
            "desligar camera",
            "turn on camera",
            "turn off camera",
            "compartilhar tela",
            "share screen",
            "encerrar modo live",
            "sair do modo live",
            "end live"
        )
        return findBestMatchingNode(roots, liveScreenIndicators, emptyList(), false) != null
    }

    private fun findLiveBottomBarCenterY(roots: List<AccessibilityNodeInfo>): Int? {
        val bottomBarSiblingKeywords = listOf(
            "compartilhar tela",
            "share screen",
            "pausar",
            "hold",
            "pause",
            "encerrar",
            "end"
        )
        val sibling = findBestMatchingNode(roots, bottomBarSiblingKeywords, emptyList(), true)
            ?: return null
        val rect = Rect()
        sibling.getBoundsInScreen(rect)
        return if (!rect.isEmpty && rect.centerY() > 0) rect.centerY() else null
    }

    private fun findBestMatchingNode(
        roots: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        excludeKeywords: List<String>,
        requireBottomHalf: Boolean
    ): AccessibilityNodeInfo? {
        val minCenterY = if (requireBottomHalf) {
            (resources.displayMetrics.heightPixels * 0.50f).toInt()
        } else {
            0
        }

        for (root in roots) {
            val found = searchNodeTree(root, keywords, excludeKeywords, minCenterY)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeTree(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        excludeKeywords: List<String>,
        minCenterY: Int
    ): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val contentDesc = normalizeText(node.contentDescription?.toString())
        val text = normalizeText(node.text?.toString())
        val viewId = normalizeText(node.viewIdResourceName?.substringAfterLast('/'))

        val combined = "$contentDesc | $text | $viewId"

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = searchNodeTree(child, keywords, excludeKeywords, minCenterY)
            if (match != null) return match
        }

        if (minCenterY > 0 && rect.centerY() < minCenterY) {
            return null
        }

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

        return null
    }

    /**
     * Dá EXATAMENTE 1 CLIQUE (nunca duplica o toque para não ligar e desligar a câmera em seguida).
     */
    private fun clickOnce(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                val cRect = Rect()
                current.getBoundsInScreen(cRect)
                if (!cRect.isEmpty && cRect.width() < resources.displayMetrics.widthPixels * 0.8f) {
                    rect.set(cRect)
                }
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return
                }
                break
            }
            current = current.parent
            depth++
        }

        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
    }

    private fun tapSavedLivePosition() {
        if (prefs.liveButtonXPx > 0 && prefs.liveButtonYPx > 0) {
            tapCoordinates(prefs.liveButtonXPx.toFloat(), prefs.liveButtonYPx.toFloat())
        } else {
            tapScreenPercent(prefs.liveButtonXPercent, prefs.liveButtonYPercent)
        }
    }

    private fun tapSavedCameraPosition() {
        if (prefs.cameraButtonXPx > 0 && prefs.cameraButtonYPx > 0) {
            tapCoordinates(prefs.cameraButtonXPx.toFloat(), prefs.cameraButtonYPx.toFloat())
        } else {
            tapScreenPercent(prefs.cameraButtonXPercent, prefs.cameraButtonYPercent)
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        dispatchGesture(gesture, null, null)
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
            "usar o gemini live",
            "use gemini live",
            "talk live",
            "gemini live",
            "modo live",
            "go live",
            "live"
        )

        private val CAMERA_BUTTON_KEYWORDS = listOf(
            "ativar camera",
            "ativar a camera",
            "ligar camera",
            "ligar a camera",
            "abrir camera",
            "usar camera",
            "iniciar video",
            "compartilhar video",
            "mostrar camera",
            "turn on camera",
            "start camera",
            "open camera",
            "use camera",
            "start video",
            "share video",
            "camera",
            "video"
        )

        private val CAMERA_ALREADY_ON_KEYWORDS = listOf(
            "desativar camera",
            "desativar a camera",
            "desligar camera",
            "parar camera",
            "alternar camera",
            "trocar camera",
            "turn off camera",
            "stop camera",
            "switch camera",
            "flip camera"
        )

        private val CAMERA_EXCLUDE_KEYWORDS = listOf(
            "desativar camera",
            "desligar camera",
            "turn off camera",
            "trocar camera",
            "alternar camera",
            "switch camera",
            "flip camera",
            "peca ao gemini",
            "ask gemini"
        )

        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName =
                ComponentName(context, GeminiLiveAccessibilityService::class.java)
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
