package com.example.tvlimit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 休息倒计时环：外圈走一圈代表剩余时间，中心显示剩余秒数。
 * 电视上比纯文字倒计时更容易让孩子看懂「还要等多久」。
 */
class CountdownRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#232D45")
        strokeWidth = 12f * density
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFC94A")
        strokeWidth = 12f * density
    }

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A96B0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val arcRect = RectF()
    private var remaining = 10
    private var total = 10

    fun setCountdown(remainingSeconds: Int, totalSeconds: Int) {
        remaining = remainingSeconds.coerceAtLeast(0)
        total = totalSeconds.coerceAtLeast(1)
        invalidate()
    }

    fun setArcColor(color: Int) {
        arcPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) - arcPaint.strokeWidth) / 2f - 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        val sweep = 360f * remaining.toFloat() / total.toFloat()
        if (sweep > 0f) {
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
        }

        val size = minOf(width, height).toFloat()
        numPaint.textSize = size * 0.32f
        unitPaint.textSize = size * 0.09f

        val baseline = cy + numPaint.textSize * 0.34f
        canvas.drawText(remaining.toString(), cx, baseline, numPaint)
        canvas.drawText("秒", cx, baseline + unitPaint.textSize * 1.75f, unitPaint)
    }
}
