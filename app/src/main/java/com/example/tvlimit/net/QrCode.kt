package com.example.tvlimit.net

/**
 * 极简二维码编码器：byte 模式 / 纠错等级 M / 版本 1~6。
 *
 * 为什么要自己写：这是一个「装了就不该再要权限、不该再联网」的家长控制 App，
 * 引一个二维码库（zixing / zxing-core）要多几 MB，还要为它写混淆规则；
 * 而我们的实际需求只有一个 —— 把 `http://192.168.3.2:8080/` 这三十来个字符画出来。
 *
 * 只支持到版本 6（最多 108 字节）是刻意的：局域网地址再长也到不了这个量级，
 * 而版本 7 开始要多画一块「版本信息」，代码量和出错面都会上一个台阶。
 *
 * 算法照 ISO/IEC 18004 实现，结构和 Nayuki 的参考实现一致，便于对照排查。
 */
object QrCode {

    /** 生成结果：size × size 的布尔矩阵，true = 黑块。 */
    class Matrix(val size: Int, val modules: Array<BooleanArray>) {
        fun isDark(row: Int, col: Int): Boolean = modules[row][col]
    }

    /** 纠错等级 M 的版本参数（块都是等长的，省掉短块拼接那段逻辑）。 */
    private class Spec(
        val version: Int,
        val ecPerBlock: Int,
        val blocks: Int,
        val dataPerBlock: Int
    )

    private val SPECS = arrayOf(
        Spec(1, 10, 1, 16),
        Spec(2, 16, 1, 28),
        Spec(3, 26, 1, 44),
        Spec(4, 18, 2, 32),
        Spec(5, 24, 2, 43),
        Spec(6, 16, 4, 27)
    )

