package com.olhosdavo.geminilive.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.util.CalibrationOverlayManager
import com.olhosdavo.geminilive.util.FeedbackHelper
import com.olhosdavo.geminilive.util.GeminiLauncher
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Serviço de Acessibilidade (v1.9):
 * - Antes de iniciar a rotina de cliques, confere se o Gemini já está aberto (seja no modo Live,
 *   em uma conversa anterior ou em segundo plano).
 * - Se estiver aberto, fecha o Gemini completamente primeiro e só depois abre do zero para fazer
 *   os Cliques 1 e 2!
 */
class GeminiLiveAccessibilityService : AccessibilityService() {

    private enum class Phase {
        IDLE,
        STEP_1_OPEN_LIVE,
        STEP_2_OPEN_CAMERA
    }

    private val handler = Handler(Looper.getMainLooper())
    private var calibrationOverlay: CalibrationOverlayManager? = null

    private var currentPhase: Phase = Phase.IDLE
    private var sessionStartTime: Long = 0L
    private var liveClickedTime: Long = 0L

    @Volatile
    var isAutomationRunning: Boolean = false
        private set

    // Rastreia se o Gemini já foi aberto anteriormente (pelo app ou pelo usuário) e ainda não foi fechado
    private var wasGeminiOpenedSinceLastClose: Boolean = true
    private var wasLeftInGeminiLive: Boolean = false
    private var wasKnownOpenAtSessionStart: Boolean = false
    private var wasWarmResumeDetected: Boolean = false

    private val detectWarmResumeRunnable = Runnable {
        val roots = getExternalAppRootNodes()
        if (countTotalNodes(roots) >= 10) {
            wasWarmResumeDetected = true
        }
    }

