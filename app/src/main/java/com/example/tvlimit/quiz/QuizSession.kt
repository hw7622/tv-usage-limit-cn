package com.example.tvlimit.quiz

import android.content.Context

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
    }
}
