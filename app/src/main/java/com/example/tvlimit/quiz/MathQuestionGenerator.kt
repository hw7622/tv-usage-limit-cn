package com.example.tvlimit.quiz

import kotlin.random.Random

/**
 * 数学题生成器：按年级出题，**答案当场算，变体无限**。
 *
 * 关键设计：干扰项不是随机数，而是「常见错算」——
 * 加法给忘进位的结果，减法给忘退位的结果，分数给分子分母直接相加的结果。
 * 孩子选了错误项，等于告诉家长他错在哪一步；纯随机数做不到这一点。
 */
object MathQuestionGenerator {

    fun generate(grade: Int, rand: Random = Random.Default): QuizQuestion = when (grade.coerceIn(1, 9)) {
        1 -> within20(rand)
        2 -> twoDigit(rand)
        3 -> threeDigit(rand)
        4 -> multiDigit(rand)
        5 -> decimalAndFraction(rand)
        6 -> percentAndFraction(rand)
        7 -> negativeAndEquation(rand)
        8 -> algebraAndRoot(rand)
        else -> quadraticAndFunction(rand)
    }

    // ===== 各年级 =====

    private fun within20(r: Random): QuizQuestion {
        return if (r.nextBoolean()) {
            val a = r.nextInt(3, 10); val b = r.nextInt(2, 10)
            val c = a + b
            numeric(
                "p1-${a}-${b}-add", "20 以内的加法", "$a + $b = ?", c,
                listOf(c - 10, c + 10, c + 1), 1,
                explain = "个位相加满十要向前一位进 1。"
            )
        } else {
            val a = r.nextInt(11, 20); val b = r.nextInt(2, 10)
            val c = a - b
            numeric(
                "p1-${a}-${b}-sub", "20 以内的减法", "$a − $b = ?", c,
                listOf(c + 10, c - 1, c + 2), 1,
                explain = "个位不够减，要从十位借 1 当 10。"
            )
        }
    }

