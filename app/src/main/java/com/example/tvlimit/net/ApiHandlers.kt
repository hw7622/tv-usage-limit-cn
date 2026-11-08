package com.example.tvlimit.net

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.HouseRules
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.data.Profile
import com.example.tvlimit.data.UsageReport
import com.example.tvlimit.quiz.BankStore
import com.example.tvlimit.quiz.QuestionBank
import com.example.tvlimit.quiz.QuizSettings
import com.example.tvlimit.quiz.QuizStats
import com.example.tvlimit.quiz.WrongStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 业务错误：状态码 + 一句能直接给家长看的中文。 */
class ApiError(val status: Int, message: String) : Exception(message)

/** 业务接口的实现。ConfigServer 只管 HTTP，这里只管「改什么、返回什么」。 */
object Api {

    private const val PREF_PIN = "ADMIN_PIN"

    private fun dao(ctx: Context) = AppDatabase.getDatabase(ctx.applicationContext).profileDao()

    private fun prefs(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun currentProfile(ctx: Context): Profile? {
        val name = prefs(ctx).getString("CURRENT_PROFILE_NAME", "儿童") ?: "儿童"
        return runBlocking { dao(ctx).getProfileByName(name) }
    }

    private fun bodyJson(body: ByteArray): JSONObject {
        if (body.isEmpty()) return JSONObject()
        return try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ApiError(400, "参数不是合法 JSON")
        }
    }

    private fun todayUsed(ctx: Context, profile: Profile?): Int {
        if (profile == null) return 0
        val log = runBlocking { dao(ctx).getUsageLog(profile.id, LocalDate.now().toString()) }
        return log?.totalUsageMinutes ?: 0
    }

    /**
     * 本场已用 + 还在休息多久。
     *
     * 这两个值真正的权威在追踪服务的内存里，这里按它落盘的 prefs 还原：
     * 休息没结束就是「这一场还没清零」，休息结束就当作新的一场。
     */
    private fun sessionInfo(ctx: Context, profile: Profile?): Pair<Int, Int> {
        if (profile == null) return 0 to 0
        val p = prefs(ctx)
        val lastEnd = p.getLong("last_session_end_time_${profile.id}", 0)
        val saved = p.getInt("session_usage_${profile.id}", 0)
        val restMs = profile.restDurationMinutes * 60_000L
        if (lastEnd <= 0 || restMs <= 0) return 0 to 0
        val elapsed = System.currentTimeMillis() - lastEnd
        if (elapsed >= restMs) return 0 to 0
        val remain = ((restMs - elapsed + 59_999L) / 60_000L).toInt()
        return saved to remain
    }

    // ===== 概览 =====

