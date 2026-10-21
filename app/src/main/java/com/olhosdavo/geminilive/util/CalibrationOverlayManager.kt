package com.olhosdavo.geminilive.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.olhosdavo.geminilive.data.PrefsManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gerencia os 2 alvos flutuantes arrastáveis sobre o Gemini:
 * - Alvo (1) LIVE (Azul)
 * - Alvo (2) CÂMERA (Amarelo)
 * Mostra as coordenadas X,Y em tempo real no topo enquanto você arrasta e grava de forma
 * síncrona com confirmação na tela. Também exibe um pulso verde no momento do clique automático!
 */
class CalibrationOverlayManager(
    private val serviceContext: Context,
    private val onTapThrough: (Float, Float) -> Unit
) {

    private val windowManager =
        serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var topBarView: View? = null
    private var pin1View: View? = null
    private var pin2View: View? = null
    private var tvLiveCoordinatesReadout: TextView? = null

    private lateinit var pin1Params: WindowManager.LayoutParams
    private lateinit var pin2Params: WindowManager.LayoutParams

    // Coordenadas reais do centro de cada pino na tela física (rawX / rawY)
    private var currentPin1CenterX: Int = 0
    private var currentPin1CenterY: Int = 0
    private var currentPin2CenterX: Int = 0
    private var currentPin2CenterY: Int = 0

    val isShowing: Boolean
        get() = topBarView != null

    fun show() {
        if (isShowing) hide()

        val prefs = PrefsManager(serviceContext)
        val metrics = serviceContext.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val pinSizePx = dpToPx(76)

        currentPin1CenterX = if (prefs.liveButtonXPx > 0) {
            prefs.liveButtonXPx
        } else {
            (screenW * 0.88f).roundToInt()
        }
        currentPin1CenterY = if (prefs.liveButtonYPx > 0) {
            prefs.liveButtonYPx
        } else {
            (screenH * 0.90f).roundToInt()
        }

        currentPin2CenterX = if (prefs.cameraButtonXPx > 0) {
            prefs.cameraButtonXPx
        } else {
            (screenW * 0.22f).roundToInt()
        }
        currentPin2CenterY = if (prefs.cameraButtonYPx > 0) {
            prefs.cameraButtonYPx
        } else {
            (screenH * 0.88f).roundToInt()
        }

        // 1. Cria o Alvo 1 (Botão LIVE - Azul)
        pin1Params = createOverlayParams(
            width = pinSizePx,
            height = pinSizePx,
            x = currentPin1CenterX - pinSizePx / 2,
            y = currentPin1CenterY - pinSizePx / 2
        )
        pin1View = createDraggableTargetPin(
            pinIndex = 1,
            title = "1",
            subtitle = "LIVE",
            fillColor = Color.parseColor("#E60284C7"),
            strokeColor = Color.parseColor("#38BDF8"),
            params = pin1Params,
            pinSizePx = pinSizePx
        )

        // 2. Cria o Alvo 2 (Botão CÂMERA - Amarelo)
        pin2Params = createOverlayParams(
            width = pinSizePx,
            height = pinSizePx,
            x = currentPin2CenterX - pinSizePx / 2,
            y = currentPin2CenterY - pinSizePx / 2
        )
        pin2View = createDraggableTargetPin(
            pinIndex = 2,
            title = "2",
            subtitle = "CÂMERA",
            fillColor = Color.parseColor("#E6D97706"),
            strokeColor = Color.parseColor("#FFD600"),
            params = pin2Params,
            pinSizePx = pinSizePx
        )

        // 3. Cria a Barra Superior de Controle
        val topBarParams = createOverlayParams(
            width = (screenW * 0.94f).roundToInt(),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = (screenW * 0.03f).roundToInt(),
            y = dpToPx(38)
        )
        topBarView = createTopControlBar()

        try {
            windowManager.addView(pin1View, pin1Params)
            windowManager.addView(pin2View, pin2Params)
            windowManager.addView(topBarView, topBarParams)

            // Sincroniza a leitura exata na tela após o layout inicial
            handler.postDelayed({
                refreshActualScreenCenters()
            }, 150L)
        } catch (_: Exception) {
            hide()
            Toast.makeText(
                serviceContext,
                "Ative o Serviço de Acessibilidade primeiro para abrir os alvos.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun hide() {
        safeRemoveView(topBarView)
        safeRemoveView(pin1View)
        safeRemoveView(pin2View)
        topBarView = null
        pin1View = null
        pin2View = null
        tvLiveCoordinatesReadout = null
    }

    /**
     * Exibe um círculo verde rápido exatamente no ponto (X, Y) onde o clique automático aconteceu,
     * para você enxergar na hora onde o app está tocando!
     */
    fun showClickPulse(x: Float, y: Float) {
        handler.post {
            val size = dpToPx(48)
            val pulseView = View(serviceContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#804ADE80"))
                    setStroke(dpToPx(4), Color.parseColor("#22C55E"))
                }
            }
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - size / 2f).roundToInt()
                this.y = (y - size / 2f).roundToInt()
            }
            try {
                windowManager.addView(pulseView, params)
                handler.postDelayed({
                    safeRemoveView(pulseView)
                }, 550L)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshActualScreenCenters() {
        val p1 = pin1View
        val p2 = pin2View
        if (p1 != null && p1.width > 0) {
            val loc1 = IntArray(2)
            p1.getLocationOnScreen(loc1)
            currentPin1CenterX = loc1[0] + p1.width / 2
            currentPin1CenterY = loc1[1] + p1.height / 2
        }
        if (p2 != null && p2.width > 0) {
            val loc2 = IntArray(2)
            p2.getLocationOnScreen(loc2)
            currentPin2CenterX = loc2[0] + p2.width / 2
            currentPin2CenterY = loc2[1] + p2.height / 2
        }
        updateCoordinateReadoutText()
    }

    private fun updateCoordinateReadoutText() {
        tvLiveCoordinatesReadout?.text =
            "📍 Alvo 1 (Live): X=$currentPin1CenterX, Y=$currentPin1CenterY   |   Alvo 2 (Câm): X=$currentPin2CenterX, Y=$currentPin2CenterY"
    }

    private fun savePositionsAndClose() {
        refreshActualScreenCenters()

        val prefs = PrefsManager(serviceContext)
        val savedOk = prefs.saveCalibration(
            liveX = currentPin1CenterX,
            liveY = currentPin1CenterY,
            cameraX = currentPin2CenterX,
            cameraY = currentPin2CenterY
        )

        val checkLiveX = prefs.liveButtonXPx
        val checkLiveY = prefs.liveButtonYPx
        val checkCamX = prefs.cameraButtonXPx
        val checkCamY = prefs.cameraButtonYPx

        FeedbackHelper.vibrateCameraReady(serviceContext)

        if (savedOk && checkLiveX > 0 && checkCamX > 0) {
            Toast.makeText(
                serviceContext,
                "✅ SALVO COM SUCESSO!\n1-Live: ($checkLiveX, $checkLiveY) | 2-Câmera: ($checkCamX, $checkCamY)",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                serviceContext,
                "⚠️ Erro ao salvar posições. Tente novamente.",
                Toast.LENGTH_LONG
            ).show()
        }

        hide()
    }

    private fun createTopControlBar(): View {
        val container = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F50F172A"))
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(2), Color.parseColor("#FFD600"))
            }
        }

        val instructions = TextView(serviceContext).apply {
            text = "1️⃣ Arraste o alvo (1) até o botão Live (dê 1 toque nele para abrir o Live).\n2️⃣ Arraste o alvo (2) até o botão Câmera e toque em SALVAR:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        container.addView(instructions)

        tvLiveCoordinatesReadout = TextView(serviceContext).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dpToPx(6), 0, 0)
        }
        updateCoordinateReadoutText()
        container.addView(tvLiveCoordinatesReadout)

        val btnRow = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpToPx(10), 0, 0)
        }

        val btnCancel = Button(serviceContext).apply {
            text = "Fechar"
            isAllCaps = false
            setTextColor(Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setOnClickListener { hide() }
        }

        val btnSave = Button(serviceContext).apply {
            text = "✅ SALVAR POSIÇÕES"
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFD600"))
                cornerRadius = dpToPx(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(10)
            }
            layoutParams = lp
            setOnClickListener { savePositionsAndClose() }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)

        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableTargetPin(
        pinIndex: Int,
        title: String,
        subtitle: String,
        fillColor: Int,
        strokeColor: Int,
        params: WindowManager.LayoutParams,
        pinSizePx: Int
    ): View {
        val pinLayout = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dpToPx(3), strokeColor)
            }
        }

        val tvCrosshair = TextView(serviceContext).apply {
            text = "＋"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val tvNumber = TextView(serviceContext).apply {
            text = title
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val tvSub = TextView(serviceContext).apply {
            text = subtitle
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        pinLayout.addView(tvCrosshair)
        pinLayout.addView(tvNumber)
        pinLayout.addView(tvSub)

        var downRawX = 0f
        var downRawY = 0f
        var startParamX = 0
        var startParamY = 0

        pinLayout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    params.x = startParamX + dx
                    params.y = startParamY + dy
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                    // Atualiza as coordenadas reais na tela usando getLocationOnScreen
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val cx = loc[0] + pinSizePx / 2
                    val cy = loc[1] + pinSizePx / 2
                    if (pinIndex == 1) {
                        currentPin1CenterX = cx
                        currentPin1CenterY = cy
                    } else {
                        currentPin2CenterX = cx
                        currentPin2CenterY = cy
                    }
                    updateCoordinateReadoutText()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    refreshActualScreenCenters()
                    val totalMoveX = abs(event.rawX - downRawX)
                    val totalMoveY = abs(event.rawY - downRawY)
                    // Se foi um toque rápido no próprio alvo (sem arrastar), passa o clique para o botão embaixo!
                    if (totalMoveX < dpToPx(10) && totalMoveY < dpToPx(10)) {
                        val cx = if (pinIndex == 1) currentPin1CenterX.toFloat() else currentPin2CenterX.toFloat()
                        val cy = if (pinIndex == 1) currentPin1CenterY.toFloat() else currentPin2CenterY.toFloat()
                        v.visibility = View.INVISIBLE
                        handler.postDelayed({
                            onTapThrough(cx, cy)
                        }, 100L)
                        handler.postDelayed({
                            v.visibility = View.VISIBLE
                        }, 600L)
                    }
                    true
                }
                else -> false
            }
        }

        return pinLayout
    }

    private fun createOverlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun safeRemoveView(view: View?) {
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            serviceContext.resources.displayMetrics
        ).roundToInt()
    }
}
