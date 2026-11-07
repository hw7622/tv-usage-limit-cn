package com.example.tvlimit.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 电视端竖向选项弹窗：方向键上下移动，OK 确认，返回键取消。
 * 用于「临时关闭多久」这类几选一的场景，比弹一列按钮更省事。
 *
 * 选项超过一屏时要能滚：年级有 9 项、题量有 8 项，真机 720dp 高
 * 一屏最多摆 7 行，第 8 项起会掉到屏幕外 —— 而且掉出去之后遥控器
 * 焦点也跟着走丢，家长只会觉得「这个选项根本点不到」。
 * 所以把选项列放进有高度上限的 ScrollView，焦点移过去时系统会自动滚。
 */
class OptionDialog(
    context: Context,
    titleText: String,
    private val options: List<Option>,
    private val onPick: (Option) -> Unit
) : Dialog(context) {

    data class Option(val label: String, val sub: String = "")

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun rowBackground(): StateListDrawable {
        fun shape(fill: String, stroke: String, strokeW: Int) = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(strokeW), Color.parseColor(stroke))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape("#FFC94A", "#FFFFFF", 3))
            addState(intArrayOf(android.R.attr.state_pressed), shape("#FFC94A", "#FFFFFF", 3))
            addState(intArrayOf(), shape("#1E2740", "#33405F", 2))
        }
    }

    private fun labelColor(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(Color.parseColor("#241A02"), Color.parseColor("#E8EEF9"))
    )

    private fun subColor(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(Color.parseColor("#5C4310"), Color.parseColor("#8A9AB8"))
    )

    init {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(34), dp(30), dp(34), dp(30))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#F5161D2E"))
                setStroke(dp(2), Color.parseColor("#33405F"))
            }
        }

        panel.addView(TextView(context).apply {
            text = titleText
            textSize = 24f
            setTextColor(Color.parseColor("#E8EEF9"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) })

        var first: View? = null
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        options.forEachIndexed { index, option ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBackground()
                isFocusable = true
                isClickable = true
                setPadding(dp(26), dp(14), dp(26), dp(14))
                setOnClickListener {
                    dismiss()
                    onPick(option)
                }
            }

            row.addView(TextView(context).apply {
                text = option.label
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor())
                // 焦点在整行上，文字要跟着父控件一起变状态，否则选中时还是浅色字压黄底
                isDuplicateParentStateEnabled = true
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            if (option.sub.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = option.sub
                    textSize = 15f
                    setTextColor(subColor())
                    isDuplicateParentStateEnabled = true
                    setPadding(0, dp(4), 0, 0)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }

            if (first == null) first = row
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else dp(12) })
        }

        // 列表最多占屏幕 78% 高：再高加上标题和面板内边距就顶出 720dp 屏幕了。
        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
        val scroller = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // 父布局给的是 UNSPECIFIED/AT_MOST 都可能，统一换成「最多 maxListHeight」
                val capped = MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            // 遥控器焦点移出可见区域时，ScrollView 靠这个把行滚进来
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        panel.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(panel)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        setOnShowListener { TvFocus.focus(first) }
    }
}
