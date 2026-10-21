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
 * Serviço de Acessibilidade (v1.8):
 * - Com tempos configuráveis separados para o Clique 1 (padrão 3.0s) e para o Clique 2 (padrão +5.5s).
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

    private val step1CalibratedRunnable = Runnable {
        val freshPrefs = PrefsManager(this)
        val x1 = freshPrefs.liveButtonXPx.toFloat()
        val y1 = freshPrefs.liveButtonYPx.toFloat()
        if (x1 > 0 && y1 > 0) {
            ensureOverlayManager().showClickPulse(x1, y1)
            tapCoordinates(x1, y1)
        }
    }

    private val step2CalibratedRunnable = Runnable {
        val freshPrefs = PrefsManager(this)
        val x2 = freshPrefs.cameraButtonXPx.toFloat()
        val y2 = freshPrefs.cameraButtonYPx.toFloat()
        if (x2 > 0 && y2 > 0) {
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
                tapCoordinates(x, y)
            }
            calibrationOverlay = manager
        }
        return manager
    }

    fun startVisualCalibrationMode() {
        stopAutomation()
        GeminiLauncher.launchOfficialGemini(this)
        handler.postDelayed({
            ensureOverlayManager().show()
        }, 650L)
    }

    fun startAutomationSession() {
        calibrationOverlay?.hide()
        stopAutomation()

        val freshPrefs = PrefsManager(this)

        GeminiLauncher.launchOfficialGemini(this)

        // Se já salvou a posição dos Alvos (1) e (2), respeita exatamente o Tempo 1 e o Tempo 2!
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
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(step1CalibratedRunnable)
        handler.removeCallbacks(step2CalibratedRunnable)
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
                    currentPhase = Phase.STEP_2_OPEN_CAMERA
                    liveClickedTime = now
                    return
                }
                val x = resources.displayMetrics.widthPixels * 0.88f
                val y = resources.displayMetrics.heightPixels * 0.90f
                ensureOverlayManager().showClickPulse(x, y)
                tapCoordinates(x, y)
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
