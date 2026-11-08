package com.example.tvlimit.quiz

import android.content.Context
import androidx.preference.PreferenceManager
import kotlin.random.Random

/**
 * 题库 + 错题权重 + 抽题。
 *
 * 题库是一个打包在 assets 里的 JSON（`assets/quiz/bank.json`），
 * 格式见 docs/电视时间管理-v2-设计方案.md §4.5。只收自编题，避免版权问题。
 */
object QuestionBank {

    @Volatile
    private var cache: List<QuizQuestion>? = null

    /** 只含 assets 里的内置题，用于网页上分开显示「内置 / 自己加的」。 */
    @Volatile
    private var builtinCache: List<QuizQuestion>? = null
    private val loadLock = Any()

    fun builtin(ctx: Context): List<QuizQuestion> {
        builtinCache?.let { return it }
        synchronized(loadLock) {
            builtinCache?.let { return it }
            val list = try {
                loadFromAssets(ctx)
            } catch (e: Exception) {
                emptyList()
            }
            builtinCache = list
            return list
        }
    }

    fun all(ctx: Context): List<QuizQuestion> {
        cache?.let { return it }
        synchronized(loadLock) {
            cache?.let { return it }
            val builtin = builtin(ctx)
            // 家长从手机网页导入的题排在内置题之后、按 id 覆盖，
            // 这样「改掉一道内置题」也能靠导入完成，不用重新打包 APK。
            val custom = BankStore.loadCustom(ctx)
            val merged = if (custom.isEmpty()) builtin else {
                val map = LinkedHashMap<String, QuizQuestion>(builtin.size + custom.size)
                builtin.forEach { map[it.id] = it }
                custom.forEach { map[it.id] = it }
                map.values.toList()
            }
            cache = merged
            return merged
        }
    }

    /** 题库文件变了（导入/清空）之后必须调用，否则还抽旧题。 */
    fun invalidate() {
        synchronized(loadLock) { cache = null }
    }

    private fun loadFromAssets(ctx: Context): List<QuizQuestion> {
        val text = ctx.assets.open("quiz/bank.json").bufferedReader().use { it.readText() }
        // 解析与校验跟家长导入共用一套，避免两处规则不一致
        return BankStore.parse(text, limitErrors = 0).first
    }

    /**
     * 按年级和科目抽一道题。
     *
     * 年级先取 ±1，抽不到再放宽到整个小学段 —— 宁可出一道稍难/稍简单的，
     * 也不要因为题库还没铺满就「弹不出题」。
     */
    fun pick(ctx: Context, grade: Int, subjects: Set<String>, rand: Random = Random.Default): QuizQuestion? {
        val all = all(ctx).filter { it.subject in subjects }
        if (all.isEmpty()) return null
        // 优先同年级；同年级没题了才放宽到 ±1，再没有就全段随机。
        // 家长设了二年级却抽到三年级题，看着就像设置没生效。
        val same = all.filter { it.grade == grade }
        val near = all.filter { it.grade in (grade - 1)..(grade + 1) }
        val base = when {
            same.isNotEmpty() -> same
            near.isNotEmpty() -> near
            else -> all
        }
        // 再避开最近出过的题：题库再大，随机抽也会「刚问过又问」，
        // 孩子会觉得程序在糊弄他。剩下的新题不足 10 道时才允许重复。
        val recent = QuizStats.recentIds(ctx)
        val fresh = base.filter { it.id !in recent }
        val pool = if (fresh.size >= 10) fresh else base
        return weightedPick(ctx, pool, rand)
    }

    private fun weightedPick(ctx: Context, pool: List<QuizQuestion>, rand: Random): QuizQuestion? {
        if (pool.isEmpty()) return null
        val weights = pool.map { QuizStats.weight(ctx, it) }
        val total = weights.sum()
        if (total <= 0) return pool.random(rand)
        var r = rand.nextInt(total)
        for (i in pool.indices) {
            r -= weights[i]
            if (r < 0) return pool[i]
        }
        return pool.last()
    }
}

