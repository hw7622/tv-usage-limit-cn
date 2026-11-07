package com.example.tvlimit.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.Profile
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProfileAdapter
    private val scope = CoroutineScope(Dispatchers.Main)
    private var focusRequested = false

    companion object {
        const val ACTION_PROFILE_CHANGED = "com.example.tvlimit.ACTION_PROFILE_CHANGED"
        const val EXTRA_PROFILE_ID = "com.example.tvlimit.EXTRA_PROFILE_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_selection)

        recyclerView = findViewById(R.id.recyclerViewProfiles)
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.itemAnimator = null

        adapter = ProfileAdapter { item -> onProfileClicked(item.profile) }
        recyclerView.adapter = adapter

        loadProfiles()
    }

    private fun loadProfiles() {
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val today = LocalDate.now().toString()

            db.profileDao().getAllProfiles().collect { profiles ->
                val withUsage = profiles.map { profile ->
                    val usageLog = db.profileDao().getUsageLog(profile.id, today)
                    val dailyUsage = usageLog?.totalUsageMinutes ?: 0

                    val lastEnd = prefs.getLong("last_session_end_time_${profile.id}", 0)
                    val storedSessionUsage = prefs.getInt("session_usage_${profile.id}", 0)
                    val currentSession = if (lastEnd > 0) {
                        val elapsed = System.currentTimeMillis() - lastEnd
                        val restMs = profile.restDurationMinutes * 60 * 1000L
                        if (elapsed < restMs) storedSessionUsage else 0
                    } else storedSessionUsage

                    ProfileWithUsage(profile, dailyUsage, currentSession)
                }

                withContext(Dispatchers.Main) {
                    adapter.submitList(withUsage)
                    if (!focusRequested && withUsage.isNotEmpty()) {
                        focusRequested = true
                        recyclerView.post {
                            TvFocus.focus(recyclerView.getChildAt(0))
                        }
                    }
                }
            }
        }
    }

    private fun onProfileClicked(profile: Profile) {
        if (!profile.isRestricted) {
            showPinDialog(profile)
        } else {
            switchToProfile(profile)
        }
    }

    private fun showPinDialog(profile: Profile) {
        PinPadDialog(
            this,
            "请输入「${profile.name}」的密码",
            verify = { pin -> pin == profile.pin }
        ) {
            switchToProfile(profile)
        }.show()
    }

    private fun switchToProfile(profile: Profile) {
        val intent = Intent(ACTION_PROFILE_CHANGED)
        intent.putExtra(EXTRA_PROFILE_ID, profile.id)
        sendBroadcast(intent)

        // 服务若被系统回收，广播没人接，切换会静默失败；
        // 记下目标档案并确保服务在跑，服务启动时按它初始化。
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .edit().putInt("PENDING_PROFILE_ID", profile.id).apply()
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, com.example.tvlimit.service.TimeTrackingService::class.java)
            )
        } catch (_: Exception) {
        }

        Toast.makeText(this, "现在是「${profile.name}」", Toast.LENGTH_SHORT).show()
        finish()
    }
}

data class ProfileWithUsage(
    val profile: Profile,
    val dailyUsage: Int,
    val sessionUsage: Int
)
