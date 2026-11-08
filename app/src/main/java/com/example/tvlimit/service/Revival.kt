package com.example.tvlimit.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.tvlimit.util.EventLog

/**
 * 「被电视管家杀掉之后怎么活过来」的统一入口。
 *
 * 原来只有 JobScheduler 看门狗一条路，但它只在**进程还活着**时有效：
 * 国产电视（小米的待机清理、TCL 的 FreezeManager、创维/酷开的省电管家）直接
 * `force-stop` 我们时，系统的行为是「把包里所有 JobService、Alarm、静态广播一起作废」，
 * 并且把应用标成 stopped 状态 —— 从此开机广播也不给、定时任务永远不跑。
 * 表现就是家长说的「昨天还好好的，今天完全没人管」。
 *
 * 参考开源电视桌面 Aerial 的做法（app/orchard/tv/guard/RevivalListener.kt），
 * 有两样东西是系统自己持有的、force-stop 之后还会把进程拉起来：
 * ① 通知使用权（NotificationListenerService）—— 授权记录存在系统里，进程死了会自动重新 bind；
 * ② 同步框架（SyncAdapter）—— 账号和同步任务归系统，force-stop 清不掉。
 * 这里不读任何通知内容，只借「重新 bind」这一个动作确认服务还在不在。
 */
object Revival {

    private const val TAG = "TvLimit"
    private val handler = Handler(Looper.getMainLooper())
    /** 一次复活流程里的核对次数，避免系统忙时无限重试。 */
    private const val MAX_CHECKS = 5

    /**
     * 服务真的在计时吗。
     *
     * 不能只看 `getRunningServices`：TCL 会把刚起来的进程冻住，dumpsys 里 ServiceRecord
     * 已经排队（startRequested=true），但 onCreate 没跑、startForegroundCount=0，
     * 这时「服务在跑」是假的（2026-11-08 真机实测）。所以以服务自己的心跳为准。
     */
    fun isServiceAlive(ctx: Context): Boolean = TimeTrackingService.isAlive()

    /**
     * 确认监控服务活着，不在就拉起来。
     *
     * @param why 记进事件日志的来源（诊断页要能看出是「谁」把服务救回来的）
     */
    fun reviveIfNeeded(ctx: Context, why: String) {
        val app = ctx.applicationContext
        if (isServiceAlive(app)) return

        Log.w(TAG, "revive: service is gone ($why)")
        // 进程刚被拉起来时 EventLog 还没 init，不先初始化这条复活记录会直接丢掉
        runCatching { EventLog.init(app) }
        EventLog.event("revive", "by" to why)
        // 看门狗的定时任务可能已经被 force-stop 连带清掉，先补排上
        ServiceWatchdog.schedule(app)
        ServiceWatchdog.startService(app)

        // 只有亮屏时才允许把透明页顶到前台：熄屏时这会把电视点亮，半夜比限制失效更吓人。
        val interactive = try {
            (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        } catch (e: Exception) {
            true
        }
        if (!interactive) {
            Log.d(TAG, "revive: screen off, skip waking up")
            return
        }
        // 立刻顶，不能等：TCL 的 FreezeManager 大约 6 秒就把后台进程冻住，
        // 一冻住我们排的任何延后检查都不会再执行（实测 ServiceRecord 排队但 onCreate 永不执行）。
        ServiceWatchdog.wakeUp(app)
        scheduleCheck(app, why, 5_000L, 1)
    }

    private fun scheduleCheck(ctx: Context, why: String, delayMs: Long, attempt: Int) {
        handler.postDelayed({
            if (isServiceAlive(ctx)) return@postDelayed
            if (attempt >= MAX_CHECKS) {
                Log.w(TAG, "revive: gave up after $attempt checks ($why)")
                return@postDelayed
            }
            Log.w(TAG, "revive: still down after ${delayMs}ms, waking up again (attempt $attempt)")
            ServiceWatchdog.startService(ctx)
            ServiceWatchdog.wakeUp(ctx)
            scheduleCheck(ctx, why, delayMs * 2, attempt + 1)
        }, delayMs)
    }

    /** 通知使用权开了没有（没有的话 force-stop 之后没人救我们）。 */
    fun isListenerEnabled(ctx: Context): Boolean = try {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val target = "${ctx.packageName}/${Listener::class.java.name}"
        val short = "${ctx.packageName}/.service.RevivalListener"
        flat.split(':').any { it.equals(target, true) || it.equals(short, true) }
    } catch (e: Exception) {
        false
    }

    /**
     * 通知监听服务。它本身不显示、不读通知，唯一作用是「被系统重新 bind 时确认监控还活着」。
     *
     * 为什么不在 onNotificationPosted 里做重活：电视上通知很少，而且这里多做事只会
     * 让我们更容易被 ROM 当成耗电应用。只在服务确实不在时才动手，且 60 秒最多一次。
     */
    class Listener : NotificationListenerService() {
        private var lastCheckAt = 0L
        private val main = Handler(Looper.getMainLooper())

        override fun onCreate() {
            super.onCreate()
            // 有些固件（创维）启动时 bind 了服务却既不回调 onListenerConnected，
            // 也不发 USER_UNLOCKED，所以 onCreate 里也要自己查一次。
            main.postDelayed({ Revival.reviveIfNeeded(this, "listener_create") }, 3_000L)
        }

        override fun onListenerConnected() {
            super.onListenerConnected()
            Log.i(TAG, "notification listener connected")
            main.postDelayed({ Revival.reviveIfNeeded(this, "listener_connected") }, 3_000L)
        }

        override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
            // 只把它当成「系统还愿意 bind 我们」的心跳，内容一个字都不读。
            val now = System.currentTimeMillis()
            if (now - lastCheckAt < 60_000L) return
            lastCheckAt = now
            if (!Revival.isServiceAlive(this)) Revival.reviveIfNeeded(this, "listener_tick")
        }
    }
}
