package com.example.tvlimit.quiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 家长自己加的题（从手机网页导入），与内置题库合并使用。
 *
 * 为什么不写进 assets：APK 里的东西改不了，加一道题就得重新打包、重新装电视。
 * 自定义题存在 `filesDir/custom_bank.json`，抽题时与内置库合并；
 * **同 id 时自定义覆盖内置**，所以「改掉一道内置题的答案」也能靠导入完成，
 * 不用改包重发。
 */
object BankStore {

    private const val FILE_NAME = "custom_bank.json"

    /** 导入上限 8MB：现有 3245 道题约 640KB，留足余量又不会被误传的大文件拖死。 */
    const val MAX_BYTES = 8 * 1024 * 1024

    private val SUBJECTS = QuizSettings.ALL_SUBJECTS.toSet()

    data class ImportResult(
        val added: Int,
        val updated: Int,
        val rejected: Int,
        val errors: List<String>,
        val customTotal: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("added", added)
            .put("updated", updated)
            .put("rejected", rejected)
            .put("errors", JSONArray(errors))
            .put("customTotal", customTotal)
    }

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun hasCustom(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 0L }

    fun meta(ctx: Context): JSONObject? = try {
        val f = file(ctx)
        if (!f.exists()) null else JSONObject(f.readText()).optJSONObject("meta")
    } catch (e: Exception) {
        null
    }

