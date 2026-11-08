package com.example.tvlimit.ui

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.data.Profile
import com.example.tvlimit.receiver.AdminReceiver
import com.example.tvlimit.service.TimeTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var tvClock: TextView
    private lateinit var tvProfileBadge: TextView
    private lateinit var tvRemainLabel: TextView
    private lateinit var tvRemainHero: TextView
    private lateinit var tvRemainUnit: TextView
    private lateinit var tvDetail: TextView
    private lateinit var pbUsage: ProgressBar
    private lateinit var permissionBar: View
    private lateinit var tvPermissionText: TextView
    private lateinit var cardSwitch: View

    private var permHints: List<String> = emptyList()
    private var lastPaused = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            tvClock.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            // 临时关闭到点自动恢复时，主界面要跟着变回来
            val pausedNow = LimitControl.isPaused(this@MainActivity)
            if (pausedNow != lastPaused) {
                lastPaused = pausedNow
                refreshUi()
            } else if (pausedNow) {
                refreshBottomBar()
            }
            clockHandler.postDelayed(this, 20_000)
        }
    }

    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LimitControl.ACTION_PAUSE_CHANGED) refreshUi()
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ProfileSelectionActivity.ACTION_PROFILE_CHANGED) {
                loadStats()
            }
        }
    }

    private val usageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TimeTrackingService.ACTION_USAGE_UPDATE) {
                val daily = intent.getIntExtra(TimeTrackingService.EXTRA_DAILY_USAGE, 0)
                val sessionLimit = intent.getIntExtra(TimeTrackingService.EXTRA_SESSION_LIMIT, -1)
                val sessionUsage = intent.getIntExtra(TimeTrackingService.EXTRA_SESSION_USAGE, 0)
                val profileName = intent.getStringExtra(TimeTrackingService.EXTRA_PROFILE_NAME)
                renderLive(daily, sessionLimit, sessionUsage, profileName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvClock = findViewById(R.id.tvClock)
        tvProfileBadge = findViewById(R.id.tvProfileBadge)
        tvRemainLabel = findViewById(R.id.tvRemainLabel)
        tvRemainHero = findViewById(R.id.tvRemainHero)
        tvRemainUnit = findViewById(R.id.tvRemainUnit)
        tvDetail = findViewById(R.id.tvDetail)
        pbUsage = findViewById(R.id.pbUsage)
        permissionBar = findViewById(R.id.permissionBar)
        tvPermissionText = findViewById(R.id.tvPermissionText)

        cardSwitch = findViewById(R.id.cardSwitch)
        val cardSettings = findViewById<View>(R.id.cardSettings)

        TvFocus.attach(cardSwitch, cardSettings, permissionBar)
        cardSwitch.setOnClickListener {
            startActivity(Intent(this, ProfileSelectionActivity::class.java))
        }
        cardSettings.setOnClickListener { showAdminPinDialog() }
        permissionBar.setOnClickListener {
            if (LimitControl.isPaused(this)) showResumePinDialog() else requestNextPermission()
        }

        registerReceiver(profileReceiver, IntentFilter(ProfileSelectionActivity.ACTION_PROFILE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(usageReceiver, IntentFilter(TimeTrackingService.ACTION_USAGE_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(pauseReceiver, IntentFilter(LimitControl.ACTION_PAUSE_CHANGED), Context.RECEIVER_NOT_EXPORTED)

        clockHandler.post(clockTick)
        cardSwitch.post { TvFocus.focus(cardSwitch) }
    }

    override fun onResume() {
        super.onResume()
        lastPaused = LimitControl.isPaused(this)
        checkPermissions()
        loadStats()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTick)
        try {
            unregisterReceiver(profileReceiver)
            unregisterReceiver(usageReceiver)
            unregisterReceiver(pauseReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    // ===== 数据 =====

    private fun loadStats() {
        scope.launch {
            val data = withContext(Dispatchers.IO) { readProfileSnapshot() } ?: return@launch
            renderProfile(data)
        }
    }

    private data class Snapshot(
        val profile: Profile,
        val dailyUsed: Int,
        val sessionUsed: Int,
        val restRemain: Int
    )

    private suspend fun readProfileSnapshot(): Snapshot? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val name = prefs.getString("CURRENT_PROFILE_NAME", "儿童") ?: "儿童"
        val dao = AppDatabase.getDatabase(applicationContext).profileDao()
        val profile = dao.getProfileByName(name) ?: return null
        val today = LocalDate.now().toString()
        val log = dao.getUsageLog(profile.id, today)
        val dailyUsed = log?.totalUsageMinutes ?: 0

        val lastEnd = prefs.getLong("last_session_end_time_${profile.id}", 0)
        val savedSession = prefs.getInt("session_usage_${profile.id}", 0)
        var sessionUsed = 0
        var restRemain = 0
        if (lastEnd > 0 && profile.restDurationMinutes > 0) {
            val elapsed = System.currentTimeMillis() - lastEnd
            val restMs = profile.restDurationMinutes * 60 * 1000L
            if (elapsed < restMs) {
                sessionUsed = savedSession
                restRemain = ((restMs - elapsed) / 60000L).toInt() + 1
            }
        }

        return Snapshot(profile, dailyUsed, sessionUsed, restRemain)
    }

    private fun renderProfile(s: Snapshot) {
        val p = s.profile
        val ctx = this
        tvProfileBadge.text = p.name

        if (!p.isRestricted) {
            tvRemainLabel.text = "这个档案随便看"
            tvRemainHero.text = "∞"
            tvRemainHero.setTextColor(ContextCompat.getColor(ctx, R.color.sun))
            tvRemainUnit.text = ""
            tvDetail.text = "家长模式，不限制观看时长"
            pbUsage.progress = 0
            return
        }

        val dailyRemain = if (p.dailyLimitMinutes > 0)
            (p.dailyLimitMinutes - s.dailyUsed).coerceAtLeast(0) else -1
        val sessionRemain = if (p.sessionLimitMinutes > 0)
            (p.sessionLimitMinutes - s.sessionUsed).coerceAtLeast(0) else -1

        // 家长临时关掉限制时，这里要说清楚「关到什么时候」，免得忘了还开着自己
        if (LimitControl.isPaused(this)) {
            val mins = LimitControl.remainMinutes(this)
            tvRemainLabel.text = "限时已临时关闭"
            tvRemainHero.text = mins.toString()
            tvRemainUnit.text = "分钟后恢复"
            val c = ContextCompat.getColor(ctx, R.color.sky)
            tvRemainHero.setTextColor(c)
            tvRemainUnit.setTextColor(c)
            tvDetail.text = if (dailyRemain >= 0)
                "这段时间不计时也不限制 · 今天还剩 $dailyRemain 分钟额度"
            else "这段时间不计时也不限制"
            pbUsage.progress = if (p.dailyLimitMinutes > 0)
                ((p.dailyLimitMinutes - s.dailyUsed).toFloat() / p.dailyLimitMinutes * 100)
                    .toInt().coerceIn(0, 100) else 100
            return
        }

        // 数字和进度条统一口径：都跟着「当前生效的那个限制」走
        val useSession = sessionRemain >= 0 && (dailyRemain < 0 || sessionRemain <= dailyRemain)
        val remain = when {
            useSession -> sessionRemain
            dailyRemain >= 0 -> dailyRemain
            else -> -1
        }

        val isResting = sessionRemain == 0 && dailyRemain != 0 && s.restRemain > 0
        val outOfDaily = dailyRemain == 0

        when {
            outOfDaily -> {
                tvRemainLabel.text = "今天看完啦"
                tvRemainHero.text = "0"
                tvRemainHero.setTextColor(ContextCompat.getColor(ctx, R.color.coral))
                tvRemainUnit.text = "分钟"
                tvRemainUnit.setTextColor(ContextCompat.getColor(ctx, R.color.coral))
                tvDetail.text = "今天的时间用完了，明天再来吧"
            }
            isResting -> {
                tvRemainLabel.text = "还要休息"
                tvRemainHero.text = s.restRemain.toString()
                tvRemainHero.setTextColor(ContextCompat.getColor(ctx, R.color.sky))
                tvRemainUnit.text = "分钟"
                tvRemainUnit.setTextColor(ContextCompat.getColor(ctx, R.color.sky))
                tvDetail.text = if (dailyRemain > 0) "休息完还能看 $dailyRemain 分钟" else "休息完就能继续看了"
            }
            remain < 0 -> {
                tvRemainLabel.text = "今天还能看"
                tvRemainHero.text = "∞"
                tvRemainHero.setTextColor(ContextCompat.getColor(ctx, R.color.sun))
                tvRemainUnit.text = ""
            }
            else -> {
                // 大数字跟着「当前生效的那个限制」走，标题也得跟着变，
                // 否则本场剩 20 分钟会被读成「今天只剩 20 分钟」。
                tvRemainLabel.text = if (useSession) "本场还能看" else "今天还能看"
                tvRemainHero.text = remain.toString()
                val c = ContextCompat.getColor(ctx, if (remain <= 5) R.color.coral else R.color.sun)
                tvRemainHero.setTextColor(c)
                tvRemainUnit.text = "分钟"
                tvRemainUnit.setTextColor(c)
                val parts = mutableListOf<String>()
                // 副行只放「另一个口径」，别把同一个数字再说一遍
                if (useSession && dailyRemain >= 0) parts.add("今天还剩 $dailyRemain 分钟")
                if (!useSession && sessionRemain >= 0) parts.add("本场还能看 $sessionRemain 分钟")
                if (p.restDurationMinutes > 0) parts.add("看完休息 ${p.restDurationMinutes} 分钟")
                tvDetail.text = if (parts.isEmpty()) "今天不限时长" else parts.joinToString(" · ")
            }
        }

        pbUsage.progress = when {
            isResting && p.restDurationMinutes > 0 ->
                ((p.restDurationMinutes - s.restRemain).toFloat() / p.restDurationMinutes * 100)
                    .toInt().coerceIn(0, 100)
            outOfDaily -> 0
            useSession && p.sessionLimitMinutes > 0 ->
                ((p.sessionLimitMinutes - s.sessionUsed).toFloat() / p.sessionLimitMinutes * 100).toInt().coerceIn(0, 100)
            p.dailyLimitMinutes > 0 ->
                ((p.dailyLimitMinutes - s.dailyUsed).toFloat() / p.dailyLimitMinutes * 100).toInt().coerceIn(0, 100)
            else -> 100
        }
    }

    private fun renderLive(daily: Int, sessionLimit: Int, sessionUsage: Int, profileName: String?) {
        if (!profileName.isNullOrEmpty()) tvProfileBadge.text = profileName
        if (LimitControl.isPaused(this)) return
        // 休息中 / 今天已看完时，以 renderProfile 算出的状态为准，别被每几秒一次的广播覆盖掉
        val resting = tvRemainLabel.text == "还要休息" || tvRemainLabel.text == "今天看完啦"
        if (resting || sessionLimit < 0) return

        val remain = (sessionLimit - sessionUsage).coerceAtLeast(0)
        tvRemainHero.text = remain.toString()
        tvRemainUnit.text = "分钟"
        val color = ContextCompat.getColor(this, if (remain <= 5) R.color.coral else R.color.sun)
        tvRemainHero.setTextColor(color)
        tvRemainUnit.setTextColor(color)
        tvRemainLabel.text = "本场还能看"
        tvDetail.text = "今天已看 $daily 分钟"
    }

    // ===== 权限 =====

    private fun hasOverlayPerm() = Settings.canDrawOverlays(this)

    private fun hasAdminPerm(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun hasUsagePerm(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun checkPermissions() {
        // 计时和遮罩只依赖「悬浮窗 + 使用情况」；
        // 设备管理只用于自动关屏，TCL 会反复撤销它，缺了也必须让监控照常跑。
        val blocking = mutableListOf<String>()
        if (!hasOverlayPerm()) blocking.add("悬浮窗")
        if (!hasUsagePerm()) blocking.add("使用情况")

        val canRun = blocking.isEmpty()
        if (canRun) startServiceSafely()

        val hints = blocking.toMutableList()
        if (!hasAdminPerm()) hints.add("设备管理")
        permHints = hints

        refreshBottomBar()

        // 提示条显隐会打乱焦点，兜底把焦点放回第一个入口
        if (window.currentFocus == null) {
            cardSwitch.post { TvFocus.focus(cardSwitch) }
        }
    }

    /** 底部那条提示：优先显示「限时临时关闭」，其次才是缺权限。 */
    private fun refreshBottomBar() {
        if (LimitControl.isPaused(this)) {
            val mins = LimitControl.remainMinutes(this)
            permissionBar.visibility = View.VISIBLE
            tvPermissionText.text = "限时已临时关闭，还剩 $mins 分钟 · 点这里恢复"
            return
        }

        if (permHints.isEmpty()) {
            permissionBar.visibility = View.GONE
            return
        }

        permissionBar.visibility = View.VISIBLE
        val canRun = !permHints.contains("悬浮窗") && !permHints.contains("使用情况")
        tvPermissionText.text = if (canRun) {
            "设备管理没开启，时间到只挡屏不关屏，点这里开启"
        } else {
            "还差 ${permHints.joinToString("、")} 权限，点这里开启"
        }
    }

    private fun refreshUi() {
        refreshBottomBar()
        loadStats()
    }

    private fun requestNextPermission() {
        when {
            !hasOverlayPerm() -> {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            !hasAdminPerm() -> {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val cn = ComponentName(this, AdminReceiver::class.java)
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "开启后才能在时间用完时关掉屏幕。")
                    }
                )
            }
            !hasUsagePerm() -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }

    private fun startServiceSafely() {
        try {
            startForegroundService(Intent(this, TimeTrackingService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "启动监控失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAdminPinDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val storedPin = prefs.getString("ADMIN_PIN", "1234") ?: "1234"
        PinPadDialog(this, "请输入家长密码", verify = { it == storedPin }) {
            startActivity(Intent(this@MainActivity, AdminActivity::class.java))
        }.show()
    }

    private fun showResumePinDialog() {
        val storedPin = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("ADMIN_PIN", "1234") ?: "1234"
        PinPadDialog(this, "输入家长密码，恢复限时", verify = { it == storedPin }) {
            LimitControl.resume(this)
            lastPaused = false
            Toast.makeText(this, "限时已恢复", Toast.LENGTH_SHORT).show()
            refreshUi()
        }.show()
    }
}
