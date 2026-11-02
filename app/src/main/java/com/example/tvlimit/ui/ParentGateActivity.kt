package com.example.tvlimit.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.example.tvlimit.R
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.service.TimeTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 休息遮罩上的「我是家长」入口。
 *
 * 以前点这个按钮直接跳去切换档案，家长只想自己看一会儿也得整页跳来跳去。
 * 现在先在原地输密码，再选「切档案」还是「临时关掉限制多久」。
 */
class ParentGateActivity : AppCompatActivity() {

    private var verified = false
    private var picked = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_gate)
        showPin()
    }

    private fun showPin() {
        val storedPin = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("ADMIN_PIN", "1234") ?: "1234"

        val dialog = PinPadDialog(
            this,
            "请输入家长密码",
            verify = { it == storedPin }
        ) {
            verified = true
            showActions()
        }
        dialog.setOnDismissListener {
            if (!verified) finish()
        }
        dialog.show()
    }

    private fun showActions() {
        val options = listOf(
            OptionDialog.Option("切换到家长模式", "不限制观看时长"),
            OptionDialog.Option("临时关闭 30 分钟", "这段时间不计时、也不拦截"),
            OptionDialog.Option("临时关闭 1 小时", "这段时间不计时、也不拦截"),
            OptionDialog.Option("临时关闭到今晚 24 点", "明天自动恢复限制")
        )
        val dialog = OptionDialog(this, "接下来做什么", options) { option ->
            picked = true
            when (option.label) {
                "切换到家长模式" -> switchToParentProfile()
                "临时关闭 30 分钟" -> {
                    LimitControl.pauseMinutes(this, 30)
                    toast("已临时关闭 30 分钟")
                    finish()
                }
                "临时关闭 1 小时" -> {
                    LimitControl.pauseMinutes(this, 60)
                    toast("已临时关闭 1 小时")
                    finish()
                }
                else -> {
                    LimitControl.pauseUntilTomorrow(this)
                    toast("今天不再限制，明天自动恢复")
                    finish()
                }
            }
        }
        dialog.setOnDismissListener {
            if (!picked) finish()
        }
        dialog.show()
    }

    private fun switchToParentProfile() {
        scope.launch {
            val parent = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).profileDao()
                    .getAllProfiles().first().firstOrNull { !it.isRestricted }
            }
            if (parent == null) {
                toast("没有找到家长模式")
                finish()
                return@launch
            }
            sendBroadcast(
                Intent(ProfileSelectionActivity.ACTION_PROFILE_CHANGED)
                    .putExtra(ProfileSelectionActivity.EXTRA_PROFILE_ID, parent.id)
            )
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().putInt("PENDING_PROFILE_ID", parent.id).apply()
            toast("现在是「${parent.name}」")
            finish()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // 遮罩上的按钮进来时已经把计时停了，退出时通知服务重新判断要不要接着计时
        try {
            sendBroadcast(Intent(TimeTrackingService.ACTION_RECHECK))
        } catch (_: Exception) {
        }
    }
}
