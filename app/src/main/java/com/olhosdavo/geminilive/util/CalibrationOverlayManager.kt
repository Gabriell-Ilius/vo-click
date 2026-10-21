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
 * Exibe 2 alvos flutuantes arrastáveis sobre o app do Gemini:
 * - Alvo (1) LIVE (Azul)
 * - Alvo (2) CÂMERA (Amarelo)
 *
 * Se o usuário der um toque rápido em cima do próprio alvo (sem arrastar),
 * o alvo fica invisível por 0.5s e clica no botão do Gemini que está exatamente embaixo dele!
 */
class CalibrationOverlayManager(
    private val serviceContext: Context,
    private val prefs: PrefsManager,
    private val onTapThrough: (Float, Float) -> Unit
) {

    private val windowManager =
        serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var topBarView: View? = null
    private var pin1View: View? = null
    private var pin2View: View? = null

    private lateinit var pin1Params: WindowManager.LayoutParams
    private lateinit var pin2Params: WindowManager.LayoutParams

    val isShowing: Boolean
        get() = topBarView != null

    fun show() {
        if (isShowing) return

        val metrics = serviceContext.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val pinSizePx = dpToPx(74)

        val startLiveX = if (prefs.liveButtonXPx > 0) {
            prefs.liveButtonXPx
        } else {
            (screenW * (prefs.liveButtonXPercent / 100f)).roundToInt()
        }
        val startLiveY = if (prefs.liveButtonYPx > 0) {
            prefs.liveButtonYPx
        } else {
            (screenH * (prefs.liveButtonYPercent / 100f)).roundToInt()
        }

        val startCamX = if (prefs.cameraButtonXPx > 0) {
            prefs.cameraButtonXPx
        } else {
            (screenW * (prefs.cameraButtonXPercent / 100f)).roundToInt()
        }
        val startCamY = if (prefs.cameraButtonYPx > 0) {
            prefs.cameraButtonYPx
        } else {
            (screenH * (prefs.cameraButtonYPercent / 100f)).roundToInt()
        }

        // 1. Cria o Alvo 1 (Botão LIVE - Azul)
        pin1Params = createOverlayParams(
            width = pinSizePx,
            height = pinSizePx,
            x = (startLiveX - pinSizePx / 2).coerceIn(0, screenW - pinSizePx),
            y = (startLiveY - pinSizePx / 2).coerceIn(0, screenH - pinSizePx)
        )
        pin1View = createDraggableTargetPin(
            title = "1",
            subtitle = "LIVE",
            fillColor = Color.parseColor("#D90284C7"),
            strokeColor = Color.parseColor("#38BDF8"),
            params = pin1Params
        )

        // 2. Cria o Alvo 2 (Botão CÂMERA - Amarelo/Âmbar)
        pin2Params = createOverlayParams(
            width = pinSizePx,
            height = pinSizePx,
            x = (startCamX - pinSizePx / 2).coerceIn(0, screenW - pinSizePx),
            y = (startCamY - pinSizePx / 2).coerceIn(0, screenH - pinSizePx)
        )
        pin2View = createDraggableTargetPin(
            title = "2",
            subtitle = "CÂMERA",
            fillColor = Color.parseColor("#D9D97706"),
            strokeColor = Color.parseColor("#FBBF24"),
            params = pin2Params
        )

        // 3. Cria a Barra Superior de Controle (Salvar / Cancelar)
        val topBarParams = createOverlayParams(
            width = (screenW * 0.94f).roundToInt(),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = (screenW * 0.03f).roundToInt(),
            y = dpToPx(40)
        )
        topBarView = createTopControlBar()

        try {
            windowManager.addView(pin1View, pin1Params)
            windowManager.addView(pin2View, pin2Params)
            windowManager.addView(topBarView, topBarParams)
        } catch (e: Exception) {
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
    }

    private fun savePositionsAndClose() {
        val p1 = pin1View
        val p2 = pin2View
        if (p1 != null && p2 != null) {
            val metrics = serviceContext.resources.displayMetrics
            val loc1 = IntArray(2)
            val loc2 = IntArray(2)
            p1.getLocationOnScreen(loc1)
            p2.getLocationOnScreen(loc2)

            val center1X = loc1[0] + p1.width / 2
            val center1Y = loc1[1] + p1.height / 2
            val center2X = loc2[0] + p2.width / 2
            val center2Y = loc2[1] + p2.height / 2

            prefs.liveButtonXPx = center1X
            prefs.liveButtonYPx = center1Y
            prefs.cameraButtonXPx = center2X
            prefs.cameraButtonYPx = center2Y

            prefs.liveButtonXPercent =
                ((center1X.toFloat() / metrics.widthPixels) * 100f).roundToInt().coerceIn(1, 99)
            prefs.liveButtonYPercent =
                ((center1Y.toFloat() / metrics.heightPixels) * 100f).roundToInt().coerceIn(1, 99)
            prefs.cameraButtonXPercent =
                ((center2X.toFloat() / metrics.widthPixels) * 100f).roundToInt().coerceIn(1, 99)
            prefs.cameraButtonYPercent =
                ((center2Y.toFloat() / metrics.heightPixels) * 100f).roundToInt().coerceIn(1, 99)
            prefs.hasVisualCalibration = true

            FeedbackHelper.vibrateCameraReady(serviceContext)
            Toast.makeText(
                serviceContext,
                "✅ Alvo 1 (Live) e Alvo 2 (Câmera) salvos com sucesso!",
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
                setStroke(dpToPx(2), Color.parseColor("#334155"))
            }
        }

        val instructions = TextView(serviceContext).apply {
            text = "1️⃣ Arraste o alvo (1) até o botão Live e dê 1 toque nele para abrir o Live.\n2️⃣ Arraste o alvo (2) até o botão Câmera e toque em Salvar:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        container.addView(instructions)

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
            text = "✅ Salvar Posições"
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FBBF24"))
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
        title: String,
        subtitle: String,
        fillColor: Int,
        strokeColor: Int,
        params: WindowManager.LayoutParams
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

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        pinLayout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    // Se foi um toque rápido no próprio alvo (sem arrastar), clica no botão embaixo dele!
                    if (dx < dpToPx(10) && dy < dpToPx(10)) {
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        val cx = loc[0] + v.width / 2f
                        val cy = loc[1] + v.height / 2f
                        v.visibility = View.INVISIBLE
                        handler.postDelayed({
                            onTapThrough(cx, cy)
                        }, 80L)
                        handler.postDelayed({
                            v.visibility = View.VISIBLE
                        }, 550L)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
