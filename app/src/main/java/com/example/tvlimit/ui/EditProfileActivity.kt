package com.example.tvlimit.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.Profile
import com.example.tvlimit.service.TimeTrackingService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 档案设置。所有数值都用左右键步进，不用输入框 —— 电视遥控器唤不出软键盘。
 */
class EditProfileActivity : AppCompatActivity() {

    companion object {
        private val DAILY = intArrayOf(-1, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240)
        private val SESSION = intArrayOf(-1, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 75, 90, 120)
        private val REST = intArrayOf(5, 10, 15, 20, 25, 30, 40, 50, 60)
    }

    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileHint: TextView
    private lateinit var tvDailyValue: TextView
    private lateinit var tvSessionValue: TextView
    private lateinit var tvRestValue: TextView
    private lateinit var tvAppsSummary: TextView

    private var dailyIdx = 5
    private var sessionIdx = 3
    private var restIdx = 3

    private var profileId: Int = -1
    private var currentProfile: Profile? = null
    private var restrictedApps: String? = null

    /**
     * 刚从「选应用」页回来时置 true。
     * loadProfile() 是异步读库的，如果它比 onActivityResult 晚落地，就会拿库里的旧值
     * 把用户刚选的结果覆盖掉——这是「勾了却等于没勾」的另一个来源。
     */
    private var appsPicked = false

    private val scope = CoroutineScope(Dispatchers.Main)
    private val saved = AtomicBoolean(false)

    private val selectAppsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            restrictedApps = result.data?.getStringExtra(AppSelectionActivity.RESULT_APPS)
            appsPicked = true
            updateAppsSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileHint = findViewById(R.id.tvProfileHint)
        tvDailyValue = findViewById(R.id.tvDailyValue)
        tvSessionValue = findViewById(R.id.tvSessionValue)
        tvRestValue = findViewById(R.id.tvRestValue)
        tvAppsSummary = findViewById(R.id.tvAppsSummary)

        bindStepper(findViewById(R.id.btnDailyMinus), findViewById(R.id.btnDailyPlus), DAILY) { i ->
            dailyIdx = i; renderDaily()
        }
        bindStepper(findViewById(R.id.btnSessionMinus), findViewById(R.id.btnSessionPlus), SESSION) { i ->
            sessionIdx = i; renderSession()
        }
        bindStepper(findViewById(R.id.btnRestMinus), findViewById(R.id.btnRestPlus), REST) { i ->
            restIdx = i; renderRest()
        }

        val cardApps = findViewById<View>(R.id.cardApps)
        TvFocus.attach(cardApps)
        cardApps.setOnClickListener {
            if (profileId <= 0) return@setOnClickListener
            selectAppsLauncher.launch(
                Intent(this, AppSelectionActivity::class.java)
                    .putExtra(AppSelectionActivity.EXTRA_SELECTED_APPS, restrictedApps)
                    .putExtra(AppSelectionActivity.EXTRA_PROFILE_ID, profileId)
            )
        }

        val btnSave = findViewById<View>(R.id.btnSave)
        val btnCancel = findViewById<View>(R.id.btnCancel)
        TvFocus.attach(btnSave, btnCancel)
        btnSave.setOnClickListener { saveProfile() }
        btnCancel.setOnClickListener { finish() }

