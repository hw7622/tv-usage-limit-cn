package com.example.tvlimit.net

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.data.HouseRules
import com.example.tvlimit.data.LimitControl
import com.example.tvlimit.quiz.BankStore
import com.example.tvlimit.quiz.QuestionBank
import com.example.tvlimit.quiz.QuizSettings
import com.example.tvlimit.quiz.QuizStats
import com.example.tvlimit.quiz.WrongStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 手机配置服务：电视上开一个极小的 HTTP 服务，家长用手机浏览器改所有设置。
 *
 * 为什么不用 NanoHTTPD 之类的库：整个功能就是几个页面 + 一组接口，
 * 引一个依赖要改 gradle、要担心混淆和体积，还要多一份别人的代码。
 * 这里手写的解析只认「请求行 + header + 定长 body」，够用且没有隐藏行为。
 *
 * 三层安全边界：
 * ① 网络层：默认只接受局域网来源（见 [isClientAllowed]），公网地址直接 403；
 * ② 应用层：所有 /api/ 接口都要先用家长 PIN 换一个内存里的 token（cookie）；
 * ③ 数据层：不读不写用户的任意文件，导入的题只落在 App 自己的 filesDir 里。
 *
 * 「在外面也能改」走的是同一条通道。我们不做任何内网穿透 ——
 * 家长在外面用 Tailscale / ZeroTier 这类点对点组网连回家时，来源地址是
 * 100.64/10（CGNAT）或 fd7a::/48（IPv6 ULA），打开设置里的「允许远程」就放行。
 * 这样开源项目不必背一台公网服务器，也不用碰证书和域名。
 */
object ConfigServer {

    private const val TAG = "TvLimit"
    private const val PORT_START = 8080
    private const val PORT_TRIES = 20
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = BankStore.MAX_BYTES + 64 * 1024
    private const val COOKIE = "tvt"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 静态页面按路径缓存：一个 60KB 的页面每次请求都读 assets，电视的 eMMC 会明显卡。 */
    private val assets = HashMap<String, ByteArray>()

    /** 登录成功后发的 token，只存在内存里：关掉服务就全部失效。 */
    private val tokens = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 暴力试 PIN：同一 IP 一分钟内最多错 5 次。 */
    private class Attempts {
        var count = 0
        var lockUntil = 0L
    }

    private val fails = ConcurrentHashMap<String, Attempts>()

    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    var port: Int = 0
        private set

    val running: Boolean get() = serverSocket?.isClosed == false

    data class Endpoint(val url: String, val label: String, val remote: Boolean)

    fun url(): String? {
        val ip = lanIp() ?: return null
        return if (port > 0) "http://$ip:$port" else null
    }

    /**
     * 所有能打开配置页的地址。
     * @param includeRemote 是否把 100.64/10（Tailscale）这类地址也列出来
     */
    fun endpoints(includeRemote: Boolean): List<Endpoint> {
        if (port <= 0) return emptyList()
        val out = ArrayList<Endpoint>()
        for ((ip, name) in ipv4Addresses()) {
            val remote = !isLanAddress(ip)
            if (remote && !includeRemote) continue
            out.add(
                Endpoint(
                    url = "http://$ip:$port",
                    label = if (remote) "远程 · ${name.ifEmpty { ip }}" else name.ifEmpty { ip },
                    remote = remote
                )
            )
        }
        return out
    }

