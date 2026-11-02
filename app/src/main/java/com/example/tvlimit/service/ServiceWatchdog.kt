package com.example.tvlimit.service

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tvlimit.ui.WakeupActivity

/**
 * 「时间管家」的看门狗。
 *
 * 只靠一个前台服务是不够的：国产电视（TCL、海信这类）自带的「后台清理」「省电管理」
 * 会在半夜或者空闲时把服务干掉，第二天孩子一开电视就完全没人管了。
 *
 * 这里用系统自带的 JobScheduler 挂一个 15 分钟一次的常驻任务，每次醒来只做一件事：
 * 确认监控服务还活着，不在就拉起来。任务本身是持久化的，关电重启后依然有效。
 */
object ServiceWatchdog {

    private const val TAG = "TvLimit"
    private const val JOB_ID = 0x7E11
    private const val PERIOD_MS = 15 * 60 * 1000L

    /** 排上看门狗任务。重复调用无副作用。 */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return

            val component = ComponentName(ctx, WatchdogJobService::class.java)
            val job = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(PERIOD_MS)
                // 重启后仍然有效（依赖 RECEIVE_BOOT_COMPLETED）
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setRequiresCharging(false)
                .build()

            val result = js.schedule(job)
            Log.d(TAG, "Watchdog scheduled: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog schedule failed: ${e.message}")
        }
    }

    /** 把监控服务叫起来。已经在跑时是空操作，不会重复创建。 */
    fun startService(ctx: Context) {
        try {
            val intent = Intent(ctx, TimeTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog startService failed: ${e.message}")
        }
    }

    /** 后台任务被系统取消后（部分 ROM 会干这事）重新排一次。 */
    fun ensureScheduled(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) == null) schedule(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog ensure failed: ${e.message}")
        }
    }

    /**
     * 服务「真的在跑」吗。
     *
     * 光看进程存在不够：实测装完新版本后，进程起来了（广播接收器在里面），
     * ServiceRecord 也在排队，但 onCreate 根本没执行 —— 一行日志都没有、limits 全失效。
     * 查自己应用的服务不需要任何权限，这里就是那道核对。
     */
    fun isServiceRunning(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
            val target = ComponentName(ctx, TimeTrackingService::class.java)
            @Suppress("DEPRECATION")
            am.getRunningServices(64).any { it.service == target }
        } catch (e: Exception) {
            Log.e(TAG, "isServiceRunning failed: ${e.message}")
            false
        }
    }

    /**
     * 把应用顶到前台再启动服务。
     * 后台直接 startForegroundService 被系统拒的时候，这是唯一稳定可行的路。
     */
    fun wakeUp(ctx: Context) {
        try {
            Log.w(TAG, "Service not running after start, waking up via foreground activity")
            val intent = Intent(ctx, WakeupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "wakeUp failed: ${e.message}")
        }
    }

    /**
     * 确保服务在跑：先按常规路径启动，几秒后核对；没起来就交给透明页从「前台」再启一次。
     *
     * @param verify 只有进程还能活几秒的场景（广播接收器 / JobService）才做核对。
     */
    fun ensureRunning(ctx: Context, verify: Boolean = true) {
        startService(ctx)
        if (!verify) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isServiceRunning(ctx)) wakeUp(ctx)
        }, 6_000L)
    }
}
