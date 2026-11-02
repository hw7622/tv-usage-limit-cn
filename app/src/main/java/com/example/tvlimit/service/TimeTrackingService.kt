package com.example.tvlimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.data.Profile
import com.example.tvlimit.data.UsageLog
import com.example.tvlimit.receiver.AdminReceiver
import com.example.tvlimit.ui.CountdownRingView
import com.example.tvlimit.ui.ParentGateActivity
import com.example.tvlimit.ui.ProfileSelectionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.example.tvlimit.ui.AppBlockedActivity

class TimeTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "TvLimitServiceChannel"
        const val NOTIFICATION_ID = 1
        // 20 秒一轮：原来 60 秒才判一次限额，到点后还能白看将近一分钟。
        // 计时改成按真实经过的毫秒累计，所以缩短间隔不会让时长算多。
        const val CHECK_INTERVAL_MS = 20_000L

        /**
         * 第一次建栈时回看多久。
         * 必须够长：孩子可能一坐下就点开片子连看两小时，中途没有任何「切换」事件。
         * 只看 30 分钟的话，服务重启后建不出栈，就不知道现在停在哪个应用上。
         */
        private const val INITIAL_QUERY_WINDOW_MS = 6 * 60 * 60_000L

        /** 拦截提示最短显示时长：太短孩子根本看不清原因。 */
        private const val BLOCK_OVERLAY_MIN_SHOW_MS = 8_000L

        /**
         * 会跟着视频一起 resume、但「不是孩子在看的内容」的包。
         * TCL 的皮肤层和系统 UI 属于这一类，判定前台时直接跳过。
         */
        private val TRANSPARENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.tcl.walleve",
            "com.tcl.cyberui.overlay"
        )

        const val ACTION_USAGE_UPDATE = "com.example.tvlimit.ACTION_USAGE_UPDATE"
        const val EXTRA_DAILY_USAGE = "daily_usage"
        const val EXTRA_SESSION_LIMIT = "session_limit"
        const val EXTRA_SESSION_USAGE = "session_usage"
        const val EXTRA_PROFILE_NAME = "profile_name"

        const val ACTION_PROFILE_UPDATED = "com.example.tvlimit.ACTION_PROFILE_UPDATED"
        const val EXTRA_PROFILE_ID_UPDATE = "profile_id_update"

        /** 家长验证结束后，让服务重新判断该不该接着计时。 */
        const val ACTION_RECHECK = "com.example.tvlimit.ACTION_RECHECK"

        private const val PREF_LAST_SESSION_END_PREFIX = "last_session_end_time_"
        private const val PREF_SESSION_USAGE_PREFIX = "session_usage_"
        private const val PREF_SESSION_SAVED_AT_PREFIX = "session_saved_at_"
        // 「这一场」属于哪一天。没有它的话，昨天没用完的本场时长会被今天接着算，
        // 表现就是孩子早上才看几分钟就被判「休息」。
        private const val PREF_SESSION_DATE_PREFIX = "session_date_"
        // 最后一次「真的在看」的时刻（不是写文件时刻）。续算窗口必须用它：
        // onDestroy 每次被电视管家杀掉都会把 saved_at 刷成现在，用 saved_at 判断，
        // 只要杀/起反复发生，昨天的本场进度就能一路续到今天。
        private const val PREF_SESSION_ACTIVE_AT_PREFIX = "session_active_at_"
        // 「今天的已用量」是按哪种范围记的：ALL=整台电视，APPS=只看勾选的应用。
        // 只在「整机 → 只看勾选应用」时用它判断是否要清零（见 applyScopeChangeIfNeeded）。
        private const val PREF_DAILY_SCOPE_PREFIX = "daily_scope_"
        /** 运行状态（排查用，外部可读） */
        private const val PREF_LAST_TICK_AT = "last_tick_at"
        private const val PREF_LAST_TICK_STATE = "last_tick_state"
    }

    /**
     * 服务内部所有协程都跑在这个 scope 上。
     *
     * 必须有 SupervisorJob + 异常处理器：以前用裸的 CoroutineScope(Dispatchers.Main)，
     * 只要有一个协程抛了未捕获异常（数据库出错、某个 View 找不到……），整个 scope 就会被
     * 取消，之后所有 launch 全部静默失效 —— 表现就是「用着用着就不计时、也不拦了」，
     * 而且不会自愈。现在单个协程出错不影响其他协程。
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                Log.e("TvLimit", "Service coroutine crashed (isolated): ${e.message}", e)
            }
    )
    private var trackingJob: Job? = null
    private var restrictedAppCheckJob: Job? = null
    private lateinit var database: AppDatabase
    @Volatile
    private var currentProfile: Profile? = null
    private var wasLimitReachedAtStart = false
    /** 防止 onCreate / onStartCommand 并发进来时重复初始化。 */
    private var initializing = false

    // Usage Tracking
    @Volatile
    private var accumulatedDailyUsage = 0
    @Volatile
    private var currentSessionUsage = 0
    private var lastSessionEndTime: Long = 0
    private var currentLogDate: String = LocalDate.now().toString()
    /** 上次结算时刻，用来按真实毫秒累计时长（0 表示需要重新起算）。 */
    private var lastTickAt = 0L
    /** 不足一分钟的零头，攒够再进位，避免来回抖动把时间算丢。 */
    private var pendingMs = 0L
    /** 上次把本场进度写盘的时刻，用来节流（见 persistSessionProgress）。 */
    private var lastPersistAt = 0L
    /** 上次写运行状态的时间，节流用。 */
    private var lastStateWriteAt = 0L

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null // Warning Overlay
    private var timeLeftOverlayView: View? = null // Time Left Overlay
    private var isOverlayShowing = false
    private var sleepTimer: CountDownTimer? = null

    // App Foreground State
    private var isAppInForeground = false
    private var isScreenOn = true
    /**
     * 此刻处于 resumed 状态的包，栈顶 = 正在前台的应用。
     *
     * 为什么用栈而不是「时间戳最大的那条 resume 事件」：
     * ① 时间戳最大的那条可能是一个盖在画面上、但孩子根本没在用的系统层（TCL 皮肤层），
     *    只看「最新一条」会被它带偏，判定成「不在受限应用里」，到点也不拦；
     * ② 反过来，如果那个系统层先 pause 再没有新的 resume，栈会正确回落到下面那个应用。
     * 栈对「打开了什么 / 退出了什么」是幂等的，重复处理同一批事件也不会算错。
     */
    private val resumedStack = ArrayDeque<String>()
    /** 已处理到的事件时间。只增量处理新事件，避免每轮重放几小时的事件。 */
    private var eventCursor = 0L
    /** 最近一次探测到的前台应用，用于判断「现在是不是在受限制的应用里」。 */
    @Volatile
    private var lastTopPackage: String? = null
    /**
     * 探测失败时的兜底值（一旦探到过就记住，不再被 null 覆盖）。
     * 只在「这一轮一条事件都没拿到」（权限被撤 / 系统忙）时才用。
     */
    @Volatile
    private var lastKnownTopPackage: String? = null
    /** 上一次打日志的前台应用，只在变化时输出，免得刷屏。 */
    private var lastLoggedTopPackage: String? = null
    /** 当前拦截遮罩是为哪个应用挂的；孩子切到允许的应用时要据此撤掉。 */
    @Volatile
    private var blockedOverlayPkg: String? = null
    /** 拦截遮罩挂上去的时刻，用来保证最短显示时长。 */
    @Volatile
    private var blockedOverlayAt = 0L
    /** 这一场是不是已经记过「结束时间」，避免每轮循环都刷新，导致休息永远不满。 */
    private var sessionEndRecorded = false
    /** 这一场的遮罩里是否已经跑过「10 秒关屏」。休息中重新显示遮罩时不要再倒计时、再关屏。 */
    private var restingLockAttempted = false
    /**
     * 遮罩当前是不是「休息态」（圆环已收起、正在显示还要休息多久）。
     * 心跳要靠它决定要不要每秒往下跳秒数 —— 10 秒关屏倒计时那段由圆环自己走，不要去改。
     */
    private var isRestUiActive = false
    /** 遮罩心跳：系统把悬浮窗摘掉（或孩子按返回把窗口搞没了）之后要能自己补回来。 */
    private var overlayHeartbeatJob: Job? = null
    /** 拦截页防抖，避免每秒重复拉起同一个页面。 */
    private var lastBlockedPkg: String? = null
    private var lastBlockedAt = 0L
    /** 「这个应用现在不能看」的悬浮窗拦截页（不依赖后台启动 Activity）。 */
    private var appBlockedOverlayView: View? = null
    private var appBlockedHeartbeatJob: Job? = null

    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            isAppInForeground = true
            updateForegroundState()
        } else if (event == Lifecycle.Event.ON_STOP) {
            isAppInForeground = false
            updateForegroundState()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d("TvLimit", "Screen OFF. Resetting to Default Profile.")
                handleScreenOff()
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                Log.d("TvLimit", "Screen ON. Resuming tracking.")
                handleScreenOn()
            }
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ProfileSelectionActivity.ACTION_PROFILE_CHANGED) {
                val profileId = intent.getIntExtra(ProfileSelectionActivity.EXTRA_PROFILE_ID, -1)
                if (profileId != -1) {
                    handleProfileSwitch(profileId)
                }
            } else if (intent?.action == ACTION_PROFILE_UPDATED) {
                // Reload current profile if it matches or just reload generally
                handleProfileUpdated()
            }
        }
    }

    /** 家长临时关闭 / 恢复限时、或者改「是否显示角落倒计时」，都要立刻生效。 */
    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LimitControl.ACTION_PAUSE_CHANGED -> {
                    if (LimitControl.isPaused(this@TimeTrackingService)) {
                        Log.d("TvLimit", "Pause on. Removing overlays.")
                        removeOverlay()
                        if (isScreenOn && !isAppInForeground && currentProfile != null) {
                            refreshBadge()
                        } else {
                            removeTimeLeftOverlay()
                        }
                    } else {
                        Log.d("TvLimit", "Pause off. Re-checking limits.")
                        if (isScreenOn && !isAppInForeground && currentProfile != null) {
                            startTracking()
                            // 走 checkLimits()，不要直接 showWarningOverlay()：
                            // 「只限制勾选应用」模式下弹全屏遮罩会把整台电视锁住，
                            // 连家长允许看的应用也一起挡住了。
                            checkLimits()
                        }
                    }
                }
                LimitControl.ACTION_BADGE_CHANGED -> {
                    Log.d("TvLimit", "Badge setting changed. show=${LimitControl.showBadge(this@TimeTrackingService)}")
                    if (isScreenOn && !isAppInForeground && currentProfile != null) refreshBadge()
                }
                ACTION_RECHECK -> handleRecheck()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ★ 第一件事必须是 startForeground()。
        // 系统给 startForegroundService() 只有 5 秒窗口，超时就抛
        // RemoteServiceException("did not then call Service.startForeground()") 直接把进程干掉。
        // 原来这行排在 Room 建库后面，装完新版本 / 开机时磁盘慢一点就踩线 ——
        // 日志里 21:22:05 那次崩溃就是这个，之后限制整整失效了十几分钟。
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("TvLimit", "startForeground failed: ${e.message}", e)
        }

        Log.d("TvLimit", "Service onCreate")
        database = AppDatabase.getDatabase(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        // Use ProcessLifecycleOwner to track foreground state
        serviceScope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        val profileFilter = IntentFilter().apply {
            addAction(ProfileSelectionActivity.ACTION_PROFILE_CHANGED)
            addAction(ACTION_PROFILE_UPDATED)
        }
        // Must specify export flag for Android 14+ if targeting new SDKs, though here receiver is dynamic inside service
        // Just standard register works for implicit broadcasts if package limited or explicit.
        // But internal component broadcast is usually fine.
        // 这两个广播只有本应用（同 UID 的 Activity）会发。
        // 之前用的是 RECEIVER_EXPORTED，等于任何第三方 App 都能发一条广播把限制「暂停」掉，
        // 孩子装个小工具就能绕过，改成 NOT_EXPORTED。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileReceiver, profileFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(profileReceiver, profileFilter)
        }

        val pauseFilter = IntentFilter().apply {
            addAction(LimitControl.ACTION_PAUSE_CHANGED)
            addAction(LimitControl.ACTION_BADGE_CHANGED)
            addAction(ACTION_RECHECK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pauseReceiver, pauseFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pauseReceiver, pauseFilter)
        }

        // 挂上看门狗：服务被杀后 15 分钟内会被自动拉起来
        ServiceWatchdog.schedule(this)

        // Initial setup
        initializeProfile()
    }

    private var initRetry = 0

    /**
     * 开机、装新版本、看门狗、主页按钮都会重复调 startService。
     * 返回 START_STICKY：万一被系统回收，系统会尽量把它再拉起来（intent 为 null），
     * 这时只需保证档案已经初始化过一遍，其余状态都在 prefs / 数据库里。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：不管从哪里被拉起（开机 / 覆盖安装 / 看门狗 / 家长点开 App），
        // 先保证已经进入前台状态，再干别的
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("TvLimit", "startForeground(onStartCommand) failed: ${e.message}", e)
        }
        if (currentProfile == null) initializeProfile()
        return START_STICKY
    }

    private fun initializeProfile() {
        if (initializing || currentProfile != null) return
        initializing = true
        serviceScope.launch {
            try {
                // 默认从「儿童」档启动；若刚在切换页选了别的档，则按选择启动，
                // 避免服务未运行时切换被静默丢弃。
                val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val pendingId = prefs.getInt("PENDING_PROFILE_ID", -1)
                val pendingProfile = if (pendingId > 0) database.profileDao().getProfileById(pendingId) else null
                if (pendingId > 0) prefs.edit().remove("PENDING_PROFILE_ID").apply()
                currentProfile = pendingProfile ?: database.profileDao().getProfileByName("儿童")
                if (currentProfile != null) {
                    initRetry = 0
                    loadUsageStats()
                    saveProfileToPrefs(currentProfile?.name)
                    Log.d("TvLimit", "Initialized with profile: ${currentProfile?.name}")
                    // If screen is already on, start tracking
                    handleScreenOn()
                }
            } catch (e: Exception) {
                Log.e("TvLimit", "initializeProfile failed: ${e.message}", e)
            } finally {
                initializing = false
            }

            // 刚开机时数据库可能还没就绪；以前这里失败就永远空转，整台电视都不受管。
            // 现在最多重试 12 次（约 1 分钟），之后由看门狗重新拉起服务再试。
            if (currentProfile == null && initRetry < 12 && isScreenOn) {
                initRetry++
                Log.w("TvLimit", "Profile not ready, retry #$initRetry in 5s")
                delay(5000)
                initializeProfile()
            }
        }
    }

    private fun handleProfileUpdated() {
        serviceScope.launch {
            val cProfile = currentProfile ?: return@launch
            // Reload from DB
            val updatedProfile = database.profileDao().getProfileById(cProfile.id)
            if (updatedProfile != null) {
                currentProfile = updatedProfile
                // Update prefs just in case name changed
                saveProfileToPrefs(updatedProfile.name)
                Log.d("TvLimit", "Profile Updated: ${updatedProfile.name}. Reloading stats/limits.")

                // 家长刚改的可能就是「限制哪些应用」——口径变了今天已用量要重算，
                // 不能等下次服务重启才生效（否则孩子点开勾选的应用还是立刻被拦）
                applyScopeChangeIfNeeded(updatedProfile)

                // We don't reset session usage here, just re-check limits against current usage
                // 勾选的应用可能改了，监控范围要跟着重启
                stopRestrictedAppMonitoring()
                startRestrictedAppMonitoring()
                checkLimits()

                // If unrestricted now, remove overlay
                if (!updatedProfile.isRestricted) {
                    removeOverlay()
                    removeTimeLeftOverlay()
                    removeAppBlockedOverlay()
                }

                // Force UI update
                sendUsageUpdateBroadcast()
            }
        }
    }

    private fun handleProfileSwitch(profileId: Int) {
        serviceScope.launch {
            // Save state of current profile before switching
            if (currentProfile != null) {
                // 正在休息时不能重写「本场结束时间」，否则切个档案休息又从零开始数
                if (isLimitReached()) persistSessionProgress(force = true) else saveProfileState(currentProfile!!)
            }

            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().remove("PENDING_PROFILE_ID").apply()

            val newProfile = database.profileDao().getProfileById(profileId)
            if (newProfile != null) {
                currentProfile = newProfile
                loadUsageStats()

                saveProfileToPrefs(newProfile.name)
                Log.d("TvLimit", "Switched to Profile: ${newProfile.name}")
                Toast.makeText(applicationContext, "当前档案：${newProfile.name}", Toast.LENGTH_SHORT).show()

                // 换了档案，应用监控范围也要跟着换
                stopRestrictedAppMonitoring()
                // 新档案是新的一场，关屏流程重新算
                restingLockAttempted = false
                // If new profile is NOT restricted, remove overlay
                if (!newProfile.isRestricted) {
                    removeOverlay()
                    removeTimeLeftOverlay()
                } else {
                    removeOverlay() // 先撤掉上一个档案留下的遮罩
                    startRestrictedAppMonitoring()
                    checkLimits()
                }
            }
        }
    }

    private fun isLimitReached(): Boolean {
        val cProfile = currentProfile ?: return false
        if (!cProfile.isRestricted) return false
        // 家长临时关掉限制期间一律不拦，也不计时（见 updateUsage）
        if (LimitControl.isPaused(this)) return false
        val daily = cProfile.dailyLimitMinutes
        val session = cProfile.sessionLimitMinutes

        val dailyReached = daily > -1 && accumulatedDailyUsage >= daily
        val sessionReached = session > -1 && currentSessionUsage >= session

        if (dailyReached || sessionReached) {
            Log.d("TvLimit", "Limit Reached! DailyReached=$dailyReached ($accumulatedDailyUsage/$daily), SessionReached=$sessionReached ($currentSessionUsage/$session)")
            return true
        }
        return false
    }

    /**
     * @param freshSession 跨天时用：新的一天必须是新的一场，不许从 prefs 里「接着算」。
     *        否则昨天那场已经用满的 session 会被原样搬过来，孩子一过零点就又被拦住。
     */
    private suspend fun loadUsageStats(freshSession: Boolean = false) {
        val today = LocalDate.now().toString()
        currentLogDate = today // Update log date to today on load
        val cProfile = currentProfile ?: return
        // 换档案/换天后重新判断「这一场是否已记录结束」
        sessionEndRecorded = false

        val usageLog = database.profileDao().getUsageLog(cProfile.id, today)
        accumulatedDailyUsage = usageLog?.totalUsageMinutes ?: 0
        // 口径换过就得把今天这个数字丢掉重来（必须在读出 accumulatedDailyUsage 之后）
        applyScopeChangeIfNeeded(cProfile)
        currentSessionUsage = 0 // Reset session usage on profile load / switch?
        // Check if we should resume session or start new
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val now = System.currentTimeMillis()
        val lastEnd = prefs.getLong(PREF_LAST_SESSION_END_PREFIX + cProfile.id, 0)
        val savedSessionUsage = prefs.getInt(PREF_SESSION_USAGE_PREFIX + cProfile.id, 0)
        val savedAt = prefs.getLong(PREF_SESSION_SAVED_AT_PREFIX + cProfile.id, 0)
        val savedDate = prefs.getString(PREF_SESSION_DATE_PREFIX + cProfile.id, null)
        val activeAt = prefs.getLong(PREF_SESSION_ACTIVE_AT_PREFIX + cProfile.id, 0)

        val restMs = cProfile.restDurationMinutes * 60 * 1000L
        // ① 休息时间还没满 —— 接着上一场算
        val restNotFinished =
            lastEnd > 0 && cProfile.restDurationMinutes > 0 && now - lastEnd < restMs
        // ② 服务被系统/电视管家杀掉后又被看门狗拉起来（没走正常关屏流程）——
        //    进度是几分钟前刚存的，接着算，不然孩子一「重启」就白拿一整场额度
        val killedRecently = activeAt > 0 && savedSessionUsage > 0 && now - activeAt < 10 * 60_000L
        // ③ 存的这一场必须属于「今天」。TCL 会周期性杀进程，看门狗拉起来时 onDestroy 又会
        //    把 savedAt 刷新成现在 —— 只要杀/起反复发生，「10 分钟内被杀」这个条件永远成立，
        //    昨天的本场时长就能一路续到今天早上。实测：今天只看了 9 分钟，本场却被判 20 分钟
        //    （额度满），孩子刚坐下就被赶去休息。
        val sameDay = savedDate == today

        currentSessionUsage =
            if (!freshSession && sameDay && (restNotFinished || killedRecently)) savedSessionUsage else 0
        if (currentSessionUsage > 0) {
            Log.w("TvLimit", "Resuming Session. Usage=$currentSessionUsage (rest=$restNotFinished, killed=$killedRecently, date=$savedDate)")
        } else {
            Log.w("TvLimit", "New Session. (saved=$savedSessionUsage@$savedAt/active=$activeAt, date=$savedDate, today=$today, rest=$restNotFinished, killed=$killedRecently)")
        }

        // 兜底：本场分钟数已经满了，但「没有正在进行的休息」（lastEnd 为 0 或早已过点）。
        // 这种账一定是上一轮残留的（进程被杀、休息计时已经走完、或升级前写下的），
        // 照原样恢复就是白白把孩子关在外头 20 分钟，而他自己根本没看。
        val sessionLimit = cProfile.sessionLimitMinutes
        if (currentSessionUsage > 0 && sessionLimit > 0 &&
            currentSessionUsage >= sessionLimit && !restNotFinished
        ) {
            Log.w("TvLimit", "Stale full session ($currentSessionUsage/$sessionLimit) without active rest -> reset")
            currentSessionUsage = 0
            prefs.edit().putInt(PREF_SESSION_USAGE_PREFIX + cProfile.id, 0).apply()
        }

        Log.w("TvLimit", "Loaded Usage: $accumulatedDailyUsage mins today.")
        // 换了档案 / 换了天，计时从这一刻重新起算
        lastTickAt = 0L
        pendingMs = 0L
        sendUsageUpdateBroadcast()
    }

    /**
     * 「今天的已用量」是按哪种范围统计的：ALL = 整台电视，APPS = 只看勾选的那几个应用。
     *
     * 只看「种类」，不看具体勾了哪些应用 —— 勾选列表里加一个 / 减一个应用，还是同一套
     * 计量范围，不该把今天的账清零（家长随手加个应用就白送半天额度，那不是防沉迷）。
     */
    private fun scopeKind(p: Profile): String {
        val apps = p.restrictedApps?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        return if (apps.isNullOrEmpty()) "ALL" else "APPS"
    }

    /**
     * 统计范围换了种类，今天这个账怎么处理。
     *
     * 规则是**不对称**的，两个方向不能一样：
     *
     * ① 整机 → 只看勾选应用：**清零**。
     *    必须清。上午按整机记的 90 分钟里绝大部分不是这几个应用的时间，旧值留着，
     *    孩子一打开勾选的应用就被判「额度用完」，一场都没开始就被拦（2026-11-01 真实 bug）。
     *
     * ② 只看勾选应用 → 改回整机：**沿用**。
     *    勾选应用的时长本来就是整机时长的子集，沿用只会少算、不会多给；
     *    反过来清零等于家长一改设置就白送 90 分钟。
     *
     * ③ 勾选列表里增删应用（仍是 APPS）：**沿用**，范围变大变小都继续算同一个账。
     *
     * 清零时本场进度和休息起点也一起清，否则「休息中」的 20 分钟还会顺着旧场次算到他头上。
     */
    private suspend fun applyScopeChangeIfNeeded(p: Profile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val key = PREF_DAILY_SCOPE_PREFIX + p.id
        val kind = scopeKind(p)
        val storedRaw = prefs.getString(key, null)
        // 兼容 v19 存下的「包名列表」格式：那时按具体列表判定，等于现在的 APPS，
        // 不能因为升级就把今天已经算好的账再抹一次
        val stored = when {
            storedRaw == null -> null
            storedRaw == "ALL" || storedRaw == "APPS" -> storedRaw
            else -> "APPS"
        }
        if (stored == kind) return

        if (kind == "ALL") {
            // 改回整机：沿用已经用掉的时间，只记下范围种类
            Log.w("TvLimit", "Scope -> ALL [$stored -> ALL], keep today's usage ($accumulatedDailyUsage mins) for ${p.name}")
            prefs.edit().putString(key, kind).apply()
            return
        }

        val today = LocalDate.now().toString()
        Log.w("TvLimit", "Scope -> APPS [$stored -> APPS], reset today's usage for ${p.name}")
        try {
            database.profileDao().resetUsageLog(p.id, today)
        } catch (e: Exception) {
            Log.e("TvLimit", "resetUsageLog failed: ${e.message}")
        }
        accumulatedDailyUsage = 0
        currentSessionUsage = 0
        sessionEndRecorded = false
        restingLockAttempted = false
        lastTickAt = 0L
        pendingMs = 0L
        prefs.edit()
            .putString(key, kind)
            .putLong(PREF_LAST_SESSION_END_PREFIX + p.id, 0L)
            .putInt(PREF_SESSION_USAGE_PREFIX + p.id, 0)
            .putLong(PREF_SESSION_SAVED_AT_PREFIX + p.id, 0L)
            .putLong(PREF_SESSION_ACTIVE_AT_PREFIX + p.id, 0L)
            .putString(PREF_SESSION_DATE_PREFIX + p.id, today)
            .apply()
        sendUsageUpdateBroadcast()
    }

    private fun saveProfileState(profile: Profile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(PREF_LAST_SESSION_END_PREFIX + profile.id, now)
            .putInt(PREF_SESSION_USAGE_PREFIX + profile.id, currentSessionUsage)
            .putLong(PREF_SESSION_SAVED_AT_PREFIX + profile.id, now)
            .putString(PREF_SESSION_DATE_PREFIX + profile.id, currentLogDate)
            .apply()
        Log.d("TvLimit", "Saved Profile State [${profile.name}]: LastEnd=$now, SessionUsage=$currentSessionUsage")
    }

    /**
     * 把「这一场已经看了多少分钟」落到磁盘上。
     *
     * 以前只在关屏和切档案时才写，服务被电视管家杀掉就丢了 —— 重启后这一场从 0 开始，
     * 孩子等于白拿一整段额度。现在每 5 分钟存一次（一次 apply 而已，开销可忽略）。
     */
    private fun persistSessionProgress(force: Boolean = false) {
        val p = currentProfile ?: return
        // 节流：调用方是按「分钟数是 5 的倍数」触发的，那一分钟里每 20 秒都会命中，
        // 没必要反复写同一个值。
        // force=true 用于「服务要死了 / 要换档案」这种一锤子买卖，必须写下去。
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 4 * 60_000L) return
        lastPersistAt = now
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .edit()
            .putInt(PREF_SESSION_USAGE_PREFIX + p.id, currentSessionUsage)
            .putLong(PREF_SESSION_SAVED_AT_PREFIX + p.id, System.currentTimeMillis())
            .putString(PREF_SESSION_DATE_PREFIX + p.id, currentLogDate)
            .apply()
    }

    /**
     * 把「当前循环状态」写到 prefs：每轮一次，外部可以直接读。
     * 字段含义：t=时间戳 s=本场已用 d=今日已用 r=是否到限额 p=是否暂停 top=前台应用 scope=是否在计范围内
     */
    private fun writeRuntimeState() {
        val now = System.currentTimeMillis()
        if (now - lastStateWriteAt < 15_000L) return
        lastStateWriteAt = now
        try {
            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
                .putLong(PREF_LAST_TICK_AT, now)
                .putString(
                    PREF_LAST_TICK_STATE,
                    "s=${currentSessionUsage} d=${accumulatedDailyUsage}" +
                        " r=${isLimitReached()} p=${LimitControl.isPaused(this)}" +
                        " top=$lastTopPackage scope=${inTrackedScope()}" +
                        " blocks=${appBlockedOverlayView != null} rest=${isOverlayShowing}"
                )
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun handleScreenOff() {
        // Capture tracking state BEFORE stopping
        val wasTracking = trackingJob?.isActive == true

        isScreenOn = false
        stopTracking()
        removeTimeLeftOverlay()

        // Only update session end time if we were actually tracking (watching).
        // AND if we didn't start in a blocked state.
        // 休息中关电视不能把「本场结束时间」改成现在 —— 那样孩子的休息得从头再数一遍，
        // 关一下电视反而被多关 20 分钟。
        if (wasTracking && !wasLimitReachedAtStart && !isLimitReached()) {
             lastSessionEndTime = System.currentTimeMillis()
             // Persist last session end time for CURRENT PROFILE
             if (currentProfile != null) {
                  saveProfileState(currentProfile!!)
             }
        } else {
             Log.d("TvLimit", "Screen OFF. Not updating SessionEnd. Tracking=$wasTracking, BlockedAtStart=$wasLimitReachedAtStart")
        }




        // Reset to Default Profile (Child)
        serviceScope.launch {
            val childProfile = database.profileDao().getProfileByName("儿童")
            if (childProfile != null) {
                currentProfile = childProfile
                Log.d("TvLimit", "Reset to Child Profile")
                loadUsageStats()
                saveProfileToPrefs(childProfile.name)
            }
        }
        removeOverlay()
        // 关屏时把拦截页也摘掉：关机再开是「新的一场」，别让上一场的拦截页糊在新画面上
        removeAppBlockedOverlay()
    }

    private fun handleScreenOn() {
        isScreenOn = true
        if (currentProfile == null) return
        // 关屏失败、遮罩还留在屏幕上时，开屏后直接回到「休息中」的样式，
        // 否则会看到圆环又从 10 跳到 0 再卡住
        if (isOverlayShowing) {
            markRestingUi()
            // 开机/唤醒这一下，系统会重新分配窗口焦点，而且经常分给下面的应用：
            // 遮罩明明盖着，「我是家长」就是按不动 —— 用户就是这么撞上的。
            // 等系统把焦点落定（约 1 秒）再抢回来，比立刻抢更稳。
            serviceScope.launch(Dispatchers.Main) {
                delay(1000)
                if (!isOverlayShowing || isAppInForeground) return@launch
                Log.d("TvLimit", "Screen on: reclaiming overlay focus")
                forceOverlayWindowFocus()
                overlayView?.findViewById<Button>(R.id.btnSwitchProfile)?.requestFocus()
            }
        }
        startTracking()
    }

    /**
     * 家长验证页关闭后重新判断状态。
     * 稍等一下再判断，等系统把「应用退到后台」的时机走完，否则会误以为还在前台。
     */
    private fun handleRecheck() {
        serviceScope.launch {
            delay(1200)
            if (!isScreenOn) return@launch
            if (isAppInForeground) {
                if (currentProfile == null) return@launch
                stopTracking()
                removeOverlay(releaseFocus = !isLimitReached())
                removeTimeLeftOverlay()
            } else {
                startTracking()
                // 同上：应用级模式不能弹全屏遮罩，交给 checkLimits() 按模式分流
                if (!LimitControl.isPaused(this@TimeTrackingService)) {
                    checkLimits()
                }
            }
        }
    }

    /**
     * 跨天重置。
     *
     * 原来是「只在 startTracking / handleScreenOff 里判一次日期」，可这两条路都会因为
     * `trackingJob?.isActive == true` 而直接 return —— 电视一直开着（或者一直停在遮罩上）
     * 时，日期永远不重判，昨天用满的 `accumulatedDailyUsage` 就一直挂在内存里。
     * 家长把限制临时关到零点，00:00 一解除暂停，下一轮循环立刻拿昨天的旧值判定
     * 「今天也看完了」，遮罩又弹出来 —— 孩子早上开电视啥也看不了。
     *
     * 现在改成每轮循环（20 秒）先判一次日期，跨天就当场重算：
     * 日额度按今天的记录（新的一天 = 0），本场额度归零，休息计时作废。
     *
     * @return true 表示刚刚发生了跨天
     */
    private suspend fun checkDayRollover(): Boolean {
        val today = LocalDate.now().toString()
        if (today == currentLogDate) return false

        Log.w("TvLimit", "Day rollover: $currentLogDate -> $today. Resetting daily usage and session.")
        val p = currentProfile
        if (p != null) {
            // 昨天那场的「结束时间 / 本场用量」全部作废，
            // 否则新的一天会接着昨天的休息计时和本场额度算。
            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
                .putLong(PREF_LAST_SESSION_END_PREFIX + p.id, 0L)
                .putInt(PREF_SESSION_USAGE_PREFIX + p.id, 0)
                .putLong(PREF_SESSION_SAVED_AT_PREFIX + p.id, 0L)
                .putString(PREF_SESSION_DATE_PREFIX + p.id, today)
                .putLong(PREF_SESSION_ACTIVE_AT_PREFIX + p.id, 0L)
                .apply()
        }

        loadUsageStats(freshSession = true)
        sessionEndRecorded = false
        restingLockAttempted = false
        // 零点不是「休息结束」，别自作主张把暂停的视频按起来 —— 半夜突然出声会吓人。
        // 孩子早上开电视，自然会有新的播放动作。
        pausedPlaybackByUs = false
        focusHeld = false
        releaseAudioFocus()

        if (isLimitReached()) {
            // 新的这一天额度本来就用完了（家长把日额度设得比 0 还低之类），保持拦截
            Log.d("TvLimit", "Day rollover but limit still reached, keeping overlay")
        } else {
            if (isOverlayShowing) removeOverlay()
            removeTimeLeftOverlay()
            refreshBadge()
        }
        sendUsageUpdateBroadcast()
        return true
    }

    private fun startTracking() {
        // 三个早退条件都记一笔：排查「明明该计时却没动静」时，
        // 第一时间就能看出是卡在哪一步，不用靠猜。
        if (trackingJob?.isActive == true) {
            Log.d("TvLimit", "startTracking skipped: already running")
            return
        }
        if (isAppInForeground) {
            Log.d("TvLimit", "startTracking skipped: our app is in foreground")
            return
        }
        if (!isScreenOn) {
            Log.d("TvLimit", "startTracking skipped: screen off")
            return
        }

        // 重新起算计时：中间关过屏 / 切过档案，不能把断档的时间补进去
        lastTickAt = 0L
        pendingMs = 0L

        // Check Rest Duration
        val cProfile = currentProfile
        if (cProfile != null && cProfile.isRestricted && cProfile.restDurationMinutes > 0) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val lastEnd = prefs.getLong(PREF_LAST_SESSION_END_PREFIX + cProfile.id, 0)
            if (lastEnd > 0) {

                 val elapsed = System.currentTimeMillis() - lastEnd
                 val required = cProfile.restDurationMinutes * 60 * 1000L

                 Log.d("TvLimit", "Checking Rest Duration: LastEnd=$lastEnd, Elapsed=${elapsed/1000}s, Required=${required/1000}s")

                 if (elapsed >= required) {
                     // Rest Duration MET.
                     // IMPORTANT: Reset session usage if we are starting a fresh session after a long break.
                     if (currentSessionUsage > 0) {
                         Log.d("TvLimit", "Rest duration MET. Resetting Session Usage from $currentSessionUsage to 0.")
                         currentSessionUsage = 0
                         sendUsageUpdateBroadcast()
                     }
                 } else {
                     Log.d("TvLimit", "Rest duration NOT met. Continuing previous session usage: $currentSessionUsage")
                     // Do NOT block here. Let isLimitReached() determine if we are blocked.
                 }
            }

        }

        // 跨天（关机过夜再开机是最常见的情形）：立刻重算，别拿昨天的额度判今天
        if (LocalDate.now().toString() != currentLogDate) {
            serviceScope.launch { checkDayRollover() }
        }

        refreshBadge()


        // Rest Duration Check Logic (Simplified for now)
        // If we want detailed Rest enforcement, we need to persist "LastSessionEnd" in DB?
        // For now, in-memory is fine.

        // Check limit state at start of tracking
        wasLimitReachedAtStart = isLimitReached()
        Log.d("TvLimit", "StartTracking: LimitReachedAtStart=$wasLimitReachedAtStart")

        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    // 跨天判定必须放在「暂停」之前：
                    // 家长临时关到 23:59 或次日 0 点，解除暂停后第一轮就得用新一天的额度，
                    // 否则昨天用满的数字还留在内存里，遮罩立刻又糊上来。
                    checkDayRollover()
                    if (LimitControl.isPaused(this@TimeTrackingService)) {
                        // 暂停中：不计数也不拦截，只按开关决定要不要显示角标
                        if (isOverlayShowing) removeOverlay()
                        removeAppBlockedOverlay()
                        refreshBadge()
                        delay(CHECK_INTERVAL_MS)
                        continue
                    }
                    // 休息时长已经到了就先清零，这一场重新开始
                    // （原来只有关屏才记结束时间，屏幕一直亮着就永远不恢复）
                    resetSessionIfRestFinished()
                    updateUsage()
                    // 运行状态落到 prefs 里，外部 run-as 就能读到「循环还活着吗、卡在哪一步」。
                    // 这台电视会过滤 D 级日志，光靠 logcat 排查会误判成「程序没跑」。
                    writeRuntimeState()

                    // 每 5 分钟把本场进度落到磁盘，服务被杀也不至于归零
                    if (currentSessionUsage > 0 && currentSessionUsage % 5 == 0) {
                        persistSessionProgress()
                    }

                    // 自愈：应用级监控的协程万一意外退出，这里把它拉回来
                    if (appScopedMode() && restrictedAppCheckJob?.isActive != true) {
                        startRestrictedAppMonitoring()
                    }

                    if (isLimitReached()) {
                        // 第一次到达限额时记下「本场结束」时间点，休息从这里开始算。
                        // 注意只写一次：服务重启或每轮循环都重写的话，休息永远不满，孩子会被永久锁死。
                        if (!sessionEndRecorded) {
                            sessionEndRecorded = markSessionEndOnce()
                            Log.w(
                                "TvLimit",
                                "Limit reached -> rest starts. daily=$accumulatedDailyUsage/${currentProfile?.dailyLimitMinutes}, " +
                                    "session=$currentSessionUsage/${currentProfile?.sessionLimitMinutes}"
                            )
                            // 到点这一刻让正在播的停下来。
                            // 勾了具体应用的模式不显示全屏遮罩，光靠拦截页盖不住声音，这里也要停。
                            pausePlaybackIfPlaying()
                        }
                        if (appScopedMode()) {
                            // 只限制勾选的应用：不整屏遮罩，让应用监控逐个把它们挡掉，
                            // 其他应用（学习类之类）还能正常用
                            if (isOverlayShowing) removeOverlay()
                            refreshBadge()
                        } else {
                            showWarningOverlay()
                            // 休息期间每轮刷新两件事：
                            // ①「还要休息 X 分钟」的分钟数（否则会一直停在第一次的数字上）
                            // ② 音频焦点（孩子按主页开了别的应用，新应用会把焦点抢走）
                            if (isOverlayShowing && restingLockAttempted) markRestingUi()
                            holdAudioFocus()
                            if (isMusicActive()) {
                                // 有播放器不认音频焦点、自己接着放（或者孩子用蓝牙遥控
                                // 按了播放键）：重新抢一次，还不听话就退回桌面兜底
                                focusHeld = false
                                pausePlaybackIfPlaying()
                            }
                        }
                    } else {
                        sessionEndRecorded = false
                        if (isOverlayShowing) removeOverlay()
                        // 额度恢复了（休息结束 / 家长加时），拦截页也该撤掉
                        removeAppBlockedOverlay()
                        // 休息结束 / 额度还有：把音频焦点还回去，播放器会自己接着播。
                        // 「只限制部分应用」模式没有遮罩，就靠这一句恢复。
                        releaseAudioFocus()
                        // 有些播放器不认「焦点归还」这件事（我们按过的媒体暂停键它倒是记得），
                        // 所以显式再按一次播放。只在确实是我们停的、而且现在真的没在响时才按，
                        // 免得把家长正在看的东西又打开一次。
                        if (pausedPlaybackByUs && !isMusicActive()) {
                            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                        }
                        pausedPlaybackByUs = false
                        refreshBadge()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单轮出错不能让整个计时循环死掉，记一笔继续跑
                    Log.e("TvLimit", "tracking loop error, keep going: ${e.message}", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        startRestrictedAppMonitoring()
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        lastTickAt = 0L
        pendingMs = 0L
        stopRestrictedAppMonitoring()
        removeTimeLeftOverlay()
    }

    /**
     * 记「这一场结束」的时间点，但只记一次。
     *
     * 已经记过就直接返回 true，不再覆盖时间戳 —— 否则每轮循环/每次服务重启都把
     * 结束时间往后推，休息时长永远凑不满，勾选的应用会变成永久打不开。
     */
    private fun markSessionEndOnce(): Boolean {
        val p = currentProfile ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val key = PREF_LAST_SESSION_END_PREFIX + p.id
        if (prefs.getLong(key, 0) > 0) return true

        prefs.edit()
            .putLong(key, System.currentTimeMillis())
            .putInt(PREF_SESSION_USAGE_PREFIX + p.id, currentSessionUsage)
            .putString(PREF_SESSION_DATE_PREFIX + p.id, currentLogDate)
            .putLong(PREF_SESSION_ACTIVE_AT_PREFIX + p.id, System.currentTimeMillis())
            .apply()
        Log.w("TvLimit", "Session end recorded. SessionUsage=$currentSessionUsage, date=$currentLogDate")
        return true
    }

    /**
     * 休息够久就把这一场清零。
     *
     * 原实现只在关屏时写「本场结束时间」，屏幕一直亮着就永远不恢复；
     * 现在按真实时间戳判断，休息满额自动重新开始，不用把电视关掉再开。
     */
    private fun resetSessionIfRestFinished() {
        val cProfile = currentProfile ?: return
        if (!cProfile.isRestricted) return
        if (currentSessionUsage <= 0) return
        val restMin = cProfile.restDurationMinutes
        if (restMin <= 0) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val key = PREF_LAST_SESSION_END_PREFIX + cProfile.id
        val lastEnd = prefs.getLong(key, 0)
        if (lastEnd <= 0) return
        if (System.currentTimeMillis() - lastEnd < restMin * 60_000L) return

        Log.w("TvLimit", "Rest finished. Reset session usage ($currentSessionUsage -> 0).")
        currentSessionUsage = 0
        sessionEndRecorded = false
        // 下一场到点时要重新走一次「10 秒关屏」
        restingLockAttempted = false
        prefs.edit()
            .putLong(key, 0L)
            // 本场已用也一起清：只清时间戳不清分钟数的话，下次服务重启会读到
            // 「usage=20 但 lastEnd=0」，虽然不会误拦，但会让人以为账没清干净。
            .putInt(PREF_SESSION_USAGE_PREFIX + cProfile.id, 0)
            .putLong(PREF_SESSION_SAVED_AT_PREFIX + cProfile.id, 0L)
            .putLong(PREF_SESSION_ACTIVE_AT_PREFIX + cProfile.id, 0L)
            .apply()
        sendUsageUpdateBroadcast()
    }

    private fun startRestrictedAppMonitoring() {
        if (restrictedAppCheckJob?.isActive == true) return
        val cProfile = currentProfile ?: return
        if (!cProfile.isRestricted) return
        val restrictedAppsStr = cProfile.restrictedApps
        // 没勾具体应用 = 整台电视限时，走全屏遮罩，不需要应用级监控
        if (restrictedAppsStr.isNullOrEmpty()) return

        val restrictedSet = restrictedAppsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        restrictedAppCheckJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val topPackage = getTopPackageName(usageStatsManager)
                    lastTopPackage = topPackage
                    // 关键：只有「今天/这一场的时长真的用完了」才拦。
                    // 时间没到，勾选过的应用照样能打开。
                    if (topPackage != null && restrictedSet.contains(topPackage) && isLimitReached()) {
                        Log.w("TvLimit", "Blocking restricted app after limit reached: $topPackage")
                        withContext(Dispatchers.Main) {
                            blockApp(topPackage)
                        }
                    } else if (topPackage != null &&
                        blockedOverlayPkg != null &&
                        topPackage != blockedOverlayPkg &&
                        topPackage != packageName &&
                        !restrictedSet.contains(topPackage) &&
                        !isHomePackage(topPackage) &&
                        System.currentTimeMillis() - blockedOverlayAt > BLOCK_OVERLAY_MIN_SHOW_MS
                    ) {
                        // 孩子被拦之后自己换去看了「允许的应用」（比如戏曲 APP）：
                        // 遮罩得跟着撤，否则一块全屏提示挡着，连能看的也看不成。
                        // 桌面不算 —— 拦完会回桌面，那时候提示还得留着让他看见原因。
                        // 另外保留最短显示时间：TCL 桌面会把上一个应用直接拉回前台，
                        // 不留缓冲的话提示只闪 1 秒，孩子根本来不及看。 
                        Log.w("TvLimit", "Switched to allowed app $topPackage, dismiss blocked overlay")
                        withContext(Dispatchers.Main) {
                            removeAppBlockedOverlay()
                            blockedOverlayPkg = null
                        }
                    }
                } catch (e: CancellationException) {
                    // 换档案 / 本应用到前台会主动取消这个协程，这不是错误，别记成红色日志
                    throw e
                } catch (e: Exception) {
                    Log.e("TvLimit", "Error checking apps: ${e.message}")
                }
                delay(1000) // Check every second
            }
        }
    }

    private fun stopRestrictedAppMonitoring() {
        restrictedAppCheckJob?.cancel()
        restrictedAppCheckJob = null
        lastTopPackage = null
        // 换档案 / 改勾选范围时要一起清掉，免得拿上一个档案的前台应用去判新档案
        lastKnownTopPackage = null
        // 前台栈和游标也清掉：下次重新从头建栈，避免沿用上一个档案的现场
        resumedStack.clear()
        eventCursor = 0L
    }

    /**
     * 取当前前台应用。
     *
     * 原来用 queryUsageStats 按 lastTimeUsed 取最大值，在电视上很不准
     * （经常拿到输入法、系统组件，或者干脆空）。改用 queryEvents 找最近一次
     * 「界面被切到前台」的事件，稳定得多。
     *
     * 【重要】查询窗口必须比「一次连续观看的时长」长。
     * 原来只看最近 60 秒：孩子连着看同一部片子，UsageEvents 里不会每秒都有
     * MOVE_TO_FOREGROUND / ACTIVITY_RESUMED，60 秒一过就什么都查不到，
     * 这里返回 null → 监控循环整条判定被跳过 → 20 分钟到了也不拦。
     * 表现就是「刚点开应用会被拦，连着看到 20 分钟反倒没人管」。
     * 现在窗口拉到 20 分钟，并且探测失败时沿用上一次的结果兜底。
     */
    private fun getTopPackageName(usageStatsManager: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        if (eventCursor <= 0L) eventCursor = now - INITIAL_QUERY_WINDOW_MS

        val events = try {
            usageStatsManager.queryEvents(eventCursor, now)
        } catch (e: Exception) {
            Log.e("TvLimit", "queryEvents failed: ${e.message}")
            null
        }
        // 查询本身失败（权限被撤 / 系统忙）：退回上一次的结果，别把状态清了
        if (events == null) return resumedStack.lastOrNull() ?: lastKnownTopPackage

        var received = 0
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            received++
            val pkg = event.packageName ?: continue
            // TCL 的皮肤层 / 系统 UI 会跟着视频一起 resume，它盖在画面上但不是「孩子在看的东西」。
            // 不剔除的话，播到一半皮肤层 resume 一次，这里就认为「前台是皮肤」，
            // 受限应用从此不再被判定命中 —— 正是「看满 20 分钟不拦」的第二个成因。
            if (pkg in TRANSPARENT_PACKAGES) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumedStack.remove(pkg)
                    resumedStack.addLast(pkg)
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    resumedStack.remove(pkg)
                }
            }
        }
        // 留 2 秒重叠：时间戳相同的边界事件会被重复处理一次，
        // 而栈的增删是幂等的，重复处理不会算错。
        eventCursor = now - 2_000L

        val top = resumedStack.lastOrNull()
        if (top == null) {
            // 这一轮一条事件都没收到（权限被撤 / 电视刚开机统计服务还没热）：
            // 用上次的结果兜底，避免「服务一起来就整台电视没人管」。
            if (received == 0) return lastKnownTopPackage
            // 有事件但没有任何应用处于前台（都在后台/已退出）：当成「没在看东西」，
            // 返回 null，调用方就不会误拦，也不会凭空计时。
            return null
        }

        if (top != lastLoggedTopPackage) {
            lastLoggedTopPackage = top
            Log.w("TvLimit", "Foreground -> $top (stack=${resumedStack.size})")
        }
        lastKnownTopPackage = top
        return top
    }

    /**
     * 「只限制勾选的应用」模式。
     * 勾了具体应用就只在这个范围内计时并拦截，其他应用不计时不打扰；
     * 一个都不勾就是整台电视限时（原来的行为）。
     */
    private fun appScopedMode(): Boolean {
        val p = currentProfile ?: return false
        return p.isRestricted && !p.restrictedApps.isNullOrEmpty()
    }

    /**
     * 这个包是不是「桌面」。
     * 拦完会主动 goHome，桌面到前台不能当成「孩子换去看了别的应用」，
     * 否则提示刚贴上去就被自己撤掉了。
     */
    private fun isHomePackage(pkg: String): Boolean = isLauncher(pkg)

    /**
     * 当前这一轮要不要计入时长。
     * 没开应用级限制就一律计时；开了就必须前台确实是勾选过的应用。
     *
     * 探测不到前台应用时**不计时**：宁可少记（孩子占便宜），也不能凭空累加，
     * 否则坐在桌面发呆也会把额度耗掉，到点突然被拦，家长完全无法理解。
     */
    private fun inTrackedScope(): Boolean {
        if (!appScopedMode()) return true
        val list = currentProfile?.restrictedApps ?: return true
        val top = lastTopPackage ?: return false
        return list.split(",").map { it.trim() }.contains(top)
    }

    /**
     * 拦截页上那句原因。
     * 原来只有「休息一下再来吧」，家长分不清到底是「今天额度用完了」还是
     * 「这一场 20 分钟到点了」，看着就像莫名其妙被拦。
     */
    private fun blockReason(): String? {
        val p = currentProfile ?: return null
        val daily = p.dailyLimitMinutes
        val session = p.sessionLimitMinutes
        return when {
            daily > -1 && accumulatedDailyUsage >= daily ->
                "今天 ${daily} 分钟已经用完了，明天再来吧"
            session > -1 && currentSessionUsage >= session ->
                "这次 ${session} 分钟到点了，休息 ${p.restDurationMinutes} 分钟再看"
            else -> null
        }
    }

    /**
     * 拦截页第二行说明。
     * 剩余分钟已经用大数字单独显示了，这里只说「为什么」，不重复「休息 X 分钟」。
     */
    private fun blockDetailText(appName: String?, reason: String?): String {
        val prefix = if (appName != null) "「$appName」" else ""
        val p = currentProfile
        val daily = p?.dailyLimitMinutes ?: -1
        val dailyUsedUp = daily > 0 && accumulatedDailyUsage >= daily
        val session = p?.sessionLimitMinutes ?: -1
        return when {
            dailyUsedUp -> "${prefix}今天的 $daily 分钟已经用完了，明天再来吧"
            session > -1 && currentSessionUsage >= session -> "${prefix}这次 $session 分钟到点了"
            reason != null -> reason
            else -> "${prefix}现在不能看，休息一下再来吧"
        }
    }

    /**
     * 拦截页上的「还要休息 X 分 XX 秒」。
     * 心跳每秒调一次，秒数实时递减 —— 孩子自己就能看出还要等多久，不会一直按遥控器试探。
     */
    private fun updateAppBlockedRest(view: View) {
        val block = view.findViewById<View>(R.id.appBlockedRestBlock) ?: return
        val p = currentProfile
        val dailyUsedUp = p != null && p.dailyLimitMinutes > 0 &&
            accumulatedDailyUsage >= p.dailyLimitMinutes
        if (!dailyUsedUp &&
            applyRestCountdown(view, R.id.tvAppBlockedRestMin, R.id.tvAppBlockedRestSec)
        ) {
            if (block.visibility != View.VISIBLE) block.visibility = View.VISIBLE
        } else {
            if (block.visibility != View.GONE) block.visibility = View.GONE
        }
    }

    private fun blockApp(packageName: String) {
        // Double check not to block ourselves or launcher
        if (packageName == this.packageName) return

        // 拦截页已经在前台（前台应用就是自己）就不用再拉一次，
        // 否则每秒重建一次页面，遥控器刚按下去页面又跳回来
        if (lastTopPackage == this.packageName) return

        // 探测有延迟，同一秒可能连着判两次；1.5 秒内不重复拉同一个拦截页。
        // 原来给的是 3 秒 —— 孩子按主页再立刻点开被拦的应用，能白用一下。
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPkg && now - lastBlockedAt < 1500) return
        lastBlockedPkg = packageName
        lastBlockedAt = now

        // 顺序很关键：先把遮罩挂上去，再退回桌面。
        //
        // 以前这里是 startActivity(AppBlockedActivity)。在 TCL 这类电视上，播放中的视频
        // 走的是独立的硬件层，压在普通应用窗口之上；后台起的拦截页虽然已经 RESUMED
        // （日志里 topPackage 立刻变成自己），窗口却被视频层盖住 —— 家长看到的就是
        // 「20 分钟只是把片子暂停了，遮罩没出来，按一下遥控器才冒出来」。
        //
        // 现在改成：先用悬浮窗把遮罩贴到最上层（悬浮窗不受后台启动限制），立刻 goHome
        // 让视频应用退到后台、硬件层释放，遮罩自然显形。
        val wasShowing = appBlockedOverlayView != null
        val shown = showAppBlockedOverlay(packageName)
        if (shown) {
            blockedOverlayPkg = packageName
            blockedOverlayAt = System.currentTimeMillis()
        }
        if (!shown) {
            // 悬浮窗权限被系统撤掉时才退回 Activity 方案，聊胜于无
            Log.w("TvLimit", "Overlay unavailable, falling back to AppBlockedActivity")
            val intent = Intent(this, AppBlockedActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("blocked_package", packageName)
            intent.putExtra("blocked_reason", blockReason())
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("TvLimit", "startActivity blocked: ${e.message}")
            }
        }
        // 只在第一次拦的时候回桌面。检测有延迟，同一秒可能连判两次；
        // 每次都 goHome 会把家长/孩子刚按下的操作一直打断。
        if (!wasShowing) goHome()
    }

    /**
     * 把「这个应用现在不能看」贴成全屏悬浮窗。
     *
     * 返回 false 表示窗口没加上（十有八九是悬浮窗权限被撤），调用方要换方案。
     */
    private fun showAppBlockedOverlay(packageName: String): Boolean {
        appBlockedOverlayView?.let { v ->
            // 窗口还挂在系统上就直接用；已经被系统悄悄摘掉就重来一次
            if (v.isAttachedToWindow) return true
            removeAppBlockedOverlay()
        }

        val view = try {
            LayoutInflater.from(this).inflate(R.layout.view_app_blocked_overlay, null)
        } catch (e: Exception) {
            Log.e("TvLimit", "inflate app blocked overlay failed: ${e.message}")
            return false
        }

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            null
        }
        val reason = blockReason()
        view.findViewById<TextView>(R.id.tvAppBlockedDetails)?.text = blockDetailText(appName, reason)
        updateAppBlockedRest(view)

        val btnHome = view.findViewById<Button>(R.id.btnAppBlockedHome)
        val btnParent = view.findViewById<Button>(R.id.btnAppBlockedParent)

        btnHome.setOnClickListener {
            removeAppBlockedOverlay()
            goHome()
        }
        btnParent.setOnClickListener {
            // 只撤遮罩 + 打开密码页。不能停计时、不能写「本场结束时间」，
            // 否则孩子点一下再返回，休息倒计时就重新数了。
            removeAppBlockedOverlay()
            val intent = Intent(applicationContext, ParentGateActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("TvLimit", "open parent gate failed: ${e.message}")
            }
        }

        // 和整机遮罩同一套键盘兜底：焦点万一落在根布局上，方向键没落点、
        // OK 键没人接，家长连密码页都进不去。这里明确处理掉。
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_MENU -> {
                    removeAppBlockedOverlay()
                    goHome()
                    true
                }
                KeyEvent.KEYCODE_HOME -> true
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    btnParent.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    btnParent.performClick()
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        // 时间到了就别让状态栏之类的东西露出来
        return try {
            windowManager?.addView(view, params)
            appBlockedOverlayView = view
            btnParent.post { btnParent.requestFocus() }
            startAppBlockedHeartbeat()
            Log.w("TvLimit", "App blocked overlay shown for $packageName")
            true
        } catch (e: Exception) {
            Log.e("TvLimit", "addView app blocked overlay failed: ${e.message}")
            appBlockedOverlayView = null
            false
        }
    }

    private fun removeAppBlockedOverlay() {
        val v = appBlockedOverlayView ?: return
        appBlockedOverlayView = null
        blockedOverlayPkg = null
        appBlockedHeartbeatJob?.cancel()
        appBlockedHeartbeatJob = null
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            Log.e("TvLimit", "removeView app blocked overlay failed: ${e.message}")
        }
        Log.d("TvLimit", "App blocked overlay removed")
    }

    /**
     * 拦截页心跳。
     *
     * 孩子按主页、开别的应用、系统回收窗口之后，悬浮窗可能被直接摘掉，
     * 而 appBlockedOverlayView 还非 null，之后所有拦截都会被这一句挡住，限制就消失了。
     * 每秒确认窗口还挂着，掉了就重来一次（顺便把秒数往下跳）。
     */
    private fun startAppBlockedHeartbeat() {
        if (appBlockedHeartbeatJob?.isActive == true) return
        appBlockedHeartbeatJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                // 1 秒一次，秒数才会真的在走（大数字是「X 分 XX 秒」）
                delay(1000)
                val v = appBlockedOverlayView ?: break
                if (!isLimitReached()) {
                    // 额度恢复了（家长加时 / 休息结束），拦截页自己撤掉
                    removeAppBlockedOverlay()
                    break
                }
                if (v.isAttachedToWindow != true) {
                    Log.w("TvLimit", "App blocked overlay detached by system, re-adding")
                    appBlockedOverlayView = null
                    appBlockedHeartbeatJob = null
                    if (isScreenOn && !isAppInForeground) {
                        showAppBlockedOverlay(lastBlockedPkg ?: return@launch)
                    }
                    break
                }
                // 大数字每秒跟着走，别停在开页时那一刻
                updateAppBlockedRest(v)

                val btn = v.findViewById<Button>(R.id.btnAppBlockedParent)
                if (btn != null && !isAppInForeground) {
                    if (v.hasWindowFocus() != true) {
                        // 焦点被桌面抢走时把这层窗口提回顶部
                        try {
                            windowManager?.updateViewLayout(v, v.layoutParams)
                        } catch (e: Exception) {
                            Log.e("TvLimit", "updateViewLayout failed: ${e.message}")
                        }
                        btn.requestFocus()
                    } else if (!btn.isFocused) {
                        btn.requestFocus()
                    }
                }
            }
        }
    }

    /**
     * 按真实经过的时间累计观看分钟数。
     *
     * 原来是「每轮循环 +1 分钟」，所以循环间隔必须正好 60 秒，否则就算错，
     * 也就导致「到点了还得再等一分钟才拦」。
     * 现在用毫秒差值结算：间隔缩到 20 秒也不会多算，到点最多 20 秒内就介入。
     */
    private suspend fun updateUsage() {
        // 临时关闭期间不计数，孩子今天剩下的额度不会被这段时间吃掉
        if (LimitControl.isPaused(this)) {
            lastTickAt = 0L
            return
        }

        val now = System.currentTimeMillis()
        // 已经到限额、正在休息：这 20 分钟不算观看时间。
        // 以前这里照常累加，孩子盯着休息遮罩坐着，每日额度又被吃掉 20 分钟，
        // 表现就是「明明休息了，今天的总时长还是提前用完」。
        if (isLimitReached()) {
            lastTickAt = 0L
            return
        }
        // 只限制勾选应用的情况下，只有真的在看那些应用才计时
        if (!inTrackedScope()) {
            // 不计时，但时钟要跟着走，免得下次一口气补一大段
            lastTickAt = now
            return
        }
        if (lastTickAt <= 0L) {
            lastTickAt = now
            return
        }

        pendingMs += now - lastTickAt
        lastTickAt = now
        val minutes = (pendingMs / 60_000L).toInt()
        if (minutes <= 0) return
        pendingMs -= minutes * 60_000L

        accumulatedDailyUsage += minutes
        currentSessionUsage += minutes

        val cProfile = currentProfile ?: return

        // 记下「最后一次真的在看」的时刻：服务被杀后要不要续算，就看这个时间新不新
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putLong(PREF_SESSION_ACTIVE_AT_PREFIX + cProfile.id, now)
            .apply()

        // Use currentLogDate instead of LocalDate.now() to anchor to the "Logical Day"
        // This ensures usage continues to be logged to the "start date" of the session
        // even if we cross midnight, until the TV is turned off.
        val logDate = currentLogDate
        val dao = database.profileDao()

        val existingLog = dao.getUsageLog(cProfile.id, logDate)
        if (existingLog == null) {
            dao.insertUsageLog(UsageLog(profileId = cProfile.id, date = logDate, totalUsageMinutes = accumulatedDailyUsage))
        } else {
            dao.updateUsageMinutes(existingLog.id, accumulatedDailyUsage)
        }

        Log.d("TvLimit", "Usage Updated [${cProfile.name}]: Daily=$accumulatedDailyUsage/${cProfile.dailyLimitMinutes}, Session=$currentSessionUsage/${cProfile.sessionLimitMinutes}")
        sendUsageUpdateBroadcast()
    }

    private fun sendUsageUpdateBroadcast() {
        val cProfile = currentProfile ?: return
        val intent = Intent(ACTION_USAGE_UPDATE)
        intent.putExtra(EXTRA_DAILY_USAGE, accumulatedDailyUsage)
        intent.putExtra(EXTRA_SESSION_LIMIT, cProfile.sessionLimitMinutes)
        intent.putExtra(EXTRA_SESSION_USAGE, currentSessionUsage)
        intent.putExtra(EXTRA_PROFILE_NAME, cProfile.name)
        // 明确指定包名：接收方注册的是 RECEIVER_NOT_EXPORTED，
        // 不指定包名的话在 Android 14 上有被当隐式广播顺手丢掉的风险，界面就不刷新了。
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun checkLimits() {
         if (isLimitReached()) {
             if (appScopedMode()) {
                 // 只限制部分应用时不做整屏遮罩
                 if (isOverlayShowing) removeOverlay()
                 refreshBadge()
             } else {
                 showWarningOverlay()
             }
         } else {
             // 时间还有富余：把可能残留的遮罩撤掉
             if (isOverlayShowing) removeOverlay()
             removeAppBlockedOverlay()
             if (trackingJob?.isActive == true && !isAppInForeground) {
                 refreshBadge()
             }
         }
    }

    private fun showWarningOverlay() {
        if (isOverlayShowing) return
        removeTimeLeftOverlay() // Ensure time left overlay is gone
        if (isAppInForeground) return // Do not show overlay if app is in foreground
        isOverlayShowing = true

        serviceScope.launch(Dispatchers.Main) {
            // 关键：判断条件必须是「窗口真的还挂在系统上」。
            // 以前只看 overlayView != null —— 窗口被系统摘掉、或 removeView 失败之后，
            // 这个字段还留着旧对象，于是这里永远跳过 inflate+addView，遮罩再也出不来。
            if (overlayView?.isAttachedToWindow != true) {
                if (overlayView != null) {
                    try {
                        windowManager?.removeView(overlayView)
                    } catch (_: Exception) {
                    }
                    overlayView = null
                }
                val inflater = LayoutInflater.from(this@TimeTrackingService)
                overlayView = inflater.inflate(R.layout.view_warning_overlay, null)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.CENTER

                // 遮罩窗口是「可获焦」的，遥控器按键会送到这里而不是落到下面的播放器上。
                // 但控制器默认没人处理返回键，事件有可能继续往下传，孩子一按返回就把播放器
                // 退出去了 —— 休息结束没东西可续播，还能顺势退到桌面乱翻。这里明确吃掉返回。
                val root = overlayView!!
                root.isFocusable = true
                root.isFocusableInTouchMode = true
                root.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) {
                        false
                    } else when (keyCode) {
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE,
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_HOME -> true
                        // 兜底一：焦点万一又落回根布局（关屏再开机、系统抢过焦点之后很常见），
                        // 根布局上没有可导航的兄弟节点，方向键按下去等于石沉大海。
                        // 这里直接把焦点按到「我是家长」上，方向键就不会白按。
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            overlayView?.findViewById<Button>(R.id.btnSwitchProfile)?.requestFocus()
                            true
                        }
                        // 兜底二：焦点在根布局时 OK 键没人接，直接当成点按钮。
                        // 孩子按到这一步是想叫家长，不能让家长进不去。
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            overlayView?.findViewById<Button>(R.id.btnSwitchProfile)?.performClick()
                            true
                        }
                        else -> false
                    }
                }
                // 这里绝对不能给根布局 requestFocus。
                // 根布局自己拿住焦点后，遥控器方向键在它身上找不到可导航的目标，
                // 「我是家长」按钮就永远选不中 —— 实测按 DOWN 毫无反应、
                // uiautomator 里 LinearLayout focused=true 而按钮 focused=false，
                // 结果就是家长进不去密码页、也关不掉遮罩，整台电视干等。
                // 焦点必须落在按钮上：见 ensureButtonFocus()。

                val btnSwitch = overlayView!!.findViewById<Button>(R.id.btnSwitchProfile)
                btnSwitch.setOnClickListener {
                    // 这里只做两件事：撤掉遮罩、打开密码页。
                    // 绝不能像以前那样停计时 + 写「本场结束时间」——孩子点一下再按返回，
                    // 结束时间就被改成现在，休息倒计时重新数，反复点几下等于永久锁死。
                    // 音频焦点也不能还，否则密码页一出来，后面的视频就自己接着播了。
                    removeOverlay(releaseFocus = false)
                    // 家长验证页：输完密码可以直接选「切档案」或「临时关闭多久」
                    val intent = Intent(applicationContext, ParentGateActivity::class.java)
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(intent)
                }

                try {
                    windowManager?.addView(overlayView, params)
                } catch (e: Exception) {
                    // 悬浮窗权限被撤掉时 addView 会抛异常；
                    // 以前这里没复位状态，之后就永远认为「遮罩已经在显示」，再也不会尝试。
                    Log.e("TvLimit", "Error showing overlay: ${e.message}")
                    overlayView = null
                    isOverlayShowing = false
                    return@launch
                }
            }

            // 先让正在播的东西停下来。遮罩窗口在部分电视（TCL 这类用视频硬件层的机器）
            // 上盖不住播放画面，光加遮罩会出现「遮罩出来了，片子还在放」。
            pausePlaybackIfPlaying()

            if (restingLockAttempted || restRemainMinutes() > 0) {
                // 休息已经在进行中（孩子点了「我是家长」又退回来、或者服务被电视管家
                // 杀掉重启）：直接给「还要休息 X 分钟」，不要再跑 10 秒倒计时、再关一次屏。
                //
                // 以前这里只认内存里的 restingLockAttempted —— 服务一重启这个标志就回到
                // false，于是正在进行的休息被打断重来：先跑一遍 10 秒关屏倒计时，
                // 再显示「还要休息 15 分钟」（20 分钟休息的剩余量）。
                // 家长看到的就是「倒计时显示完，又让我休息 15 分钟」，以为被罚了两轮。
                // 现在用 prefs 里的「本场结束时间」推导，进程重启也不丢。
                markRestingUi()
            } else {
                resetRestUi()
                startSleepCountdown()
            }
            startOverlayHeartbeat()
            ensureButtonFocus()
        }
    }

    /**
     * 把遥控器焦点放到「我是家长」上。
     *
     * 根布局为了拦返回键是可获焦的，但它一旦持有焦点，方向键就没有落点，
     * 按钮彻底选不中。所以每次显示遮罩、每次刷新休息态，都要把焦点按回按钮。
     */
    private fun ensureButtonFocus() {
        val v = overlayView ?: return
        val btn = v.findViewById<Button>(R.id.btnSwitchProfile) ?: return
        btn.isFocusable = true
        btn.post {
            if (overlayView?.isAttachedToWindow == true && !btn.isFocused) {
                btn.requestFocus()
            }
        }
    }

    /**
     * 把输入焦点抢回遮罩窗口。
     *
     * 关屏再开机、系统弹了别的悬浮窗、从桌面切回来之后，遮罩还好好盖在屏幕上，
     * 但输入焦点可能已经跑到下面那层去了 —— 表现就是「画面明明盖着，遥控器按什么都没反应」。
     * 安卓没有公开的 requestWindowFocus()，但把 FLAG_NOT_FOCUSABLE 摘掉再挂上，
     * 系统会把它当成「这个窗口要开始收按键」，焦点就回来了。
     */
    private fun forceOverlayWindowFocus() {
        val v = overlayView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(v, lp)
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(v, lp)
            Log.d("TvLimit", "forceOverlayWindowFocus: reclaimed input focus")
        } catch (e: Exception) {
            Log.e("TvLimit", "forceOverlayWindowFocus failed: ${e.message}")
        }
    }

    /**
     * 遮罩心跳。
     *
     * 休息期间孩子会按返回、按主页、开别的应用，系统偶尔会把悬浮窗直接摘掉 ——
     * 但 isOverlayShowing 还是 true，之后所有 showWarningOverlay() 都被这一句挡住，
     * 遮罩就再也不回来了（以前就是这个表现：休息中途按了几下遥控器，限制就消失了）。
     * 现在每 3 秒确认一次窗口真的还挂在系统上。
     */
    private fun startOverlayHeartbeat() {
        if (overlayHeartbeatJob?.isActive == true) return
        overlayHeartbeatJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                // 1 秒一次：休息态的「X 分 XX 秒」要按真实秒数往下跳。
                // 以前是 3 秒，数字会一顿一顿地跳，看着像卡住。
                delay(1000)
                if (!isOverlayShowing) break
                if (overlayView?.isAttachedToWindow != true) {
                    Log.w("TvLimit", "Overlay was detached by system, re-adding")
                    overlayView = null
                    isOverlayShowing = false
                    overlayHeartbeatJob = null // 让重新显示时能再起一个新心跳
                    if (isLimitReached() && !isAppInForeground && isScreenOn) {
                        showWarningOverlay()
                    }
                    break
                }
                // 休息态：把「还剩 X 分 XX 秒」往下跳一格。
                // 10 秒关屏倒计时那段（isRestUiActive=false）由圆环自己在走，这里不要插手。
                if (isRestUiActive) overlayView?.let { updateRestBlockUi(it) }
                // 焦点自愈：
                // 1) 整个窗口丢了输入焦点（关屏再开机最常见）——把焦点抢回来；
                // 2) 窗口有焦点但焦点不在按钮上——按回按钮。
                // 只在不是本应用在前台时做，免得跟密码页抢。
                val btn = overlayView?.findViewById<Button>(R.id.btnSwitchProfile)
                if (btn != null && !isAppInForeground) {
                    if (overlayView?.hasWindowFocus() != true) {
                        Log.w("TvLimit", "Overlay has no window focus, reclaiming")
                        forceOverlayWindowFocus()
                        btn.requestFocus()
                    } else if (!btn.isFocused) {
                        Log.d("TvLimit", "Overlay lost focus, restoring to button")
                        btn.requestFocus()
                    }
                }
            }
        }
    }

    private fun startSleepCountdown() {
        sleepTimer?.cancel()
        sleepTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999) / 1000).toInt()
                overlayView?.findViewById<CountdownRingView>(R.id.ringCountdown)
                    ?.setCountdown(seconds, 10)
            }

            override fun onFinish() {
                // 关屏要靠设备管理权限，而 TCL 会过一段时间自动把它撤销，
                // 撤掉之后 lockNow() 什么都不做 —— 以前这里不管成不成都把圆环停在
                // 「0 秒」，看着就像卡死了。现在关屏成不成，界面都切到休息态。
                restingLockAttempted = true
                val locked = lockDevice()
                if (!locked) {
                    // 关不了屏就至少保证播放是停的
                    pausePlaybackIfPlaying()
                }
                markRestingUi()
            }
        }.start()
    }

    /** 遮罩刚出现时的样子：圆环 + 10 秒关屏倒计时。 */
    private fun resetRestUi() {
        val v = overlayView ?: return
        isRestUiActive = false
        v.findViewById<CountdownRingView>(R.id.ringCountdown)?.apply {
            visibility = View.VISIBLE
            setCountdown(10, 10)
        }
        v.findViewById<View>(R.id.restBlock)?.visibility = View.GONE
        v.findViewById<TextView>(R.id.tvTitle)?.text = "休息时间到啦"
        v.findViewById<TextView>(R.id.tvMessage)?.text = "让眼睛放个假，看看远处吧"
        v.findViewById<TextView>(R.id.tvTimer)?.apply {
            visibility = View.VISIBLE
            text = "屏幕马上自动关掉"
        }
    }

    /**
     * 关屏之后的常态：把倒计时收起来，直接说还要休息多久。
     * 一直挂个「0 秒」家长会以为程序卡死了。
     *
     * 剩余时间现在是整个页面最大的字（104sp）：电视在 2~3 米外看，
     * 家长和孩子最先要找的就是「还有多久」，其余文案都退到辅助位置。
     */
    private fun markRestingUi() {
        val v = overlayView ?: return
        // 圆环必须 GONE —— INVISIBLE 还占着 260dp，大数字就没地方放了
        v.findViewById<CountdownRingView>(R.id.ringCountdown)?.visibility = View.GONE

        val p = currentProfile
        val dailyLimit = p?.dailyLimitMinutes ?: -1
        val dailyUsedUp = dailyLimit > 0 && accumulatedDailyUsage >= dailyLimit

        v.findViewById<TextView>(R.id.tvTitle)?.text =
            if (dailyUsedUp) "今天看完啦" else "休息时间到啦"
        v.findViewById<TextView>(R.id.tvMessage)?.text =
            if (dailyUsedUp) "明天再来吧" else "让眼睛放个假，看看远处吧"

        // 进入休息态：心跳从下一秒开始负责把「分/秒」往下跳
        isRestUiActive = true
        updateRestBlockUi(v)

        // 圆环一收，布局重排，焦点可能跟着丢；顺手按回按钮
        ensureButtonFocus()
    }

    /**
     * 刷新「还剩 X 分 XX 秒」这一块。心跳每秒调一次，所以数字会实时往下跳。
     *
     * 两个注意点：
     * 1) 只在可见性真的变化时才赋值 —— 每秒都 setVisibility 会让布局反复重排，焦点容易丢；
     * 2) 日额度用完时根本不显示这块（不是「休息一会儿就好」，而是今天彻底没了）。
     */
    private fun updateRestBlockUi(v: View) {
        val p = currentProfile
        val dailyLimit = p?.dailyLimitMinutes ?: -1
        val dailyUsedUp = dailyLimit > 0 && accumulatedDailyUsage >= dailyLimit
        val restBlock = v.findViewById<View>(R.id.restBlock)
        val tvTimer = v.findViewById<TextView>(R.id.tvTimer)

        if (!dailyUsedUp && applyRestCountdown(v, R.id.tvRestMin, R.id.tvRestSec)) {
            if (restBlock != null && restBlock.visibility != View.VISIBLE) {
                restBlock.visibility = View.VISIBLE
            }
            if (tvTimer != null && tvTimer.visibility != View.GONE) {
                tvTimer.visibility = View.GONE
            }
        } else {
            if (restBlock != null && restBlock.visibility != View.GONE) {
                restBlock.visibility = View.GONE
            }
            tvTimer?.apply {
                if (visibility != View.VISIBLE) visibility = View.VISIBLE
                val t = if (dailyUsedUp) {
                    if (dailyLimit > 0) "今天已经看满 $dailyLimit 分钟" else "明天再来吧"
                } else {
                    "休息好了就能接着看"
                }
                if (text != t) text = t
            }
        }
    }

    /**
     * 休息还剩多少毫秒。0 表示已经没有休息在进行（或压根没设休息时长）。
     * 分钟、秒两种显示都从这里推导，保证两边算出来的剩余量永远一致。
     */
    private fun restRemainMs(): Long {
        val p = currentProfile ?: return 0
        if (p.restDurationMinutes <= 0) return 0
        val lastEnd = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getLong(PREF_LAST_SESSION_END_PREFIX + p.id, 0)
        if (lastEnd <= 0) return 0
        val ms = lastEnd + p.restDurationMinutes * 60_000L - System.currentTimeMillis()
        return if (ms <= 0) 0 else ms
    }

    private fun restRemainMinutes(): Int {
        val ms = restRemainMs()
        return if (ms <= 0) 0 else ((ms + 59_999L) / 60_000L).toInt()
    }

    /**
     * 剩余休息秒数（向上取整）。
     * 取整方向必须是「进」：还剩 0.4 秒时显示 1，而不是提前显示 0 分 0 秒却还在拦着。
     */
    private fun restRemainSeconds(): Long {
        val ms = restRemainMs()
        return if (ms <= 0) 0 else (ms + 999L) / 1000L
    }

    /**
     * 把剩余休息时间拆成「分」「秒」写到大数字上。
     * 秒数补零（09 而不是 9）让两位恒定，跳动时整块不会左右晃。
     * 返回 false 表示已经休息完，调用方据此把倒计时块收起来。
     */
    private fun applyRestCountdown(v: View, minId: Int, secId: Int): Boolean {
        val total = restRemainSeconds()
        if (total <= 0) return false
        val m = (total / 60).toInt()
        val s = (total % 60).toInt()
        v.findViewById<TextView>(minId)?.text = m.toString()
        v.findViewById<TextView>(secId)?.text = if (s < 10) "0$s" else s.toString()
        return true
    }

    /** 系统当前有没有音频输出（有人真的在放东西）。 */
    private fun isMusicActive(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            am.isMusicActive
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 到点了，把正在播的东西「按一下暂停」。
     *
     * 为什么用音频焦点而不是模拟按遥控器：普通应用没法往别的应用注入按键事件
     * （那需要系统签名权限），但音频焦点是系统给的官方口子 ——
     * 正在播的播放器一旦发现焦点被抢，会自己暂停；我们把焦点还回去，它会自己接着播。
     * 效果就等于「暂停 → 休息 → 继续」，而且不用孩子再点一下。
     *
     * 只有判断出「确实在看东西」才动手：要么有声音在放，要么前台停在一个非桌面的应用上
     * （后者是为了兜住静音看片的情况）。
     */
    private fun pausePlaybackIfPlaying() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val playing = try {
            am.isMusicActive
        } catch (e: Exception) {
            false
        }

        // 别在这里现查 UsageEvents：这个函数是在主线程调用的（休息期间每 20 秒一次），
        // 查一次几小时的事件会让遥控器按键明显发涩。用监控协程已经探到的结果就够了。
        val foreground = lastTopPackage ?: lastKnownTopPackage
        val onSomeApp = foreground != null &&
            foreground != packageName &&
            !isLauncher(foreground)

        if (!playing && !onSomeApp) {
            Log.d("TvLimit", "Nothing is playing (already at home/idle), skip pause")
            return
        }

        Log.d("TvLimit", "Playing detected (sound=$playing, fg=$foreground), stealing audio focus")
        holdAudioFocus()

        // 再补一条媒体键。音频焦点要靠播放器「自觉」，
        // 而媒体键是它自己的播放/暂停开关，命中率更高。
        // 顺序很重要：先抢焦点（守规矩的会自己停），再按媒体键（不规矩的也能停）。
        // 如果它已经因焦点停了，这条暂停键是幂等的，不会出问题。
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        pausedPlaybackByUs = true

        if (!playing) {
            // 前台停着某个应用但一点声音都没有 —— 大概率是静音看片，音频焦点管不到画面，
            // 只能退回桌面把画面断掉
            goHome()
            return
        }

        // 少数电视自带播放器不认音频焦点：2.5 秒后如果还在响，退回桌面兜底
        serviceScope.launch {
            delay(2500)
            val stillPlaying = try {
                am.isMusicActive
            } catch (e: Exception) {
                false
            }
            if (stillPlaying) {
                Log.w("TvLimit", "Playback ignored audio focus, fallback to home")
                goHome()
            }
        }
    }

    private var launcherPackage: String? = null

    private fun isLauncher(pkg: String): Boolean {
        if (launcherPackage == null) {
            try {
                launcherPackage = packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName
            } catch (e: Exception) {
                launcherPackage = ""
            }
        }
        return launcherPackage == pkg
    }

    // ===== 音频焦点：休息期间一直占着，谁也别想出声 =====

    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    /** 我们主动按过「暂停」没有。只有按过，休息结束才去按「播放」。 */
    private var pausedPlaybackByUs = false

    /**
     * 直接给系统发一条媒体键（暂停 / 播放）。
     *
     * 为什么光抢音频焦点不够：焦点只对「守规矩」的播放器有效。
     * 乐看这类自带播放引擎的直播壳、还有一些电视盒子播放器，
     * 被抢了焦点照样出声。媒体键是媒体会话的官方开关，绝大多数播放器都认。
     *
     * AudioManager.dispatchMediaKeyEvent 对 KEYCODE_MEDIA_* 不需要特殊权限，
     * 这也是为什么不能拿它去模拟遥控器的「确定」——那个要系统签名，做不到。
     */
    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            Log.d("TvLimit", "dispatchMediaKey $keyCode")
        } catch (e: Exception) {
            Log.e("TvLimit", "dispatchMediaKey($keyCode) failed: ${e.message}")
        }
    }

    /**
     * 注意用的是 GAIN_TRANSIENT（临时占用）而不是 GAIN（长期占用）：
     * 只有「临时」语义下，播放器才会在焦点归还时自动恢复播放 —— 这就是「休息完继续看」的关键。
     */
    private fun holdAudioFocus() {
        if (focusHeld) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { change ->
                        // 孩子按主页开了另一个应用时，新应用会先把焦点拿走。
                        // 这里把状态清掉，休息循环里每一轮会重新抢回来，
                        // 不然「遮罩还在、声音回来了」。
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            Log.d("TvLimit", "Audio focus lost ($change), will re-request")
                            focusHeld = false
                        }
                    }
                    .build()
                am.requestAudioFocus(req)
                focusRequest = req
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            focusHeld = true
            Log.d("TvLimit", "Audio focus held (transient) — playback should pause")
        } catch (e: Exception) {
            Log.e("TvLimit", "requestAudioFocus failed: ${e.message}")
        }
    }

    private fun releaseAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("TvLimit", "abandonAudioFocus failed: ${e.message}")
        }
        focusRequest = null
    }

    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
        } catch (e: Exception) {
            Log.e("TvLimit", "goHome failed: ${e.message}")
        }
    }

    /**
     * 撤掉休息遮罩。
     *
     * releaseFocus = false 用于「临时撤掉去输家长密码」：遮罩没了但限制还在，
     * 这时候不能把音频焦点还回去，否则后面的视频马上就自己续播了。
     */
    private fun removeOverlay(releaseFocus: Boolean = true) {
        // 以前这里是 `if (!isOverlayShowing) return` —— 只要有一次状态没对上（心跳里
        // 已经把 isOverlayShowing 置 false、但 window 还在），残留的窗口就再也没人撤，
        // 整块黑屏糊在电视上。现在无条件清理，代价只是一次 removeView。
        val hadView = overlayView != null || isOverlayShowing
        isOverlayShowing = false
        isRestUiActive = false
        sleepTimer?.cancel()
        sleepTimer = null
        overlayHeartbeatJob?.cancel()
        overlayHeartbeatJob = null
        // 休息结束 / 家长接管，把音频焦点还回去，别一直霸着
        if (releaseFocus) releaseAudioFocus()
        if (!hadView) return

        serviceScope.launch(Dispatchers.Main) {
            if (overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e("TvLimit", "Error removing overlay: ${e.message}")
                }
                overlayView = null
            }
        }
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "电视时间监控",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电视时间管家")
            .setContentText("正在记录观看时长…")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
    }

    private fun lockDevice(): Boolean {
        return try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, AdminReceiver::class.java)
            if (devicePolicyManager.isAdminActive(componentName)) {
                devicePolicyManager.lockNow()
                Log.d("TvLimit", "lockNow executed")
                true
            } else {
                Log.w("TvLimit", "lockNow skipped: device admin not active (TCL revokes it)")
                false
            }
        } catch (e: Exception) {
            Log.e("TvLimit", "lockNow failed: ${e.message}")
            false
        }
    }

    private fun saveProfileToPrefs(name: String?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        prefs.edit().putString("CURRENT_PROFILE_NAME", name ?: "儿童").apply()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun updateForegroundState() {
        if (isAppInForeground) {
            Log.d("TvLimit", "App in foreground. Pausing tracking.")
            stopTracking()
            // 休息中被孩子从主页绕进本应用时，遮罩要撤（不然盖住我们自己的界面），
            // 但音频焦点不能还 —— 还了后面的视频就自己续播，成了绕过口子
            removeOverlay(releaseFocus = !isLimitReached())
            removeTimeLeftOverlay()
            // 本应用（含家长密码页）到前台时，拦截页必须让开，
            // 否则一块全屏悬浮窗会把密码页盖住，家长连密码都输不了。
            removeAppBlockedOverlay()
        } else {
            Log.d("TvLimit", "App went to background. Resuming tracking if screen is on.")
            if (isScreenOn && currentProfile != null) {
                startTracking()
            }
        }
    }

    override fun onDestroy() {
        // 先把悬浮窗摘掉。服务被 stop 而进程还活着时，这两个 View 会永久糊在画面上，
        // 而且因为 Service 实例没了，之后没有任何代码能把它移除 —— 电视就废了。
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        isOverlayShowing = false
        sleepTimer?.cancel()
        overlayHeartbeatJob?.cancel()
        overlayHeartbeatJob = null
        releaseAudioFocus()

        try {
            timeLeftOverlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        timeLeftOverlayView = null

        // 拦截页同理：服务没了就没人能撤它，必须在这里摘掉
        try {
            appBlockedOverlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        appBlockedOverlayView = null
        appBlockedHeartbeatJob?.cancel()
        appBlockedHeartbeatJob = null

        // 进度同步落盘：不依赖协程，进程随时可能被干掉
        try {
            persistSessionProgress(force = true)
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(profileReceiver)
            unregisterReceiver(pauseReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        } catch (_: Exception) {
        }

        trackingJob?.cancel()
        trackingJob = null
        restrictedAppCheckJob?.cancel()
        restrictedAppCheckJob = null
        serviceScope.cancel()

        super.onDestroy()
    }
    /**
     * 右上角「还剩 X 分」小条的统一入口。
     * 家长觉得它挡画面，默认关掉；开关在「家长设置 → 角落倒计时」。
     */
    private fun refreshBadge() {
        if (!LimitControl.showBadge(this)) {
            removeTimeLeftOverlay()
            return
        }
        if (isOverlayShowing) return // 休息遮罩优先
        if (timeLeftOverlayView == null) showTimeLeftOverlay() else updateTimeLeftOverlay()
    }

    private fun showTimeLeftOverlay() {
        if (!LimitControl.showBadge(this)) return
        if (timeLeftOverlayView != null) return // Already showing
        if (isOverlayShowing) return // Warning overlay takes precedence
        if (currentProfile == null || !currentProfile!!.isRestricted) return

        serviceScope.launch(Dispatchers.Main) {
            if (timeLeftOverlayView == null) {
                try {
                    val inflater = LayoutInflater.from(this@TimeTrackingService)
                    timeLeftOverlayView = inflater.inflate(R.layout.view_time_left_overlay, null)

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    params.gravity = Gravity.TOP or Gravity.END
                    params.x = 85
                    params.y = 12

                    windowManager?.addView(timeLeftOverlayView, params)
                    updateTimeLeftOverlay() // Initial update
                } catch (e: Exception) {
                    Log.e("TvLimit", "Error showing time left overlay: ${e.message}")
                    timeLeftOverlayView = null
                }
            }
        }
    }

    private fun updateTimeLeftOverlay() {
        if (timeLeftOverlayView == null) return
        val cProfile = currentProfile ?: return

        serviceScope.launch(Dispatchers.Main) {
            try {
                // 临时关闭期间，角标如实显示「已关闭」，免得家长忘了还关着
                if (LimitControl.isPaused(this@TimeTrackingService)) {
                    val mins = LimitControl.remainMinutes(this@TimeTrackingService)
                    timeLeftOverlayView?.findViewById<TextView>(R.id.tvTimeLeftDetails)?.apply {
                        text = "限时已关闭 $mins 分"
                        setTextColor(Color.parseColor("#FFC94A"))
                    }
                    timeLeftOverlayView?.findViewById<ProgressBar>(R.id.pbTimeLeft)?.progress = 0
                    return@launch
                }

                // Calculate remaining time
                val dailyLimit = cProfile.dailyLimitMinutes
                val sessionLimit = cProfile.sessionLimitMinutes

                var remainingDaily = if (dailyLimit > -1) dailyLimit - accumulatedDailyUsage else Int.MAX_VALUE
                var remainingSession = if (sessionLimit > -1) sessionLimit - currentSessionUsage else Int.MAX_VALUE

                val remainingMinutes = minOf(remainingDaily, remainingSession)

                val displayTime = if (remainingMinutes > 1000) "不限" else "$remainingMinutes 分"

                val tvDetails = timeLeftOverlayView?.findViewById<TextView>(R.id.tvTimeLeftDetails)
                val progressBar = timeLeftOverlayView?.findViewById<ProgressBar>(R.id.pbTimeLeft)

                tvDetails?.text = "$displayTime"

                // Progress Bar calculation
                // What is the 'max'?
                // Getting a perfect % is hard because limits vary.
                // Let's use Session Limit as the base for the bar if it exists, else Daily.
                // If remaining is small, bar should be small.
                // Let's try to show % of "Limit".
                val activeLimit = if (sessionLimit > -1 && remainingSession <= remainingDaily) sessionLimit else dailyLimit
                val activeUsage = if (sessionLimit > -1 && remainingSession <= remainingDaily) currentSessionUsage else accumulatedDailyUsage

                val isLowTime = remainingMinutes <= 5
                progressBar?.progressDrawable = resources.getDrawable(
                    if (isLowTime) R.drawable.progress_bar_urgent
                    else R.drawable.progress_bar_horizontal_custom
                )
                if (activeLimit > 0) {
                    val progress = ((activeLimit - activeUsage).toFloat() / activeLimit * 100).toInt().coerceIn(0, 100)
                    progressBar?.progress = progress
                } else {
                    progressBar?.progress = 100
                }
                tvDetails?.setTextColor(
                    if (isLowTime) Color.parseColor("#FF7A7A") else Color.parseColor("#E8EEF9")
                )

            } catch (e: Exception) {
               Log.e("TvLimit", "Error updating overlay: ${e.message}")
            }
        }
    }

    private fun removeTimeLeftOverlay() {
        if (timeLeftOverlayView == null) return

        serviceScope.launch(Dispatchers.Main) {
            if (timeLeftOverlayView != null) {
                try {
                    windowManager?.removeView(timeLeftOverlayView)
                } catch (e: Exception) {
                    Log.e("TvLimit", "Error removing time left overlay: ${e.message}")
                }
                timeLeftOverlayView = null
            }
        }
    }
}
