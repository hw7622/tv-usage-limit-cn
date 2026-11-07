package com.example.tvlimit.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 排查用的滚动事件日志。
 *
 * 为什么要自己写文件，而不是只用 logcat：
 * ① 这台 TCL 电视只稳定输出 W/E 级日志，D 级基本看不到；
 * ② logcat 缓冲区只有几百 KB，几十分钟就滚掉，"昨天下午孩子看了多久"根本查不到；
 * ③ prefs 里那行 last_tick_state 只有"现在"，没有"当时"，事后无法复盘。
 *
 * 格式：JSONL，一行一个事件，按天滚动。
 * 容量：单文件 2MB、保留 14 天、总量 20MB，超了删最老的。
 *
 * 双通道：写文件的同时用 Log.w 打一条，`adb logcat -s TvLimit:W` 能实时看。
 */
object EventLog {

    private const val TAG = "TvLimit"
    private const val DIR = "logs"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_DAYS = 14
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

    /** 两次 tick 间隔超过这个值，就认为中间有"没人看着"的空窗，记一条 gap。 */
    const val GAP_THRESHOLD_MS = 40_000L

    private const val PREF_GAP_DAY = "gap_day"
    private const val PREF_GAP_MS = "gap_ms_today"
    private const val PREF_GAP_CNT = "gap_cnt_today"
    private const val PREF_LAST_TICK = "eventlog_last_tick"
    private const val PREF_LAST_TICK_ON = "eventlog_last_tick_screen_on"
    private const val PREF_SCREEN_OFF_AT = "eventlog_screen_off_at"

