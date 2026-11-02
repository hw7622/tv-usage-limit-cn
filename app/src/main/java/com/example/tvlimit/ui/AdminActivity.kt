package com.example.tvlimit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.data.Profile
import com.example.tvlimit.service.ServiceWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminActivity : AppCompatActivity() {

    private lateinit var profileList: LinearLayout
    private lateinit var tvPauseState: TextView
    private lateinit var tvBadgeState: TextView
    private lateinit var tvKeepAliveState: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        profileList = findViewById(R.id.profileList)
        tvPauseState = findViewById(R.id.tvPauseState)
        tvBadgeState = findViewById(R.id.tvBadgeState)
        tvKeepAliveState = findViewById(R.id.tvKeepAliveState)
        val cardPause = findViewById<View>(R.id.cardPause)
        val cardBadge = findViewById<View>(R.id.cardBadge)
        val cardChangePassword = findViewById<View>(R.id.cardChangePassword)
        val cardKeepAlive = findViewById<View>(R.id.cardKeepAlive)
        TvFocus.attach(cardPause, cardBadge, cardChangePassword, cardKeepAlive)
        cardPause.setOnClickListener { showPauseOptions() }
        cardBadge.setOnClickListener {
            val on = !LimitControl.showBadge(this)
            LimitControl.setShowBadge(this, on)
            updateBadgeCard()
            toast(if (on) "右上角会显示剩余时间" else "右上角倒计时已关掉")
        }
        cardChangePassword.setOnClickListener { showChangePasswordDialog() }
        cardKeepAlive.setOnClickListener { onKeepAliveClicked() }
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
        updatePauseCard()
        updateBadgeCard()
        updateKeepAliveCard()
    }

    // ===== 后台常驻 =====

    private fun isBatteryExempt(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun updateKeepAliveCard() {
        if (!::tvKeepAliveState.isInitialized) return
        val ok = isBatteryExempt()
        tvKeepAliveState.text = if (ok) {
            "系统不会清理它，开机自动恢复"
        } else {
            "点这里设置，免得被电视管家杀掉"
        }
        tvKeepAliveState.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.mint else R.color.coral)
        )
    }

    private fun onKeepAliveClicked() {
        // 顺手把看门狗重新排一遍，装的旧版本可能还没排上
        ServiceWatchdog.schedule(this)
        ServiceWatchdog.startService(this)

        if (isBatteryExempt()) {
            toast("已经设置好了。若还是失效，到 设置 → 应用 → 电视时间管家 打开「自启动」")
            return
        }
        val opened = try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            true
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("请到 设置 → 应用 → 电视时间管家 里允许后台运行")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateBadgeCard() {
        tvBadgeState.text = if (LimitControl.showBadge(this)) {
            "已显示在右上角"
        } else {
            "不显示，画面更干净"
        }
    }

    private fun updatePauseCard() {
        tvPauseState.text = if (LimitControl.isPaused(this)) {
            "已关闭 · 还剩 ${LimitControl.remainMinutes(this)} 分钟自动恢复"
        } else {
            "家长想自己看一会儿时，可以先关掉一段时间"
        }
    }

    /** 「临时关闭」的时长选择：方向键上下选，OK 确认。 */
    private fun showPauseOptions() {
        val paused = LimitControl.isPaused(this)
        val options = mutableListOf(
            OptionDialog.Option("关闭 30 分钟", "这段时间不计时、也不拦截"),
            OptionDialog.Option("关闭 1 小时", "这段时间不计时、也不拦截"),
            OptionDialog.Option("关闭 2 小时", "这段时间不计时、也不拦截"),
            OptionDialog.Option("关到今晚 24 点", "明天自动恢复限制")
        )
        if (paused) options.add(OptionDialog.Option("立刻恢复限制", "现在就按原来的时长管"))

        OptionDialog(this, "临时关闭限时", options) { option ->
            when {
                option.label == "立刻恢复限制" -> {
                    LimitControl.resume(this)
                    toast("限时已恢复")
                }
                option.label == "关闭 30 分钟" -> {
                    LimitControl.pauseMinutes(this, 30)
                    toast("已临时关闭 30 分钟")
                }
                option.label == "关闭 1 小时" -> {
                    LimitControl.pauseMinutes(this, 60)
                    toast("已临时关闭 1 小时")
                }
                option.label == "关闭 2 小时" -> {
                    LimitControl.pauseMinutes(this, 120)
                    toast("已临时关闭 2 小时")
                }
                else -> {
                    LimitControl.pauseUntilTomorrow(this)
                    toast("今天不再限制，明天自动恢复")
                }
            }
            updatePauseCard()
        }.show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun loadProfiles() {
        scope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(applicationContext).profileDao()
            val profiles = dao.getAllProfiles().first()
            withContext(Dispatchers.Main) { render(profiles) }
        }
    }

    private fun render(profiles: List<Profile>) {
        profileList.removeAllViews()
        profiles.forEach { profile ->
            val row = layoutInflater.inflate(R.layout.item_profile_row, profileList, false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (112 * density).toInt()
            )
            lp.topMargin = (16 * density).toInt()
            row.layoutParams = lp

            row.findViewById<ImageView>(R.id.ivAvatar).setImageResource(
                if (profile.isRestricted) R.drawable.ic_avatar_kid else R.drawable.ic_avatar_adult
            )
            row.findViewById<TextView>(R.id.tvName).text = profile.name
            row.findViewById<TextView>(R.id.tvSub).text = describe(profile)

            row.setOnClickListener {
                startActivity(
                    Intent(this, EditProfileActivity::class.java).putExtra("PROFILE_ID", profile.id)
                )
            }
            TvFocus.attach(row, 1.02f)
            profileList.addView(row)
        }

        profileList.post {
            (profileList.getChildAt(0) ?: profileList.getChildAt(1))?.requestFocus()
        }
    }

    private fun describe(p: Profile): String {
        if (!p.isRestricted) return "不受时长限制，可以随时进入"
        val daily = if (p.dailyLimitMinutes > 0) "${p.dailyLimitMinutes} 分钟" else "不限"
        val session = if (p.sessionLimitMinutes > 0) "${p.sessionLimitMinutes} 分钟" else "不限"
        // 把「限制了几个应用」直接摆出来，家长不用点进去猜设置到底生效没有
        val apps = p.restrictedApps?.split(",")?.count { it.isNotBlank() } ?: 0
        val appsText = if (apps == 0) "整机统一限时" else "限 $apps 个应用"
        return "每天 $daily · 一次 $session · 休息 ${p.restDurationMinutes} 分钟 · $appsText"
    }

    private fun showChangePasswordDialog() {
        PinPadDialog(
            this,
            "输入 4 位新的家长密码",
            verify = { pin -> pin.length == 4 }
        ) { newPin ->
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("ADMIN_PIN", newPin).apply()
            Toast.makeText(this, "家长密码已改为 $newPin，请记好", Toast.LENGTH_LONG).show()
        }.show()
    }
}