    /** 对齐图案中心坐标，版本 1 没有。 */
    private val ALIGN = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34)
    )

    private const val ECC_M_FORMAT_BITS = 0

    // ===== GF(256) =====

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun rsGenerator(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor gfMul(g[j], EXP[i])
            }
            g = next
        }
        return g
    }

    private fun rsRemainder(data: IntArray, degree: Int): IntArray {
        val gen = rsGenerator(degree)
        val res = IntArray(degree)
        for (b in data) {
            val factor = b xor res[0]
            System.arraycopy(res, 1, res, 0, degree - 1)
            res[degree - 1] = 0
            if (factor != 0) {
                for (i in 0 until degree) {
                    res[i] = res[i] xor gfMul(gen[i + 1], factor)
                }
            }
        }
        return res
    }

    // ===== 对外 =====

    /** 内容太长（超过 108 字节）返回 null，调用方自己决定怎么降级。 */
    fun encode(text: String): Matrix? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 4 位模式 + 8 位长度 + 8×n 位数据，必须塞得进这一版的数据容量
        val spec = SPECS.firstOrNull { 12 + bytes.size * 8 <= it.dataPerBlock * it.blocks * 8 }
            ?: return null
        val capacityBits = spec.dataPerBlock * spec.blocks * 8

        val bb = ArrayList<Boolean>(capacityBits)
        fun put(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bb.add((value ushr i) and 1 == 1)
        }
        put(0b0100, 4)          // byte 模式
        put(bytes.size, 8)      // v1~v9 的字符计数是 8 位
        bytes.forEach { put(it.toInt() and 0xFF, 8) }

        // 终止符 + 补齐到字节边界
        val term = minOf(4, capacityBits - bb.size)
        repeat(term) { bb.add(false) }
        while (bb.size % 8 != 0) bb.add(false)

        val dataCodewords = IntArray(bb.size / 8) { i ->
            var v = 0
            for (j in 0 until 8) if (bb[i * 8 + j]) v = v or (1 shl (7 - j))
            v
        }.toMutableList()
        // 填充字节：0xEC / 0x11 交替，直到填满数据容量
        var pad = 0xEC
        while (dataCodewords.size < spec.dataPerBlock * spec.blocks) {
            dataCodewords.add(pad)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val codewords = interleave(dataCodewords, spec)
        return render(codewords, spec)
    }

    private fun interleave(data: List<Int>, spec: Spec): IntArray {
        val blocks = ArrayList<IntArray>(spec.blocks)
        var p = 0
        for (b in 0 until spec.blocks) {
            val blk = IntArray(spec.dataPerBlock)
            for (i in 0 until spec.dataPerBlock) {
                blk[i] = data[p++]
            }
            blocks.add(blk)
        }
        val ecs = blocks.map { rsRemainder(it, spec.ecPerBlock) }

        val out = IntArray(spec.blocks * (spec.dataPerBlock + spec.ecPerBlock))
        var k = 0
        for (i in 0 until spec.dataPerBlock) {
            for (b in 0 until spec.blocks) out[k++] = blocks[b][i]
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until spec.blocks) out[k++] = ecs[b][i]
        }
        return out
    }

    // ===== 画矩阵 =====

    private class Canvas(val version: Int) {
        val size = 17 + version * 4
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(row: Int, col: Int, dark: Boolean) {
            modules[row][col] = dark
            isFunction[row][col] = true
        }
    }

    private fun render(codewords: IntArray, spec: Spec): Matrix {
        val v = spec.version
        val c = Canvas(v)

        // 定位图案 + 分隔符
        for ((sr, sc) in listOf(0 to 0, 0 to c.size - 7, c.size - 7 to 0)) {
            for (dr in -1..7) for (dc in -1..7) {
                val r = sr + dr
                val col = sc + dc
                if (r !in 0 until c.size || col !in 0 until c.size) continue
                val dist = maxOf(Math.abs(dr - 3), Math.abs(dc - 3))
                c.setFunction(r, col, dist != 2 && dist != 4)
            }
        }

        // 定时图案
        for (i in 0 until c.size) {
            if (!c.isFunction[6][i]) c.setFunction(6, i, i % 2 == 0)
            if (!c.isFunction[i][6]) c.setFunction(i, 6, i % 2 == 0)
        }

        // 对齐图案
        val pos = ALIGN[v - 1]
        for (r in pos) for (col in pos) {
            // 三个角上的是定位图案，跳过
            val corner = (r == 6 && col == 6) ||
                (r == 6 && col == c.size - 7) ||
                (r == c.size - 7 && col == 6)
            if (corner) continue
            for (dr in -2..2) for (dc in -2..2) {
                val dist = maxOf(Math.abs(dr), Math.abs(dc))
                c.setFunction(r + dr, col + dc, dist != 1)
            }
        }

        // 右下角那一格永远是黑的
        c.setFunction(c.size - 8, 8, true)
        // 先把格式信息区域占住，免得数据位写进去
        reserveFormat(c)

        // 数据位
        placeData(c, codewords)

        // 8 种掩码试一遍，取罚分最低的
        var best: Array<BooleanArray>? = null
        var bestScore = Int.MAX_VALUE
        var bestMask = 0
        for (mask in 0 until 8) {
            val copy = Array(c.size) { c.modules[it].copyOf() }
            applyMask(copy, c.isFunction, mask)
            drawFormat(c, copy, mask)
            val score = penalty(copy)
            if (score < bestScore) {
                bestScore = score
                best = copy
                bestMask = mask
            }
        }
        val out = best ?: c.modules
        return Matrix(c.size, out)
    }

    private fun reserveFormat(c: Canvas) {
        for (i in 0..8) {
            if (i != 6) {
                if (!c.isFunction[8][i]) c.setFunction(8, i, false)
                if (!c.isFunction[i][8]) c.setFunction(i, 8, false)
            }
        }
        for (i in 0 until 8) {
            if (!c.isFunction[8][c.size - 1 - i]) c.setFunction(8, c.size - 1 - i, false)
            if (!c.isFunction[c.size - 1 - i][8]) c.setFunction(c.size - 1 - i, 8, false)
        }
    }

    private fun placeData(c: Canvas, codewords: IntArray) {
        val size = c.size
        var bit = 0
        val total = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val col = right - j
                    val row = if (upward) size - 1 - vert else vert
                    if (c.isFunction[row][col]) continue
                    var dark = false
                    if (bit < total) {
                        dark = (codewords[bit / 8] ushr (7 - bit % 8)) and 1 == 1
                    }
                    bit++
                    c.modules[row][col] = dark
                }
            }
            right -= 2
        }
    }

    private fun applyMask(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, mask: Int) {
        for (r in modules.indices) for (col in modules.indices) {
            if (isFunction[r][col]) continue
            val invert = when (mask) {
                0 -> (r + col) % 2 == 0
                1 -> r % 2 == 0
                2 -> col % 3 == 0
                3 -> (r + col) % 3 == 0
                4 -> (r / 2 + col / 3) % 2 == 0
                5 -> (r * col) % 2 + (r * col) % 3 == 0
                6 -> ((r * col) % 2 + (r * col) % 3) % 2 == 0
                else -> ((r + col) % 2 + (r * col) % 3) % 2 == 0
            }
            if (invert) modules[r][col] = !modules[r][col]
        }
    }

    private fun drawFormat(c: Canvas, modules: Array<BooleanArray>, mask: Int) {
        val data = (ECC_M_FORMAT_BITS shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) and 1) * 0x537
        }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1

        val size = c.size
        // 第一份：左上三个方向
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)
        // 第二份：右上 + 左下
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)
        modules[size - 8][8] = true
    }

    // ===== 掩码罚分（ISO 18004 §8.8.2 四条规则）=====

    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0

        // N1：行/列里 5 个及以上连续同色
        for (r in 0 until n) {
            var run = 1
            for (col in 1 until n) {
                if (m[r][col] == m[r][col - 1]) {
                    run++
                } else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        for (col in 0 until n) {
            var run = 1
            for (r in 1 until n) {
                if (m[r][col] == m[r - 1][col]) {
                    run++
                } else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }

        // N2：2×2 同色块
        for (r in 0 until n - 1) for (col in 0 until n - 1) {
            val v = m[r][col]
            if (v == m[r][col + 1] && v == m[r + 1][col] && v == m[r + 1][col + 1]) score += 3
        }

        // N3：形似定位图案的 1:1:3:1:1 结构
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (r in 0 until n) for (col in 0 until n - 10) {
            var hit1 = true
            var hit2 = true
            for (k in 0..10) {
                if (m[r][col + k] != p1[k]) hit1 = false
                if (m[r][col + k] != p2[k]) hit2 = false
            }
            if (hit1) score += 40
            if (hit2) score += 40
        }
        for (col in 0 until n) for (r in 0 until n - 10) {
            var hit1 = true
            var hit2 = true
            for (k in 0..10) {
                if (m[r + k][col] != p1[k]) hit1 = false
                if (m[r + k][col] != p2[k]) hit2 = false
            }
            if (hit1) score += 40
            if (hit2) score += 40
        }

        // N4：黑白比例偏离 50%
        var dark = 0
        for (r in 0 until n) for (col in 0 until n) if (m[r][col]) dark++
        val percent = dark * 100 / (n * n)
        score += Math.abs(percent - 50) / 5 * 10
        return score
    }
}