    private val lock = Any()
    /**
     * 落盘放单线程队列里做：调用方经常在主线程（20 秒循环），
     * 写文件+flush 虽然只有几百字节，但电视的 eMMC 偶尔会卡一下，
     * 不能让排查用的日志反过来把计时循环拖住。
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tvlimit-eventlog").apply { isDaemon = true }
    }
    private var dir: File? = null
    private var writer: BufferedWriter? = null
    private var writerDay: String = ""
    private var lastTickAt = 0L
    private var lastTickState: String = ""
    private var lastTickWriteAt = 0L
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        synchronized(lock) {
            if (dir != null) return
            dir = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
            prune()
        }
    }

    /**
     * 写一条事件。
     * @param ev 事件名，见设计文档 §2.3
     * @param pairs 形如 "s" to 12, "top" to "com.foo"
     */
    fun event(ev: String, vararg pairs: Pair<String, Any?>) {
        val sb = StringBuilder(96)
        sb.append("{\"t\":").append(System.currentTimeMillis())
        sb.append(",\"at\":\"").append(timeFmt.format(Date())).append('"')
        sb.append(",\"ev\":\"").append(ev).append('"')
        for ((k, v) in pairs) {
            sb.append(",\"").append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v)
                else -> sb.append('"').append(esc(v.toString())).append('"')
            }
        }
        sb.append('}')
        val line = sb.toString()
        write(line)
        // W 级不会被这台电视过滤，实时排查可以直接看 logcat
        try {
            Log.w(TAG, line)
        } catch (_: Exception) {
        }
    }

    /**
     * 每轮循环调一次：负责「空窗检测」和「心跳」。
     *
     * gap 是判断"漏拦"的直接证据 —— 服务被 TCL 冻结、被系统杀掉、被杀毒软件掐掉，
     * 都会表现为这里的间隔突然变成几十秒到十几分钟。
     *
     * 跨进程也要能算：进程被杀后内存里的时间戳就没了，所以上一轮的时刻必须落在 prefs 里。
     * 同时要记住"上一轮屏幕是不是亮着"——电视关了一整夜再开机，那不是漏拦，
     * 只是没人看电视，不能算进"今日中断"，否则这个指标永远红着，家长反而不看了。
     */
    fun tick(ctx: Context, state: String, screenOn: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val prev = if (lastTickAt > 0) lastTickAt else prefs.getLong(PREF_LAST_TICK, 0L)
        val prevScreenOn = prefs.getBoolean(PREF_LAST_TICK_ON, false)
        lastTickAt = now
        prefs.edit().putLong(PREF_LAST_TICK, now).putBoolean(PREF_LAST_TICK_ON, screenOn).apply()

        if (prev > 0) {
            val gap = now - prev
            // 屏幕关着的那段不算"监控中断"。
            // 两个判据缺一不可：
            // ① prevScreenOn —— 上一轮 tick 时屏幕是亮的；
            // ② offAt < prev —— 那段空窗里没有「关屏」发生。
            // 只判断据①会漏：唤醒时会先把屏幕状态写成 on，再跑到这里，
            // 关屏 50 秒就被记成「监控中断 53 秒」，夜里攒一夜能到十几个小时。
            if (gap > GAP_THRESHOLD_MS && prevScreenOn && !screenWentOffDuring(prefs, prev)) {
                recordGap(ctx, gap)
            }
            // 墙钟被人改过（或 NTP 大跳）：计时会跟着错，必须留痕
            if (gap < -5_000L) {
                event("clock", "delta" to gap, "note" to "wall_clock_moved_back")
            }
        }
        // 心跳：状态变了立刻写；没变就 60 秒一条，别把文件写爆
        if (!force && now - lastTickWriteAt < 60_000L && state == lastTickState) return
        lastTickWriteAt = now
        lastTickState = state
        event("tick", "st" to state)
    }

    /**
     * 记一下屏幕状态。必须在关屏那一刻立刻写：
     * 否则「关电视 → 进程被杀 → 第二天开机」这段会被当成十几小时的监控中断。
     */
    fun noteScreen(ctx: Context, on: Boolean) {
        try {
            val e = PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean(PREF_LAST_TICK_ON, on)
            // 记下"关屏那一刻"，空窗检测靠它区分「电视没人看」和「服务被干掉」。
            // 开屏时不清理：唤醒流程会先写 on 再跑 tick，清掉就没证据了；
            // 判据是 offAt >= 上一次 tick，正常 tick 之后这个时间戳自然过期。
            if (!on) e.putLong(PREF_SCREEN_OFF_AT, System.currentTimeMillis())
            e.apply()
        } catch (_: Exception) {
        }
    }

    /** 这段空窗里是不是发生过关屏（那就不是漏拦）。 */
    private fun screenWentOffDuring(prefs: android.content.SharedPreferences, prevTickAt: Long): Boolean {
        val offAt = prefs.getLong(PREF_SCREEN_OFF_AT, 0L)
        return offAt > 0L && offAt >= prevTickAt
    }

    private fun recordGap(ctx: Context, gapMs: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val today = dayFmt.format(Date())
        var ms = prefs.getLong(PREF_GAP_MS, 0)
        var cnt = prefs.getInt(PREF_GAP_CNT, 0)
        if (prefs.getString(PREF_GAP_DAY, null) != today) {
            ms = 0
            cnt = 0
        }
        prefs.edit()
            .putString(PREF_GAP_DAY, today)
            .putLong(PREF_GAP_MS, ms + gapMs)
            .putInt(PREF_GAP_CNT, cnt + 1)
            .apply()
        event("gap", "ms" to gapMs, "cnt" to (cnt + 1), "sum" to (ms + gapMs))
    }

    /** 今天的空窗合计（毫秒）/ 次数。跨天自动算 0。 */
    fun gapToday(ctx: Context): Pair<Long, Int> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val today = dayFmt.format(Date())
        if (prefs.getString(PREF_GAP_DAY, null) != today) return 0L to 0
        return prefs.getLong(PREF_GAP_MS, 0) to prefs.getInt(PREF_GAP_CNT, 0)
    }

    fun clearGapToday(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_GAP_DAY, dayFmt.format(Date()))
            .putLong(PREF_GAP_MS, 0)
            .putInt(PREF_GAP_CNT, 0)
            .apply()
    }

    fun logFile(day: String = dayFmt.format(Date())): File? {
        val d = dir ?: return null
        return File(d, "events-$day.jsonl")
    }

    fun logFiles(): List<File> {
        val d = dir ?: return emptyList()
        return d.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 诊断页用：读最近 n 行（从最新的文件往回读）。 */
    fun tail(n: Int = 200): List<String> {
        val files = logFiles()
        if (files.isEmpty()) return emptyList()
        val out = ArrayList<String>(n)
        for (f in files) {
            if (out.size >= n) break
            try {
                val lines = f.readLines()
                var i = lines.size - 1
                while (i >= 0 && out.size < n) {
                    val l = lines[i]
                    if (l.isNotBlank()) out.add(l)
                    i--
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 把日志拷到一个外部可见的位置（电视文件管理器能看到），返回目标目录。 */
    fun exportTo(ctx: Context): File? {
        val src = logFiles()
        if (src.isEmpty()) return null
        val root = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dest = File(root, "export-${dayFmt.format(Date())}").apply {
            if (!exists()) mkdirs()
        }
        try {
            for (f in src) {
                f.copyTo(File(dest, f.name), overwrite = true)
            }
        } catch (_: Exception) {
            return null
        }
        return dest
    }

    // ===== 内部 =====

    private fun write(line: String) {
        if (dir == null) return
        io.execute {
            synchronized(lock) {
                val d = dir ?: return@synchronized
                val day = dayFmt.format(Date())
                try {
                    val f = File(d, "events-$day.jsonl")
                    if (f.length() > MAX_FILE_BYTES) {
                        // 单日过大（异常刷日志）：换个后缀截断，别把磁盘写满
                        f.renameTo(File(d, "events-$day.1.jsonl"))
                    }
                    if (writer == null || writerDay != day) {
                        closeWriter()
                        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(File(d, "events-$day.jsonl"), true), "UTF-8"))
                        writerDay = day
                    }
                    writer?.apply {
                        write(line)
                        newLine()
                        flush()
                    }
                } catch (_: Exception) {
                    // 日志绝不能反过来把服务搞崩
                    closeWriter()
                }
            }
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        writerDay = ""
    }

    private fun prune() {
        val d = dir ?: return
        val files = d.listFiles { f -> f.name.startsWith("events-") } ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L
        var total = 0L
        for (f in files) {
            if (f.lastModified() < cutoff) {
                f.delete()
            } else {
                total += f.length()
            }
        }
        if (total > MAX_TOTAL_BYTES) {
            files.filter { it.exists() }.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= MAX_TOTAL_BYTES) return@forEach
                total -= f.length()
                f.delete()
            }
        }
    }

    private fun esc(s: String): String {
        if (s.indexOf('"') < 0 && s.indexOf('\\') < 0 && s.indexOf('\n') < 0) return s
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    /** 进程启动时记一条，带上版本号，方便区分"是哪一版在跑"。 */
    fun logBoot(ctx: Context) {
        val vc = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                @Suppress("DEPRECATION")
                it.versionCode
            }
        } catch (_: Exception) {
            -1
        }
        event(
            "boot", "vc" to vc, "pid" to android.os.Process.myPid(),
            "sdk" to Build.VERSION.SDK_INT, "model" to Build.MODEL
        )
    }
}
