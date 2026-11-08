package com.example.tvlimit.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout

/**
 * 遥控器可操作的 4 位数字密码键盘。
 *
 * 右侧提示「切换档案」必须能用遥控器走完，而 EditText 在电视上唤不出软键盘，
 * 所以这里用纯按钮键盘：方向键移动、OK 确认，输满 4 位自动校验。
 */
class PinPadDialog(
    context: Context,
    titleText: String,
    private val verify: (String) -> Boolean,
    private val onSuccess: (String) -> Unit
) : Dialog(context) {

    private val entered = StringBuilder()
    private lateinit var slotView: TextView
    private lateinit var errorView: TextView

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun panelBackground(): GradientDrawable = TvDrawables.panel(::dp)

    /** 焦点态的统一写法见 [TvDrawables]：外圈白 + 内圈深褐，不再是「亮黄底配白边」。 */
    private fun keyBackground(): StateListDrawable = TvDrawables.rowBackground(::dp, 20)

    private fun keyTextColor(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf()
        ),
        intArrayOf(Color.parseColor("#241A02"), Color.parseColor("#E8EEF9"))
    )

    init {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(32), dp(36), dp(28))
            background = panelBackground()
        }

        panel.addView(TextView(context).apply {
            text = titleText
            textSize = 25f
            setTextColor(Color.parseColor("#E8EEF9"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        slotView = TextView(context).apply {
            textSize = 34f
            letterSpacing = 0.28f
            setTextColor(Color.parseColor("#FFC94A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
            includeFontPadding = false
            text = slotText()
        }
        panel.addView(slotView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        errorView = TextView(context).apply {
            text = "密码不对，再试一次"
            textSize = 16f
            setTextColor(Color.parseColor("#FF7A7A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
            includeFontPadding = false
            visibility = View.INVISIBLE
        }
        panel.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("删除", "0", "返回")
        )

        var first: TextView? = null
        for (row in rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for ((index, label) in row.withIndex()) {
                val btn = TextView(context).apply {
                    text = label
                    textSize = if (label.length > 1) 20f else 30f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(keyTextColor())
                    background = keyBackground()
                    gravity = Gravity.CENTER
                    // 只设 focusable：focusableInTouchMode 会让「触摸模式下的第一次点击
                    // 只取焦点、不派发 onClick」，表现就是每个数字都要点两下（鼠标/触摸测试）。
                    isFocusable = true
                    isClickable = true
                    includeFontPadding = false
                    setOnClickListener { onKey(label) }
                }
                if (first == null) first = btn
                val lp = LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                    if (index < row.size - 1) marginEnd = dp(14)
                }
                rowLayout.addView(btn, lp)
            }
            panel.addView(rowLayout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }

        setContentView(panel)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { onKey("0"); true }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { onKey("1"); true }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { onKey("2"); true }
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { onKey("3"); true }
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { onKey("4"); true }
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> { onKey("5"); true }
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> { onKey("6"); true }
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> { onKey("7"); true }
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> { onKey("8"); true }
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> { onKey("9"); true }
                KeyEvent.KEYCODE_DEL -> { onKey("删除"); true }
                else -> false
            }
        }

        setOnShowListener {
            TvFocus.focus(first)
        }
    }

    private fun slotText(): String {
        val filled = entered.length
        val sb = StringBuilder()
        for (i in 0 until 4) {
            if (i > 0) sb.append(' ')
            sb.append(if (i < filled) '●' else '○')
        }
        return sb.toString()
    }

    private fun onKey(label: String) {
        errorView.visibility = View.INVISIBLE
        when (label) {
            "删除" -> {
                if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            }
            "返回" -> {
                dismiss()
                return
            }
            else -> {
                if (entered.length < 4) entered.append(label)
            }
        }
        slotView.text = slotText()

        if (entered.length == 4) {
            val pin = entered.toString()
            if (verify(pin)) {
                dismiss()
                onSuccess(pin)
            } else {
                entered.setLength(0)
                slotView.text = slotText()
                errorView.visibility = View.VISIBLE
            }
        }
    }
}
