package com.example.tvlimit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.tvlimit.net.QrCode

/**
 * 把二维码画到电视上。白底黑块是硬性要求：电视的深色模式下把二维码反色，
 * 一半手机扫不出来，家长会以为「这个功能是坏的」。
 *
 * 静区（二维码四周的空白）按标准留 4 个模块宽，不靠外面的 padding 撑 ——
 * 外面一旦有背景图或渐变，静区就不算数了。
 */
class QrView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var matrix: QrCode.Matrix? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fgPaint = Paint().apply { color = Color.BLACK }
    private val rect = RectF()

    fun setText(text: String?) {
        matrix = if (text.isNullOrBlank()) null else QrCode.encode(text)
        contentDescription = if (matrix == null) "二维码生成失败" else "扫码打开配置页"
        invalidate()
    }

    fun hasCode(): Boolean = matrix != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED &&
                MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> dp(220)

            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            else -> minOf(w, h)
        }
        setMeasuredDimension(size, size)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        val m = matrix ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // 4 个模块的静区，摊在四周
        val cell = minOf(w, h) / (m.size + 8f)
        val offsetX = (w - cell * m.size) / 2f
        val offsetY = (h - cell * m.size) / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRect(rect, bgPaint)

        for (r in 0 until m.size) {
            for (c in 0 until m.size) {
                if (!m.isDark(r, c)) continue
                val left = offsetX + c * cell
                val top = offsetY + r * cell
                // 每格多画 1px：低分辨率屏幕上 cell 是小数，不留缝会出现白色网格
                canvas.drawRect(left, top, left + cell + 1f, top + cell + 1f, fgPaint)
            }
        }
    }
}