    @Synchronized
    fun start(ctx: Context): Int {
        if (running) return port
        val app = ctx.applicationContext
        var lastError: Exception? = null
        for (p in PORT_START until PORT_START + PORT_TRIES) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 16)
                serverSocket = ss
                port = p
                acceptThread = thread(isDaemon = true, name = "config-server") { acceptLoop(app, ss) }
                Log.i(TAG, "config server listening on $p")
                return p
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "端口 ${PORT_START}~${PORT_START + PORT_TRIES - 1} 都被占用：" + (lastError?.message ?: "")
        )
    }

    @Synchronized
    fun stop() {
        tokens.clear()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
        acceptThread = null
        port = 0
        Log.i(TAG, "config server stopped")
    }

    /** 开启时用：方便家长在电视上看到「手机该打开哪个地址」。 */
    fun lanIp(): String? {
        val list = ipv4Addresses()
        return list.firstOrNull { isLanAddress(it.first) }?.first ?: list.firstOrNull()?.first
    }

    private fun ipv4Addresses(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name
                if (name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        @Suppress("DEPRECATION")
                        val ip = addr.hostAddress ?: continue
                        out.add(ip to name)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** 局域网地址：10/8、172.16/12、192.168/16、169.254/16。 */
    private fun isLanAddress(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    /**
     * 这个来源能不能用配置页。
     *
     * 默认只放行局域网 —— 「只监听局域网」这句话不能靠「路由器不会转发」来保证，
     * 光猫把电视放到 DMZ、或者家里开了端口映射，这条服务就直接暴露在公网上了。
     */
    fun isClientAllowed(addr: InetAddress?, allowRemote: Boolean): Boolean {
        if (addr == null) return true
        if (addr.isLoopbackAddress) return true
        if (allowRemote) return true
        return when (addr) {
            is Inet4Address -> {
                @Suppress("DEPRECATION")
                isLanAddress(addr.hostAddress ?: return false)
            }

            is Inet6Address -> {
                // 链路本地 fe80::/10 与 ULA fc00::/7 都是「家里这张网」的地址
                val b = addr.address
                val b0 = b[0].toInt() and 0xFF
                b0 == 0xFE || (b0 and 0xFE) == 0xFC
            }

            else -> false
        }
    }

    // ===== HTTP =====

    private fun acceptLoop(ctx: Context, ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (ss.isClosed) break else continue
            }
            try {
                pool.execute { handle(ctx, socket) }
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun handle(ctx: Context, socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())

            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val rawPath = requestLine[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] =
                        lines[i].substring(idx + 1).trim()
                }
            }

            // 来源检查放在最前面：连页面都不给，别提试 PIN 了
            val allowRemote = HouseRules.allowRemote(ctx)
            if (!isClientAllowed(socket.inetAddress, allowRemote)) {
                Log.w(TAG, "rejected non-lan client: ${socket.inetAddress?.hostAddress}")
                respond(out, 403, "text/html; charset=utf-8", FORBIDDEN_PAGE.toByteArray())
                return
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) {
                json(out, 413, error("内容太大，最大 8MB"))
                return
            }
            val body = if (length > 0) readBody(input, length) else ByteArray(0)

            route(ctx, method, path, query, headers, body, out)
            out.flush()
        } catch (e: ApiError) {
            // 业务层抛出来的错误：状态码和文案都是给家长看的
            try {
                val out = BufferedOutputStream(socket.getOutputStream())
                json(out, e.status, error(e.message))
                out.flush()
            } catch (ignored: Exception) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /** 逐字节读到空行。手写的原因：header 里没有 Content-Length 时也不该阻塞。 */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream(512)
        var matched = 0
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            when {
                b == '\r'.code && matched == 0 -> matched = 1
                b == '\n'.code && matched == 1 -> matched = 2
                b == '\r'.code && matched == 2 -> matched = 3
                b == '\n'.code && matched == 3 -> return buf.toString("UTF-8")
                else -> matched = 0
            }
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun route(
        ctx: Context,
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: ByteArray,
        out: BufferedOutputStream
    ) {
        // 登录页不需要 token：家长得先看到页面才能输 PIN
        if (method == "GET" && !path.startsWith("/api/")) {
            serveAsset(ctx, path, out)
            return
        }

        if (path == "/api/login" && method == "POST") {
            login(ctx, headers, body, out)
            return
        }

        if (!path.startsWith("/api/")) {
            json(out, 404, error("没有这个页面"))
            return
        }

        if (!isAuthed(headers)) {
            json(out, 401, error("请先输入家长密码"))
            return
        }

        val result = when (path) {
            "/api/overview" -> Api.overview(ctx)
            "/api/report" -> Api.report(ctx, query)
            "/api/extend" -> Api.extend(ctx, body)
            "/api/pause" -> Api.pause(ctx, body)
            "/api/resume" -> Api.resume(ctx)
            "/api/profiles" -> Api.profiles(ctx)
            "/api/profile" -> Api.updateProfile(ctx, body)
            "/api/profile/switch" -> Api.switchProfile(ctx, body)
            "/api/schedule" -> if (method == "POST") Api.setSchedule(ctx, body) else Api.schedule(ctx)
            "/api/settings" -> if (method == "POST") Api.setSettings(ctx, body) else Api.settings(ctx)
            "/api/config" -> if (method == "POST") Api.setConfig(ctx, body) else Api.config(ctx)
            "/api/pin" -> Api.changePin(ctx, body)
            "/api/quiz/test" -> Api.quizTest(ctx)
            "/api/bank/gaps" -> Api.bankGaps(ctx)
            "/api/bank/import" -> Api.importBank(ctx, query, body)
            "/api/bank/clear" -> Api.clearBank(ctx)
            "/api/bank/export" -> {
                val bytes = BankStore.exportJson(ctx).toByteArray()
                respond(
                    out, 200, "application/json; charset=utf-8", bytes,
                    "Content-Disposition: attachment; filename=\"custom_bank.json\"\r\n"
                )
                return
            }

            "/api/mistakes" -> Api.mistakes(ctx)
            "/api/mistakes/clear" -> Api.clearMistakes(ctx)
            "/api/logout" -> {
                logout(headers)
                JSONObject().put("ok", true)
            }

            else -> {
                json(out, 404, error("没有这个接口"))
                return
            }
        }
        json(out, 200, result)
    }

    private fun serveAsset(ctx: Context, path: String, out: BufferedOutputStream) {
        val name = when (path) {
            "/", "/index.html" -> "index.html"
            "/app.js" -> "app.js"
            "/app.css" -> "app.css"
            "/favicon.ico" -> {
                respond(out, 204, "image/x-icon", ByteArray(0))
                return
            }

            else -> {
                json(out, 404, error("没有这个页面"))
                return
            }
        }
        val bytes = assets[name] ?: try {
            ctx.assets.open("web/$name").readBytes().also { assets[name] = it }
        } catch (e: Exception) {
            json(out, 404, error("缺少页面文件 $name"))
            return
        }
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            name.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        respond(out, 200, type, bytes)
    }

    private fun login(
        ctx: Context,
        headers: Map<String, String>,
        body: ByteArray,
        out: BufferedOutputStream
    ) {
        val ip = headers["x-forwarded-for"] ?: "local"
        val now = System.currentTimeMillis()
        val rec = fails.getOrPut(ip) { Attempts() }
        synchronized(rec) {
            if (rec.lockUntil > now) {
                json(out, 429, error("试错太多次，请等一分钟"))
                return
            }
        }

        val pin = try {
            JSONObject(String(body, Charsets.UTF_8)).optString("pin", "")
        } catch (e: Exception) {
            ""
        }
        val stored = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
            .getString("ADMIN_PIN", "1234") ?: "1234"

        if (pin != stored) {
            synchronized(rec) {
                rec.count += 1
                if (rec.count >= 5) {
                    rec.count = 0
                    rec.lockUntil = now + 60_000
                }
            }
            json(out, 403, error("家长密码不对"))
            return
        }

        val token = UUID.randomUUID().toString()
        tokens.add(token)
        respond(
            out, 200, "application/json; charset=utf-8",
            JSONObject().put("ok", true).toString().toByteArray(),
            "Set-Cookie: $COOKIE=$token; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax\r\n"
        )
    }

    private fun logout(headers: Map<String, String>) {
        tokenOf(headers)?.let { tokens.remove(it) }
    }

    private fun tokenOf(headers: Map<String, String>): String? {
        val cookie = headers["cookie"] ?: return null
        val hit = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$COOKIE=") }
            ?: return null
        return hit.substringAfter('=')
    }

    private fun isAuthed(headers: Map<String, String>): Boolean {
        val t = tokenOf(headers) ?: return false
        return tokens.contains(t)
    }

    // ===== 响应 =====

    internal fun error(msg: String?) = JSONObject().put("ok", false).put("error", msg ?: "出错了")

    internal fun json(out: BufferedOutputStream, code: Int, obj: JSONObject) {
        respond(out, code, "application/json; charset=utf-8", obj.toString().toByteArray())
    }

    private fun respond(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: String = ""
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            if (extraHeaders.isNotEmpty()) append(extraHeaders)
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    private val FORBIDDEN_PAGE = """
        <!doctype html><html lang="zh-CN"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>不能在当前网络打开</title>
        <body style="background:#0d1220;color:#e8eef9;font:16px/1.7 -apple-system,system-ui,sans-serif;padding:32px">
        <h2 style="color:#ff7a7a">这个地址不能打开电视配置页</h2>
        <p>手机和电视需要连在同一个 WiFi（家里那张网）。</p>
        <p>如果你在外面，先在电视的设置里打开「允许远程」，并让两台设备都登录同一个
        Tailscale / ZeroTier 账号，再用电视上显示的那个 100.x 地址。</p>
        <p style="color:#9faac2">出于安全考虑，公网地址一律拒绝，不做任何例外。</p>
        </body></html>
    """.trimIndent()
}
