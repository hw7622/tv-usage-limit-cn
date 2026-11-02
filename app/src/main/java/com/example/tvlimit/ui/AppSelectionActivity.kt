package com.example.tvlimit.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : ComponentActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var rv: RecyclerView
    private lateinit var btnDone: Button
    private val appList = mutableListOf<AppItem>()
    private var saving = false

    companion object {
        const val EXTRA_SELECTED_APPS = "selected_apps"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val RESULT_APPS = "result_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        rv = findViewById(R.id.rvAppList)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.itemAnimator = null

        val preSelected = intent.getStringExtra(EXTRA_SELECTED_APPS)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        btnDone = findViewById(R.id.btnDone)
        TvFocus.attach(btnDone)
        btnDone.setOnClickListener { commit() }

        loadApps(preSelected)
    }

    /**
     * 「选好了」= 当场写库，不等上一页的「保存」。
     *
     * 原来的做法是把选中结果塞进 setResult 交回去，等家长回到上一页再按保存才落库。
     * 这条链路太脆：TCL 会在后台 activity 上打 ProtectLru 标记，返回过程中一旦被回收，
     * 或者家长顺手按了返回键，勾选结果就没了 —— 界面还显示「全部应用」，看着像已经生效，
     * 实际上一个应用都没限制。现在选完立刻生效，写失败会明确提示。
     */
    private fun commit() {
        if (saving) return
        saving = true

        val selected = appList.filter { it.isSelected }.joinToString(",") { it.packageName }
        val profileId = intent.getIntExtra(EXTRA_PROFILE_ID, -1)

        CoroutineScope(Dispatchers.IO).launch {
            var ok = true
            var reason: String? = null
            try {
                if (profileId > 0) {
                    val rows = AppDatabase.getDatabase(applicationContext)
                        .profileDao()
                        .updateRestrictedApps(profileId, selected)
                    if (rows <= 0) {
                        ok = false
                        reason = "档案不存在"
                    }
                }
            } catch (e: Exception) {
                ok = false
                reason = e.message
            }

            withContext(Dispatchers.Main) {
                if (!ok) {
                    saving = false
                    Toast.makeText(
                        this@AppSelectionActivity,
                        "没写进去${if (reason.isNullOrBlank()) "" else "：$reason"}，再点一次「选好了」",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_APPS, selected))
                // 立刻通知服务刷新：家长改完勾选、直接按返回不回上一页保存时，
                // 服务里还是旧的列表，会继续按老范围拦（或者不拦）。
                if (profileId > 0) {
                    sendBroadcast(
                        Intent(com.example.tvlimit.service.TimeTrackingService.ACTION_PROFILE_UPDATED)
                            .putExtra(
                                com.example.tvlimit.service.TimeTrackingService.EXTRA_PROFILE_ID_UPDATE,
                                profileId
                            )
                            .setPackage(packageName)
                    )
                }
                finish()
            }
        }
    }

    private fun loadApps(preSelected: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val leanback = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

            val combined = (pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(leanback, 0))
                .distinctBy { it.activityInfo.packageName }

            val items = combined.mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == this@AppSelectionActivity.packageName) return@mapNotNull null
                AppItem(
                    name = info.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = info.loadIcon(pm),
                    isSelected = preSelected.contains(pkg)
                )
            }.sortedBy { it.name }

            withContext(Dispatchers.Main) {
                appList.clear()
                appList.addAll(items)
                adapter = AppAdapter(appList) { _, _ -> }
                rv.adapter = adapter
            }
        }
    }
}
