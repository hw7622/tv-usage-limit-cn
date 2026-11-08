package com.example.tvlimit.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.UsageReport
import com.example.tvlimit.quiz.WrongStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学习报告：最近 7 天看了多久、答得怎么样、错在哪。
 *
 * 电视上只做「一眼能看懂」的版本：一根根柱子 + 几行字 + 错题前 5 条。
 * 想看每天的明细和全部错题，手机扫码进网页 —— 那里屏幕大、能滚动、还能复制。
 *
 * 刻意不做图表库：一条柱状图不值得引 MPAndroidChart（它自己就 1MB+），
 * 用 View 的高度就是柱子的高度，够用且没有依赖。
 */
class StudyReportActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        load()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg_deep))
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        scroll.addView(root)
        return scroll
    }

    private fun text(
        s: String,
        size: Int,
        colorId: Int = R.color.text_main,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        setTextColor(color(colorId))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    private fun card(block: LinearLayout.() -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(24), dp(20), dp(24), dp(20))
            block()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        root.addView(box, lp)
        return box
    }

    private fun load() {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val name = prefs.getString("CURRENT_PROFILE_NAME", "儿童") ?: "儿童"
            val dao = AppDatabase.getDatabase(applicationContext).profileDao()
            val profile = withContext(Dispatchers.IO) { dao.getProfileByName(name) }
            if (profile == null) {
                root.addView(text("还没有选中档案", 18, R.color.text_sub))
                return@launch
            }
            val report = withContext(Dispatchers.IO) {
                UsageReport.report(applicationContext, profile.id, 7)
            }
            val wrongs = withContext(Dispatchers.IO) { WrongStore.ranked(applicationContext) }
            render(profile.name, report, wrongs)
        }
    }

    private fun render(
        profileName: String,
        report: org.json.JSONObject,
        wrongs: List<WrongStore.Entry>
    ) {
        root.addView(text("这一周", 34, R.color.text_main, bold = true))
        root.addView(
            text("$profileName · 近 7 天", 15, R.color.text_dim).apply {
                setPadding(0, dp(8), 0, dp(22))
            }
        )

        val total = report.optInt("total")
        val avg = report.optInt("avg")
        val trend = report.optInt("trend")
        val activeDays = report.optInt("activeDays")

        // ===== 柱状图 =====
        card {
            addView(text("每天看了多久", 15, R.color.text_sub, bold = true))
            val chart = LinearLayout(this@StudyReportActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(18), 0, 0)
            }
            val days = report.optJSONArray("daily")
            var max = 1
            if (days != null) for (i in 0 until days.length()) {
                max = maxOf(max, days.optJSONObject(i)?.optInt("minutes") ?: 0)
            }
            if (days != null) for (i in 0 until days.length()) {
                val d = days.optJSONObject(i) ?: continue
                val minutes = d.optInt("minutes")
                val date = d.optString("date")
                val col = LinearLayout(this@StudyReportActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                val h = if (minutes == 0) dp(3) else dp(6) + (dp(120) * minutes / max)
                val bar = View(this@StudyReportActivity).apply {
                    setBackgroundColor(
                        when {
                            minutes == 0 -> color(R.color.surface_hi)
                            minutes >= max * 0.85 -> color(R.color.mint)
                            else -> color(R.color.sky)
                        }
                    )
                }
                col.addView(bar, LinearLayout.LayoutParams(dp(26), h))
                col.addView(
                    text(date.takeLast(2) + "日", 11, R.color.text_dim).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                    }
                )
                chart.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(chart)
            addView(
                text(
                    if (trend > 0) "比上一周多 $trend 分钟" else if (trend < 0) "比上一周少 ${-trend} 分钟" else "和上一周持平",
                    13,
                    if (trend > 0) R.color.coral else R.color.text_dim
                ).apply { setPadding(0, dp(10), 0, 0) }
            )
        }

        // ===== 数字 =====
        card {
            addView(text("这 7 天", 15, R.color.text_sub, bold = true))
            addView(statRow("总时长", if (total >= 60) "${total / 60} 小时 ${total % 60} 分" else "$total 分钟"))
            addView(statRow("看了几天", "$activeDays 天"))
            addView(statRow("看的那几天平均", if (avg >= 60) "${avg / 60} 小时 ${avg % 60} 分" else "$avg 分钟"))
            addView(statRow("被拦住", "${report.optInt("blocks")} 次"))
        }

        // ===== 答题 =====
        val quiz = report.optJSONObject("quiz")
        if (quiz != null) {
            val answered = quiz.optInt("answered")
            val accuracy = quiz.optDouble("accuracy", 0.0)
            card {
                addView(text("答题", 15, R.color.text_sub, bold = true))
                addView(statRow("答对 / 答错", "${quiz.optInt("pass")} / ${quiz.optInt("wrong")}"))
                addView(
                    statRow(
                        "正确率",
                        if (answered > 0) "$accuracy%" else "还没有记录"
                    )
                )
                addView(statRow("错过的知识点", "${quiz.optInt("wrongKnowledge")} 个"))
            }
        }

        // ===== 错题 =====
        if (wrongs.isNotEmpty()) {
            card {
                addView(text("最该补的几道", 15, R.color.text_sub, bold = true))
                wrongs.take(5).forEach { w ->
                    addView(
                        text("${subjectLabel(w.subject)} · ${w.knowledge} · 错 ${w.wrong} 次", 14, R.color.sun)
                            .apply { setPadding(0, dp(14), 0, dp(4)) }
                    )
                    addView(text(w.stem, 16, R.color.text_main))
                    val ans = w.options.getOrNull(w.answer)?.let {
                        "正确答案：${'A' + w.answer}. $it"
                    } ?: ""
                    if (ans.isNotEmpty()) {
                        addView(
                            text(ans, 14, R.color.mint).apply { setPadding(0, dp(5), 0, 0) }
                        )
                    }
                }
            }
        }

        // ===== 返回 =====
        // 用 TextView 而不是 Button：AppCompat 的 backgroundTint 会在部分电视 ROM 上
        // 把 selector 描边整圈盖掉，就是「按钮边框显示不全」的来源。
        val btn = TextView(this).apply {
            text = "返回"
            gravity = Gravity.CENTER
            setTextColor(color(R.color.bg_deep))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setBackgroundResource(R.drawable.selector_btn_primary)
            setOnClickListener { finish() }
            isFocusable = true
        }
        root.addView(
            btn,
            LinearLayout.LayoutParams(dp(200), dp(58)).apply { topMargin = dp(8) }
        )
        btn.post { btn.requestFocus() }

        root.addView(
            text("更细的明细（每天的用量、全部错题）用手机扫码打开配置页看。", 13, R.color.text_dim)
                .apply { setPadding(0, dp(16), 0, 0) }
        )
    }

    private fun statRow(k: String, v: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(9))
        }
        row.addView(text(k, 15, R.color.text_sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(v, 15, R.color.text_main, bold = true))
        return row
    }

    private fun subjectLabel(s: String): String = when (s) {
        "math" -> "数学"
        "chinese" -> "语文"
        "english" -> "英语"
        "science" -> "科学"
        "physics" -> "物理"
        "chemistry" -> "化学"
        "biology" -> "生物"
        "history" -> "历史"
        "geography" -> "地理"
        "politics" -> "道法"
        else -> s
    }
}
