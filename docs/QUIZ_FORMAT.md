# 题库格式与转换

> 面向：想自己加题的人、想把别的题库导进来的人。
> 相关实现：`tools/import_bank.py`、`app/src/main/java/.../quiz/QuestionBank.kt`

---

## 0. 整体链路

```
   手工编题（Excel/WPS）
            │  export / 直接写 CSV
            ▼
       questions.csv  ──convert──┐
                                 ├──► bank.json ──► 打进 APK ──► 电视
   第三方题库（自己下载）         │
            │  profile 映射        │
            ▼                      │
       xxx.csv ──convert──────────┘
```

仓库里**只放工具和格式说明，不放任何第三方题库数据**。想用哪个源，自己下载、自己转。
这样仓库不承担数据许可义务，谁用谁负责。

---

## 1. APP 实际读的格式

`assets/quiz/bank.json`，一个 JSON 对象：

```json
{
  "version": 2,
  "source": "自编",
  "license": "Apache-2.0",
  "retrievedAt": "2026-11-07",
  "questions": [
    {
      "id": "chinese-g2-0030",
      "subject": "chinese",
      "grade": 2,
      "knowledge": "字形",
      "stem": "「碧」字是什么结构？",
      "options": ["上下结构", "左右结构", "半包围结构", "独体字"],
      "answer": 0,
      "explain": "碧由「王」「白」「石」上下叠成，是上下结构。",
      "difficulty": 1
    }
  ]
}
```

顶层只有 `questions` 是必需的，其余三个是出处标注，App 不读但也别省——将来要追许可就是靠它。

| 字段 | 必需 | 取值 | 说明 |
|---|---|---|---|
| `id` | 否 | `科目-g年级-序号` | 缺了 App 会按顺序补 `q-N`，但合并去重要靠它，别手改成重复的 |
| `subject` | 是 | `math` `chinese` `english` `science` `physics` `chemistry` `biology` `history` `geography` `politics` | 必须是英文 key，中文标签只在界面显示时用 |
| `grade` | 是 | 1–9 | 1–6 小学，7–9 初中 |
| `knowledge` | 否 | 任意短语 | 错题加权按「科目+知识点」记，默认「综合」 |
| `stem` | 是 | ≤ 60 字 | 电视上超过 26 字会折行，看是能看，但不好读 |
| `options` | 是 | 2–4 项 | **4 是硬上限**：界面只有 `tvOpt0..3` 四格。2 项即判断题 |
| `answer` | 是 | 0 基下标 | `0` 表示 A。指向文本的会被转换器换回下标 |
| `explain` | 否 | 任意 | 答错两次后展示 |
| `difficulty` | 否 | 1–3 | 暂时只用于展示，不参与抽题权重 |

---

## 2. 人怎么编题：用 CSV，别手写 JSON

JSON 里少个逗号就整库加载失败（`QuestionBank` 会静默返回空表，表现为「弹不出题」）。
所以编题走 CSV，让工具去生成 JSON。

**先导出成表格**（每行一道题，Excel/WPS 直接打开，utf-8-sig 不会乱码）：

```bash
python tools/import_bank.py export app/src/main/assets/quiz/bank.json -o 题库.csv
```

表头固定这 12 列：

| 列 | 填什么 |
|---|---|
| `id` | 留空，工具会编号 |
| `subject` | `chinese` / `english` / … |
| `grade` | `2` |
| `knowledge` | `拼音`、`量词`、`字形`… |
| `stem` | 题干，**不要出现「如图」「阅读短文」** |
| `optionA`–`optionD` | 选项文本；判断题只填 A、B |
| `answer` | `A`/`B`/`C`/`D`，也接受 `0`–`3`、`1`–`4` 或选项原文 |
| `explain` | 一句话讲清楚为什么，可以留空 |
| `difficulty` | 1–3，可不填 |

改完转回去：

```bash
python tools/import_bank.py convert 题库.csv -o tmp/new.json --source 自编 --license "Apache-2.0"
python tools/import_bank.py rebalance tmp/new.json        # 打散正确答案的位置
python tools/import_bank.py merge app/src/main/assets/quiz/bank.json tmp/new.json \
    --stamp 2026-11-07 -o app/src/main/assets/quiz/bank.json
```

`rebalance` 别省。手写题时正确项很容易一直放在第一格，攒到一定程度孩子就学会「一路按左键」。

`merge` 会把新题追加在后面、按 `科目-g年级-序号` 接着编号，并且**跟已有题目去重**（归一化后题干相同就跳过），
所以「改一道旧题」不能靠 merge，得直接改 CSV 里那一行再整库重建，或者用编辑器改 JSON。

---

## 3. 工具的五个子命令

```bash
# 体检：题量矩阵、缺口、非法题、重复题干
python tools/import_bank.py check app/src/main/assets/quiz/bank.json

# 导出成 CSV
python tools/import_bank.py export app/src/main/assets/quiz/bank.json -o 题库.csv

# 任意源转成 bank.json（字段名靠 profile 映射）
python tools/import_bank.py convert 源文件.csv --profile tools/profiles/template.json \
    -o tmp/new.json --limit 50 --dry-run

# 打散正确答案的位置（按 科目+年级 分桶各自均分）
python tools/import_bank.py rebalance tmp/new.json

# 合并进基线库
python tools/import_bank.py merge base.json a.json b.json -o out.json
```

`check` 的实际输出（当前仓库）：

```
科目         1    2    3    4    5    6    7    8    9    合计
语文         ·   38    3    3    2    2    ·    ·    ·     48
英语         ·   18    8    3    3    4    ·    ·    ·     36
科学         ·    ·    5    5    ·    ·    ·    ·    ·     10
...
结构检查：全部通过（无非法字段、无重复题干）

答案分布：A 41(31%)  B 38(28%)  C 31(23%)  D 24(18%)

覆盖度（目标单科单年级 ≥ 150 道，按单科每天 12 道算能撑几天）
  语文     2年级    38 道  缺 112  约 3 天转一圈
```

