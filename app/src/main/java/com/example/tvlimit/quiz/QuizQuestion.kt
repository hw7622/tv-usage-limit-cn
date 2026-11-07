package com.example.tvlimit.quiz

/**
 * 一道题。
 *
 * 电视上只有遥控器，所以题型限定为「四选一」和「判断」。所有题目统一成 options + answer，
 * 判断题就是两个选项 —— 渲染层不需要区分题型，只按选项个数排版即可。
 */
data class QuizQuestion(
    val id: String,
    val subject: String,      // math / chinese / english / science / physics ...
    val grade: Int,
    val knowledge: String,    // 知识点标签，错题按它加权复现
    val stem: String,
    val options: List<String>,
    val answer: Int,
    val explain: String? = null,
    val difficulty: Int = 1
) {

    val isTrueFalse: Boolean get() = options.size == 2

    fun subjectLabel(): String = when (subject) {
        "math" -> "数学"
        "chinese" -> "语文"
        "english" -> "英语"
        "science" -> "科学"
        "physics" -> "物理"
        "chemistry" -> "化学"
        "biology" -> "生物"
        "history" -> "历史"
        "geography" -> "地理"
        "politics" -> "道德与法治"
        else -> subject
    }
}
