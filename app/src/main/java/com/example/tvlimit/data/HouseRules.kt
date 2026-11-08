package com.example.tvlimit.data

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 「家里的规矩」：可用时段、今天临时加时、是否允许从局域网外访问。
 *
 * 为什么和 Profile 表分开：
 * ① 作息和加时都是「整机一份、按天失效」的东西，不是每个档案一份；
 * ② 加进 Profile 要升数据库版本，现有的破坏性迁移会把孩子的历史用量清空。
 *
 * 加时的语义刻意做成「只加今天」：家长在手机上点「+15 分钟」，心里想的是
 * 「今天再给你看一刻钟」，不是「以后每天都多 15 分钟」。第二天自动归零。
 */
object HouseRules {

    const val ACTION_RULES_CHANGED = "com.example.tvlimit.ACTION_RULES_CHANGED"

    private const val PREF_SCHEDULE = "schedule_json"
    private const val PREF_EXTRA_DATE = "extra_minutes_date"
    private const val PREF_EXTRA_MIN = "extra_minutes_today"
    private const val PREF_ALLOW_REMOTE = "allow_remote_access"

    /** 临时加时的单次上限，防止手滑点成 1000 分钟。 */
    private const val EXTRA_CAP_MINUTES = 240

    private fun sp(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun notifyChanged(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent(ACTION_RULES_CHANGED).setPackage(ctx.packageName))
        } catch (_: Exception) {
        }
    }

    // ===== 可用时段 =====

    /** 一段可用时间，分钟为单位（0..1440）。start > end 表示跨夜，如 21:00-07:00。 */
    data class Window(val start: Int, val end: Int) {
        fun contains(minute: Int): Boolean =
            if (start <= end) minute >= start && minute < end
            else minute >= start || minute < end

        fun label(): String = "${hhmm(start)}-${hhmm(end)}"
    }

    data class Schedule(
        val enabled: Boolean,
        val weekday: List<Window>,
        val weekend: List<Window>
    ) {
        /** 一天都没配就等于没设作息：宁可不管，也不能因为配错把电视全锁死。 */
        val configured: Boolean get() = weekday.isNotEmpty() || weekend.isNotEmpty()

        fun windowsFor(day: DayOfWeek): List<Window> =
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) weekend else weekday

        fun summaryFor(day: DayOfWeek): String {
            val w = windowsFor(day)
            return if (w.isEmpty()) "不限" else w.joinToString("、") { it.label() }
        }
    }

    private fun hhmm(minute: Int): String {
        val m = minute.coerceIn(0, 1440)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun schedule(ctx: Context): Schedule {
        // isLimitReached() 每秒被调几次，别每次都解析 JSON。
        // 用原始字符串做缓存 key：改动只有一条写路径（setSchedule/setScheduleEnabled），
        // 写完读回来的字符串必然不同，缓存自动失效。
        val raw = sp(ctx).getString(PREF_SCHEDULE, null)
        if (raw == cachedRaw) return cachedSchedule
        val s = parse(raw)
        cachedSchedule = s
        cachedRaw = raw
        return s
    }

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedSchedule = Schedule(false, emptyList(), emptyList())

    fun scheduleJson(ctx: Context): JSONObject = toJson(schedule(ctx))

    /**
     * 保存作息。返回 null 表示成功，否则是给家长看的中文原因。
     *
     * 校验宁可严一点：这个开关一旦配错，孩子第二天早上就开不了电视，
     * 而家长多半想不起来是自己昨天动过这里。
     */
    fun setSchedule(ctx: Context, raw: String): String? {
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return "不是合法的 JSON"
        }
        val enabled = o.optBoolean("enabled", false)
        val weekday = parseWindows(o.optJSONArray("weekday")) ?: return "工作日的时段格式不对"
        val weekend = parseWindows(o.optJSONArray("weekend")) ?: return "周末的时段格式不对"
        if (enabled && weekday.isEmpty() && weekend.isEmpty()) {
            return "开了作息但一段可用时间都没填，这样电视会全天锁定"
        }
        for (w in weekday + weekend) {
            if (w.end == w.start) return "有一段时间的起止时间相同（${w.label()}）"
        }
        val json = JSONObject()
            .put("enabled", enabled)
            .put("weekday", windowsToJson(weekday))
            .put("weekend", windowsToJson(weekend))
        sp(ctx).edit().putString(PREF_SCHEDULE, json.toString()).apply()
        notifyChanged(ctx)
        return null
    }

    fun setScheduleEnabled(ctx: Context, enabled: Boolean) {
        val s = schedule(ctx)
        val json = JSONObject()
            .put("enabled", enabled)
            .put("weekday", windowsToJson(s.weekday))
            .put("weekend", windowsToJson(s.weekend))
        sp(ctx).edit().putString(PREF_SCHEDULE, json.toString()).apply()
        notifyChanged(ctx)
    }

    /** 现在是不是「不该看」的时间。没配置、没开、当天没填时段，都算可以看。 */
    fun isOutsideSchedule(ctx: Context, at: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val s = schedule(ctx)
        if (!s.enabled || !s.configured) return false
        val minute = at.hour * 60 + at.minute
        val today = s.windowsFor(at.dayOfWeek)
        if (today.any { it.contains(minute) }) return false
        // 跨夜窗口（21:00-07:00）的凌晨那半段，算的是**前一天**那张表。
        // 只用今天的表判断的话，「工作日 21:00-07:00」在周六 02:00 会被当成
        // 周末没到 08:00 而拦掉，孩子周五晚上看一半就被踢出来。
        val overnight = s.windowsFor(at.minusDays(1).dayOfWeek)
            .any { it.start > it.end && minute < it.end }
        if (overnight) return false
        // 当天一段都没填 = 不限（和上面对「没配置」的态度一致：
        // 宁可不管，也不能因为漏填把电视全锁死）
        if (today.isEmpty()) return false
        return true
    }

    /** 「今天是 17:00-19:30」—— 拦截页上要告诉孩子什么时候能看。 */
    fun todayLabel(ctx: Context, at: ZonedDateTime = ZonedDateTime.now()): String {
        val s = schedule(ctx)
        val windows = s.windowsFor(at.dayOfWeek)
        return if (windows.isEmpty()) "今天不限时" else "今天可以看：" + windows.joinToString("、") { it.label() }
    }

    private fun parse(raw: String?): Schedule {
        if (raw.isNullOrBlank()) return Schedule(false, emptyList(), emptyList())
        return try {
            val o = JSONObject(raw)
            Schedule(
                enabled = o.optBoolean("enabled", false),
                weekday = parseWindows(o.optJSONArray("weekday")) ?: emptyList(),
                weekend = parseWindows(o.optJSONArray("weekend")) ?: emptyList()
            )
        } catch (e: Exception) {
            Schedule(false, emptyList(), emptyList())
        }
    }

    /** 兼容两种写法：`[[510,630]]` 和 `[{"start":510,"end":630}]`。 */
    private fun parseWindows(arr: JSONArray?): List<Window>? {
        if (arr == null) return emptyList()
        val out = ArrayList<Window>(arr.length())
        for (i in 0 until arr.length()) {
            val w = when (val item = arr.opt(i)) {
                is JSONArray -> {
                    if (item.length() < 2) return null
                    Window(item.optInt(0, -1), item.optInt(1, -1))
                }

                is JSONObject -> Window(item.optInt("start", -1), item.optInt("end", -1))
                else -> return null
            }
            if (w.start < 0 || w.start > 1440 || w.end < 0 || w.end > 1440) return null
            out.add(w)
        }
        return out
    }

    private fun windowsToJson(windows: List<Window>): JSONArray {
        val arr = JSONArray()
        windows.forEach { arr.put(JSONArray().put(it.start).put(it.end)) }
        return arr
    }

    private fun toJson(s: Schedule): JSONObject = JSONObject()
        .put("enabled", s.enabled)
        .put("weekday", windowsToJson(s.weekday))
        .put("weekend", windowsToJson(s.weekend))

    // ===== 今天临时加时 =====

    /** 今天额外给的分钟数；跨天自动失效。 */
    fun extraToday(ctx: Context): Int {
        val p = sp(ctx)
        if (p.getString(PREF_EXTRA_DATE, "") != LocalDate.now().toString()) return 0
        return p.getInt(PREF_EXTRA_MIN, 0).coerceAtLeast(0)
    }

    /** 加时，返回加完之后今天总共多了多少分钟。 */
    fun addExtraToday(ctx: Context, minutes: Int): Int {
        val today = LocalDate.now().toString()
        val base = extraToday(ctx)
        // 同一天里连续点「+15」是叠加的，但整体封顶，别让手滑变成「今天不设限」
        val total = (base + minutes).coerceIn(0, EXTRA_CAP_MINUTES)
        sp(ctx).edit()
            .putString(PREF_EXTRA_DATE, today)
            .putInt(PREF_EXTRA_MIN, total)
            .apply()
        notifyChanged(ctx)
        return total
    }

    fun clearExtraToday(ctx: Context) {
        sp(ctx).edit()
            .putString(PREF_EXTRA_DATE, LocalDate.now().toString())
            .putInt(PREF_EXTRA_MIN, 0)
            .apply()
        notifyChanged(ctx)
    }

    /** 把加时算进当天的上限。-1（不限）保持 -1。 */
    fun dailyLimitWithExtra(ctx: Context, limit: Int): Int =
        if (limit < 0) limit else limit + extraToday(ctx)

    // ===== 远程访问 =====

    /**
     * 是否允许从「非局域网」的地址打开手机配置页。
     *
     * 默认关闭。打开的典型场景是家长在外面用 Tailscale / ZeroTier 这类
     * 点对点组网连回家 —— 不需要公网服务器、不需要域名，装个 App 扫码登录就行。
     * 我们不做任何穿透，只决定「要不要接受来自这些网卡的请求」。
     */
    fun allowRemote(ctx: Context): Boolean = sp(ctx).getBoolean(PREF_ALLOW_REMOTE, false)

    fun setAllowRemote(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(PREF_ALLOW_REMOTE, on).apply()
    }
}