    fun overview(ctx: Context): JSONObject {
        val profile = currentProfile(ctx)
        val used = todayUsed(ctx, profile)
        val (sessionUsed, restRemain) = sessionInfo(ctx, profile)
        val extra = HouseRules.extraToday(ctx)
        val dailyLimit = profile?.let { HouseRules.dailyLimitWithExtra(ctx, it.dailyLimitMinutes) } ?: -1

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        val device = JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("version", version)
            .put("ip", ConfigServer.lanIp() ?: "")
            .put("port", ConfigServer.port)
            .put("remoteAllowed", HouseRules.allowRemote(ctx))

        val p = profile
        val profileJson = if (p == null) JSONObject.NULL else JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("restricted", p.isRestricted)
            .put("dailyLimit", p.dailyLimitMinutes)
            .put("dailyLimitWithExtra", dailyLimit)
            .put("sessionLimit", p.sessionLimitMinutes)
            .put("restDuration", p.restDurationMinutes)
            .put("used", used)
            .put("sessionUsed", sessionUsed)
            .put("restRemain", restRemain)
            .put("remain", if (dailyLimit < 0) -1 else (dailyLimit - used).coerceAtLeast(0))

        val schedule = HouseRules.schedule(ctx)
        val scheduleJson = JSONObject()
            .put("enabled", schedule.enabled)
            .put("weekday", windowsToJson(schedule.weekday))
            .put("weekend", windowsToJson(schedule.weekend))
            .put("outsideNow", HouseRules.isOutsideSchedule(ctx))
            .put("todayLabel", HouseRules.todayLabel(ctx))

        val all = QuestionBank.all(ctx)
        val bank = JSONObject()
            .put("builtin", QuestionBank.builtin(ctx).size)
            .put("custom", BankStore.loadCustom(ctx).size)
            .put("total", all.size)

        return JSONObject()
            .put("ok", true)
            .put("device", device)
            // 还是默认密码就提醒一句：电视上的二维码摆在客厅，孩子也能扫
            .put("pinDefault", (prefs(ctx).getString(PREF_PIN, "1234") ?: "1234") == "1234")
            .put("profile", profileJson)
            .put("extra", extra)
            .put("paused", JSONObject()
                .put("on", LimitControl.isPaused(ctx))
                .put("remain", LimitControl.remainMinutes(ctx)))
            .put("schedule", scheduleJson)
            .put("quiz", JSONObject()
                .put("enabled", QuizSettings.enabled(ctx))
                .put("interval", QuizSettings.intervalMinutes(ctx))
                .put("count", QuizSettings.questionsPerSession(ctx))
                .put("grade", QuizSettings.grade(ctx))
                .put("subjects", JSONArray(QuizSettings.subjects(ctx).toList()))
                .put("pending", QuizSettings.isPending(ctx)))
            .put("bank", bank)
            .put("wrong", JSONObject()
                .put("count", WrongStore.count(ctx))
                .put("knowledge", QuizStats.wrongKnowledgeCount(ctx)))
            .put("showBadge", LimitControl.showBadge(ctx))
            .put("today", JSONObject()
                .put("used", used)
                .put("limit", dailyLimit))
    }

    private fun windowsToJson(windows: List<HouseRules.Window>): JSONArray {
        val arr = JSONArray()
        windows.forEach { arr.put(JSONArray().put(it.start).put(it.end)) }
        return arr
    }

    // ===== 快捷操作 =====

    fun extend(ctx: Context, body: ByteArray): JSONObject {
        val minutes = bodyJson(body).optInt("minutes", 0)
        if (minutes !in 0..240) throw ApiError(400, "加时长度要在 1~240 分钟之间")
        // 0 = 撤销今天所有加时。家长点错了要能退回去，
        // 否则只能等第二天 —— 而「多给的时间」正是最容易被孩子发现的口子。
        val extra = if (minutes == 0) {
            HouseRules.clearExtraToday(ctx)
            0
        } else {
            HouseRules.addExtraToday(ctx, minutes)
        }
        val profile = currentProfile(ctx)
        val used = todayUsed(ctx, profile)
        val limit = profile?.let { HouseRules.dailyLimitWithExtra(ctx, it.dailyLimitMinutes) } ?: -1
        return JSONObject()
            .put("ok", true)
            .put("extra", extra)
            .put("limit", limit)
            .put("used", used)
            .put("remain", if (limit < 0) -1 else (limit - used).coerceAtLeast(0))
    }

    fun pause(ctx: Context, body: ByteArray): JSONObject {
        val o = bodyJson(body)
        if (o.optString("until") == "tonight") {
            LimitControl.pauseUntilTomorrow(ctx)
        } else {
            val minutes = o.optInt("minutes", 0)
            if (minutes !in 1..1440) throw ApiError(400, "暂停时长要在 1~1440 分钟之间")
            LimitControl.pauseMinutes(ctx, minutes)
        }
        return JSONObject()
            .put("ok", true)
            .put("on", LimitControl.isPaused(ctx))
            .put("remain", LimitControl.remainMinutes(ctx))
    }

    fun resume(ctx: Context): JSONObject {
        LimitControl.resume(ctx)
        return JSONObject().put("ok", true).put("on", false)
    }

