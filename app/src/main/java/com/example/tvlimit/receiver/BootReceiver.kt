package com.example.tvlimit.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tvlimit.service.RevivalSync
import com.example.tvlimit.service.ServiceWatchdog

/**
 * 开机 / 快速开机 / 装新版本 之后把监控服务拉起来。
 *
 * 之前只听了 BOOT_COMPLETED：电视用遥控器「关机」其实只是待机，TCL 这类机器
 * 再开机时常常只发 QUICKBOOT_POWERON；而每次覆盖安装新版本后服务也不会自动回来，
 * 必须手动开一次 App 才生效。这两种情况现在都补上了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val interesting = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.HTC_QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!interesting) return

        // 复活通道的账号/同步任务在 force-stop 后会被清掉，每次开机都补一遍
        RevivalSync.ensure(context)

        // 直接启动阶段数据还没解密：这时候去开 Room 会直接崩，
        // 只把定时任务排上，真正的启动留给紧接着的 BOOT_COMPLETED。
        if (action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            ServiceWatchdog.schedule(context)
            return
        }

        Log.d("BootReceiver", "$action -> start TimeTrackingService")
        // 先把看门狗排上（装新版本后 JobScheduler 里的任务会被清掉）
        ServiceWatchdog.schedule(context)

        // TCL 的 FreezeManager 会在进程起来约 6 秒后把它冻住：
        //   FreezeManager: doFreeze <pid> pkg com.example.tvlimit status: HighUsedApp, FreezeApp
        // 被冻住之后 startForegroundService 的请求永远不会执行（dumpsys 里连 ServiceRecord 都没有），
        // 结果就是「装完新版本 / 开机后一切正常，但限额完全没人管」。
        //
        // 对策：不要等核对，直接把透明页顶起来 —— 有可见 Activity 的进程不会被判成后台可冻结，
        // 而且从前台启动前台服务是系统一定放行的路径。透明页 startService 完立刻 finish。
        ServiceWatchdog.startService(context)
        ServiceWatchdog.wakeUp(context)
    }
}
