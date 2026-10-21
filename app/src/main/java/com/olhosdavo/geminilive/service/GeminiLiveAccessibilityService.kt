package com.olhosdavo.geminilive.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.util.FeedbackHelper
import com.olhosdavo.geminilive.util.GeminiLauncher
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço de Acessibilidade que aciona os botões do Gemini por COMANDO VIRTUAL (ACTION_CLICK):
 * - Etapa 1 (STEP_1_OPEN_LIVE): Localiza o objeto virtual do botão "Live" e envia ACTION_CLICK.
 * - Etapa 2 (STEP_2_OPEN_CAMERA): Aguarda a tela do Gemini Live carregar, localiza o objeto virtual
 *   do botão "Câmera" (por nome/ID ou pelo 1º botão virtual da barra inferior do Live) e envia
 *   EXATAMENTE 1 comando ACTION_CLICK, encerrando imediatamente para nunca fechar a câmera.
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
    private var liveRetryCount: Int = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            // Timeout total de segurança de 18 segundos
            if (now - sessionStartTime > 18_000L) {
                stopAutomation()
                return
            }

            processCurrentStep(now)

            if (currentPhase != Phase.IDLE) {
                handler.postDelayed(this, 300L)
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
        liveRetryCount = 0

        GeminiLauncher.launchOfficialGemini(this)

        handler.postDelayed(pollRunnable, 700L)
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
                // 1) Se já estiver na tela do Gemini Live, avança direto para a Etapa 2
                if (isAlreadyInsideGeminiLive(rootNodes)) {
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now - 1000L
                    return
                }

                // 2) Localiza o botão virtual "Live" na árvore do Gemini e envia ACTION_CLICK
                val liveNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = LIVE_BUTTON_KEYWORDS,
                    excludeKeywords = emptyList(),
                    requireBottomHalf = false
                )

                if (liveNode != null && performVirtualClick(liveNode)) {
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                }
            }

            Phase.STEP_2_OPEN_CAMERA -> {
                // 1) Se a câmera já estiver ligada, confirma com vibração e encerra
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

                // 2) Se após 2.2s ainda estiver na tela inicial de texto do Gemini (porque o app ainda
                // estava carregando no 1º clique), envia o comando virtual no botão Live novamente
                if (isStillOnGeminiMainChatScreen(rootNodes)) {
                    if ((now - liveClickedTime) >= 2200L && liveRetryCount < 3) {
                        val liveNodeRetry = findBestMatchingNode(
                            roots = rootNodes,
                            keywords = LIVE_BUTTON_KEYWORDS,
                            excludeKeywords = emptyList(),
                            requireBottomHalf = false
                        )
                        if (liveNodeRetry != null && performVirtualClick(liveNodeRetry)) {
                            liveRetryCount++
                            liveClickedTime = now
                        }
                    }
                    // Nunca tenta clicar na câmera enquanto ainda estiver na tela de chat inicial!
                    return
                }

                // 3) Aguarda o Gemini Live abrir e conectar (padrão 2.5s após entrar no Live)
                val elapsedSinceLive = now - liveClickedTime
                if (elapsedSinceLive < prefs.cameraDelayMs) return

                // 4) Camada 1: Procura o botão virtual da Câmera na barra inferior do Gemini Live por nome/ID
                val cameraNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = CAMERA_BUTTON_KEYWORDS,
                    excludeKeywords = CAMERA_EXCLUDE_KEYWORDS,
                    requireBottomHalf = true
                )

                if (cameraNode != null && performVirtualClick(cameraNode)) {
                    FeedbackHelper.vibrateCameraReady(this)
                    // Encerra IMEDIATAMENTE após 1 único comando virtual para nunca desligar a câmera!
                    stopAutomation()
                    return
                }

                // 5) Camada 2: Se o botão da câmera não tiver texto compatível no aparelho,
                // pega o 1º botão virtual clicável à esquerda na barra inferior do Gemini Live e envia ACTION_CLICK
                val leftmostBottomButtonNode = findLeftmostLiveBottomBarNode(rootNodes)
                if (leftmostBottomButtonNode != null && performVirtualClick(leftmostBottomButtonNode)) {
                    FeedbackHelper.vibrateCameraReady(this)
                    stopAutomation()
                }
            }

            Phase.IDLE -> Unit
        }
    }

    /**
     * Envia o comando virtual nativo do Android (ACTION_CLICK) diretamente para o componente
     * de interface (ou seu container clicável), sem usar toque cego na tela e sem clicar duas vezes.
     */
    private fun performVirtualClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
     * Localiza o objeto AccessibilityNodeInfo clicável mais à esquerda na barra de controles
     * inferior do Gemini Live ([Câmera] [Compartilhar Tela] [Pausar] [Encerrar]).
     */
    private fun findLeftmostLiveBottomBarNode(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val minY = (screenHeight * 0.65f).toInt()
        val maxY = (screenHeight * 0.96f).toInt()

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        for (root in roots) {
            collectBottomClickableNodes(root, minY, maxY, screenWidth, candidates)
        }

        if (candidates.size >= 3) {
            return candidates.minByOrNull { it.second.centerX() }?.first
        }
        return null
    }

    private fun collectBottomClickableNodes(
        node: AccessibilityNodeInfo?,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        outCandidates: MutableList<Pair<AccessibilityNodeInfo, Rect>>
    ) {
        if (node == null || !node.isVisibleToUser) return

        if (node.isClickable && node.isEnabled) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            if (rect.centerY() in minY..maxY &&
                width in 36..(screenWidth / 2) &&
                height in 36..380
            ) {
                outCandidates.add(node to rect)
            }
        }

        for (i in 0 until node.childCount) {
            collectBottomClickableNodes(node.getChild(i), minY, maxY, screenWidth, outCandidates)
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
