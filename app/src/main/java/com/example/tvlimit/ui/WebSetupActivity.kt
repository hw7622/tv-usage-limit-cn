package com.example.tvlimit.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tvlimit.R
import com.example.tvlimit.data.HouseRules
import com.example.tvlimit.net.ConfigServer

/**
 * 「手机扫码配置」页：一个大二维码，家长拿手机一扫就进配置页。
 *
 * 为什么单独一页而不是塞进设置列表：家长要用的时候，电视上正放着片子，
 * 让他先去「家长设置 → 答题门禁 → 手机配置」翻三层菜单，不如在首页直接给一个入口。
 *
 * 这个页面退出后服务仍然开着 —— 手机上的页面还在用。
 * 但不会一直开着：20 分钟没有任何请求，服务会自己关掉（见 ConfigServer.idleLoop）。
 * 想提前关就回这里点「停止」。
 */
class WebSetupActivity : AppCompatActivity() {

    private lateinit var qr: QrView
    private lateinit var tvUrl: TextView
    private lateinit var tvState: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvTip: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRemote: Button

    /** 上一次看到的运行状态，用来判断是不是「刚刚被自动关掉了」。 */
    private var lastRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val was = lastRunning
            refresh()
            if (was && !lastRunning) {
                toast("长时间没人用，已自动关掉（省电、也更安全）")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_setup)

        qr = findViewById(R.id.qrView)
        tvUrl = findViewById(R.id.tvWebUrl)
        tvState = findViewById(R.id.tvWebState)
        tvSub = findViewById(R.id.tvWebSetupSub)
        tvTip = findViewById(R.id.tvWebTip)
        btnToggle = findViewById(R.id.btnWebToggle)
        btnRemote = findViewById(R.id.btnWebRemote)

        // 进这个页面就是想用：没开就顺手开起来，少按一次
        if (!ConfigServer.running) {
            try {
                ConfigServer.start(this)
            } catch (e: Exception) {
                toast("开不起来：${e.message}")
            }
        }

        TvFocus.attach(btnToggle, btnRemote)
        btnToggle.setOnClickListener { toggle() }
        btnRemote.setOnClickListener {
            val on = !HouseRules.allowRemote(this)
            HouseRules.setAllowRemote(this, on)
            refresh()
            toast(
                if (on) "已允许组网工具连进来（公网地址仍然一律拒绝）"
                else "只允许家里同一个网络访问"
            )
        }

        btnToggle.post { TvFocus.focus(btnToggle) }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ConfigServer.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stateReceiver)
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle() {
        if (ConfigServer.running) {
            ConfigServer.stop(this)
            refresh()
            toast("已停止，手机现在打不开了")
        } else {
            try {
                ConfigServer.start(this)
                refresh()
            } catch (e: Exception) {
                toast("开不起来：${e.message}")
            }
        }
    }

    private fun refresh() {
        val running = ConfigServer.running
        lastRunning = running
        val url = ConfigServer.url()
        btnToggle.text = if (running) "停止" else "开启"
        btnRemote.text = "允许远程：" + if (HouseRules.allowRemote(this)) "开" else "关"

        if (!running) {
            qr.setText(null)
            // 没码的时候连白底一起收起来，否则屏幕上会留一个莫名其妙的白色方块
            qr.visibility = View.INVISIBLE
            tvUrl.text = "服务已关闭"
            tvUrl.setTextColor(getColor(R.color.text_dim))
            tvState.text = "点下面的「开启」，手机上就能打开配置页"
            tvSub.text = "手机连同一个 WiFi，扫下面的码"
            tvTip.text = "配置页只在电视上开一个很小的网页服务，关掉就完全不可访问。\n没人用的时候 20 分钟后会自动关。"
            return
        }

        if (url == null) {
            qr.setText(null)
            qr.visibility = View.INVISIBLE
            tvUrl.text = "没取到局域网地址"
            tvUrl.setTextColor(getColor(R.color.coral))
            tvState.text = "电视现在没连网：插网线或连上 WiFi 再回来"
            tvSub.text = "先把电视连上网"
            return
        }

        qr.setText(url)
        qr.visibility = View.VISIBLE
        tvUrl.text = url
        tvUrl.setTextColor(getColor(R.color.mint))

        // 电视同时插了网线又连了 WiFi 时会有两个地址，扫码打不开多半是连到了另一个网段
        val lans = ConfigServer.endpoints(includeRemote = false)
        tvState.text = if (lans.size > 1) {
            "端口 ${ConfigServer.port} · 扫不开就换一个地址试试：${lans[1].url}"
        } else {
            "端口 ${ConfigServer.port} · 手机和电视要在同一个网络"
        }

        val remotes = ConfigServer.endpoints(includeRemote = true).filter { it.remote }
        tvSub.text = when {
            HouseRules.allowRemote(this) && remotes.isNotEmpty() ->
                "家里：扫码即可 · 外面：${remotes.first().url}"

            HouseRules.allowRemote(this) ->
                "已允许远程，但还没检测到 Tailscale 之类的地址"

            else -> "手机连同一个 WiFi，扫下面的码"
        }
        tvTip.text = if (HouseRules.allowRemote(this)) {
            "在外面用：电视和手机都装 Tailscale（或 ZeroTier），登录同一个账号，\n" +
                "然后用电视上显示的那个 100.x 地址打开。不需要公网 IP，也不用改路由器。\n" +
                "公网地址一律拒绝：就算路由器把端口映射出去了，别人也进不来。"
        } else {
            "进去后输入家长密码，能改额度、作息、答题参数、题库和错题。\n" +
                "只在你自己家的网络里能打开，不需要联网。没人用时 20 分钟后自动关。"
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
