package com.example.tvlimit.quiz

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject
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
    private val loadLock = Any()

    fun all(ctx: Context): List<QuizQuestion> {
        cache?.let { return it }
        synchronized(loadLock) {
            cache?.let { return it }
            val loaded = try {
                loadFromAssets(ctx)
            } catch (e: Exception) {
                emptyList()
            }
            cache = loaded
            return loaded
        }
    }

    private fun loadFromAssets(ctx: Context): List<QuizQuestion> {
        val text = ctx.assets.open("quiz/bank.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.optJSONArray("questions") ?: return emptyList()
        val list = ArrayList<QuizQuestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val optionsArr = o.optJSONArray("options") ?: continue
            val options = ArrayList<String>(optionsArr.length())
            for (j in 0 until optionsArr.length()) options.add(optionsArr.optString(j))
            if (options.size < 2) continue
            val answer = o.optInt("answer", -1)
            if (answer !in options.indices) continue
            list.add(
                QuizQuestion(
                    id = o.optString("id", "q-$i"),
                    subject = o.optString("subject", "chinese"),
                    grade = o.optInt("grade", 2),
                    knowledge = o.optString("knowledge", "综合"),
                    stem = o.optString("stem", ""),
                    options = options,
                    answer = answer,
                    explain = o.optString("explain", "").ifBlank { null },
                    difficulty = o.optInt("difficulty", 1)
                )
            )
        }
        return list
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
        val pool = when {
            same.isNotEmpty() -> same
            near.isNotEmpty() -> near
            else -> all
        }
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
        return QuestionBank.pick(ctx, grade, subjects, rand)
            ?: MathQuestionGenerator.generate(grade, rand)
    }
}
