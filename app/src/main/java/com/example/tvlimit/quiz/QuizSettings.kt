package com.example.tvlimit.quiz

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 答题门禁的家长参数 + 运行态。
 *
 * 为什么不放进 Room 的 Profile 表：加一列就要升数据库版本，现有 `fallbackToDestructiveMigration`
 * 会把孩子的历史用量一起清掉。这些参数本来也是「整机一份」，不是「每个档案一份」，
 * 放 SharedPreferences 更合适，也不影响老版本的数据库。
 */
object QuizSettings {

    private const val PREF_ENABLED = "quiz_enabled"
    private const val PREF_INTERVAL = "quiz_interval_minutes"
    private const val PREF_COUNT = "quiz_questions_per_session"
    private const val PREF_GRADE = "quiz_grade"
    private const val PREF_SUBJECTS = "quiz_subjects"
    /** 默认值迁移标记，见 [migrateDefaults]。 */
    private const val PREF_SCHEMA = "quiz_settings_schema"

    private const val SCHEMA_CURRENT = 2

    /** 间隔默认 10 分钟：20 分钟比单次时长还长，永远轮不到弹题（2.7.6）。 */
    const val DEFAULT_INTERVAL_MINUTES = 10
    const val DEFAULT_QUESTIONS = 3

    private val INTERVAL_RANGE = 3..60
    private val COUNT_RANGE = 1..10

    /** 自上次答题以来累计的观看时长（毫秒）。只在「真的在看」时增长。 */
    private const val PREF_SEG_MS = "quiz_seg_ms"
    /** 有题没答完：断电/强杀后靠它立刻重弹。 */
    private const val PREF_PENDING = "quiz_pending"
    /** 被暂停的应用（答完要回去）。 */
    private const val PREF_PENDING_PKG = "quiz_pending_pkg"

    private fun sp(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(PREF_ENABLED, on).apply()
        if (!on) resetRuntime(ctx)
    }

    /** 每看多少分钟弹一组题。（旧版本叫「每看多少分钟弹一道题」） */
    fun intervalMinutes(ctx: Context): Int =
        sp(ctx).getInt(PREF_INTERVAL, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(INTERVAL_RANGE.first, INTERVAL_RANGE.last)

    fun setIntervalMinutes(ctx: Context, m: Int) {
        sp(ctx).edit().putInt(PREF_INTERVAL, m.coerceIn(INTERVAL_RANGE.first, INTERVAL_RANGE.last))
            .apply()
    }

    /** 一次弹几道题。答错追加的题不算在这里。 */
    fun questionsPerSession(ctx: Context): Int =
        sp(ctx).getInt(PREF_COUNT, DEFAULT_QUESTIONS)
            .coerceIn(COUNT_RANGE.first, COUNT_RANGE.last)

    fun setQuestionsPerSession(ctx: Context, n: Int) {
        sp(ctx).edit().putInt(PREF_COUNT, n.coerceIn(COUNT_RANGE.first, COUNT_RANGE.last)).apply()
    }

    /**
     * 老版本升级时把「旧的默认值」搬成新的默认值。
     *
     * 为什么需要这一步：SharedPreferences 里已经存了 interval=20，
     * 光改 `getInt` 的默认值对老用户完全不生效 —— 他们仍然 20 分钟才弹一次，
     * 而且 20 分刚好等于单次时长，`isLimitReached()` 会先命中，题目永远弹不出来。
     *
     * 只迁移「还停在上一个默认值」的情况：家长自己把间隔设成过 15/30 的不动。
     */
    fun migrateDefaults(ctx: Context) {
        val prefs = sp(ctx)
        if (prefs.getInt(PREF_SCHEMA, 1) >= SCHEMA_CURRENT) return
        val e = prefs.edit()
        if (!prefs.contains(PREF_INTERVAL) || prefs.getInt(PREF_INTERVAL, -1) == 20) {
            e.putInt(PREF_INTERVAL, DEFAULT_INTERVAL_MINUTES)
        }
        if (!prefs.contains(PREF_COUNT)) {
            e.putInt(PREF_COUNT, DEFAULT_QUESTIONS)
        }
        e.putInt(PREF_SCHEMA, SCHEMA_CURRENT)
        e.apply()
    }

    fun grade(ctx: Context): Int = sp(ctx).getInt(PREF_GRADE, 2).coerceIn(1, 9)

    fun setGrade(ctx: Context, g: Int) {
        sp(ctx).edit().putInt(PREF_GRADE, g.coerceIn(1, 9)).apply()
    }

    fun subjects(ctx: Context): Set<String> {
        val raw = sp(ctx).getStringSet(PREF_SUBJECTS, null) ?: return DEFAULT_SUBJECTS
        // 空集合没有意义（一道题都出不来），当默认处理
        return if (raw.isEmpty()) DEFAULT_SUBJECTS else raw
    }

    fun setSubjects(ctx: Context, subjects: Set<String>) {
        sp(ctx).edit().putStringSet(PREF_SUBJECTS, subjects.toSet()).apply()
    }

    /** 全部可选科目（题库里出现过的 + 数学生成器）。 */
    val ALL_SUBJECTS = listOf(
        "math", "chinese", "english", "science", "physics",
        "chemistry", "biology", "history", "geography", "politics"
    )

    private val DEFAULT_SUBJECTS = setOf("math", "chinese", "english")

    // ===== 运行态 =====

    fun segMs(ctx: Context): Long = sp(ctx).getLong(PREF_SEG_MS, 0L)

    fun addSegMs(ctx: Context, delta: Long): Long {
        val v = (segMs(ctx) + delta).coerceAtLeast(0L)
        sp(ctx).edit().putLong(PREF_SEG_MS, v).apply()
        return v
    }

    fun resetSeg(ctx: Context) {
        sp(ctx).edit().putLong(PREF_SEG_MS, 0L).apply()
    }

    fun isPending(ctx: Context): Boolean = sp(ctx).getBoolean(PREF_PENDING, false)

    fun pendingPkg(ctx: Context): String? = sp(ctx).getString(PREF_PENDING_PKG, null)

    fun setPending(ctx: Context, pending: Boolean, pkg: String? = null) {
        sp(ctx).edit()
            .putBoolean(PREF_PENDING, pending)
            .putString(PREF_PENDING_PKG, pkg)
            .apply()
    }

    fun resetRuntime(ctx: Context) {
        sp(ctx).edit()
            .putLong(PREF_SEG_MS, 0L)
            .putBoolean(PREF_PENDING, false)
            .putString(PREF_PENDING_PKG, null)
            .apply()
    }
}
