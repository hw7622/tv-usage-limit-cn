package com.example.tvlimit.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.tvlimit.service.TimeTrackingService

/**
 * 一个「什么也不显示」的透明页，唯一作用是**把应用顶到前台**。
 *
 * 为什么需要它：
 * 装完新版本 / 开机之后，广播接收器里调 startForegroundService()，系统有时根本不创建服务
 * （dumpsys 里能看到 ServiceRecord 在排队，startForegroundCount 一直是 0，一行日志都没有），
 * 表现就是「电视上一切正常，但其实完全没人管」—— 限额、拦截全失效，而且没人会发现。
 *
 * 从前台（Activity 生命周期）里启动前台服务则是系统一定放行的路径。所以看门狗发现服务
 * 确实没起来时，就打开这个透明页：它 startService 完立刻 finish，用户只看到一瞬间的闪动，
 * 或者什么都看不到（主题是半透明的）。
 */
class WakeupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w("TvLimit", "WakeupActivity: starting service from foreground")
        try {
            startForegroundService(Intent(this, TimeTrackingService::class.java))
        } catch (e: Exception) {
            Log.e("TvLimit", "WakeupActivity startService failed: ${e.message}")
        }
        // 不建任何 View，立刻退出；服务已经在跑了
        finish()
        overridePendingTransition(0, 0)
    }
}
