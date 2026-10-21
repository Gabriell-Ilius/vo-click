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
import com.olhosdavo.geminilive.util.FeedbackHelper
import com.olhosdavo.geminilive.util.GeminiLauncher
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço de Acessibilidade (v1.6):
 * - Usa a mesma lógica da v1.3 que localiza o botão da câmera na árvore do Gemini Live e
 *   toca no centro geométrico exato do botão (clickNodeBounds), mas executa EXATAMENTE 1 VEZ
 *   e chama stopAutomation() imediatamente para a câmera abrir e NÃO fechar!
 */
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
    private var liveScreenConfirmedTime: Long = 0L
    private var liveClickAttempts: Int = 0
    private var lastLiveAttemptTime: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            if (now - sessionStartTime > 20_000L) {
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
        liveScreenConfirmedTime = 0L
        liveClickAttempts = 0
        lastLiveAttemptTime = 0L

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
                if (isAlreadyInsideGeminiLive(rootNodes)) {
                    transitionToStep2(now, alreadyConfirmed = true)
                    return
                }

                if (now - lastLiveAttemptTime < 1500L) return

                val liveNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = LIVE_BUTTON_KEYWORDS,
                    excludeKeywords = emptyList(),
                    requireBottomHalf = false
                )

                if (liveNode != null) {
                    liveClickAttempts++
                    lastLiveAttemptTime = now
                    clickNodeBounds(liveNode)
                    transitionToStep2(now, alreadyConfirmed = false)
                    return
                }

                if ((now - sessionStartTime) >= 2800L) {
                    liveClickAttempts++
                    lastLiveAttemptTime = now
                    tapScreenPercent(88, 91)
                    transitionToStep2(now, alreadyConfirmed = false)
                }
            }

            Phase.STEP_2_OPEN_CAMERA -> {
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

                // Se ainda estiver na tela inicial de texto do Gemini, tenta clicar no Live novamente
                val stillOnMainChat = isStillOnGeminiMainChatScreen(rootNodes)
                if (stillOnMainChat && (now - liveClickedTime) > 2200L && liveClickAttempts < 3) {
                    val liveNodeRetry = findBestMatchingNode(
                        roots = rootNodes,
                        keywords = LIVE_BUTTON_KEYWORDS,
                        excludeKeywords = emptyList(),
                        requireBottomHalf = false
                    )
                    if (liveNodeRetry != null) {
                        liveClickAttempts++
                        liveClickedTime = now
                        clickNodeBounds(liveNodeRetry)
                        return
                    }
                }

                if (liveScreenConfirmedTime == 0L && isAlreadyInsideGeminiLive(rootNodes)) {
                    liveScreenConfirmedTime = now
                }

                val referenceTime = if (liveScreenConfirmedTime > 0L) liveScreenConfirmedTime else liveClickedTime
                val elapsed = now - referenceTime

                val requiredWait = if (liveScreenConfirmedTime > 0L) {
                    1800L
                } else {
                    maxOf(2600L, prefs.cameraDelayMs)
                }
                if (elapsed < requiredWait) return

                // 1) Procura o botão de ligar a Câmera na metade inferior da tela do Gemini Live
                val cameraNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_BUTTON_KEYWORDS,
                    excludeKeywords = CAMERA_EXCLUDE_KEYWORDS,
                    requireBottomHalf = true
                )

                if (cameraNode != null) {
                    showStepToast("Ativando câmera do Gemini Live...")
                    // Toca no centro exato do botão encontrado e ENCERRA IMEDIATAMENTE
                    // (para nunca dar o 2º clique que fechava a câmera na v1.3!)
                    tapNodeCenter(cameraNode)
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 2) Se não achou pelo nome, usa a altura Y dos botões irmãos da barra inferior do Live
                val siblingBarY = findLiveBottomBarCenterY(rootNodes)
                if (siblingBarY != null) {
                    showStepToast("Ativando câmera na barra inferior...")
                    val x = resources.displayMetrics.widthPixels * 0.22f
                    tapCoordinates(x, siblingBarY.toFloat())
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 3) Procura o 1º botão clicável à esquerda na barra inferior do Gemini Live
                val bottomBarLeftButtonRect = findLeftmostLiveBottomBarButton(rootNodes)
                if (bottomBarLeftButtonRect != null) {
                    showStepToast("Ativando câmera...")
                    tapCoordinates(
                        bottomBarLeftButtonRect.centerX().toFloat(),
                        bottomBarLeftButtonRect.centerY().toFloat()
                    )
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                    return
                }

                // 4) Fallback se já passaram mais de 4 segundos após entrar no Live
                if ((now - liveClickedTime) >= 4000L) {
                    tapScreenPercent(22, 88)
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                }
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
            "end live",
            "pausar live",
            "hold live"
        )
        return findBestMatchingNode(roots, liveScreenIndicators, emptyList(), false) != null
    }

    private fun isStillOnGeminiMainChatScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        if (isAlreadyInsideGeminiLive(roots)) return false
        val mainChatIndicators = listOf(
            "peca ao gemini",
            "pergunte ao gemini",
            "ask gemini",
            "conversar no live",
            "entrar no modo live",
            "use gemini live"
        )
        return findBestMatchingNode(roots, mainChatIndicators, emptyList(), false) != null
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

    private fun findLeftmostLiveBottomBarButton(roots: List<AccessibilityNodeInfo>): Rect? {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val minY = (screenHeight * 0.68f).toInt()
        val maxY = (screenHeight * 0.96f).toInt()

        val bottomClickableRects = mutableListOf<Rect>()
        for (root in roots) {
            collectBottomClickableNodes(root, minY, maxY, screenWidth, bottomClickableRects)
        }

        if (bottomClickableRects.size >= 3) {
            return bottomClickableRects.minByOrNull { it.centerX() }
        }
        return null
    }

    private fun collectBottomClickableNodes(
        node: AccessibilityNodeInfo?,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        outRects: MutableList<Rect>
    ) {
        if (node == null || !node.isVisibleToUser) return

        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            if (rect.centerY() in minY..maxY &&
                width in 36..(screenWidth / 2) &&
                height in 36..380
            ) {
                outRects.add(rect)
            }
        }

        for (i in 0 until node.childCount) {
            collectBottomClickableNodes(node.getChild(i), minY, maxY, screenWidth, outRects)
        }
    }

    /**
     * Usado na Etapa 1 (abrir Live): aciona ACTION_CLICK e toque nas coordenadas do botão.
     */
    private fun clickNodeBounds(node: AccessibilityNodeInfo) {
        val rect = getClickableBounds(node)
        var current: AccessibilityNodeInfo? = node
        var clickedByAction = false
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                clickedByAction = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickedByAction) break
            }
            current = current.parent
            depth++
        }

        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val delay = if (clickedByAction) 120L else 0L
            handler.postDelayed({
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }, delay)
        }
    }

    /**
     * Usado na Etapa 2 (abrir Câmera do Live): obtém a posição exata do botão na árvore
     * e dá 1 ÚNICO toque no centro dele para abrir a câmera sem desligá-la em seguida.
     */
    private fun tapNodeCenter(node: AccessibilityNodeInfo) {
        val rect = getClickableBounds(node)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, 110L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun showStepToast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
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