/**
 * 错题本（极简版）：按「科目 + 知识点」记错几次、连对几次。
 *
 * 文章里说「AI 判断孩子是否真正掌握」，本质就是这个权重：
 * 错得多的知识点下次更容易被抽到，连对两次就降回正常。
 * 不需要模型，也不该为了这四个字把 APK 做成 200MB。
 */
object QuizStats {

    private const val PREF = "quiz_mistakes"
    private const val CLEAR_AT = "cleared_at"
    private const val PREF_RECENT = "quiz_recent_ids"
    /** 记住最近这么多道题不重复。按每天 24 道非数学题算，够撑 20 多天。 */
    private const val RECENT_MAX = 600

    private fun sp(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun key(q: QuizQuestion) = "${q.subject}|${q.knowledge}"

    fun weight(ctx: Context, q: QuizQuestion): Int {
        val (wrong, right) = read(ctx, key(q))
        // 基础权重 1；每错一次 +2；连对两次以上降权（最低 1）
        return (1 + wrong * 2 - (right / 2)).coerceAtLeast(1)
    }

    fun onWrong(ctx: Context, q: QuizQuestion) {
        val (wrong, _) = read(ctx, key(q))
        write(ctx, key(q), wrong + 1, 0)
    }

    fun onRight(ctx: Context, q: QuizQuestion) {
        val (wrong, right) = read(ctx, key(q))
        // 答对不再增加错题计数，但记连对，用来降权
        write(ctx, key(q), wrong, right + 1)
    }

    private fun read(ctx: Context, key: String): Pair<Int, Int> {
        val raw = sp(ctx).getString(key, null) ?: return 0 to 0
        val parts = raw.split(",")
        if (parts.size != 2) return 0 to 0
        return (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
    }

    private fun write(ctx: Context, key: String, wrong: Int, right: Int) {
        sp(ctx).edit().putString(key, "$wrong,$right").apply()
    }

    /** 错过的知识点数量，管理页直接显示。 */
    fun wrongKnowledgeCount(ctx: Context): Int {
        return sp(ctx).all.count { (k, v) ->
            k != CLEAR_AT && v is String && (v.split(",").firstOrNull()?.toIntOrNull() ?: 0) > 0
        }
    }

    /** 最近出过的题（新→旧），用于避免短时间内重复出同一道。 */
    fun recentIds(ctx: Context): Set<String> {
        val raw = sp(ctx).getString(PREF_RECENT, null) ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split('\n').toHashSet()
    }

    fun markSeen(ctx: Context, id: String) {
        if (id.isEmpty()) return
        val list = (sp(ctx).getString(PREF_RECENT, null) ?: "")
            .split('\n').filter { it.isNotEmpty() }.toMutableList()
        if (list.lastOrNull() == id) return
        list.add(id)
        val trimmed = if (list.size > RECENT_MAX) list.subList(list.size - RECENT_MAX, list.size) else list
        sp(ctx).edit().putString(PREF_RECENT, trimmed.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) {
        val editor = sp(ctx).edit()
        sp(ctx).all.keys.filter { it != CLEAR_AT }.forEach { editor.remove(it) }
        editor.putLong(CLEAR_AT, System.currentTimeMillis()).apply()
    }
}

/** 出题入口：数学优先走生成器（变体无限），其他科目走题库。 */
object QuizEngine {

    fun next(ctx: Context, rand: Random = Random.Default): QuizQuestion {
        val grade = QuizSettings.grade(ctx)
        val subjects = QuizSettings.subjects(ctx)
        val mathEnabled = "math" in subjects
        // 数学占大头：程序生成、不会重复，也最适合「错一题变体再来」
        if (mathEnabled && (subjects.size == 1 || rand.nextInt(100) < 55)) {
            return MathQuestionGenerator.generate(grade, rand)
        }
        val fromBank = QuestionBank.pick(ctx, grade, subjects, rand)
        if (fromBank != null) {
            QuizStats.markSeen(ctx, fromBank.id)
            return fromBank
        }
        return MathQuestionGenerator.generate(grade, rand)
    }
}
