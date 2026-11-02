package com.example.tvlimit.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * 看门狗任务的执行体。每 15 分钟醒一次，确保监控服务还在。
 * 这里不做任何耗时操作，检查完立刻结束，免得被系统判定为耗电应用。
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.w("TvLimit", "Watchdog tick. service running=${ServiceWatchdog.isServiceRunning(this)}")
        if (ServiceWatchdog.isServiceRunning(this)) {
            // 已经在跑：什么都不用做，只要确认任务本身没被系统清掉
            ServiceWatchdog.ensureScheduled(this)
            return false
        }

        // 不在跑：先按常规路径叫一次，紧接着用透明页从「前台」再叫一次。
        // 不能等几秒再核对 —— TCL 的 FreezeManager 大约 6 秒就会把这个进程冻住，
        // 到时候连核对都执行不了（实测 doFreeze ... FreezeApp freeze: true）。
        ServiceWatchdog.startService(this)
        ServiceWatchdog.wakeUp(this)
        ServiceWatchdog.ensureScheduled(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 被系统打断就要求重试
        return true
    }
}
