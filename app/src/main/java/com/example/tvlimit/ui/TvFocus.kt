package com.example.tvlimit.ui

import android.view.View

/**
 * 电视端焦点辅助。
 *
 * ## 为什么焦点反馈不再「放大」
 *
 * 以前是 `scale = 1.02`（列表卡片 1.04），想做出「选中项跳出来」的效果。问题是放大
 * **必然**让控件超出自己的布局边界，于是描边被吃掉：
 *
 * - 父容器默认 `clipChildren = true`，把超出的那条边裁掉 → 「边框只有三条边」；
 * - 就算关掉裁剪，放大后的控件会盖住兄弟控件，后画的把先画的描边压掉；
 * - 各厂商 ROM 对 View 边界、阴影的处理不一致，所以这个毛病「换台电视就换个样」。
 *
 * 试过「非焦点 0.98、焦点 1.0」（只缩不放），确实不会溢出，但只有被 [attach] 过的
 * 控件才缩，同一行里漏掉一个就会差 2% 高矮不齐 —— 比没有动效更难看。
 *
 * 所以现在**不改变任何控件尺寸**，焦点完全由 selector 的描边承担：
 * 非焦点 1~2dp 细边、焦点 4dp 亮色粗边。粗细差一倍，三米外一眼就能看见。
 *
 * ## 千万不要再去动祖先的 clipChildren
 *
 * 之前这里为了让放大的描边不被裁，会把整条祖先链的 `clipChildren` 设成 false。
 * 这个做法有个隐蔽的副作用：**`ScrollView` 就是靠 `clipChildren` 裁剪滚动内容的**。
 * 关掉之后，滚出视口的卡片会继续往下画，直接糊在固定底栏的按钮上 ——
 * 表现出来是「按钮的边框被别的东西盖住，显示不全」。
 *
 * 既然现在不放大，这一步也就不需要了，一并删掉。
 */
object TvFocus {

    /**
     * 把焦点给某个控件，并要求「一定成功」。
     *
     * 为什么不能用 requestFocus()：整机处于触摸模式（刚用鼠标/触摸点过屏幕）时，
     * 非 focusableInTouchMode 的 View 调用 requestFocus() 会直接返回 false，
     * 于是「题出来了但没有任何高亮，按一下方向键才亮」。requestFocusFromTouch()
     * 会先退出触摸模式再聚焦，遥控器和鼠标混用时才稳。
     */
    fun focus(view: View?): Boolean {
        val v = view ?: return false
        if (v.requestFocus()) return true
        return try {
            v.requestFocusFromTouch()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把控件纳入焦点体系。
     *
     * 现在只做一件事：确保它真的能被遥控器选中。以前这里会改祖先的裁剪、挂缩放动画，
     * 现在焦点样式完全交给 selector，所以剩下的就是把 `focusable` 补齐 ——
     * 用 `<TextView>` 当按钮时最容易漏这个属性，漏了页面就没有任何焦点。
     */
    fun attach(vararg views: View) {
        views.forEach { attach(it) }
    }

    fun attach(view: View) {
        view.isFocusable = true
    }
}