    /**
     * Confere se o Gemini já estava aberto antes da rotina.
     * Se estava aberto, fecha a sessão/janela atual do Gemini primeiro, aguarda encerrar,
     * abre o Gemini limpo do zero e só então agenda o Clique 1 e o Clique 2!
     */
    private val checkAndCloseOldSessionRunnable = Runnable {
        val freshPrefs = PrefsManager(this)
        val rootNodes = getExternalAppRootNodes()
        val inLiveNow = isAlreadyInsideGeminiLive(rootNodes)
        val shouldCloseFirst =
            wasKnownOpenAtSessionStart ||
                wasWarmResumeDetected ||
                inLiveNow ||
                isKeyboardOpen() ||
                hasExistingConversationOnScreen(rootNodes)

        if (!shouldCloseFirst) {
            return@Runnable
        }

        // Cancela qualquer clique pendente enquanto fecha o Gemini aberto
        handler.removeCallbacks(step1CalibratedRunnable)
        handler.removeCallbacks(step2CalibratedRunnable)
        handler.removeCallbacks(pollRunnable)

        if (inLiveNow) {
            Toast.makeText(
                this,
                "🔄 Gemini Live já estava aberto: fechando para reiniciar...",
                Toast.LENGTH_SHORT
            ).show()

            // 1) Toca no botão "Encerrar (X)" na barra inferior do Gemini Live
            clickEndGeminiLiveButton(rootNodes, freshPrefs)
            wasLeftInGeminiLive = false

            // 2) Fecha a janela do Gemini com Back + limpa processos de fundo
            handler.postDelayed({
                if (isGeminiWindowOnScreen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, 850L)

            handler.postDelayed({
                if (isGeminiWindowOnScreen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                GeminiLauncher.killGeminiBackgroundProcesses(this)
            }, 1350L)

            handler.postDelayed({
                if (isGeminiWindowOnScreen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                GeminiLauncher.killGeminiBackgroundProcesses(this)
            }, 1750L)

            // 3) Reabre o Gemini do zero e executa os Cliques 1 e 2!
            handler.postDelayed({
                relaunchCleanAndScheduleClicks()
            }, 2300L)
        } else {
            Toast.makeText(
                this,
                "🔄 Gemini já estava aberto: fechando para começar do zero...",
                Toast.LENGTH_SHORT
            ).show()

            // 1) Fecha teclado / conversa / janela do Gemini com Back
            if (isGeminiWindowOnScreen() || isKeyboardOpen()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            handler.postDelayed({
                if (isGeminiWindowOnScreen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, 450L)

            handler.postDelayed({
                if (isGeminiWindowOnScreen()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                GeminiLauncher.killGeminiBackgroundProcesses(this)
            }, 900L)

            // 2) Reabre o Gemini do zero e executa os Cliques 1 e 2!
            handler.postDelayed({
                relaunchCleanAndScheduleClicks()
            }, 1550L)
        }
    }

    private val step1CalibratedRunnable = Runnable {
        val freshPrefs = PrefsManager(this)
        val x1 = freshPrefs.liveButtonXPx.toFloat()
        val y1 = freshPrefs.liveButtonYPx.toFloat()
        if (x1 > 0 && y1 > 0) {
            wasLeftInGeminiLive = true
            wasGeminiOpenedSinceLastClose = true
            ensureOverlayManager().showClickPulse(x1, y1)
            tapCoordinates(x1, y1)
        }
    }

    private val step2CalibratedRunnable = Runnable {
        val freshPrefs = PrefsManager(this)
        val x2 = freshPrefs.cameraButtonXPx.toFloat()
        val y2 = freshPrefs.cameraButtonYPx.toFloat()
        if (x2 > 0 && y2 > 0) {
            wasLeftInGeminiLive = true
            wasGeminiOpenedSinceLastClose = true
            ensureOverlayManager().showClickPulse(x2, y2)
            tapCoordinates(x2, y2)
            FeedbackHelper.vibrateCameraReady(this)
        }
        stopAutomation()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            if (now - sessionStartTime > 25_000L) {
                stopAutomation()
                return
            }

            processAutomaticFallbackStep(now)

            if (currentPhase != Phase.IDLE) {
                handler.postDelayed(this, 350L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""
        if (!isAutomationRunning && currentPhase == Phase.IDLE) {
            if (pkg == GeminiLauncher.PACKAGE_GEMINI) {
                wasGeminiOpenedSinceLastClose = true
            } else if (pkg == GeminiLauncher.PACKAGE_GOOGLE_APP) {
                val cls = event?.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (cls.contains("robin") || cls.contains("bard") || cls.contains("gemini")) {
                    wasGeminiOpenedSinceLastClose = true
                }
            }
        }

        if (currentPhase == Phase.IDLE) return
        val freshPrefs = PrefsManager(this)
        if (!freshPrefs.hasVisualCalibration) {
            processAutomaticFallbackStep(System.currentTimeMillis())
        }
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

    private fun ensureOverlayManager(): CalibrationOverlayManager {
        var manager = calibrationOverlay
        if (manager == null) {
            manager = CalibrationOverlayManager(this) { x, y ->
                wasLeftInGeminiLive = true
                wasGeminiOpenedSinceLastClose = true
                tapCoordinates(x, y)
            }
            calibrationOverlay = manager
        }
        return manager
    }

    fun startVisualCalibrationMode() {
        stopAutomation()
        wasGeminiOpenedSinceLastClose = true
        GeminiLauncher.killGeminiBackgroundProcesses(this)
        GeminiLauncher.launchOfficialGemini(this)
        handler.postDelayed({
            ensureOverlayManager().show()
        }, 650L)
    }

    fun startAutomationSession() {
        calibrationOverlay?.hide()
        stopAutomation()

        isAutomationRunning = true
        val freshPrefs = PrefsManager(this)

        wasKnownOpenAtSessionStart = isGeminiWindowOnScreen() || wasGeminiOpenedSinceLastClose
        wasWarmResumeDetected = false

        GeminiLauncher.killGeminiBackgroundProcesses(this)
        GeminiLauncher.launchOfficialGemini(this)

        // Se já sabemos que o Gemini estava aberto antes, traz ele para frente apenas para
        // fechá-lo aos 950ms e em seguida reabrir do zero!
        if (wasKnownOpenAtSessionStart) {
            handler.postDelayed(checkAndCloseOldSessionRunnable, 950L)
            return
        }

        // Caso contrário, agenda os cliques normalmente, mas ainda confere aos 450ms/900ms
        // se o Gemini já estava aberto em segundo plano
        handler.postDelayed(detectWarmResumeRunnable, 450L)
        handler.postDelayed(checkAndCloseOldSessionRunnable, 900L)
        scheduleClick1AndClick2(freshPrefs)
    }

    private fun relaunchCleanAndScheduleClicks() {
        wasGeminiOpenedSinceLastClose = true
        wasKnownOpenAtSessionStart = false
        wasWarmResumeDetected = false

        val freshPrefs = PrefsManager(this)
        GeminiLauncher.killGeminiBackgroundProcesses(this)
        GeminiLauncher.launchOfficialGemini(this)
        scheduleClick1AndClick2(freshPrefs)
    }

    private fun scheduleClick1AndClick2(freshPrefs: PrefsManager) {
        wasGeminiOpenedSinceLastClose = true

        if (freshPrefs.hasVisualCalibration &&
            freshPrefs.liveButtonXPx > 0 &&
            freshPrefs.cameraButtonXPx > 0
        ) {
            val delayStep1 = freshPrefs.liveDelayMs
            val delayStep2 = delayStep1 + freshPrefs.cameraDelayMs

            val t1Sec = freshPrefs.liveDelayTenths / 10.0
            val t2Sec = freshPrefs.cameraDelayTenths / 10.0
            Toast.makeText(
                this,
                "Clique 1 em ${t1Sec}s • Clique 2 em +${t2Sec}s",
                Toast.LENGTH_SHORT
            ).show()

            handler.postDelayed(step1CalibratedRunnable, delayStep1)
            handler.postDelayed(step2CalibratedRunnable, delayStep2)
            return
        }

        currentPhase = Phase.STEP_1_OPEN_LIVE
        sessionStartTime = System.currentTimeMillis()
        liveClickedTime = 0L
        handler.postDelayed(pollRunnable, freshPrefs.liveDelayMs)
    }

    private fun stopAutomation() {
        currentPhase = Phase.IDLE
        isAutomationRunning = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(detectWarmResumeRunnable)
        handler.removeCallbacks(checkAndCloseOldSessionRunnable)
        handler.removeCallbacks(step1CalibratedRunnable)
        handler.removeCallbacks(step2CalibratedRunnable)
    }

    private fun isGeminiWindowOnScreen(): Boolean {
        try {
            windows?.forEach { window ->
                val pkg = window.root?.packageName?.toString() ?: ""
                if (pkg == GeminiLauncher.PACKAGE_GEMINI || pkg == GeminiLauncher.PACKAGE_GOOGLE_APP) {
                    return true
                }
            }
        } catch (_: Exception) {
        }
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
        return activePkg == GeminiLauncher.PACKAGE_GEMINI || activePkg == GeminiLauncher.PACKAGE_GOOGLE_APP
    }

    private fun isKeyboardOpen(): Boolean {
        try {
            windows?.forEach { window ->
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun isAlreadyInsideGeminiLive(roots: List<AccessibilityNodeInfo>): Boolean {
        if (roots.isEmpty()) return false

        // Se a caixa de texto "Peça ao Gemini / Ask Gemini" ou o menu de anexos (Galeria/Arquivos)
        // estiver na tela, estamos na tela de chat normal, NÃO no Gemini Live!
        if (hasChatInputPromptBox(roots)) return false
        if (findBestMatchingNode(roots, ATTACHMENT_SHEET_KEYWORDS, false) != null) return false

        // 1. Procura por textos/botões típicos da tela do Gemini Live
        if (findBestMatchingNode(roots, LIVE_SCREEN_INDICATORS, false) != null) {
            return true
        }

        // 2. Se o próprio app deixou no modo Live anteriormente (ou se há botões de controle
        //    na barra inferior e nenhuma caixa de texto de chat), está na tela do Gemini Live!
        val bottomButtons = countClickableNodesInBottomHalf(roots)
        return (wasLeftInGeminiLive && bottomButtons >= 1) || bottomButtons >= 3
    }

    private fun hasExistingConversationOnScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        if (roots.isEmpty()) return false
        return findBestMatchingNode(roots, EXISTING_CONVERSATION_KEYWORDS, false) != null
    }

    private fun hasChatInputPromptBox(roots: List<AccessibilityNodeInfo>): Boolean {
        for (root in roots) {
            if (searchForChatInputNode(root)) return true
        }
        return false
    }

    private fun searchForChatInputNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isVisibleToUser) return false
        if (node.isEditable) return true
        val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (cls.contains("edittext")) return true

        val contentDesc = normalizeText(node.contentDescription?.toString())
        val text = normalizeText(node.text?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeText(node.hintText?.toString())
        } else {
            ""
        }
        val combined = "$contentDesc | $text | $hint"
        for (kw in CHAT_INPUT_KEYWORDS) {
            if (combined.contains(kw)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchForChatInputNode(child)) return true
        }
        return false
    }

    private fun clickEndGeminiLiveButton(
        roots: List<AccessibilityNodeInfo>,
        freshPrefs: PrefsManager
    ) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // Camada 1: Procura pelo botão com descrição "Encerrar / End / Fechar" na metade inferior
        val endNode = findBestMatchingNode(roots, END_LIVE_KEYWORDS, true)
        if (endNode != null) {
            val rect = getClickableBounds(endNode)
            if (!rect.isEmpty) {
                ensureOverlayManager().showClickPulse(rect.centerX().toFloat(), rect.centerY().toFloat())
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                return
            }
        }

        // Camada 2: Procura o botão clicável mais à direita na barra inferior do Gemini Live
        val rightMostBottomRect = findRightMostBottomButtonBounds(roots, freshPrefs)
        if (rightMostBottomRect != null && !rightMostBottomRect.isEmpty) {
            val cx = rightMostBottomRect.centerX().toFloat()
            val cy = rightMostBottomRect.centerY().toFloat()
            ensureOverlayManager().showClickPulse(cx, cy)
            tapCoordinates(cx, cy)
            return
        }

        // Camada 3: Posição espelhada do Alvo 2 (Câmera fica na esquerda da barra do Live;
        // o botão X Encerrar fica na mesma altura Y no lado direito!)
        if (freshPrefs.cameraButtonXPx > 0 && freshPrefs.cameraButtonYPx > 0) {
            val mirrorX = (screenW - freshPrefs.cameraButtonXPx).coerceIn(
                (screenW * 0.70f).toInt(),
                (screenW * 0.92f).toInt()
            ).toFloat()
            val sameRowY = freshPrefs.cameraButtonYPx.toFloat()
            ensureOverlayManager().showClickPulse(mirrorX, sameRowY)
            tapCoordinates(mirrorX, sameRowY)
            return
        }

        // Fallback final: Alvo 1 ou canto inferior direito
        val fallbackX = if (freshPrefs.liveButtonXPx > 0) freshPrefs.liveButtonXPx.toFloat() else screenW * 0.82f
        val fallbackY = if (freshPrefs.liveButtonYPx > 0) freshPrefs.liveButtonYPx.toFloat() else screenH * 0.88f
        ensureOverlayManager().showClickPulse(fallbackX, fallbackY)
        tapCoordinates(fallbackX, fallbackY)
    }

    private fun findRightMostBottomButtonBounds(
        roots: List<AccessibilityNodeInfo>,
        freshPrefs: PrefsManager
    ): Rect? {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val targetY = if (freshPrefs.cameraButtonYPx > 0) {
            freshPrefs.cameraButtonYPx
        } else {
            (screenH * 0.88f).toInt()
        }
        val candidates = mutableListOf<Rect>()
        for (root in roots) {
            collectBottomClickableRects(root, screenW, screenH, targetY, candidates)
        }
        return candidates
            .filter { it.centerX() > screenW * 0.65f }
            .maxByOrNull { it.centerX() }
    }

    private fun collectBottomClickableRects(
        node: AccessibilityNodeInfo?,
        screenW: Int,
        screenH: Int,
        targetY: Int,
        out: MutableList<Rect>
    ) {
        if (node == null || !node.isVisibleToUser) return
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty &&
                rect.width() < screenW * 0.35f &&
                rect.centerY() > screenH * 0.70f &&
                abs(rect.centerY() - targetY) < (screenH * 0.12f)
            ) {
                out.add(rect)
            }
        }
        for (i in 0 until node.childCount) {
            collectBottomClickableRects(node.getChild(i), screenW, screenH, targetY, out)
        }
    }

    private fun countClickableNodesInBottomHalf(roots: List<AccessibilityNodeInfo>): Int {
        val minCenterY = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        var count = 0
        for (root in roots) {
            count += countClickableInSubtree(root, minCenterY)
        }
        return count
    }

    private fun countClickableInSubtree(node: AccessibilityNodeInfo?, minCenterY: Int): Int {
        if (node == null || !node.isVisibleToUser) return 0
        var c = 0
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isClickable && rect.centerY() >= minCenterY && rect.width() < resources.displayMetrics.widthPixels * 0.45f) {
            c = 1
        }
        for (i in 0 until node.childCount) {
            c += countClickableInSubtree(node.getChild(i), minCenterY)
        }
        return c
    }

    private fun countTotalNodes(roots: List<AccessibilityNodeInfo>): Int {
        var total = 0
        for (root in roots) {
            total += countNodesRecursive(root)
        }
        return total
    }

    private fun countNodesRecursive(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        var c = 1
        for (i in 0 until node.childCount) {
            c += countNodesRecursive(node.getChild(i))
        }
        return c
    }

    private fun processAutomaticFallbackStep(now: Long) {
        val freshPrefs = PrefsManager(this)
        val rootNodes = getExternalAppRootNodes()
        if (rootNodes.isEmpty()) return

        when (currentPhase) {
            Phase.STEP_1_OPEN_LIVE -> {
                if (now - sessionStartTime < freshPrefs.liveDelayMs) return

                val liveNode = findBestMatchingNode(rootNodes, LIVE_BUTTON_KEYWORDS, false)
                if (liveNode != null) {
                    val rect = getClickableBounds(liveNode)
                    if (!rect.isEmpty) {
                        ensureOverlayManager().showClickPulse(rect.centerX().toFloat(), rect.centerY().toFloat())
                        tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                    } else {
                        liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    wasLeftInGeminiLive = true
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                    return
                }
                val x = resources.displayMetrics.widthPixels * 0.88f
                val y = resources.displayMetrics.heightPixels * 0.90f
                ensureOverlayManager().showClickPulse(x, y)
                tapCoordinates(x, y)
                wasLeftInGeminiLive = true
                currentPhase = Phase.STEP_2_OPEN_CAMERA
                liveClickedTime = now
            }

            Phase.STEP_2_OPEN_CAMERA -> {
                if (now - liveClickedTime < freshPrefs.cameraDelayMs) return

                val cameraNode = findBestMatchingNode(rootNodes, CAMERA_BUTTON_KEYWORDS, true)
                if (cameraNode != null) {
                    val rect = getClickableBounds(cameraNode)
                    if (!rect.isEmpty) {
                        ensureOverlayManager().showClickPulse(rect.centerX().toFloat(), rect.centerY().toFloat())
                        tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                } else {
                    val x = resources.displayMetrics.widthPixels * 0.22f
                    val y = resources.displayMetrics.heightPixels * 0.88f
                    ensureOverlayManager().showClickPulse(x, y)
                    tapCoordinates(x, y)
                }
                wasLeftInGeminiLive = true
                FeedbackHelper.vibrateCameraReady(this)
                stopAutomation()
            }

            Phase.IDLE -> Unit
        }
    }

    private fun getExternalAppRootNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: ""
                if (pkg.isNotBlank() && pkg != packageName && pkg != "com.android.systemui") {
                    result.add(root)
                }
            }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString() ?: ""
                if (pkg.isNotBlank() && pkg != packageName && pkg != "com.android.systemui") {
                    result.add(root)
                }
            }
        }
        return result
    }

    private fun findBestMatchingNode(
        roots: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        requireBottomHalf: Boolean
    ): AccessibilityNodeInfo? {
        val minCenterY = if (requireBottomHalf) {
            (resources.displayMetrics.heightPixels * 0.50f).toInt()
        } else {
            0
        }
        for (root in roots) {
            val found = searchNodeTree(root, keywords, minCenterY)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeTree(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        minCenterY: Int
    ): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = searchNodeTree(child, keywords, minCenterY)
            if (match != null) return match
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (minCenterY > 0 && rect.centerY() < minCenterY) return null

        val contentDesc = normalizeText(node.contentDescription?.toString())
        val text = normalizeText(node.text?.toString())
        val viewId = normalizeText(node.viewIdResourceName?.substringAfterLast('/'))
        val combined = "$contentDesc | $text | $viewId"

        if (combined.length > 4) {
            for (kw in keywords) {
                if (contentDesc.contains(kw) || text.contains(kw) || viewId.contains(kw)) {
                    return node
                }
            }
        }
        return null
    }

    private fun getClickableBounds(node: AccessibilityNodeInfo): Rect {
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
                break
            }
            current = current.parent
            depth++
        }
        return rect
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 160L)
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
            "use gemini live",
            "talk live",
            "gemini live",
            "modo live",
            "go live",
            "live"
        )

        private val CAMERA_BUTTON_KEYWORDS = listOf(
            "ativar camera",
            "ligar camera",
            "abrir camera",
            "turn on camera",
            "start camera",
            "camera",
            "video"
        )

        private val LIVE_SCREEN_INDICATORS = listOf(
            "ativar camera",
            "desativar camera",
            "ligar camera",
            "desligar camera",
            "turn on camera",
            "turn off camera",
            "start video",
            "stop video",
            "compartilhar tela",
            "share screen",
            "encerrar modo live",
            "sair do modo live",
            "end live",
            "pausar live",
            "hold live",
            "em espera",
            "on hold"
        )

        private val END_LIVE_KEYWORDS = listOf(
            "encerrar",
            "sair do modo live",
            "end live",
            "end",
            "close",
            "fechar",
            "desligar",
            "leave"
        )

        private val CHAT_INPUT_KEYWORDS = listOf(
            "peca ao gemini",
            "ask gemini",
            "digite",
            "type a message",
            "conversar no live",
            "use gemini live",
            "entrar no modo live"
        )

        private val ATTACHMENT_SHEET_KEYWORDS = listOf(
            "galeria",
            "gallery",
            "arquivos",
            "files",
            "drive"
        )

        private val EXISTING_CONVERSATION_KEYWORDS = listOf(
            "nova conversa",
            "new chat",
            "resposta boa",
            "good response",
            "resposta ruim",
            "bad response"
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