    private fun twoDigit(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(23, 79); val b = r.nextInt(13, 79)
                val c = a + b
                numeric(
                    "p2-${a}-${b}-add", "两位数加法（进位）", "$a + $b = ?", c,
                    listOf(c - 10, c + 10, c + 1), 2,
                    explain = "个位相加满十，别忘了进到十位。"
                )
            }
            1 -> {
                val a = r.nextInt(42, 96); val b = r.nextInt(13, 39)
                val c = a - b
                numeric(
                    "p2-${a}-${b}-sub", "两位数减法（退位）", "$a − $b = ?", c,
                    listOf(c + 10, c - 10, c + 1), 2,
                    explain = "个位不够减，从十位退 1 当 10 再减。"
                )
            }
            else -> {
                val a = r.nextInt(2, 10); val b = r.nextInt(2, 10)
                val c = a * b
                numeric(
                    "p2-${a}-${b}-mul", "表内乘法", "$a × $b = ?", c,
                    listOf(a * (b - 1), a * (b + 1), c + a), 2,
                    explain = "$a × $b 就是 $b 个 $a 相加。"
                )
            }
        }
    }

    private fun threeDigit(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(120, 590); val b = r.nextInt(110, 390)
                val c = a + b
                numeric(
                    "p3-${a}-${b}-add", "三位数加法", "$a + $b = ?", c,
                    listOf(c - 100, c + 10, c - 10), 2,
                    explain = "相同数位对齐，从个位加起。"
                )
            }
            1 -> {
                val a = r.nextInt(12, 30); val b = r.nextInt(3, 10)
                val c = a * b
                numeric(
                    "p3-${a}-${b}-mul", "两位数乘一位数", "$a × $b = ?", c,
                    listOf(a * (b - 1), a * (b + 1), c - 10), 2,
                    explain = "先用个位乘，再用十位乘，最后相加。"
                )
            }
            else -> {
                val b = r.nextInt(3, 10); val q = r.nextInt(11, 40); val a = b * q
                numeric(
                    "p3-${a}-${b}-div", "除法", "$a ÷ $b = ?", q,
                    listOf(q + 1, q - 1, q + 10), 2,
                    explain = "想「$b 乘几等于 $a」。"
                )
            }
        }
    }

    private fun multiDigit(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(112, 480); val b = r.nextInt(12, 29)
                val c = a * b
                numeric(
                    "p4-${a}-${b}-mul", "三位数乘两位数", "$a × $b = ?", c,
                    listOf(a * (b - 1), a * (b / 10), c + a * 10), 3,
                    explain = "分别用个位、十位去乘，再把两个积相加。"
                )
            }
            1 -> {
                val a = r.nextInt(6, 19); val b = r.nextInt(3, 9); val c = r.nextInt(2, 9)
                val correct = a + b * c
                numeric(
                    "p4-${a}-${b}-${c}-mix", "四则混合运算", "$a + $b × $c = ?", correct,
                    listOf((a + b) * c, a * b + c, correct + b), 3,
                    explain = "先算乘法，再算加法。"
                )
            }
            else -> {
                // 单位换算：1 米 = 100 厘米 / 1 千克 = 1000 克 / 1 时 = 60 分
                val base = listOf(
                    Triple("米", "厘米", 100),
                    Triple("千克", "克", 1000),
                    Triple("时", "分", 60),
                    Triple("分", "秒", 60)
                ).random(r)
                val n = r.nextInt(2, 9)
                val correct = n * base.third
                numeric(
                    "p4-unit-${base.first}-$n", "单位换算",
                    "$n ${base.first} = ? ${base.second}", correct,
                    listOf(correct / 10, correct * 10, correct + 10), 2,
                    explain = "1 ${base.first} = ${base.third} ${base.second}。"
                )
            }
        }
    }

    private fun decimalAndFraction(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                // 一位小数加法，用整数缩放保证不出现浮点误差
                val a = r.nextInt(11, 89); val b = r.nextInt(11, 89)
                val c = a + b
                decimal(
                    "p5-${a}-${b}-dec", "小数加法",
                    "${fmt1(a)} + ${fmt1(b)} = ?", c,
                    listOf(c - 10, c + 10, c + 1), 2,
                    explain = "小数点对齐，满十进一。"
                )
            }
            1 -> {
                // 同分母分数加法
                val d = r.nextInt(4, 10)
                val a = r.nextInt(1, d); val b = r.nextInt(1, d)
                val n = a + b
                // 正确结果：分母不变、分子相加（能约成 1 的写成 1）
                val correct = if (n == d) "1" else "$n/$d"
                val uniq = LinkedHashSet(
                    listOf(correct, "$n/${d * 2}", "$n/${d + 1}", "${a + b}/${b + a + d}")
                ).toMutableList()
                // 兜底补齐：候选值必须随计数变化，否则 add 失败会原地死循环
                var extra = 2
                while (uniq.size < 4 && extra < 30) {
                    uniq.add("$n/${d * extra}")
                    extra++
                }
                val shuffled = uniq.shuffled(r)
                QuizQuestion(
                    id = "p5-frac-$a-$b-$d", subject = "math", grade = 5,
                    knowledge = "同分母分数加法", stem = "$a/$d + $b/$d = ?",
                    options = shuffled, answer = shuffled.indexOf(correct),
                    explain = "分母不变，分子相加。", difficulty = 2
                )
            }
            else -> {
                val w = r.nextInt(4, 13); val h = r.nextInt(3, 10)
                val area = w * h
                numeric(
                    "p5-area-$w-$h", "长方形面积",
                    "长 ${w} 厘米、宽 ${h} 厘米的长方形，面积是多少平方厘米？", area,
                    listOf(2 * (w + h), w + h, area + w), 2,
                    explain = "长方形面积 = 长 × 宽。"
                )
            }
        }
    }

    private fun percentAndFraction(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val base = r.nextInt(2, 9) * 20
                val p = listOf(10, 20, 25, 50, 75).random(r)
                val c = base * p / 100
                numeric(
                    "p6-pct-$base-$p", "百分数",
                    "$base 的 $p% 是多少？", c,
                    listOf(base * p / 10, c + base / 10, c * 2), 3,
                    explain = "求一个数的百分之几，用这个数 × 百分数。"
                )
            }
            1 -> {
                val a = r.nextInt(1, 6); val b = r.nextInt(2, 7)
                val c = r.nextInt(1, 6); val d = r.nextInt(2, 7)
                val num = a * c; val den = b * d
                val correct = "$num/$den"
                val uniq = LinkedHashSet(
                    listOf(
                        correct,
                        "${a * d}/$den",
                        "${a + c}/${b + d}",
                        "$num/${b + d}"
                    )
                ).toMutableList()
                var extra = 2
                while (uniq.size < 4 && extra < 30) {
                    uniq.add("$num/${den + extra}")
                    extra++
                }
                val shuffled = uniq.shuffled(r)
                QuizQuestion(
                    id = "p6-fmul-$a-$b-$c-$d", subject = "math", grade = 6,
                    knowledge = "分数乘法", stem = "$a/$b × $c/$d = ?",
                    options = shuffled, answer = shuffled.indexOf(correct),
                    explain = "分子乘分子，分母乘分母。", difficulty = 3
                )
            }
            else -> {
                val a = r.nextInt(2, 9); val b = r.nextInt(2, 9)
                // 后项乘以 10，前项也得乘以 10，比值才不变。
                // 之前把 a 当成答案，结果「选对的」被判错、「选错的」被判对。
                val correct = a * 10
                numeric(
                    "p6-ratio-$a-$b", "比例",
                    "$a : $b = ? : ${b * 10}", correct,
                    listOf(a, b, a + b), 3,
                    explain = "比的前项和后项同时乘 10，比值不变。"
                )
            }
        }
    }

    private fun negativeAndEquation(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(-9, 0); val b = r.nextInt(2, 12)
                val c = a + b
                numeric(
                    "j7-neg-$a-$b", "有理数加法", "($a) + $b = ?", c,
                    listOf(-c, c - 2 * b, a - b), 2,
                    explain = "异号两数相加，取绝对值大的符号，用大减小。"
                )
            }
            1 -> {
                val a = r.nextInt(2, 8); val b = r.nextInt(-9, 10); val x = r.nextInt(-6, 7)
                val c = a * x + b
                numeric(
                    "j7-eq-$a-$b-$c", "一元一次方程", "解方程：${a}x ${sign(b)} = $c", x,
                    listOf(-x, x + 1, x - 1), 3,
                    explain = "先移项（变号），再把系数化为 1。"
                )
            }
            else -> {
                val a = r.nextInt(2, 9); val b = r.nextInt(2, 9)
                // 题干是 (-a) × b，答案就是 -ab。
                // 原来是「a × (-ab)」却把 -ab 当答案，题干和答案对不上。
                val correct = -a * b
                numeric(
                    "j7-mul-$a-$b", "有理数乘法", "(-$a) × $b = ?", correct,
                    listOf(a * b, correct - a, a * b + b), 3,
                    explain = "异号相乘得负，绝对值相乘。"
                )
            }
        }
    }

    private fun algebraAndRoot(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(2, 6); val b = r.nextInt(2, 7)
                val stem = "(x + $a)(x + $b) 展开后一次项系数是？"
                val correct = a + b
                numeric(
                    "j8-exp-$a-$b", "整式乘法", stem, correct,
                    listOf(a * b, a + b + 1, a - b), 3,
                    explain = "一次项系数 = 两个常数项之和。"
                )
            }
            1 -> {
                val n = listOf(4, 9, 16, 25, 36, 49, 64, 81, 100).random(r)
                val correct = when (n) {
                    4 -> 2; 9 -> 3; 16 -> 4; 25 -> 5; 36 -> 6; 49 -> 7; 64 -> 8; 81 -> 9; else -> 10
                }
                numeric(
                    "j8-sqrt-$n", "平方根", "√$n = ?", correct,
                    listOf(correct * 2, correct + 1, n / 2), 3,
                    explain = "找哪个数的平方等于 $n。"
                )
            }
            else -> {
                // 只出整数勾股数，答案才是整数。
                // 原来随机取两条直角边、把 a²+b² 当斜边，3 和 4 的「斜边」变成了 25。
                val triples = listOf(
                    Triple(3, 4, 5), Triple(6, 8, 10), Triple(5, 12, 13),
                    Triple(8, 15, 17), Triple(9, 12, 15), Triple(7, 24, 25)
                )
                val t = triples[r.nextInt(triples.size)]
                val k = if (r.nextBoolean()) 1 else 2
                val a = t.first * k; val b = t.second * k; val c = t.third * k
                numeric(
                    "j8-pyth-$a-$b", "勾股定理",
                    "直角三角形两条直角边为 $a 和 $b，斜边是多少？",
                    c, listOf(a + b, c + 1, a * b), 3,
                    explain = "斜边² = 直角边² + 直角边²，$a² + $b² = ${c * c}。"
                )
            }
        }
    }

    private fun quadraticAndFunction(r: Random): QuizQuestion {
        return when (r.nextInt(3)) {
            0 -> {
                val p = r.nextInt(1, 6); val q = r.nextInt(1, 6)
                val b = -(p + q); val c = p * q
                val stem = "方程 x² ${sign(b)}x ${sign(c)} = 0 的正根是？"
                numeric(
                    "j9-quad-$p-$q", "一元二次方程", stem, maxOf(p, q),
                    listOf(minOf(p, q), p + q, p * q), 4,
                    explain = "因式分解成 (x − $p)(x − $q) = 0。"
                )
            }
            1 -> {
                val a = r.nextInt(2, 5); val b = r.nextInt(-5, 6); val x = r.nextInt(-3, 4)
                val c = a * x + b
                numeric(
                    "j9-fn-$a-$b-$x", "一次函数",
                    "y = ${a}x ${sign(b)}，当 x = $x 时 y = ?", c,
                    listOf(-c, c + a, c - a), 4,
                    explain = "把 x 的值代进去算。"
                )
            }
            else -> {
                val p = listOf(10, 20, 25).random(r)
                val base = r.nextInt(2, 9) * 100
                val c = base * p / 100
                numeric(
                    "j9-grow-$base-$p", "增长率",
                    "某数先增加 $p%，增加的部分是多少（原数为 $base）？", c,
                    listOf(base + c, base * p / 10, c / 10), 4,
                    explain = "增加的部分 = 原数 × 增长率。"
                )
            }
        }
    }

    // ===== 工具 =====

    private fun sign(v: Int): String = if (v >= 0) "+ $v" else "− ${-v}"

    private fun fmt1(scaled: Int): String {
        val i = scaled / 10
        val f = scaled % 10
        return if (f == 0) "$i.0" else "$i.$f"
    }

    /** 整数题：正确答案 + 常见错算，去重后打乱。 */
    private fun numeric(
        id: String,
        knowledge: String,
        stem: String,
        correct: Int,
        wrongs: List<Int>,
        grade: Int,
        explain: String? = null
    ): QuizQuestion {
        val correctText = correct.toString()
        val opts = mutableListOf(correctText)
        for (w in wrongs) {
            if (opts.size >= 4) break
            val t = w.toString()
            if (t != correctText && t !in opts) opts.add(t)
        }
        var k = 1
        while (opts.size < 4) {
            val t = (correct + k).toString()
            if (t != correctText && t !in opts) opts.add(t)
            k++
            if (k > 20) break
        }
        val shuffled = opts.shuffled()
        return QuizQuestion(id, "math", grade, knowledge, stem, shuffled, shuffled.indexOf(correctText), explain)
    }

    /** 小数题：传入放大 10 倍的整数，显示成一位小数。 */
    private fun decimal(
        id: String,
        knowledge: String,
        stem: String,
        correctScaled: Int,
        wrongScaled: List<Int>,
        grade: Int,
        explain: String? = null
    ): QuizQuestion {
        val correctText = fmt1(correctScaled)
        val opts = mutableListOf(correctText)
        for (w in wrongScaled) {
            if (opts.size >= 4) break
            val t = fmt1(w)
            if (t != correctText && t !in opts) opts.add(t)
        }
        var k = 1
        while (opts.size < 4) {
            val t = fmt1(correctScaled + k)
            if (t != correctText && t !in opts) opts.add(t)
            k++
        }
        val shuffled = opts.shuffled()
        return QuizQuestion(id, "math", grade, knowledge, stem, shuffled, shuffled.indexOf(correctText), explain)
    }
}
