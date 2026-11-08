package com.example.tvlimit.data

import android.content.Context
import com.example.tvlimit.quiz.QuizStats
import com.example.tvlimit.quiz.WrongStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 用量报表：把 Room 里的每日分钟数、错题本、事件日志拼成一张「这周怎么样」的卡片。
 *
 * 数据来源三处，各管一段：
 * - usage_logs：每天看了多少分钟（唯一的权威来源，追踪服务每次 tick 都写）；
 * - WrongStore：错在哪几道题；
 * - EventLog（JSONL 文件）：答题通过/答错/被拦的次数 —— 这些是"发生了一次"的瞬时事件，
 *   Room 里没有表，也犯不上为它加一张表（每次都写库，电视的 eMMC 受不了）。
 *
 * 事件统计要扫日志文件，所以做了 2 分钟缓存：家长在手机上连点几个页签，
 * 不该每次都把 20MB 日志读一遍。
 */
object UsageReport {

    private val dayFmt = DateTimeFormatter.BASIC_ISO_DATE

    data class DayStat(val date: String, val minutes: Int)

    /**
     * 事件计数缓存：**按天数分开存**。
     * 原来只有一个槽位、命中条件是 `cached.days >= days`，
     * 于是先看 30 天再切 7 天，7 天报表上还挂着 30 天的次数，家长一眼就能看出不对。
     */
    private val eventCache = HashMap<Int, Pair<Long, Map<String, Int>>>()

    suspend fun daily(ctx: Context, profileId: Int, days: Int): List<DayStat> {
        val n = days.coerceIn(1, 90)
        val from = LocalDate.now().minusDays((n - 1).toLong()).toString()
        val rows = AppDatabase.getDatabase(ctx.applicationContext)
            .profileDao().getUsageLogsSince(profileId, from)
        val byDate = rows.associate { it.date to it.totalUsageMinutes }
        val out = ArrayList<DayStat>(n)
        for (i in n - 1 downTo 0) {
            val d = LocalDate.now().minusDays(i.toLong()).toString()
            out.add(DayStat(d, byDate[d] ?: 0))
        }
        return out
    }

    /**
     * 学习周报。
     *
     * @param days 统计天数，默认 7（本周滚动 7 天，不是自然周 —— 家长周日晚上打开时，
     *             「本周一到现在」只有 1 天数据，看着像孩子没看电视）。
     */
    suspend fun report(ctx: Context, profileId: Int, days: Int = 7): JSONObject {
        val n = days.coerceIn(1, 90)
        val today = daily(ctx, profileId, n)
        val total = today.sumOf { it.minutes }
        val maxDay = today.maxByOrNull { it.minutes }
        val activeDays = today.count { it.minutes > 0 }

        val prevFrom = LocalDate.now().minusDays((n * 2 - 1).toLong()).toString()
        val prevTo = LocalDate.now().minusDays(n.toLong()).toString()
        val prevRows = AppDatabase.getDatabase(ctx.applicationContext)
            .profileDao().getUsageLogsSince(profileId, prevFrom)
            .filter { it.date in prevFrom..prevTo }
        val prevTotal = prevRows.sumOf { it.totalUsageMinutes }

        val ev = eventCounts(ctx, n)
        val pass = ev["quiz_pass"] ?: 0
        val wrong = ev["quiz_wrong"] ?: 0
        val answered = pass + wrong
        val blocks = (ev["block"] ?: 0) + (ev["limit"] ?: 0)

        val daysJson = JSONArray()
        today.forEach {
            daysJson.put(JSONObject().put("date", it.date).put("minutes", it.minutes))
        }

        val wrongItems = JSONArray()
        WrongStore.ranked(ctx).take(10).forEach { wrongItems.put(it.toJson()) }

        return JSONObject()
            .put("days", n)
            .put("daily", daysJson)
            .put("total", total)
            .put("avg", if (activeDays > 0) total / activeDays else 0)
            .put("activeDays", activeDays)
            .put("maxDay", maxDay?.date ?: "")
            .put("maxMinutes", maxDay?.minutes ?: 0)
            .put("prevTotal", prevTotal)
            .put("trend", total - prevTotal)
            .put(
                "quiz", JSONObject()
                    .put("pass", pass)
                    .put("wrong", wrong)
                    .put("answered", answered)
                    .put("accuracy", if (answered > 0) Math.round(pass * 1000.0 / answered) / 10.0 else 0.0)
                    .put("wrongKnowledge", QuizStats.wrongKnowledgeCount(ctx))
            )
            .put("blocks", blocks)
            .put("topWrong", wrongItems)
    }

    /** 扫事件日志数次数。只认 ev 字段，用子串匹配比解析 JSON 快一个数量级。 */
    private fun eventCounts(ctx: Context, days: Int): Map<String, Int> {
        val now = System.currentTimeMillis()
        synchronized(eventCache) {
            eventCache[days]?.let { (at, counts) ->
                if (now - at < 120_000L) return counts
            }
        }

        val names = listOf("quiz_pass", "quiz_wrong", "block", "limit", "quiz_abandon")
        val out = names.associateWith { 0 }.toMutableMap()
        val dir = File(ctx.applicationContext.filesDir, "logs")
        if (dir.isDirectory) {
            val from = LocalDate.now().minusDays((days - 1).toLong()).format(dayFmt)
            dir.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".jsonl") }
                ?.forEach { f ->
                    val day = f.name.removePrefix("events-").removeSuffix(".jsonl")
                    if (day < from) return@forEach
                    try {
                        f.forEachLine { line ->
                            names.forEach { n ->
                                if (line.contains("\"ev\":\"$n\"")) {
                                    out[n] = (out[n] ?: 0) + 1
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
        }
        synchronized(eventCache) {
            if (eventCache.size > 6) eventCache.clear()
            eventCache[days] = now to out
        }
        return out
    }

    /** 「3 天前」这种人话，列表里比 2026-11-05 好读。 */
    fun humanDay(date: String): String {
        return try {
            val d = LocalDate.parse(date)
            val diff = ChronoUnit.DAYS.between(d, LocalDate.now())
            when (diff) {
                0L -> "今天"
                1L -> "昨天"
                else -> "$diff 天前"
            }
        } catch (e: Exception) {
            date
        }
    }
}