        profileId = intent.getIntExtra("PROFILE_ID", -1)
        if (profileId == -1) {
            finish()
        } else {
            loadProfile(profileId)
        }
    }

    private fun bindStepper(minus: View, plus: View, steps: IntArray, onChange: (Int) -> Unit) {
        TvFocus.attach(minus, plus)
        minus.setOnClickListener {
            val cur = currentIndex(steps)
            if (cur > 0) onChange(cur - 1)
        }
        plus.setOnClickListener {
            val cur = currentIndex(steps)
            if (cur < steps.size - 1) onChange(cur + 1)
        }
    }

    private fun currentIndex(steps: IntArray): Int = when (steps) {
        DAILY -> dailyIdx
        SESSION -> sessionIdx
        else -> restIdx
    }

    private fun loadProfile(id: Int) {
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val p = db.profileDao().getProfileById(id)
            withContext(Dispatchers.Main) {
                currentProfile = p
                if (p == null) {
                    finish()
                    return@withContext
                }
                tvProfileName.text = p.name
                dailyIdx = nearestIndex(DAILY, p.dailyLimitMinutes)
                sessionIdx = nearestIndex(SESSION, p.sessionLimitMinutes)
                restIdx = nearestIndex(REST, p.restDurationMinutes)
                // 用户刚从选应用页回来时，别用库里的旧值盖掉他刚选的结果
                if (!appsPicked) restrictedApps = p.restrictedApps

                renderDaily()
                renderSession()
                renderRest()
                updateAppsSummary()

                if (!p.isRestricted) {
                    // 家长档案不做时长限制，隐藏三个步进器
                    findViewById<View>(R.id.rowDaily).visibility = View.GONE
                    findViewById<View>(R.id.rowSession).visibility = View.GONE
                    findViewById<View>(R.id.rowRest).visibility = View.GONE
                    // 不限时的档案勾「限制哪些应用」也不会生效，直接藏掉，免得家长白设
                    findViewById<View>(R.id.cardApps).visibility = View.GONE
                    tvProfileHint.text = "家长模式不受时长限制，也不用限制应用，随时都能看"
                    findViewById<View>(R.id.btnSave).requestFocus()
                } else {
                    findViewById<View>(R.id.btnDailyMinus).requestFocus()
                }
            }
        }
    }

    private fun nearestIndex(steps: IntArray, value: Int): Int {
        if (value <= 0) return steps.indexOfFirst { it <= 0 }.coerceAtLeast(0)
        var best = 0
        var bestDiff = Int.MAX_VALUE
        steps.forEachIndexed { i, v ->
            if (v > 0) {
                val d = abs(v - value)
                if (d < bestDiff) {
                    bestDiff = d
                    best = i
                }
            }
        }
        return best
    }

    private fun renderStep(tv: TextView, steps: IntArray, idx: Int) {
        val v = steps[idx]
        if (v <= 0) {
            tv.text = "不限"
            tv.setTextColor(ContextCompat.getColor(this, R.color.sky))
        } else {
            tv.text = "$v 分钟"
            tv.setTextColor(ContextCompat.getColor(this, R.color.sun))
        }
    }

    private fun renderDaily() = renderStep(tvDailyValue, DAILY, dailyIdx)
    private fun renderSession() = renderStep(tvSessionValue, SESSION, sessionIdx)
    private fun renderRest() = renderStep(tvRestValue, REST, restIdx)

    private fun updateAppsSummary() {
        val count = restrictedApps?.split(",")?.count { it.isNotBlank() } ?: 0
        // 「全部应用」会被读成「所有应用都受限」，实际含义是相反的那一个：一个都没挑
        tvAppsSummary.text = if (count == 0) "不限应用" else "$count 个应用"
        tvAppsSummary.setTextColor(
            ContextCompat.getColor(this, if (count == 0) R.color.text_dim else R.color.sky)
        )
    }

    private fun saveProfile() {
        val p = currentProfile
        if (p == null) {
            // 以前这里是静默 return：按钮点了没反应，也没有任何提示，只能猜
            Toast.makeText(this, "设置还没读出来，稍等一下再按保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (!saved.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(applicationContext).profileDao()
            var ok = false
            var reason: String? = null

            try {
                // 只改三项时长：限制应用那一列由选应用页当场写库，
                // 这里要是拿内存里的 old 值回写，等于把刚选的应用又抹掉。
                val base = dao.getProfileById(p.id) ?: p
                val newProfile = base.copy(
                    dailyLimitMinutes = DAILY[dailyIdx],
                    sessionLimitMinutes = SESSION[sessionIdx],
                    restDurationMinutes = REST[restIdx]
                )

                val rows = dao.updateProfile(newProfile)
                if (rows > 0) {
                    // 写完读回来核对，别让「已保存」的 Toast 骗人
                    val back = dao.getProfileById(p.id)
                    ok = back != null &&
                        back.dailyLimitMinutes == newProfile.dailyLimitMinutes &&
                        back.sessionLimitMinutes == newProfile.sessionLimitMinutes &&
                        back.restDurationMinutes == newProfile.restDurationMinutes
                }
                if (!ok) {
                    dao.insertProfile(newProfile)
                    val back = dao.getProfileById(p.id)
                    ok = back != null &&
                        back.dailyLimitMinutes == newProfile.dailyLimitMinutes &&
                        back.sessionLimitMinutes == newProfile.sessionLimitMinutes &&
                        back.restDurationMinutes == newProfile.restDurationMinutes
                    if (!ok && reason == null) reason = "写入未生效"
                }
                if (ok) {
                    sendBroadcast(
                        Intent(TimeTrackingService.ACTION_PROFILE_UPDATED)
                            .putExtra(TimeTrackingService.EXTRA_PROFILE_ID_UPDATE, newProfile.id)
                            // 明确指定包名，别让系统当成隐式广播丢掉（接收方是 NOT_EXPORTED）
                            .setPackage(packageName)
                    )
                }
            } catch (e: Exception) {
                ok = false
                reason = e.message
                android.util.Log.w("TVLimit", "saveProfile failed", e)
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(this@EditProfileActivity, "已保存", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    saved.set(false)
                    Toast.makeText(
                        this@EditProfileActivity,
                        "保存失败${if (reason.isNullOrBlank()) "" else "：$reason"}，请再按一次",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
