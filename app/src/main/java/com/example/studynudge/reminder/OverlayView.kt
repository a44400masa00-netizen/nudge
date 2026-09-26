package com.example.studynudge.reminder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 画面全体に出す警告・開始演出のView。
 *
 * 見た目・アニメーションの仕様（チャットでプレビューして確定した内容）:
 * 1. 背景がふわっと半透明オレンジで染まる
 * 2. 上下の横線が中央から左右に伸びる → 少し遅れて四隅の短い縦ティックが
 *    枠の外側へ飛び出すように伸びる（横線より短く、横線とは触れない）
 * 3. 文字が数回チカチカ明滅してから、文字間隔が広がりながら定着する
 *
 * 枠は横長・薄め（幅に対して高さが小さい）にして、文字が枠内に収まるよう
 * 自動で文字サイズを縮小する。
 */
class OverlayView(
    context: Context,
    private val message: String,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = 0xFFFFB259.toInt() // 薄いオレンジ
        style = Paint.Style.FILL
    }

    private val framePaint = Paint().apply {
        color = 0xFFFF6A1A.toInt() // 鮮やかなオレンジ
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = 0xFFFF6A1A.toInt()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    // --- アニメーション進捗（0〜1） ---
    private var bgFrameT = 0f      // 背景フェード＋枠のopacity/scale
    private var hLineT = 0f        // 横線の伸び
    private var vTickT = 0f        // 四隅ティックの伸び
    private var textOpacity = 0f   // 文字の明滅
    private var letterSpacingT = 0f // 文字間隔のアニメーション

    private val maxBgAlpha = 90        // 背景の最大不透明度（0-255）
    private val baseLetterSpacing = 0.03f
    private val finalLetterSpacing = 0.18f

    private val introDurationMs = 950L
    private val autoDismissDelayMs = 2800L
    private val fadeOutDurationMs = 320L

    init {
        isClickable = true
        setOnClickListener { animateOut() }
        animateIn()
    }

    private fun animateIn() {
        ValueAnimator.ofFloat(0f, introDurationMs.toFloat()).apply {
            duration = introDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedValue as Float

                bgFrameT = (elapsed / 380f).coerceIn(0f, 1f)
                hLineT = ((elapsed - 120f) / 380f).coerceIn(0f, 1f)
                vTickT = ((elapsed - 280f) / 260f).coerceIn(0f, 1f)
                letterSpacingT = ((elapsed - 420f) / 500f).coerceIn(0f, 1f)

                val flickerRelative = elapsed - 420f
                textOpacity = when {
                    flickerRelative < 0f -> 0f
                    flickerRelative >= 400f -> 1f
                    else -> {
                        val step = (flickerRelative / 80f).toInt()
                        if (step % 2 == 0) 0.25f else 1f
                    }
                }
                invalidate()
            }
        }.start()
        postDelayed({ animateOut() }, autoDismissDelayMs)
    }

    private fun animateOut() {
        val startBg = bgFrameT
        val startText = textOpacity
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = fadeOutDurationMs
            addUpdateListener {
                val v = it.animatedValue as Float
                bgFrameT = startBg * v
                hLineT = v
                vTickT = v
                textOpacity = startText * v
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismiss()
                }
            })
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 背景
        bgPaint.alpha = (maxBgAlpha * bgFrameT).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 枠のジオメトリ（横長・薄め）
        val boxWidth = w * 0.78f
        val boxHeight = boxWidth * 0.24f
        val left = (w - boxWidth) / 2f
        val right = left + boxWidth
        val top = h / 2f - boxHeight / 2f
        val bottom = h / 2f + boxHeight / 2f

        val insetX = boxWidth * 0.10f          // 横線の左右の余白
        val tickLength = boxHeight * 0.22f     // 縦ティックの長さ（横線より短い）

        val frameScale = 0.92f + 0.08f * bgFrameT
        canvas.save()
        canvas.scale(frameScale, frameScale, w / 2f, h / 2f)

        framePaint.alpha = (255 * bgFrameT).toInt().coerceIn(0, 255)

        // 横線：中央から左右に伸びる
        drawGrowingHLine(canvas, left + insetX, right - insetX, top, hLineT)
        drawGrowingHLine(canvas, left + insetX, right - insetX, bottom, hLineT)

        // 四隅の短い縦ティック：枠の外側へ飛び出す（横線とは接しない）
        drawTick(canvas, left, top, -1f, tickLength, vTickT)
        drawTick(canvas, right, top, -1f, tickLength, vTickT)
        drawTick(canvas, left, bottom, 1f, tickLength, vTickT)
        drawTick(canvas, right, bottom, 1f, tickLength, vTickT)

        // 文字（枠に収まるよう自動縮小）
        val availableWidth = boxWidth - insetX * 2f - boxWidth * 0.06f
        val fittedTextSize = fitTextSize(message, boxHeight * 0.30f, availableWidth, finalLetterSpacing)
        textPaint.textSize = fittedTextSize
        textPaint.letterSpacing = baseLetterSpacing + (finalLetterSpacing - baseLetterSpacing) * letterSpacingT
        textPaint.alpha = (255 * textOpacity).toInt().coerceIn(0, 255)
        val fm = textPaint.fontMetrics
        val textY = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(message, w / 2f, textY, textPaint)

        canvas.restore()
    }

    /** テキストが availableWidth に収まるよう、文字サイズを必要なら縮小する */
    private fun fitTextSize(text: String, startSize: Float, availableWidth: Float, letterSpacing: Float): Float {
        var size = startSize
        val measurePaint = Paint(textPaint).apply { this.letterSpacing = letterSpacing }
        repeat(20) {
            measurePaint.textSize = size
            val width = measurePaint.measureText(text)
            if (width <= availableWidth || size <= 8f) return size
            size *= 0.92f
        }
        return size
    }

    /** 中心から左右へ伸びる横線 */
    private fun drawGrowingHLine(canvas: Canvas, x1: Float, x2: Float, y: Float, p: Float) {
        val mid = (x1 + x2) / 2f
        val half = (x2 - x1) / 2f * p
        canvas.drawLine(mid - half, y, mid + half, y, framePaint)
    }

    /** 枠の角(x, edgeY)から、directionの向き(-1=上, 1=下)へ伸びる短いティック */
    private fun drawTick(canvas: Canvas, x: Float, edgeY: Float, direction: Float, tickLength: Float, p: Float) {
        val endY = edgeY + direction * tickLength * p
        canvas.drawLine(x, edgeY, x, endY, framePaint)
    }
}
