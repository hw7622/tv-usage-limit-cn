package com.example.tvlimit.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.example.tvlimit.R

class AppBlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked)

        val packageName = intent.getStringExtra("blocked_package")
        val tvDetails = findViewById<TextView>(R.id.tvBlockedDetails)
        val appName = packageName?.let {
            try {
                val info = packageManager.getApplicationInfo(it, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                null
            }
        }
        if (appName != null) {
            val reason = intent.getStringExtra("blocked_reason")
            tvDetails.text = if (reason.isNullOrBlank()) {
                "「$appName」现在不能看，休息一下再来吧"
            } else {
                "「$appName」现在不能看：$reason"
            }
        }

        val btnGoBack = findViewById<Button>(R.id.btnGoBack)
        val btnSwitch = findViewById<Button>(R.id.btnSwitchProfile)
        TvFocus.attach(btnGoBack, btnSwitch)

        btnGoBack.setOnClickListener { goHome() }
        // 「我是家长」必须走密码验证，不能直接跳档案选择页，否则孩子点一下就绕过了
        btnSwitch.setOnClickListener {
            startActivity(
                Intent(this, ParentGateActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
            finish()
        }
        btnSwitch.post { TvFocus.focus(btnSwitch) }

        // 用 OnBackPressedDispatcher 而不是重写 onBackPressed()：
        // 后者在开启「预测性返回」的系统上不会被调用，返回键会直接把页面 finish 掉，
        // 孩子按一下返回就回到被限制的应用里了。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }
}
