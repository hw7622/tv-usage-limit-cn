package com.example.tvlimit.ui

import android.view.View
import android.view.ViewGroup

/**
 * 电视端焦点反馈：遥控器移上去时轻微放大，配合 selector 描边，
 * 远距离一眼就能看出「现在选中的是哪一个」。
 *
 * 注意：放大后的卡片会超出父容器边界，而 ViewGroup 默认会把超出的部分裁掉，
 * 表现就是「描边只有三条边、右边和圆角被切平」。所以这里顺带把整条祖先链的
 * 裁剪都关掉，让放大后的描边能完整画出来。
 */
object TvFocus {

    fun attach(vararg views: View, scale: Float = 1.02f) {
        views.forEach { attach(it, scale) }
    }

    fun attach(view: View, scale: Float = 1.02f) {
        allowOverflow(view)
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(140)
                .start()
        }
    }

    /** 关掉所有祖先容器的子视图裁剪，避免缩放后的描边被切掉。 */
    private fun allowOverflow(view: View) {
        var parent = view.parent
        while (parent is ViewGroup) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
        }
    }
}