    /**
     * 解析题库文本。接受两种写法：
     * - 完整对象 `{ "questions": [...] }`（与 assets 同格式）
     * - 裸数组 `[ {...}, {...} ]`（手机端手工粘贴时最省事）
     *
     * 返回「解析成功的题」+「被拒绝的题及原因」。校验故意严格：
     * 宁可少收一道，也不要让一道坏题在电视上弹成空白。
     */
    fun parse(text: String, limitErrors: Int = 20): Pair<List<QuizQuestion>, List<String>> {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("questions") ?: JSONArray()
        }
        val ok = ArrayList<QuizQuestion>(arr.length())
        val errors = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                if (errors.size < limitErrors) errors.add("第 ${i + 1} 题：不是 JSON 对象")
                continue
            }
            val stem = o.optString("stem", "").trim()
            val subject = o.optString("subject", "").trim()
            val grade = o.optInt("grade", 0)
            val optionsArr = o.optJSONArray("options")
            val options = ArrayList<String>()
            if (optionsArr != null) {
                for (j in 0 until optionsArr.length()) options.add(optionsArr.optString(j, "").trim())
            }
            // 逐项校验，错误信息带上题号，家长在手机上能直接定位到那一行
            val bad = when {
                stem.isEmpty() -> "题干为空"
                subject !in SUBJECTS -> "科目「$subject」不认识"
                grade !in 1..9 -> "年级 $grade 不在 1-9"
                options.size !in 2..4 -> "选项要 2-4 个，实际 ${options.size} 个"
                options.any { it.isEmpty() } -> "有选项是空的"
                else -> null
            }
            if (bad != null) {
                if (errors.size < limitErrors) errors.add("第 ${i + 1} 题：$bad")
                continue
            }
            val answer = readAnswer(o, options.size)
            if (answer < 0) {
                if (errors.size < limitErrors) errors.add("第 ${i + 1} 题：答案下标越界")
                continue
            }
            val id = o.optString("id", "").trim().ifEmpty { "custom-" + hash(stem) }
            ok.add(
                QuizQuestion(
                    id = id,
                    subject = subject,
                    grade = grade,
                    knowledge = o.optString("knowledge", "综合").ifBlank { "综合" },
                    stem = stem,
                    options = options,
                    answer = answer,
                    explain = o.optString("explain", "").ifBlank { null },
                    difficulty = o.optInt("difficulty", 1).coerceIn(1, 3)
                )
            )
        }
        return ok to errors
    }

    /** 答案既接受 0 基下标，也接受 A-D / 1-4（手机上手写 JSON 的人很容易写字母）。 */
    private fun readAnswer(o: JSONObject, optionCount: Int): Int {
        val raw = o.opt("answer") ?: return -1
        if (raw is Number) return raw.toInt().takeIf { it in 0 until optionCount } ?: -1
        val s = raw.toString().trim().uppercase()
        if (s.length == 1 && s[0] in 'A'..'D') {
            val idx = s[0] - 'A'
            return if (idx < optionCount) idx else -1
        }
        val n = s.toIntOrNull() ?: return -1
        // 纯数字字符串按 1 基理解（Excel 里习惯写 1-4），0 只有一种合法解释
        return when {
            n == 0 -> 0
            n in 1..optionCount -> n - 1
            else -> -1
        }
    }

    fun loadCustom(ctx: Context): List<QuizQuestion> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList() else parse(f.readText()).first
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 导入并合并。[replace] 为 true 时先清掉旧的自定义题。
     *
     * 合并键是 id：新题覆盖同 id 的旧题（含内置），所以重复导入同一份文件不会翻倍。
     */
    fun import(ctx: Context, text: String, replace: Boolean = false): ImportResult {
        require(text.toByteArray().size <= MAX_BYTES) { "文件超过 8MB" }
        val (incoming, errors) = parse(text)
        // 一道都没解析出来时绝对不能往下走：
        // `{"items":[...]}`、键名写成 `question`、或者整段被 Web 端转义坏了，
        // 都会得到「合法 JSON 但 0 道题」。原来是照常写盘，配合「覆盖导入」
        // 等于把家长攒的几千道题一次性清空，接口还回 ok。
        require(incoming.isNotEmpty()) {
            "没有解析出任何题目：要 {\"questions\":[...]} 或直接 [...]，" +
                "每道题都要有 stem / subject / grade / options / answer"
        }
        val merged = LinkedHashMap<String, QuizQuestion>()
        if (!replace) loadCustom(ctx).forEach { merged[it.id] = it }
        var added = 0
        var updated = 0
        incoming.forEach { q ->
            if (merged.containsKey(q.id)) updated++ else added++
            merged[q.id] = q
        }
        write(ctx, merged.values.toList())
        QuestionBank.invalidate()
        return ImportResult(added, updated, errors.size, errors, merged.size)
    }

    fun clear(ctx: Context): Int {
        val n = customCount(ctx)
        file(ctx).delete()
        QuestionBank.invalidate()
        return n
    }

    fun customCount(ctx: Context): Int = loadCustom(ctx).size

    /** 导出当前自定义题（家长换电视、或想改一改再导回来）。 */
    fun exportJson(ctx: Context): String =
        report(loadCustom(ctx), meta(ctx)).toString(2)

    private fun write(ctx: Context, list: List<QuizQuestion>) {
        val root = report(list, null)
        root.put("meta", JSONObject().apply {
            put("source", "手机导入")
            put("importedAt", System.currentTimeMillis())
        })
        // 先写临时文件再改名：导入一份大题库时正好断电 / 被电视管家杀进程，
        // 直接覆盖会留下半截 JSON，下次读不出来就等于「题库空了」。
        val target = file(ctx)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun report(list: List<QuizQuestion>, meta: JSONObject?): JSONObject {
        val arr = JSONArray()
        list.forEach { q ->
            arr.put(JSONObject().apply {
                put("id", q.id)
                put("subject", q.subject)
                put("grade", q.grade)
                put("knowledge", q.knowledge)
                put("stem", q.stem)
                put("options", JSONArray(q.options))
                put("answer", q.answer)
                q.explain?.let { put("explain", it) }
                put("difficulty", q.difficulty)
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("source", "手机导入")
            put("license", "家长自编，仅本机使用")
            put("questions", arr)
            meta?.let { put("meta", it) }
        }
    }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(12)
    }
}