    fun quizTest(ctx: Context): JSONObject {
        // 和遥控器上「试一组」走同一条广播，家长在手机上点完能立刻在电视上看到
        ctx.sendBroadcast(
            Intent("com.example.tvlimit.ACTION_QUIZ_TEST").setPackage(ctx.packageName)
        )
        return JSONObject().put("ok", true)
    }

    // ===== 报表 =====

    fun report(ctx: Context, query: String): JSONObject {
        val days = query.split('&')
            .mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 && kv[0] == "days" } }
            .firstOrNull()?.get(1)?.toIntOrNull() ?: 7
        val profile = currentProfile(ctx)
            ?: throw ApiError(400, "还没有选中的档案")
        val report = runBlocking { UsageReport.report(ctx, profile.id, days) }
        return JSONObject()
            .put("ok", true)
            .put("profile", profile.name)
            .put("report", report)
    }

    // ===== 档案与额度 =====

    fun profiles(ctx: Context): JSONObject {
        val flow = dao(ctx).getAllProfiles()
        // getAllProfiles 是 Flow，取第一帧即可
        val list = runBlocking { flow.first() }
        val current = prefs(ctx).getString("CURRENT_PROFILE_NAME", "儿童")
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("restricted", p.isRestricted)
                    .put("dailyLimit", p.dailyLimitMinutes)
                    .put("sessionLimit", p.sessionLimitMinutes)
                    .put("restDuration", p.restDurationMinutes)
                    .put("used", todayUsed(ctx, p))
                    .put("current", p.name == current)
                    .put("apps", p.restrictedApps ?: "")
            )
        }
        return JSONObject().put("ok", true).put("profiles", arr)
    }

    fun updateProfile(ctx: Context, body: ByteArray): JSONObject {
        val o = bodyJson(body)
        val id = o.optInt("id", -1)
        if (id <= 0) throw ApiError(400, "缺少档案 id")
        val old = runBlocking { dao(ctx).getProfileById(id) }
            ?: throw ApiError(404, "没有这个档案")

        val daily = if (o.has("dailyLimit")) o.optInt("dailyLimit") else old.dailyLimitMinutes
        val session = if (o.has("sessionLimit")) o.optInt("sessionLimit") else old.sessionLimitMinutes
        val rest = if (o.has("restDuration")) o.optInt("restDuration") else old.restDurationMinutes

        if (daily != -1 && daily !in 0..720) throw ApiError(400, "每天的时长要填 -1（不限）或 0~720 分钟")
        if (session != -1 && session !in 1..240) throw ApiError(400, "单次时长要填 -1（不限）或 1~240 分钟")
        // 家长档案是「不限制」，休息时长可以是 0；孩子档案至少 1 分钟，
        // 否则「休息 0 分钟」等于到点立刻恢复，限制形同虚设。
        if (rest !in 0..180) throw ApiError(400, "休息时长要在 0~180 分钟之间")
        if (rest == 0 && daily != -1) throw ApiError(400, "限时档案的休息时长不能是 0")

        val updated = old.copy(
            dailyLimitMinutes = daily,
            sessionLimitMinutes = session,
            restDurationMinutes = rest
        )
        runBlocking { dao(ctx).updateProfile(updated) }
        // 追踪服务收到后会重载档案，改完立刻生效，不用等下一轮 tick
        ctx.sendBroadcast(
            Intent("com.example.tvlimit.ACTION_PROFILE_UPDATED").setPackage(ctx.packageName)
        )
        return JSONObject().put("ok", true)
            .put("dailyLimit", daily)
            .put("sessionLimit", session)
            .put("restDuration", rest)
    }

    fun switchProfile(ctx: Context, body: ByteArray): JSONObject {
        val id = bodyJson(body).optInt("id", -1)
        if (id <= 0) throw ApiError(400, "缺少档案 id")
        val target = runBlocking { dao(ctx).getProfileById(id) }
            ?: throw ApiError(404, "没有这个档案")
        // 和电视上「切换档案」完全同一条路径：写 prefs + 广播 + 确保服务在跑
        prefs(ctx).edit()
            .putString("CURRENT_PROFILE_NAME", target.name)
            .putInt("PENDING_PROFILE_ID", target.id)
            .apply()
        ctx.sendBroadcast(
            Intent("com.example.tvlimit.ACTION_PROFILE_CHANGED")
                .setPackage(ctx.packageName)
                .putExtra("com.example.tvlimit.EXTRA_PROFILE_ID", target.id)
        )
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, com.example.tvlimit.service.TimeTrackingService::class.java)
            )
        } catch (_: Exception) {
        }
        return JSONObject().put("ok", true).put("name", target.name)
    }

    // ===== 作息 =====

    fun schedule(ctx: Context): JSONObject {
        val s = HouseRules.schedule(ctx)
        return JSONObject().put("ok", true)
            .put("enabled", s.enabled)
            .put("weekday", windowsToJson(s.weekday))
            .put("weekend", windowsToJson(s.weekend))
            .put("outsideNow", HouseRules.isOutsideSchedule(ctx))
            .put("todayLabel", HouseRules.todayLabel(ctx))
    }

    fun setSchedule(ctx: Context, body: ByteArray): JSONObject {
        val err = HouseRules.setSchedule(ctx, String(body, Charsets.UTF_8))
        if (err != null) throw ApiError(400, err)
        return schedule(ctx)
    }

    // ===== 答题门禁设置 =====

    fun settings(ctx: Context): JSONObject = JSONObject()
        .put("ok", true)
        .put("enabled", QuizSettings.enabled(ctx))
        .put("interval", QuizSettings.intervalMinutes(ctx))
        .put("count", QuizSettings.questionsPerSession(ctx))
        .put("grade", QuizSettings.grade(ctx))
        .put("subjects", JSONArray(QuizSettings.subjects(ctx).toList()))

    fun setSettings(ctx: Context, body: ByteArray): JSONObject {
        val o = bodyJson(body)
        if (o.has("enabled")) QuizSettings.setEnabled(ctx, o.optBoolean("enabled"))
        if (o.has("interval")) QuizSettings.setIntervalMinutes(ctx, o.optInt("interval"))
        if (o.has("count")) QuizSettings.setQuestionsPerSession(ctx, o.optInt("count"))
        if (o.has("grade")) QuizSettings.setGrade(ctx, o.optInt("grade"))
        val subjects = o.optJSONArray("subjects")
        if (subjects != null && subjects.length() > 0) {
            val set = HashSet<String>()
            for (i in 0 until subjects.length()) set.add(subjects.optString(i))
            QuizSettings.setSubjects(ctx, set)
        }
        if (o.has("showBadge")) LimitControl.setShowBadge(ctx, o.optBoolean("showBadge"))
        return settings(ctx)
    }

    // ===== 远程访问等整机开关 =====

    fun config(ctx: Context): JSONObject = JSONObject()
        .put("ok", true)
        .put("allowRemote", HouseRules.allowRemote(ctx))
        .put("showBadge", LimitControl.showBadge(ctx))
        .put("endpoints", JSONArray().apply {
            ConfigServer.endpoints(includeRemote = true).forEach {
                put(JSONObject().put("url", it.url).put("label", it.label).put("remote", it.remote))
            }
        })

    fun setConfig(ctx: Context, body: ByteArray): JSONObject {
        val o = bodyJson(body)
        if (o.has("allowRemote")) HouseRules.setAllowRemote(ctx, o.optBoolean("allowRemote"))
        if (o.has("showBadge")) LimitControl.setShowBadge(ctx, o.optBoolean("showBadge"))
        return config(ctx)
    }

    fun changePin(ctx: Context, body: ByteArray): JSONObject {
        val o = bodyJson(body)
        val old = o.optString("old", "")
        val fresh = o.optString("new", "")
        val stored = prefs(ctx).getString(PREF_PIN, "1234") ?: "1234"
        if (old != stored) throw ApiError(403, "原来的密码不对")
        if (fresh.length !in 4..8 || fresh.any { !it.isDigit() }) {
            throw ApiError(400, "新密码要 4~8 位数字")
        }
        prefs(ctx).edit().putString(PREF_PIN, fresh).apply()
        return JSONObject().put("ok", true)
    }

    // ===== 题库 =====

    fun bankGaps(ctx: Context): JSONObject {
        val all = QuestionBank.all(ctx)
        // 哪些年级「该有这个科目的题」按课标来：生物到初二就没有了，
        // 化学初三才开课 —— 拿一张只写起始年级的表去判断，会把
        // 「初三没有生物题」也算成缺口，家长照着补题反而是白费劲。
        val ranges = mapOf(
            "chinese" to (1 to 9), "english" to (1 to 9), "science" to (1 to 6),
            "physics" to (8 to 9), "chemistry" to (9 to 9),
            "biology" to (7 to 8), "history" to (7 to 9),
            "geography" to (7 to 9), "politics" to (7 to 9)
        )
        val counts = LinkedHashMap<String, IntArray>()
        ranges.keys.forEach { counts[it] = IntArray(10) }
        all.forEach { q ->
            // 自定义题可能带奇怪的科目名，忽略掉就行，不必报错
            counts[q.subject]?.let { arr ->
                if (q.grade in 1..9) arr[q.grade] += 1
            }
        }
        // 两个阈值，回答两个不同的问题：
        // THRESHOLD（8）——「这格几乎没题」，孩子当天就会撞见重复；
        // TARGET（150）——「这格够不够撑一周」，项目的长期目标。
        // 只报 8 会把 116 道的五年级英语也说成没问题，家长就不知道该补哪。
        val THRESHOLD = 8
        val TARGET = 150
        val gaps = JSONArray()
        val subjects = JSONArray()
        var thin = 0
        ranges.forEach { (subject, range) ->
            val (from, to) = range
            val row = JSONArray()
            for (g in 1..9) {
                val n = counts[subject]?.get(g) ?: 0
                row.put(n)
                if (g in from..to) {
                    if (n < TARGET) thin += 1
                    if (n < THRESHOLD) {
                        gaps.put(
                            JSONObject().put("subject", subject).put("grade", g)
                                .put("count", n).put("need", THRESHOLD)
                        )
                    }
                }
            }
            subjects.put(
                JSONObject().put("subject", subject).put("from", from).put("to", to)
                    .put("counts", row)
            )
        }
        return JSONObject().put("ok", true)
            .put("threshold", THRESHOLD)
            .put("target", TARGET)
            .put("thin", thin)
            .put("grades", JSONArray((1..9).toList()))
            .put("subjects", subjects)
            .put("gaps", gaps)
            .put("gapCount", gaps.length())
    }

    fun importBank(ctx: Context, query: String, body: ByteArray): JSONObject {
        if (body.isEmpty()) throw ApiError(400, "没有收到内容")
        val replace = query.split('&').any { it == "replace=1" }
        val result = try {
            BankStore.import(ctx, String(body, Charsets.UTF_8), replace)
        } catch (e: Exception) {
            throw ApiError(400, e.message ?: "导入失败")
        }
        return JSONObject().put("ok", true)
            .put("result", result.toJson())
            .put("total", QuestionBank.all(ctx).size)
    }

    fun clearBank(ctx: Context): JSONObject = JSONObject()
        .put("ok", true)
        .put("removed", BankStore.clear(ctx))
        .put("total", QuestionBank.all(ctx).size)

    // ===== 错题 =====

    fun mistakes(ctx: Context): JSONObject {
        val arr = JSONArray()
        WrongStore.ranked(ctx).forEach { arr.put(it.toJson()) }
        return JSONObject().put("ok", true)
            .put("items", arr)
            .put("count", arr.length())
            .put("knowledge", QuizStats.wrongKnowledgeCount(ctx))
    }

    fun clearMistakes(ctx: Context): JSONObject {
        WrongStore.clear(ctx)
        QuizStats.clear(ctx)
        return JSONObject().put("ok", true)
    }
}
