package com.example.tvlimit.data

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「临时关闭限时」的开关。
 *
 * 家长自己偶尔想看个片子，没必要每次都走「切换档案」；用这个把限制临时关掉一段时间，
 * 到点自动恢复。关闭期间既不计时也不拦截，孩子今天剩下的额度一点都不会被吃掉。
 */
object LimitControl {

    const val PREF_PAUSE_UNTIL = "PAUSE_UNTIL"
    const val ACTION_PAUSE_CHANGED = "com.example.tvlimit.ACTION_PAUSE_CHANGED"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun pausedUntil(ctx: Context): Long = prefs(ctx).getLong(PREF_PAUSE_UNTIL, 0L)

    fun isPaused(ctx: Context): Boolean = pausedUntil(ctx) > System.currentTimeMillis()

    /** 还剩几分钟恢复，向上取整，最小 0。 */
    fun remainMinutes(ctx: Context): Int {
        val ms = pausedUntil(ctx) - System.currentTimeMillis()
        return if (ms <= 0L) 0 else ((ms + 59_999L) / 60_000L).toInt()
    }

    fun pauseMinutes(ctx: Context, minutes: Int) {
        set(ctx, System.currentTimeMillis() + minutes * 60_000L)
    }

    /** 关到今晚 24 点。 */
    fun pauseUntilTomorrow(ctx: Context) {
        val until = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        set(ctx, until)
    }

    fun resume(ctx: Context) = set(ctx, 0L)

    private fun set(ctx: Context, until: Long) {
        prefs(ctx).edit().putLong(PREF_PAUSE_UNTIL, until).apply()
        try {
            ctx.sendBroadcast(Intent(ACTION_PAUSE_CHANGED))
        } catch (_: Exception) {
        }
    }

    // ===== 右上角那个「还剩 X 分」的小条 =====
    // 家长反映挡画面，默认不显示。想要随时瞄一眼剩余时间的，可以在设置里打开。
    const val PREF_SHOW_BADGE = "SHOW_TIME_BADGE"
    const val ACTION_BADGE_CHANGED = "com.example.tvlimit.ACTION_BADGE_CHANGED"

    fun showBadge(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_SHOW_BADGE, false)

    fun setShowBadge(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_SHOW_BADGE, on).apply()
        try {
            ctx.sendBroadcast(Intent(ACTION_BADGE_CHANGED))
        } catch (_: Exception) {
        }
    }
}
