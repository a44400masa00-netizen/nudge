package com.example.studynudge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 他のアプリの上に表示する、画面上部のポップアップ。メインスレッドから呼ぶこと。 */
class OverlayBanner(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private var textView: TextView? = null
    private val pending = StringBuilder()
    private var typing = false
    private var done = false
    private val hideRunnable = Runnable { hide() }

    private val typer = object : Runnable {
        override fun run() {
            val tv = textView
            if (tv == null) {
                typing = false
                return
            }
            if (pending.isNotEmpty()) {
                val ch = pending[0]
                pending.deleteCharAt(0)
                tv.append(ch.toString())
                handler.postDelayed(this, 45L)
            } else {
                typing = false
                if (done) handler.postDelayed(hideRunnable, 15_000L)
            }
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun start(): Boolean {
        hide()
        if (!Settings.canDrawOverlays(context)) return false
        done = false

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2222831"))
        bg.cornerRadius = dp(16f).toFloat()
        card.background = bg

        val header = TextView(context)
        header.text = "勉強コーチ"
        header.setTextColor(Color.parseColor("#9FB3FF"))
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        header.setTypeface(header.typeface, Typeface.BOLD)
        card.addView(header)

        val body = TextView(context)
        body.setTextColor(Color.WHITE)
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        body.setPadding(0, dp(4f), 0, 0)
        card.addView(body)

        val frame = FrameLayout(context)
        frame.setPadding(dp(12f), dp(8f), dp(12f), 0)
        frame.addView(card)
        frame.setOnClickListener { hide() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP

        return try {
            wm.addView(frame, lp)
            container = frame
            textView = body
            true
        } catch (e: Exception) {
            container = null
            textView = null
            false
        }
    }

    fun push(s: String) {
        if (container == null) return
        pending.append(s)
        if (!typing) {
            typing = true
            handler.post(typer)
        }
    }

    fun end() {
        done = true
        if (container != null && !typing && pending.isEmpty()) {
            handler.postDelayed(hideRunnable, 15_000L)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(typer)
        pending.setLength(0)
        typing = false
        val c = container
        if (c != null) {
            try {
                wm.removeView(c)
            } catch (e: Exception) {
            }
        }
        container = null
        textView = null
    }
}
