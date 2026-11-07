package com.example.tvlimit.service

import com.example.tvlimit.ui.TvFocus

import android.app.Service
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlimit.R
import com.example.tvlimit.quiz.QuizQuestion
import com.example.tvlimit.quiz.QuizSession

/**
 * 答题门禁的界面层：一个全屏悬浮窗 + 一组题。
 *
 * 为什么是悬浮窗不是 Activity：TCL 的硬件视频层会盖住普通 Activity 窗口，
 * 后台 startActivity 的页面即使 RESUMED 也看不见（11-02 实测）。悬浮窗能压在最上层，
 * 再配合 goHome 让视频应用退到后台、硬件层释放，画面才真的换得过来。
 *
 * 这个类只负责「显示 + 收按键 + 判题」，什么时候弹、答完放不放行，由 TimeTrackingService 决定。
 */
class QuizGate(
    private val service: Service,
    private val windowManager: WindowManager,
    private val callback: Callback
) {

    interface Callback {
        /** 全部答对：可以放行，pkg 是弹题时正在看的应用。 */
        fun onPassed(pkg: String?)

        /** 点了返回 / 不看了：留在桌面，题保持 pending。 */
        fun onAbandon()

        /** 点「我是家长」：交给密码页处理。 */
        fun onParent()

        fun onLog(ev: String, vararg pairs: Pair<String, Any?>)
    }

    private var root: View? = null
    private var session: QuizSession? = null
    private var pausedPkg: String? = null
    private var inputLocked = false
    private val handler = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = root != null

    private val optionIds = intArrayOf(R.id.opt0, R.id.opt1, R.id.opt2, R.id.opt3)
    private val textIds = intArrayOf(R.id.tvOpt0, R.id.tvOpt1, R.id.tvOpt2, R.id.tvOpt3)
    private val badgeIds = intArrayOf(R.id.tvBadge0, R.id.tvBadge1, R.id.tvBadge2, R.id.tvBadge3)

    // ===== 对外 =====

    fun show(pkg: String?) {
        if (root != null) return
        pausedPkg = pkg
        session = QuizSession.start(service)
        attach()
    }

    fun dismiss() {
        stopHeartbeat()
        val v = root ?: return
        root = null
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.e(TAG, "removeView quiz failed: ${e.message}")
        }
        session = null
        pausedPkg = null
        inputLocked = false
    }

    // ===== 内部 =====

    private fun attach() {
        val view = try {
            LayoutInflater.from(service).inflate(R.layout.view_quiz_overlay, null)
        } catch (e: Exception) {
            Log.e(TAG, "inflate quiz overlay failed: ${e.message}")
            callback.onLog("quiz_fail", "why" to "inflate")
            return
        }

        bindKeys(view)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView quiz failed: ${e.message}")
            callback.onLog("quiz_fail", "why" to "addview", "msg" to e.message)
            return
        }

        root = view
        render()
        scheduleFocus()
        startHeartbeat()
    }

    /**
     * 窗口刚 addView 时还没有 window focus，这时候 requestFocus() 会「成功但立刻被清掉」，
     * 表现是题面出来了却没有任何高亮，孩子按一下方向键才看到光标（以为题目坏了）。
     * 所以在几个时间点各补一次，谁先成功算谁。
     */
    private fun scheduleFocus() {
        for (delay in longArrayOf(0L, 80L, 200L, 420L, 800L, 1400L)) {
            handler.postDelayed({
                val v = root ?: return@postDelayed
                if (v.findFocus() == null) {
                    val ok = TvFocus.focus(firstOption())
                    if (delay == 800L || !ok) {
                        Log.w(TAG, "quiz focus retry@${delay}ms ok=$ok")
                    }
                }
            }, delay)
        }
    }

    private fun bindKeys(view: View) {
        for (i in optionIds.indices) {
            val container = view.findViewById<View>(optionIds[i])
            container.setOnClickListener { answer(i) }
            // 焦点停在选项上时按返回：事件会先给选项，它不消费就会往上冒。
            // 这里也装一份，避免某些机型上 BACK 冒不到根布局。
            container.setOnKeyListener { _, keyCode, event -> handleExitKey(keyCode, event) }
        }

        view.findViewById<View>(R.id.btnQuizParent).setOnClickListener {
            callback.onLog("quiz_parent", "s" to (session?.remaining() ?: -1))
            dismiss()
            callback.onParent()
        }

        // 「不看了」必须能用方向键走到 —— 返回键在悬浮窗上不一定收得到
        view.findViewById<View>(R.id.btnQuizQuit).setOnClickListener { quit() }

        // 遥控器兜底：焦点万一落在根布局上，方向键没有落点、OK 没人接，
        // 孩子就会「看着题目按什么都没反应」。—— 和拦截页同一套处理。
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (handleExitKey(keyCode, event)) {
                true
            } else if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    TvFocus.focus(firstOption())
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    firstOption()?.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /** 返回 / 主页这类「退出」键，选项和根布局都要接。 */
    private fun handleExitKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_MENU -> {
                quit()
                true
            }
            KeyEvent.KEYCODE_HOME -> true
            else -> false
        }
    }

    private fun quit() {
        callback.onLog("quiz_abandon", "remain" to (session?.remaining() ?: -1))
        dismiss()
        callback.onAbandon()
    }

    private fun firstOption(): View? {
        val v = root ?: return null
        val s = session ?: return null
        val q = s.current() ?: return null
        return v.findViewById<View>(optionIds[0]).takeIf { q.options.isNotEmpty() }
    }

    private fun render() {
        val v = root ?: return
        val s = session ?: return
        val q = s.current() ?: return

        v.findViewById<TextView>(R.id.tvQuizSubject).text =
            "${q.subjectLabel()} · ${gradeText(q.grade)}"
        v.findViewById<TextView>(R.id.tvQuizRemain).text =
            "还剩 ${s.remaining()} 题 · 答错会加题"
        v.findViewById<TextView>(R.id.tvQuizStem).text = q.stem

        val feedback = v.findViewById<TextView>(R.id.tvQuizFeedback)
        feedback.text = ""
        feedback.setTextColor(ContextCompat.getColor(service, R.color.coral))

        for (i in optionIds.indices) {
            val container = v.findViewById<View>(optionIds[i])
            val text = v.findViewById<TextView>(textIds[i])
            val badge = v.findViewById<TextView>(badgeIds[i])
            if (i < q.options.size) {
                container.visibility = View.VISIBLE
                container.isEnabled = true
                container.setBackgroundResource(R.drawable.bg_quiz_option)
                container.translationX = 0f
                text.text = q.options[i]
                badge.text = ('A' + i).toString()
            } else {
                container.visibility = View.GONE
            }
        }

        // 判断题只有上面一行：把「向下」直接接到按钮，
        // 否则方向键会去找一个已经 GONE 的选项，遥控器像卡住一样。
        val bottomRowVisible = q.options.size > 2
        v.findViewById<View>(R.id.opt0).nextFocusDownId =
            if (bottomRowVisible) R.id.opt2 else R.id.btnQuizParent
        v.findViewById<View>(R.id.opt1).nextFocusDownId =
            if (bottomRowVisible) R.id.opt3 else R.id.btnQuizQuit

        // 判断题只有两个选项：上面一排撑满，下面一排隐藏
        v.findViewById<View>(R.id.tvQuizStem).post { TvFocus.focus(firstOption()) }
    }

    private fun answer(index: Int) {
        if (inputLocked) return
        val s = session ?: return
        val q = s.current() ?: return
        if (index !in q.options.indices) return

        val outcome = s.submit(service, index)
        when (outcome) {
            is QuizSession.Outcome.Finished -> {
                flashCorrect(index)
                inputLocked = true
                callback.onLog(
                    "quiz_pass", "subj" to q.subject, "kn" to q.knowledge,
                    "pkg" to pausedPkg
                )
                handler.postDelayed({
                    val pkg = pausedPkg
                    dismiss()
                    callback.onPassed(pkg)
                }, 1100)
            }

            is QuizSession.Outcome.Correct -> {
                flashCorrect(index)
                inputLocked = true
                showFeedback("答对了 · 还剩 ${s.remaining()} 题", correct = true)
                handler.postDelayed({
                    inputLocked = false
                    render()
                }, 850)
            }

            is QuizSession.Outcome.Wrong -> {
                flashWrong(index)
                inputLocked = true
                val msg = if (outcome.reveal) {
                    "正确答案是 ${optionLetter(q)}：${outcome.correctText} · 再加 ${outcome.added} 道"
                } else {
                    "再想想 · 这次要加 ${outcome.added} 道题"
                }
                showFeedback(msg, correct = false)
                callback.onLog(
                    "quiz_wrong", "subj" to q.subject, "kn" to q.knowledge,
                    "chose" to index, "ans" to q.answer,
                    "n" to outcome.added, "reveal" to outcome.reveal
                )
                handler.postDelayed({
                    inputLocked = false
                    render()
                    showFeedback(msg, correct = false)
                }, 1100)
            }
        }
    }

    private fun optionLetter(q: QuizQuestion): String {
        val i = q.answer
        return if (i in 0..3) ('A' + i).toString() else "?"
    }

    private fun showFeedback(text: String, correct: Boolean) {
        val v = root?.findViewById<TextView>(R.id.tvQuizFeedback) ?: return
        v.text = text
        v.setTextColor(
            ContextCompat.getColor(
                service,
                if (correct) R.color.mint else R.color.coral
            )
        )
    }

    private fun flashCorrect(index: Int) {
        val v = root ?: return
        v.findViewById<View>(optionIds[index])
            .setBackgroundResource(R.drawable.bg_quiz_option_correct)
    }

    private fun flashWrong(index: Int) {
        val v = root ?: return
        val container = v.findViewById<View>(optionIds[index])
        container.setBackgroundResource(R.drawable.bg_quiz_option_wrong)
        // 抖一下：3 米外也能看出「这题答错了」
        val shake = android.animation.ObjectAnimator.ofFloat(
            container, "translationX", 0f, -16f, 14f, -10f, 8f, -4f, 0f
        )
        shake.duration = 340
        shake.start()
    }

    private fun gradeText(grade: Int): String = when (grade) {
        1 -> "一年级"
        2 -> "二年级"
        3 -> "三年级"
        4 -> "四年级"
        5 -> "五年级"
        6 -> "六年级"
        7 -> "初一"
        8 -> "初二"
        9 -> "初三"
        else -> "$grade 年级"
    }

    // ===== 心跳：窗口被系统摘掉就重挂 =====

    private val heartbeat = object : Runnable {
        override fun run() {
            val v = root
            if (v == null) return
            if (v.isAttachedToWindow != true) {
                Log.w(TAG, "Quiz overlay detached, re-adding")
                root = null
                stopHeartbeat()
                attach()
                return
            }
            if (!v.hasWindowFocus()) {
                try {
                    windowManager.updateViewLayout(v, v.layoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "quiz updateViewLayout failed: ${e.message}")
                }
                if (v.findFocus() == null) TvFocus.focus(firstOption())
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 1000)
    }

    private fun stopHeartbeat() {
        handler.removeCallbacks(heartbeat)
    }

    companion object {
        private const val TAG = "TvLimit"
    }
}
