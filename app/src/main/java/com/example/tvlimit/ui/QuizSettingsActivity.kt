package com.example.tvlimit.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tvlimit.R
import com.example.tvlimit.quiz.QuizSettings
import com.example.tvlimit.quiz.QuizStats
import com.example.tvlimit.service.TimeTrackingService

/**
 * 家长设置：答题门禁。
 *
 * 参数全部落在 SharedPreferences（见 QuizSettings），不进 Profile 表 ——
 * 加一列就要升数据库版本，现有的破坏性迁移会把孩子的历史用量一起清掉。
 */
class QuizSettingsActivity : AppCompatActivity() {

    private lateinit var tvSwitchValue: TextView
    private lateinit var tvIntervalValue: TextView
    private lateinit var tvCountValue: TextView
    private lateinit var tvGradeValue: TextView
    private lateinit var tvSubjectsValue: TextView
    private lateinit var tvMistakes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_settings)

        // 幂等：服务没起来的时候家长先打开这个页面，也能看到新默认值
        QuizSettings.migrateDefaults(this)

        tvSwitchValue = findViewById(R.id.tvQuizSwitchValue)
        tvIntervalValue = findViewById(R.id.tvQuizIntervalValue)
        tvCountValue = findViewById(R.id.tvQuizCountValue)
        tvGradeValue = findViewById(R.id.tvQuizGradeValue)
        tvSubjectsValue = findViewById(R.id.tvQuizSubjectsValue)
        tvMistakes = findViewById(R.id.tvQuizMistakes)

        val cardSwitch = findViewById<View>(R.id.cardQuizSwitch)
        val cardInterval = findViewById<View>(R.id.cardQuizInterval)
        val cardCount = findViewById<View>(R.id.cardQuizCount)
        val cardGrade = findViewById<View>(R.id.cardQuizGrade)
        val cardSubjects = findViewById<View>(R.id.cardQuizSubjects)
        val btnClear = findViewById<View>(R.id.btnQuizClearMistakes)
        val btnPreview = findViewById<View>(R.id.btnQuizPreview)

        TvFocus.attach(cardSwitch, cardInterval, cardCount, cardGrade, cardSubjects, btnClear, btnPreview)

        cardSwitch.setOnClickListener {
            val on = !QuizSettings.enabled(this)
            QuizSettings.setEnabled(this, on)
            updateUi()
            toast(
                if (on) "已开启：每看 ${QuizSettings.intervalMinutes(this)} 分钟" +
                    "答 ${QuizSettings.questionsPerSession(this)} 道题"
                else "已关闭答题"
            )
        }
        cardInterval.setOnClickListener { showIntervalDialog() }
        cardCount.setOnClickListener { showCountDialog() }
        cardGrade.setOnClickListener { showGradeDialog() }
        cardSubjects.setOnClickListener { showSubjectsDialog() }
        btnClear.setOnClickListener {
            QuizStats.clear(this)
            updateUi()
            toast("错题记录已清空")
        }
        btnPreview.setOnClickListener {
            // 直接让服务立刻弹一组：家长不用真的等一个间隔才知道孩子会看到什么
            sendBroadcast(
                Intent(TimeTrackingService.ACTION_QUIZ_TEST).setPackage(packageName)
            )
            toast("马上会弹出一组题，答完或点「不看了」就退出")
        }

        cardSwitch.post { TvFocus.focus(cardSwitch) }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val on = QuizSettings.enabled(this)
        tvSwitchValue.text = if (on) "已开启" else "已关闭"
        tvSwitchValue.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.mint else R.color.coral)
        )
        tvIntervalValue.text = "${QuizSettings.intervalMinutes(this)} 分钟"
        tvCountValue.text = "${QuizSettings.questionsPerSession(this)} 道"
        tvGradeValue.text = gradeLabel(QuizSettings.grade(this))
        tvSubjectsValue.text = QuizSettings.subjects(this)
            .map { subjectLabel(it) }
            .joinToString("、")

        val wrong = QuizStats.wrongKnowledgeCount(this)
        tvMistakes.text = if (wrong == 0) {
            "错题记录：还没有错题"
        } else {
            "错题记录：$wrong 个知识点错过，这些题会更常出现"
        }
    }

    private fun showIntervalDialog() {
        val values = listOf(5, 10, 15, 20, 30, 45)
        val options = values.map { m ->
            OptionDialog.Option(
                "$m 分钟",
                if (m == QuizSettings.intervalMinutes(this)) "当前设置" else ""
            )
        }
        OptionDialog(this, "每隔多久弹一组题", options) { picked ->
            val idx = options.indexOf(picked)
            if (idx in values.indices) {
                QuizSettings.setIntervalMinutes(this, values[idx])
                updateUi()
                toast("已改成每看 ${values[idx]} 分钟答一组题")
            }
        }.show()
    }

    private fun showCountDialog() {
        val values = listOf(1, 2, 3, 4, 5, 6, 8, 10)
        val cur = QuizSettings.questionsPerSession(this)
        val options = values.map { n ->
            OptionDialog.Option(
                "$n 道",
                when {
                    n == cur -> "当前设置"
                    n <= 2 -> "一组题很快答完，打断感最轻"
                    n <= 4 -> "适合小学低年级"
                    else -> "题量偏大，答错的追加题会更多"
                }
            )
        }
        OptionDialog(this, "每次答几道题", options) { picked ->
            val idx = options.indexOf(picked)
            if (idx in values.indices) {
                QuizSettings.setQuestionsPerSession(this, values[idx])
                updateUi()
            }
        }.show()
    }

    private fun showGradeDialog() {
        val options = (1..9).map { g -> OptionDialog.Option(gradeLabel(g), "") }
        OptionDialog(this, "按哪个年级出题", options) { picked ->
            val idx = options.indexOf(picked)
            if (idx >= 0) {
                QuizSettings.setGrade(this, idx + 1)
                updateUi()
            }
        }.show()
    }

    /**
     * 多选科目。
     *
     * 每点一个科目就关掉弹窗、再开一个新的，把勾选状态带出来 ——
     * 这样不用做一个新的多选控件，遥控器操作路径也和单选完全一样。
     */
    private fun showSubjectsDialog() {
        val subs = QuizSettings.subjects(this).toMutableSet()
        val subjects = QuizSettings.ALL_SUBJECTS
        val options = subjects.map { s ->
            val on = s in subs
            OptionDialog.Option(
                (if (on) "✓ " else "○ ") + subjectLabel(s),
                if (s == "math") "程序生成，题目不会重复" else "来自内置题库"
            )
        } + OptionDialog.Option("选好了", "关闭这个窗口")

        OptionDialog(this, "出题科目（可多选）", options) { picked ->
            val idx = options.indexOf(picked)
            if (idx in subjects.indices) {
                val s = subjects[idx]
                if (s in subs) {
                    if (subs.size <= 1) {
                        toast("至少留一科")
                    } else {
                        subs.remove(s)
                        QuizSettings.setSubjects(this, subs)
                    }
                } else {
                    subs.add(s)
                    QuizSettings.setSubjects(this, subs)
                }
                updateUi()
                showSubjectsDialog()
            }
        }.show()
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

    private fun gradeLabel(g: Int): String = when (g) {
        1 -> "一年级"
        2 -> "二年级"
        3 -> "三年级"
        4 -> "四年级"
        5 -> "五年级"
        6 -> "六年级"
        7 -> "初一"
        8 -> "初二"
        9 -> "初三"
        else -> "$g 年级"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
