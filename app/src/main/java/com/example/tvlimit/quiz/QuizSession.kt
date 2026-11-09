package com.example.tvlimit.quiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次答题会话：一道原题 + 按错题次数追加的题。
 *
 * 规则照文章来：
 * - 答错 1 次，追加 1 道；错 2 次追加 2 道；错 3 次追加 3 道；
 * - 原题**留在队首**，先重做它，再做追加的题；
 * - 全部答对才算过。
 *
 * 追加数量在 5 道封顶。文章里没写上限，但电视上真让孩子连答 15 道，
 * 结果一定是「关机不看了」——限制的目的本来是打断，不是劝退。
 */
class QuizSession private constructor(
    val queue: MutableList<QuizQuestion>,
    var wrongOnCurrent: Int = 0
) {

    fun current(): QuizQuestion? = queue.firstOrNull()

    fun remaining(): Int = queue.size

    /**
     * 把「还没答完的这一组题」原样存成字符串。
     *
     * 为什么必须存下来：这个会话只活在这个对象里，孩子按返回退出（或进程被杀、电视断电）
     * 就整组消失，下次重进又是最初那 3 道 —— 等于「答错追加」这条规则按一下返回就能抵消。
     * 存的是题目快照而不是 id，题库改过之后也能照原题接着答。
     */
    fun toJson(): String = JSONObject().apply {
        put("wrong", wrongOnCurrent)
        put("queue", JSONArray().also { arr -> queue.forEach { arr.put(it.toJson()) } })
    }.toString()

    fun submit(ctx: Context, chosenIndex: Int): Outcome {
        val q = queue.firstOrNull() ?: return Outcome.Finished
        val correct = chosenIndex == q.answer

        return if (correct) {
            QuizStats.onRight(ctx, q)
            WrongStore.onRight(ctx, q)
            queue.removeAt(0)
            wrongOnCurrent = 0
            if (queue.isEmpty()) Outcome.Finished else Outcome.Correct
        } else {
            QuizStats.onWrong(ctx, q)
            // 同一道题顺手记进错题本：家长在手机上看的是题干和正解，
            // QuizStats 里只有知识点计数，给人看不够。
            WrongStore.onWrong(ctx, q, chosenIndex)
            wrongOnCurrent += 1
            val add = wrongOnCurrent.coerceAtMost(5)
            repeat(add) { queue.add(QuizEngine.next(ctx)) }
            Outcome.Wrong(
                added = add,
                reveal = wrongOnCurrent >= 2,
                correctText = q.options.getOrNull(q.answer) ?: "",
                explain = q.explain
            )
        }
    }

    sealed class Outcome {
        /** 答对了，但还有追加的题要答。 */
        object Correct : Outcome()

        /** 全部答完，可以继续看电视。 */
        object Finished : Outcome()

        /**
         * @param added 这次又追加了几道
         * @param reveal 是否到了「该给答案了」的程度（同一题错第 2 次）
         */
        data class Wrong(
            val added: Int,
            val reveal: Boolean,
            val correctText: String,
            val explain: String?
        ) : Outcome()
    }

    companion object {
        /**
         * 开一轮答题：先出 [count] 道原题，答错再按规则追加。
         *
         * 一次出多道时避免「连着两道一模一样的题」：数学是现算的，题干撞车虽然少见，
         * 但语文/英语是从固定题库里抽，连抽两道同题孩子会以为程序卡了。
         * 重试几次仍抽不到新题就接受重复，不能为了去重把会话卡死。
         */
        fun start(ctx: Context, count: Int = QuizSettings.questionsPerSession(ctx)): QuizSession {
            val n = count.coerceIn(1, 10)
            val queue = mutableListOf<QuizQuestion>()
            while (queue.size < n) {
                var q = QuizEngine.next(ctx)
                var tries = 0
                while (queue.any { it.stem == q.stem } && tries < 6) {
                    q = QuizEngine.next(ctx)
                    tries++
                }
                queue.add(q)
            }
            return QuizSession(queue)
        }

        /**
         * 恢复上次没答完的那一组。数据坏了就返回 null，让调用方重新开一组；
         * 绝不能因为一条坏 JSON 把孩子永久锁在答题页。
         */
        fun restore(raw: String?): QuizSession? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("queue") ?: return null
                val queue = ArrayList<QuizQuestion>(arr.length())
                for (i in 0 until arr.length()) {
                    val q = arr.optJSONObject(i)?.toQuestion() ?: continue
                    queue.add(q)
                }
                if (queue.isEmpty()) null else QuizSession(queue, o.optInt("wrong", 0))
            } catch (e: Exception) {
                null
            }
        }
    }
}

private fun QuizQuestion.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("subject", subject)
    put("grade", grade)
    put("knowledge", knowledge)
    put("stem", stem)
    put("options", JSONArray(options))
    put("answer", answer)
    put("explain", explain ?: JSONObject.NULL)
    put("difficulty", difficulty)
}

private fun JSONObject.toQuestion(): QuizQuestion? {
    val stem = optString("stem", "")
    if (stem.isEmpty()) return null
    val optsArr = optJSONArray("options") ?: return null
    val options = ArrayList<String>(optsArr.length())
    for (i in 0 until optsArr.length()) options.add(optsArr.optString(i, ""))
    if (options.size < 2) return null
    val answer = optInt("answer", -1)
    if (answer !in options.indices) return null
    return QuizQuestion(
        id = optString("id", ""),
        subject = optString("subject", "math"),
        grade = optInt("grade", 2),
        knowledge = optString("knowledge", ""),
        stem = stem,
        options = options,
        answer = answer,
        explain = if (isNull("explain")) null else optString("explain", ""),
        difficulty = optInt("difficulty", 1)
    )
}
