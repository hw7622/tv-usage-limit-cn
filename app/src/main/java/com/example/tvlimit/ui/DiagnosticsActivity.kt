package com.example.tvlimit.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tvlimit.R
import com.example.tvlimit.service.Revival
import com.example.tvlimit.util.EventLog

/**
 * 运行诊断：把「监控有没有在干活」摊开给家长看。
 *
 * 之前排查"孩子超时还能看"，只能翻 logcat（这台电视还过滤 D 级日志、几十分钟就滚掉），
 * 事后什么都查不到。这里把三件事摆出来：
 * ① 今天中断了几次、累计多久 —— 服务被冻结/杀掉，这里一定有数字；
 * ② 最近一次心跳是几秒前 —— 大于 40 秒就说明循环卡住了；
 * ③ 原始事件流 —— 到点、拦截、休息、前后台切换全在里面，可以直接导出。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var tvGapCount: TextView
    private lateinit var tvGapTotal: TextView
    private lateinit var tvLastTick: TextView
    private lateinit var tvStateLine: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = true
    private val tick = object : Runnable {
        override fun run() {
            if (autoRefresh) refresh()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        // 日志目录是在服务里初始化的；家长直接从设置进来时服务可能刚被杀过，
        // 不初始化的话 tail() 拿不到任何东西，页面就永远是「还没有日志」。
        EventLog.init(this)

        tvGapCount = findViewById(R.id.tvGapCount)
        tvGapTotal = findViewById(R.id.tvGapTotal)
        tvLastTick = findViewById(R.id.tvLastTick)
        tvStateLine = findViewById(R.id.tvStateLine)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        val btnRefresh = findViewById<TextView>(R.id.btnRefresh)
        val btnExport = findViewById<TextView>(R.id.btnExport)
        val btnBack = findViewById<TextView>(R.id.btnBack)
        TvFocus.attach(btnRefresh, btnExport, btnBack)

        btnRefresh.setOnClickListener {
            refresh()
            toast("已刷新")
        }
        btnExport.setOnClickListener {
            val dir = EventLog.exportTo(this)
            if (dir == null) {
                toast("没有日志可导出")
            } else {
                toast("已导出到：${dir.absolutePath}")
            }
        }
        btnBack.setOnClickListener { finish() }

        TvFocus.focus(btnRefresh)
        handler.post(tick)
    }

    override fun onDestroy() {
        autoRefresh = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refresh() {
        val (gapMs, gapCnt) = EventLog.gapToday(this)
        tvGapCount.text = "$gapCnt 次"
        tvGapCount.setTextColor(getColor(if (gapCnt == 0) R.color.mint else R.color.coral))
        tvGapTotal.text = fmtDuration(gapMs)
        tvGapTotal.setTextColor(getColor(if (gapMs < 60_000L) R.color.mint else R.color.coral))

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val lastTickAt = prefs.getLong("last_tick_at", 0L)
        if (lastTickAt <= 0L) {
            tvLastTick.text = "—"
            tvLastTick.setTextColor(getColor(R.color.text_dim))
        } else {
            val age = (System.currentTimeMillis() - lastTickAt) / 1000
            tvLastTick.text = "${age}秒前"
            tvLastTick.setTextColor(
                getColor(
                    when {
                        age < 40 -> R.color.mint
                        age < 120 -> R.color.sun
                        else -> R.color.coral
                    }
                )
            )
        }

        val state = prefs.getString("last_tick_state", null)
        val keepAlive = buildString {
            append(if (Revival.isServiceAlive(this@DiagnosticsActivity)) "服务在跑" else "服务没在跑")
            append(" · 通知保活").append(if (Revival.isListenerEnabled(this@DiagnosticsActivity)) "已开" else "未开")
            val battery = try {
                (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
            } catch (e: Exception) {
                false
            }
            append(" · 省电白名单").append(if (battery) "已加" else "未加")
        }
        tvStateLine.text = (if (state == null) {
            "监控服务还没有开始计时（电视待机时是正常的）"
        } else {
            "s=本场 d=今日 r=到点 p=暂停 top=前台 scope=是否计时 rest=整机遮罩 → $state"
        }) + "\n保活：" + keepAlive

        val lines = EventLog.tail(200)
        tvLog.text = if (lines.isEmpty()) {
            "还没有日志。装完新版本后看一会儿电视就会出现。"
        } else {
            lines.joinToString("\n") { pretty(it) }
        }
    }

    /** 把一行 JSON 压成「12:03:41 limit d=86 dmax=90 s=20 ...」，遥控器上看得清。 */
    private fun pretty(raw: String): String {
        val time = Regex("\"at\":\"[^ ]+ ([0-9:.]+)\"").find(raw)?.groupValues?.get(1)?.take(8) ?: "--:--:--"
        val ev = Regex("\"ev\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "?"
        val rest = raw
            .replace(Regex("^\\{\"t\":[0-9]+,|\"at\":\"[^\"]*\",|\"ev\":\"[^\"]*\",?"), "")
            .removeSuffix("}")
            .replace("\"", "")
            .replace(",", " ")
            .replace(":", "=")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "$time  $ev  $rest"
    }

    private fun fmtDuration(ms: Long): String {
        if (ms <= 0L) return "0 秒"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "$m 分 $s 秒" else "$s 秒"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