`convert --dry-run` 会先告诉你「10 条里通过 2 条、丢弃 8 条、分别因为什么」，确认无误再落盘。

那个「每天 12 道」是按默认配置推出来的：日限 180 分 ÷ 每组 10 分 × 每组 3 道 = 54 道/天，
其中数学占 55%（走生成器、不消耗题库），剩下约 24 道由语文和英语均分。改了配置就用 `--per-day` 覆盖。

---

## 4. 转第三方题库：写 profile 就行

以某个 CSV 为例，它的列名可能叫 `question / A / B / C / D / answer / subject`，
复制一份模板改右边：

```json
{
  "name": "某来源",
  "license": "MIT",
  "retrievedAt": "2026-11-07",
  "fields": {
    "stem": "question",
    "options": ["A", "B", "C", "D"],
    "answer": "answer",
    "subject": "subject",
    "grade": "grade",
    "knowledge": "knowledge",
    "explain": "analysis"
  }
}
```

不写 `options` 时，工具按 `optionA / option_a / A` 三种写法依次去找，`E`、`F` 也会读进来
——但会在校验时按「超过 4 项」明确拒掉，而不是悄悄丢掉第五项导致答案错位。

**科目和年级不用一定写映射**：`语文/数学/英语…`、`二年级`、`初二`、`七年级`、`G2` 内置都能认。
只有特殊写法（比如 `primary-2`）才需要在 profile 里补 `subjects` / `grades` 两张表。

### 四个容易踩的点

1. **答案基准**：有的源用 `0–3`，有的用 `1–4`。工具会整列扫一遍自动判断（出现过 0 就按 0 基），
   判错了用 `--answer-base 0|1` 强制指定。**先 `--limit 20 --dry-run` 看几条再全量。**
2. **判断题**：源里若是「选项只有 A、B」，能直接转；若是「题干 + 对/错 + 无选项」，
   得先在这一列拼出 `对`/`错` 两个选项，或者放弃这批。
3. **带图题**：含「如图」「下图」「阅读短文」的会被自动丢弃。硬留下来的话孩子在电视上没法答。
4. **解析带公式**：纯文本能显示，LaTeX（`$x^2$`）会原样显示成美元符号，要么洗掉要么弃用。

---

## 5. 答案位置：最容易漏掉的坑

**手写题库的默认习惯是「正确项放第一个」，攒多了就成了漏洞。** 第一次体检时，
120 道题里 96% 的答案是 A —— 孩子只要一路按左键就能过门禁，拦不住任何人。

`rebalance` 的处理方式：按 (科目, 年级) 分桶，桶内先打乱顺序，
再把正确答案依次挪到不同格子。**只改正确项站哪一格，选项内容和解析都不动。**

```bash
python tools/import_bank.py rebalance app/src/main/assets/quiz/bank.json --dry-run
```

```
重排前：A 128(96%)  B 5(4%)  D 1(1%)
重排后：A 41(31%)  B 38(28%)  C 31(23%)  D 24(18%)
```

两个注意点：

- **解析里写了「选 A」「答案是 B」的题会被跳过**，不重排。重排后字母对不上，孩子按解析会被带进沟里。
  所以自编题的解析要写「为什么这个对」，而不是「选第几个」。第三方题库这种写法很常见，
  跳过的数量会打印出来。
- 重排是**确定性**的（默认 seed 固定），同样的输入得到同样的输出，不会每次跑都变，git diff 才好读。

`check` 会打印分布，任一项超过 45% 就告警。

---

## 6. 校验规则一览（阈值都是屏幕决定的）

| 规则 | 处理 | 为什么 |
|---|---|---|
| 选项数 2–4 | 丢弃 | 界面只有四格 |
| 选项为空 / 少于 2 项 | 丢弃 | 渲染会崩 |
| 答案不在选项范围内 | 丢弃 | 点不出正确答案，孩子会一直卡住 |
| 缺题干 | 丢弃 | — |
| 题干 > 60 字 | 丢弃 | 一屏放不下 |
| 题干 > 26 字 | 告警 | 会折行，能看但不好读 |
| 选项 > 30 字 | 丢弃 | 格子是 2 行 28sp，再长就被省略号截断 |
| 选项 > 16 字 | 告警 | 折成两行 |
| 含「如图/下图/阅读短文…」 | 丢弃 | 电视上没图没上下文 |
| 科目不在白名单 | 丢弃 | 家长端按白名单筛 |
| 年级不在 1–9 | 丢弃 | — |
| 题干归一化后重复 | 丢弃 | 去掉标点空白后相同即视为同一题 |
| 某一项答案占比 > 45% | 告警 | 孩子会靠猜；跑 `rebalance` |

归一化去重能挡住「快乐的反义词是？」和「快乐的反义词是？？」，但挡不住同义改写。
真要严格判重，得人工过一遍——这也是「自编为主」比「灌别人的库」更可控的地方。

---

## 7. 转完之后怎么进到电视

**当前**：改的是 `assets/quiz/bank.json`，需要重新编译打包才生效。

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`QuestionBank` 在 `object` 里缓存了第一次解析的结果，所以**换库必须重启进程**（装新包自带重启）。

**待办**：家长端「题库管理」——从 U 盘或局域网导入 JSON，落盘到 `filesDir/quiz/bank.json`，
查找顺序变成「私有目录 → assets」，带 mtime 热加载。设计见 [ROADMAP.md](ROADMAP.md) P0 末节。
到那时本工具产出的 JSON 可以原样导入，不用再改代码重新打包。
