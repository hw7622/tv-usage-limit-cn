package com.example.tvlimit.quiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 错题本：把「错在哪道题」记下来，家长在手机上能看到题干、正确答案和错了几次。
 *
 * 和 [QuizStats] 的分工：
 * - QuizStats 只存 `知识点 -> 错了几个/对了几个` 的计数，用来给出题加权，很轻；
 * - WrongStore 存题目快照（题干、选项、正解、解释），是给人看的。
 *
 * 为什么不合并：QuizStats 的计数一天之内会被读上千次（每次出题都读），
 * 把题干塞进 SharedPreferences 会让它越来越慢；而错题快照只在答错时写一次。
 *
 * 按「科目+知识点」合并，不是按题目 id：数学是现算的，每道题的 id 都不同，
 * 按 id 存的话 300 条上限一天就满了，家长看到的全是同一类口算题。
 */
object WrongStore {

    private const val FILE_NAME = "wrong_book.json"
    private const val MAX_ENTRIES = 300

    data class Entry(
        val id: String,
        val subject: String,
        val grade: Int,
        val knowledge: String,
        val stem: String,
        val options: List<String>,
        val answer: Int,
        val explain: String?,
        val wrong: Int,
        val right: Int,
        val lastAt: Long,
        val lastChose: Int
    ) {
        fun toJson(): JSONObject {
            val opts = JSONArray()
            options.forEach { opts.put(it) }
            return JSONObject()
                .put("id", id)
                .put("subject", subject)
                .put("grade", grade)
                .put("knowledge", knowledge)
                .put("stem", stem)
                .put("options", opts)
                .put("answer", answer)
                .put("explain", explain ?: "")
                .put("wrong", wrong)
                .put("right", right)
                .put("lastAt", lastAt)
                .put("lastChose", lastChose)
        }

        companion object {
            fun from(o: JSONObject): Entry {
                val opts = ArrayList<String>()
                val arr = o.optJSONArray("options")
                if (arr != null) for (i in 0 until arr.length()) opts.add(arr.optString(i))
                return Entry(
                    id = o.optString("id"),
                    subject = o.optString("subject"),
                    grade = o.optInt("grade", 0),
                    knowledge = o.optString("knowledge"),
                    stem = o.optString("stem"),
                    options = opts,
                    answer = o.optInt("answer", 0),
                    explain = o.optString("explain").ifEmpty { null },
                    wrong = o.optInt("wrong", 0),
                    right = o.optInt("right", 0),
                    lastAt = o.optLong("lastAt", 0L),
                    lastChose = o.optInt("lastChose", -1)
                )
            }
        }
    }

    private val lock = Any()
    private var cache: MutableList<Entry>? = null
    private var cacheFile: File? = null

    /** 落盘是几 KB 的写，但答错那一刻主线程正在渲染反馈，不能让它等 eMMC。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wrong-book").apply { isDaemon = true }
    }

    private fun keyOf(q: QuizQuestion): String = "${q.subject}|${q.knowledge}"

    fun list(ctx: Context): List<Entry> = synchronized(lock) { load(ctx).toList() }

    fun count(ctx: Context): Int = synchronized(lock) { load(ctx).size }

    /** 答错：新增或累加，把最新一次看到的题目快照换上去。 */
    fun onWrong(ctx: Context, q: QuizQuestion, chose: Int) {
        synchronized(lock) {
            val list = load(ctx)
            val key = keyOf(q)
            val old = list.firstOrNull { it.id == key }
            list.removeAll { it.id == key }
            list.add(
                0,
                Entry(
                    id = key,
                    subject = q.subject,
                    grade = q.grade,
                    knowledge = q.knowledge,
                    stem = q.stem,
                    options = q.options,
                    answer = q.answer,
                    explain = q.explain,
                    wrong = (old?.wrong ?: 0) + 1,
                    right = old?.right ?: 0,
                    lastAt = System.currentTimeMillis(),
                    lastChose = chose
                )
            )
            while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
            persist(ctx, list)
        }
    }

    /** 答对：把「后来答对了几次」加上去，但保留错题记录（家长要看「错过什么」）。 */
    fun onRight(ctx: Context, q: QuizQuestion) {
        synchronized(lock) {
            val list = load(ctx)
            val key = keyOf(q)
            val idx = list.indexOfFirst { it.id == key }
            if (idx < 0) return
            val e = list[idx]
            list[idx] = e.copy(right = e.right + 1, lastAt = System.currentTimeMillis())
            persist(ctx, list)
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            cache = ArrayList()
            cacheFile = File(ctx.applicationContext.filesDir, FILE_NAME)
            persist(ctx, cache!!)
        }
    }

    /** 按错次从多到少，家长最该先看的是前几条。 */
    fun ranked(ctx: Context): List<Entry> =
        synchronized(lock) { load(ctx).sortedByDescending { it.wrong } }

    private fun load(ctx: Context): MutableList<Entry> {
        val file = File(ctx.applicationContext.filesDir, FILE_NAME)
        val cached = cache
        if (cached != null && cacheFile == file) return cached
        val out = ArrayList<Entry>()
        try {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                val arr = root.optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val e = Entry.from(o)
                    if (e.id.isNotEmpty() && e.stem.isNotEmpty()) out.add(e)
                }
            }
        } catch (e: Exception) {
            // 文件坏了就当空的：错题本丢了大不了从头记，不能因此让答题界面崩掉
        }
        cache = out
        cacheFile = file
        return out
    }

    private fun persist(ctx: Context, items: List<Entry>) {
        val file = File(ctx.applicationContext.filesDir, FILE_NAME)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val text = JSONObject().put("version", 1).put("items", arr).toString()
        io.execute {
            try {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }
}
