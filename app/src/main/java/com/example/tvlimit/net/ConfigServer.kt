package com.example.tvlimit.net

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.tvlimit.data.AppDatabase
import com.example.tvlimit.quiz.BankStore
import com.example.tvlimit.quiz.QuestionBank
import com.example.tvlimit.quiz.QuizSettings
import com.example.tvlimit.quiz.QuizStats
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
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
 * 局域网配置服务：电视上开一个极小的 HTTP 服务，家长用手机浏览器改题库和参数。
 *
 * 为什么不用 NanoHTTPD 之类的库：整个功能就是一个页面 + 六个接口，
 * 引一个依赖要改 gradle、要担心混淆和体积，还要多一份别人的代码。
 * 这里手写的解析只认「请求行 + header + 定长 body」，够用且没有隐藏行为。
 *
 * 安全边界：只监听局域网，所有 /api/ 接口都要先输家长 PIN 换一个内存里的 token；
 * 不读不写用户的任意文件，导入的题只落在 App 自己的 filesDir 里。
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
    private var html: ByteArray? = null

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

    fun url(): String? {
        val ip = lanIp() ?: return null
        return if (port > 0) "http://$ip:$port" else null
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

    /** 局域网 IPv4。优先 wlan/eth，跳过 tun、rmnet 这类虚拟网卡。 */
    fun lanIp(): String? {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name
                if (name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        @Suppress("DEPRECATION")
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
        }
        return null
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

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) {
                json(out, 413, error("内容太大，最大 8MB"))
                return
            }
            val body = if (length > 0) readBody(input, length) else ByteArray(0)

            route(ctx, method, path, query, headers, body, out)
            out.flush()
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
        // 首页不需要登录：家长得先看到页面才能输 PIN
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val bytes = html ?: ctx.assets.open("web/index.html").readBytes().also { html = it }
            respond(out, 200, "text/html; charset=utf-8", bytes)
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

        when (path) {
            "/api/status" -> json(out, 200, status(ctx))
            "/api/bank/import" -> importBank(ctx, query, body, out)
            "/api/bank/clear" -> {
                val n = BankStore.clear(ctx)
                json(out, 200, JSONObject().put("ok", true).put("removed", n))
            }
            "/api/bank/export" -> {
                val bytes = BankStore.exportJson(ctx).toByteArray()
                respond(
                    out, 200, "application/json; charset=utf-8", bytes,
                    "Content-Disposition: attachment; filename=\"custom_bank.json\"\r\n"
                )
            }
            "/api/settings" -> updateSettings(ctx, body, out)
            "/api/mistakes/clear" -> {
                QuizStats.clear(ctx)
                json(out, 200, JSONObject().put("ok", true))
            }
            "/api/quiz/test" -> {
                // 家长在手机上点「在电视上试一组」：走和遥控器试答同一条广播
                ctx.sendBroadcast(
                    Intent("com.example.tvlimit.ACTION_QUIZ_TEST").setPackage(ctx.packageName)
                )
                json(out, 200, JSONObject().put("ok", true))
            }
            else -> json(out, 404, error("没有这个接口"))
        }
    }

    private fun login(ctx: Context, headers: Map<String, String>, body: ByteArray, out: BufferedOutputStream) {
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
            "Set-Cookie: $COOKIE=$token; Path=/; Max-Age=86400; HttpOnly\r\n"
        )
    }

    private fun isAuthed(headers: Map<String, String>): Boolean {
        val cookie = headers["cookie"] ?: return false
        val hit = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$COOKIE=") } ?: return false
        return tokens.contains(hit.substringAfter('='))
    }

    private fun status(ctx: Context): JSONObject {
        val all = QuestionBank.all(ctx)
        val builtin = QuestionBank.builtin(ctx)
        val custom = BankStore.loadCustom(ctx)

        val bySubject = JSONObject()
        val byGrade = JSONObject()
        all.forEach { q ->
            bySubject.put(q.subject, bySubject.optInt(q.subject, 0) + 1)
            val k = q.grade.toString()
            byGrade.put(k, byGrade.optInt(k, 0) + 1)
        }

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        val settings = JSONObject()
            .put("enabled", QuizSettings.enabled(ctx))
            .put("interval", QuizSettings.intervalMinutes(ctx))
            .put("count", QuizSettings.questionsPerSession(ctx))
            .put("grade", QuizSettings.grade(ctx))
            .put("subjects", JSONArray(QuizSettings.subjects(ctx).toList()))

        return JSONObject()
            .put(
                "device", JSONObject()
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("version", version)
                    .put("ip", lanIp() ?: "")
                    .put("port", port)
            )
            .put(
                "bank", JSONObject()
                    .put("builtin", builtin.size)
                    .put("custom", custom.size)
                    .put("total", all.size)
                    .put("bySubject", bySubject)
                    .put("byGrade", byGrade)
                    .put("meta", BankStore.meta(ctx) ?: JSONObject.NULL)
            )
            .put("settings", settings)
            .put("usage", usage(ctx))
            .put("mistakes", QuizStats.wrongKnowledgeCount(ctx))
    }

    /** 今日用量只读展示：真正的累计在追踪服务内存里，从这里改会被它下次 tick 覆盖掉。 */
    private fun usage(ctx: Context): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
        val name = prefs.getString("CURRENT_PROFILE_NAME", "儿童") ?: "儿童"
        return try {
            val dao = AppDatabase.getDatabase(ctx).profileDao()
            val profile = runBlocking { dao.getProfileByName(name) }
            val log = if (profile != null) {
                runBlocking { dao.getUsageLog(profile.id, LocalDate.now().toString()) }
            } else null
            JSONObject()
                .put("profile", profile?.name ?: name)
                .put("today", log?.totalUsageMinutes ?: 0)
                .put("dailyLimit", profile?.dailyLimitMinutes ?: -1)
                .put("sessionLimit", profile?.sessionLimitMinutes ?: -1)
        } catch (e: Exception) {
            JSONObject().put("profile", name).put("today", 0).put("dailyLimit", -1).put("sessionLimit", -1)
        }
    }

    private fun importBank(ctx: Context, query: String, body: ByteArray, out: BufferedOutputStream) {
        if (body.isEmpty()) {
            json(out, 400, error("没有收到内容"))
            return
        }
        val replace = query.split('&').any { it == "replace=1" }
        try {
            val result = BankStore.import(ctx, String(body, Charsets.UTF_8), replace)
            json(
                out, 200, JSONObject()
                    .put("ok", true)
                    .put("result", result.toJson())
                    .put("total", QuestionBank.all(ctx).size)
            )
        } catch (e: Exception) {
            json(out, 400, error(e.message ?: "导入失败"))
        }
    }

    private fun updateSettings(ctx: Context, body: ByteArray, out: BufferedOutputStream) {
        val o = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            json(out, 400, error("参数不是合法 JSON"))
            return
        }
        if (o.has("enabled")) QuizSettings.setEnabled(ctx, o.optBoolean("enabled"))
        if (o.has("interval")) QuizSettings.setIntervalMinutes(ctx, o.optInt("interval"))
        if (o.has("count")) QuizSettings.setQuestionsPerSession(ctx, o.optInt("count"))
        if (o.has("grade")) QuizSettings.setGrade(ctx, o.optInt("grade"))
        val subjects = o.optJSONArray("subjects")
        if (subjects != null && subjects.length() > 0) {
            val set = HashSet<String>()
            for (i in 0 until subjects.length()) set.add(subjects.optString(i))
            QuizSettings.setSubjects(ctx, set)
        }
        json(
            out, 200, JSONObject().put("ok", true).put(
                "settings", JSONObject()
                    .put("enabled", QuizSettings.enabled(ctx))
                    .put("interval", QuizSettings.intervalMinutes(ctx))
                    .put("count", QuizSettings.questionsPerSession(ctx))
                    .put("grade", QuizSettings.grade(ctx))
                    .put("subjects", JSONArray(QuizSettings.subjects(ctx).toList()))
            )
        )
    }

    // ===== 响应 =====

    private fun error(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun json(out: BufferedOutputStream, code: Int, obj: JSONObject) {
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
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> "Error"
    }
}
