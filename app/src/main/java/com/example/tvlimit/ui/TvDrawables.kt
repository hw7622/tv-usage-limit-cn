package com.example.tvlimit.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable

/**
 * 弹窗里动态生成的按钮背景（密码盘、选项弹窗）。
 *
 * 为什么单独拎一个文件：
 * 焦点样式原来有两套 —— XML 里一套（`selector_btn_primary` 等），
 * 弹窗在 Kotlin 里又各写一套，两边颜色各改各的。结果就是
 * 「有的页面有描边、有的页面没有」，换个电视看着还不一样。
 * 现在把「焦点长什么样」收敛到这里，两边都调用同一个函数。
 *
 * 焦点态是**两条环**：外圈白 3dp + 内圈深褐 3dp。
 * 单圈白边贴在亮黄填充上只有 1.3:1 的对比度，电视上三米外等于没画；
 * 双环各自贴着一边 —— 外侧对深色背景 15:1、内侧对亮黄 8:1，
 * 任何亮度、任何偏色的面板上都看得见。
 */
object TvDrawables {

    /** 焦点时的填充：比常态的 #FFC94A 更亮，远看也有「这个亮了」的信号 */
    private const val FILL_FOCUS = "#FFE9A8"
    private const val RING_INNER = "#5C4300"
    private const val RING_OUTER = "#FFFFFF"

    private const val FILL_IDLE = "#1E2740"
    private const val STROKE_IDLE = "#33405F"

    private const val PANEL_FILL = "#F5161D2E"

    private fun focusRing(dp: (Int) -> Int, radius: Int): LayerDrawable {
        val outer = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(FILL_FOCUS))
            setStroke(dp(6), Color.parseColor(RING_OUTER))
        }
        val inner = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(FILL_FOCUS))
            setStroke(dp(3), Color.parseColor(RING_INNER))
        }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun idleRing(dp: (Int) -> Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(FILL_IDLE))
            setStroke(dp(2), Color.parseColor(STROKE_IDLE))
        }

    /**
     * 列表项 / 键盘按键的三态背景。
     *
     * @param radius 圆角半径（dp）。按键用 20，选项行用 18。
     */
    fun rowBackground(dp: (Int) -> Int, radius: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusRing(dp, radius))
            addState(intArrayOf(android.R.attr.state_pressed), focusRing(dp, radius))
            addState(intArrayOf(), idleRing(dp, radius))
        }

    /** 弹窗面板本身：半透明深底 + 一圈灰边 */
    fun panel(dp: (Int) -> Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(30).toFloat()
        setColor(Color.parseColor(PANEL_FILL))
        setStroke(dp(2), Color.parseColor(STROKE_IDLE))
    }
}
