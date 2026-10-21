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
import com.olhosdavo.geminilive.data.PrefsManager
import com.olhosdavo.geminilive.util.FeedbackHelper
import com.olhosdavo.geminilive.util.GeminiLauncher
import java.text.Normalizer
import java.util.Locale

/**
 * Serviço de Acessibilidade que automatiza a abertura do Gemini Live com Câmera em 2 etapas:
 * - Etapa 1 (STEP_1_OPEN_LIVE): Localiza e clica no botão "Live" no app oficial do Gemini.
 * - Etapa 2 (STEP_2_OPEN_CAMERA): Aguarda o modo Live conectar e clica no botão de "Câmera"
 *   na barra inferior do Gemini Live (com retentativa automática até a câmera abrir).
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
    private var cameraClickAttempts: Int = 0
    private var lastCameraAttemptTime: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.IDLE) return

            val now = System.currentTimeMillis()
            // Timeout total de segurança de 16 segundos
            if (now - sessionStartTime > 16_000L) {
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
        cameraClickAttempts = 0
        lastCameraAttemptTime = 0L

        GeminiLauncher.launchOfficialGemini(this)

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

        // Aguarda sair da tela do nosso próprio aplicativo antes de procurar botões
        val firstPkg = rootNodes.firstOrNull()?.packageName?.toString() ?: ""
        if (firstPkg == packageName && (now - sessionStartTime) < 1800L) {
            return
        }

        when (currentPhase) {
            Phase.STEP_1_OPEN_LIVE -> {
                // 1) Verifica se JÁ estamos dentro da tela do Gemini Live
                if (isAlreadyInsideGeminiLive(rootNodes)) {
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now - 1500L
                    return
                }

                // 2) Procura o botão "Live" / "Conversar no Live" por texto ou descrição
                val liveNode = findBestMatchingNode(
                    roots = rootNodes,
                    keywords = LIVE_BUTTON_KEYWORDS,
                    excludeKeywords = emptyList(),
                    requireBottomHalf = false
                )

                if (liveNode != null) {
                    clickNodeAndTapBounds(liveNode, preferGestureTap = false)
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                    return
                }

                // 3) Fallback por coordenada se após 2.2 segundos o botão Live não foi achado por texto
                if (prefs.useCoordinateFallback && (now - sessionStartTime) >= 2200L) {
                    tapScreenPercent(prefs.liveButtonXPercent, prefs.liveButtonYPercent)
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                }
            }

            Phase.STEP_2_OPEN_CAMERA -> {
                // 1) Se a câmera já estiver ligada ("Turn off camera" / "Desativar câmera" / "Switch camera"),
                // vibra confirmando sucesso e encerra a automação!
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

                val elapsedSinceLive = now - liveClickedTime
                // Aguarda o Gemini Live terminar a animação de abertura e conexão (mínimo 2.4s ou valor configurado)
                val requiredInitialWait = maxOf(2400L, prefs.cameraDelayMs)
                if (elapsedSinceLive < requiredInitialWait) return

                // Aguarda pelo menos 1.7s entre cada tentativa de clique na câmera
                if (now - lastCameraAttemptTime < 1700L) return

                // Se já tentou 3 vezes, encerra para não clicar repetidamente
                if (cameraClickAttempts >= 3) {
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
                    cameraClickAttempts++
                    lastCameraAttemptTime = now
                    // No Gemini Live, o toque físico nas coordenadas do botão (preferGestureTap = true)
                    // é mais confiável que apenas ACTION_CLICK
                    clickNodeAndTapBounds(cameraNode, preferGestureTap = (cameraClickAttempts == 1))
                    if (cameraClickAttempts >= 2) {
                        FeedbackHelper.vibrateCameraReady(this)
                    }
                    return
                }

                // 3) Se não achou pelo nome "Camera", procura a barra inferior do Gemini Live
                // (ex: alinhado com o botão "Share screen" / "Hold" / "End") e clica no 1º botão à esquerda
                val bottomBarLeftButtonRect = findLeftmostLiveBottomBarButton(rootNodes)
                if (bottomBarLeftButtonRect != null) {
                    cameraClickAttempts++
                    lastCameraAttemptTime = now
                    tapCoordinates(
                        bottomBarLeftButtonRect.centerX().toFloat(),
                        bottomBarLeftButtonRect.centerY().toFloat()
                    )
                    if (cameraClickAttempts >= 2) {
                        FeedbackHelper.vibrateCameraReady(this)
                    }
                    return
                }

                // 4) Fallback final por coordenada configurada (X%, Y%)
                if (prefs.useCoordinateFallback) {
                    cameraClickAttempts++
                    lastCameraAttemptTime = now
                    tapScreenPercent(prefs.cameraButtonXPercent, prefs.cameraButtonYPercent)
                    if (cameraClickAttempts >= 2) {
                        FeedbackHelper.vibrateCameraReady(this)
                    }
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
            "sair do modo live",
            "end live"
        )
        return findBestMatchingNode(roots, liveScreenIndicators, emptyList(), false) != null
    }

    private fun findBestMatchingNode(
        roots: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        excludeKeywords: List<String>,
        requireBottomHalf: Boolean
    ): AccessibilityNodeInfo? {
        val minCenterY = if (requireBottomHalf) {
            (resources.displayMetrics.heightPixels * 0.55f).toInt()
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

        if (minCenterY > 0 && rect.centerY() < minCenterY) {
            // Mesmo que um container pai tenha centro acima da metade, seus filhos podem estar na barra inferior
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val match = searchNodeTree(child, keywords, excludeKeywords, minCenterY)
                if (match != null) return match
            }
            return null
        }

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
            val match = searchNodeTree(child, keywords, excludeKeywords, minCenterY)
            if (match != null) return match
        }
        return null
    }

    /**
     * Na tela do Gemini Live, a barra inferior possui 4 botões alinhados horizontalmente:
     * [Câmera] [Compartilhar Tela] [Pausar/Hold] [Encerrar/End].
     * Se encontrarmos qualquer indicador da barra inferior ou pelo menos 3 botões clicáveis alinhados
     * na parte inferior da tela, retornamos o botão mais à esquerda (que é o botão de Câmera).
     */
    private fun findLeftmostLiveBottomBarButton(roots: List<AccessibilityNodeInfo>): Rect? {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val minY = (screenHeight * 0.72f).toInt()
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
            // Botões da barra inferior do Gemini Live têm tamanho de botão (não ocupam a largura toda)
            if (rect.centerY() in minY..maxY &&
                width in 40..(screenWidth / 2) &&
                height in 40..350
            ) {
                outRects.add(rect)
            }
        }

        for (i in 0 until node.childCount) {
            collectBottomClickableNodes(node.getChild(i), minY, maxY, screenWidth, outRects)
        }
    }

    private fun clickNodeAndTapBounds(node: AccessibilityNodeInfo, preferGestureTap: Boolean) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (preferGestureTap && !rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            return
        }

        var current: AccessibilityNodeInfo? = node
        var clicked = false
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                val cRect = Rect()
                current.getBoundsInScreen(cRect)
                if (!cRect.isEmpty) {
                    rect.set(cRect)
                }
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) break
            }
            current = current.parent
            depth++
        }

        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
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
            "ligar camera",
            "abrir camera",
            "usar camera",
            "iniciar video",
            "compartilhar video",
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
            "flip camera"
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
