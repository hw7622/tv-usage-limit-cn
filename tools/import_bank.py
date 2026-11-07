#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题库转换 / 体检 / 合并工具。

把任意来源的题目（CSV、TSV、JSON）转成 APP 能直接读的 bank.json，
并且负责校验、去重、编号、覆盖度统计。仓库本身不存放第三方题库数据，
只提供这个工具和映射配置——数据由使用者自己下载、自己转换。

四个子命令：

    check   体检现有题库：题量矩阵、缺口、重复、非法题
    export  题库 -> CSV（在 Excel / WPS 里改题，改完再 convert 回来）
    convert 源文件 -> bank.json 片段（字段名靠 profile 映射）
    merge   基线库 + 若干片段 -> 合并后的 bank.json

示例：

    python tools/import_bank.py check app/src/main/assets/quiz/bank.json

    python tools/import_bank.py export app/src/main/assets/quiz/bank.json -o /tmp/bank.csv

    python tools/import_bank.py convert 某题库.csv \\
        --profile tools/profiles/template.json -o /tmp/new.json

    python tools/import_bank.py merge app/src/main/assets/quiz/bank.json /tmp/new.json \\
        -o app/src/main/assets/quiz/bank.json

只用标准库，Python 3.7+ 即可，不依赖 pandas。
"""

import argparse
import csv
import datetime
import json
import os
import random
import re
import sys
import unicodedata
from collections import Counter, OrderedDict, defaultdict

# ---------------------------------------------------------------- 常量

# 与 QuizQuestion.subjectLabel() / QuizSettings.ALL_SUBJECTS 保持一致
SUBJECTS = OrderedDict([
    ("math", "数学"),
    ("chinese", "语文"),
    ("english", "英语"),
    ("science", "科学"),
    ("physics", "物理"),
    ("chemistry", "化学"),
    ("biology", "生物"),
    ("history", "历史"),
    ("geography", "地理"),
    ("politics", "道德与法治"),
])

SUBJECT_ALIASES = {
    "数学": "math", "语文": "chinese", "英语": "english", "英文": "english",
    "科学": "science", "物理": "physics", "化学": "chemistry",
    "生物": "biology", "历史": "history", "地理": "geography",
    "道德与法治": "politics", "道法": "politics", "政治": "politics",
    "品德": "politics", "思想品德": "politics",
}

_CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
           "六": 6, "七": 7, "八": 8, "九": 9}

# 屏幕约束：选项是 2x2 网格、maxLines=2、28sp，超过就会 ellipsize
MAX_OPTIONS, MIN_OPTIONS = 4, 2
MAX_STEM_LEN = 60          # 超过直接丢：电视上一屏放不下
WARN_STEM_LEN = 26         # 超过只告警：会折行，但不难看
MAX_OPTION_LEN = 30        # 选项格是 2 行、28sp，再长就 ellipsize 了
WARN_OPTION_LEN = 16       # 超过会折成两行，能显示但不好看

# 依赖图片或上下文的题在电视上没法答，直接丢
CONTEXT_HINTS = [
    "如图", "下图", "上图", "图中", "看图", "图片", "见右图", "见左图",
    "如图所示", "阅读短文", "阅读下面", "阅读材料", "根据材料", "上述材料",
    "文中", "原文", "请看图", "示意图",
]

# 解析里如果写了「选 A」「答案是 B」，重排选项后会对不上号，这类题保持原样
EXPLAIN_LETTER_RE = re.compile(r"(选|选项|答案)\s*[ABCD]")

PUNCT_RE = re.compile(
    r"[\s\u3000,.!?;:'\"`~@#$%^&*()\[\]{}<>/\\|+=-_"
    r"\u3001\u3002\uff01\uff1f\uff1b\uff1a\uff0c\u201c\u201d\u2018\u2019"
    r"\uff08\uff09\u3010\u3011\u300a\u300b\u2026\u2014\uff0d]+"
)


# ---------------------------------------------------------------- 小工具

def out(s=""):
    """Windows 控制台默认 GBK，中文可能直接抛异常。"""
    try:
        print(s)
    except UnicodeEncodeError:
        sys.stdout.write(s.encode("utf-8", "replace").decode("utf-8", "replace") + "\n")


def norm_text(s):
    """归一化：全角转半角、去掉所有空白与标点、英文小写。用于判重。"""
    if s is None:
        return ""
    s = unicodedata.normalize("NFKC", str(s))
    s = PUNCT_RE.sub("", s)
    return s.lower()


def norm_subject(raw):
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    if s in SUBJECTS:
        return s
    if s in SUBJECT_ALIASES:
        return SUBJECT_ALIASES[s]
    low = s.lower()
    if low in SUBJECTS:
        return low
    return None


def norm_grade(raw):
    """'二年级' / '2' / 'G2' / '初二' / '七年级' -> int，取不到返回 None。"""
    if raw is None:
        return None
    s = unicodedata.normalize("NFKC", str(raw)).strip()
    if not s:
        return None
    s = s.replace("年级", "").replace("Grade", "").replace("grade", "")
    s = s.replace("G", "").replace("g", "").strip()
    if re.fullmatch(r"\d+", s):
        n = int(s)
        return n if 1 <= n <= 9 else None
    if s in _CN_NUM:
        return _CN_NUM[s]
    if s.startswith("初") and len(s) == 2 and s[1] in _CN_NUM:
        return 6 + _CN_NUM[s[1]]
    return None


def resolve_answer(raw, options, base="auto"):
    """把各种写法的答案统一成 0 基下标。认不出来返回 None。"""
    if raw is None:
        return None
    s = unicodedata.normalize("NFKC", str(raw)).strip()
    if not s:
        return None

    # A / a / A. / A、 / (A)
    m = re.fullmatch(r"[（(]?\s*([A-Za-z])\s*[.、)）]?", s)
    if m:
        idx = ord(m.group(1).upper()) - ord("A")
        return idx if 0 <= idx < len(options) else None

    # 纯数字
    if re.fullmatch(r"\d+", s):
        n = int(s)
        if base == "0":
            return n if 0 <= n < len(options) else None
        if base == "1":
            return n - 1 if 1 <= n <= len(options) else None
        # auto：0 一律按 0 基；其余按 1 基
        if n == 0:
            return 0
        return n - 1 if 1 <= n <= len(options) else None

    # 直接写了选项原文
    key = norm_text(s)
    for i, opt in enumerate(options):
        if norm_text(opt) == key:
            return i
    return None


def detect_answer_base(raw_answers):
    """整列看一眼：出现过 0 就按 0 基，全是 1..N 就按 1 基。"""
    nums = []
    for a in raw_answers:
        if a is None:
            continue
        s = unicodedata.normalize("NFKC", str(a)).strip()
        if re.fullmatch(r"\d+", s):
            nums.append(int(s))
    if not nums:
        return "auto"
    return "0" if min(nums) == 0 else "1"


def has_context_dependency(stem):
    return any(h in stem for h in CONTEXT_HINTS)


# ---------------------------------------------------------------- 读文件

def read_text(path):
    for enc in ("utf-8-sig", "utf-8", "gb18030", "utf-16"):
        try:
            with open(path, "r", encoding=enc) as f:
                return f.read()
        except (UnicodeDecodeError, UnicodeError):
            continue
    raise SystemExit("读不出文件内容，编码试过 utf-8 / gb18030 / utf-16：%s" % path)


def read_records(path, records_key=None):
    """返回 list[dict]。CSV/TSV 按表头取列，JSON 取数组。"""
    lower = path.lower()
    if lower.endswith(".json"):
        data = json.loads(read_text(path))
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            if records_key and isinstance(data.get(records_key), list):
                return data[records_key]
            for k in ("questions", "data", "items", "records", "list"):
                if isinstance(data.get(k), list):
                    return data[k]
        raise SystemExit("JSON 里找不到题目数组（试过 questions/data/items/records）：%s" % path)

    text = read_text(path)
    delim = "\t" if lower.endswith((".tsv", ".txt")) else ","
    if delim == "," and text.count("\t") > text.count(","):
        delim = "\t"
    rows = list(csv.reader(text.splitlines(), delimiter=delim))
    rows = [r for r in rows if any(c.strip() for c in r)]
    if len(rows) < 2:
        raise SystemExit("表格没有数据行：%s" % path)
    header = [c.strip() for c in rows[0]]
    recs = []
    for r in rows[1:]:
        recs.append({header[i]: (r[i] if i < len(r) else "") for i in range(len(header))})
    return recs


# ---------------------------------------------------------------- 规范化单题

class Reject(Exception):
    pass


def build_question(rec, profile, answer_base="auto"):
    """把一条源记录转成标准题目 dict，不合格抛 Reject(原因)。"""
    fields = profile.get("fields", {})

    def get(*names, default=""):
        for n in names:
            if n and n in rec and str(rec[n]).strip() != "":
                return str(rec[n]).strip()
        return default

    # --- 选项
    options = []
    if isinstance(rec.get("options"), list):
        options = [str(x).strip() for x in rec["options"]]
    else:
        opt_names = fields.get("options")
        if opt_names:
            options = [get(n) for n in opt_names]
        else:
            # 默认依次找 optionA / option_a / A 这几种写法，
            # 多出来的 E、F 也读进来——好在后面按「超过 4 项」明确拒掉，
            # 而不是悄悄丢掉第五项、让答案对不上号。
            keys = []
            for letter in "ABCDEF":
                for cand in ("option" + letter, "option_" + letter.lower(), letter):
                    if cand in rec:
                        keys.append(cand)
                        break
            options = [get(k) for k in keys]

    while options and not options[-1]:
        options.pop()
    if len(options) < MIN_OPTIONS:
        raise Reject("选项不足 2 项")
    if len(options) > MAX_OPTIONS:
        raise Reject("选项超过 4 项（屏幕只有 4 格）")
    if any(not o for o in options):
        raise Reject("选项有空项")

    # --- 题干
    stem = get(fields.get("stem", "stem"), "题干", "question", "title", "text")
    if not stem:
        raise Reject("缺题干")
    if has_context_dependency(stem):
        raise Reject("依赖图片/上下文")
    if len(stem) > MAX_STEM_LEN:
        raise Reject("题干超过 %d 字" % MAX_STEM_LEN)

    # --- 答案
    raw_ans = get(fields.get("answer", "answer"), "答案", "correct", "label")
    answer = resolve_answer(raw_ans, options, answer_base)
    if answer is None:
        raise Reject("答案无法识别（原值 %r）" % raw_ans)

    # --- 科目 / 年级
    subject = norm_subject(get(fields.get("subject", "subject"), "科目", "学科"))
    if subject is None:
        raise Reject("科目不在白名单")
    raw_grade = get(fields.get("grade", "grade"), "年级", "level")
    grade = norm_grade(raw_grade)
    if grade is None:
        digits = unicodedata.normalize("NFKC", raw_grade).replace("年级", "").strip()
        raise Reject("年级超出 1-9" if re.fullmatch(r"\d+", digits) else "年级无法识别")

    # --- 可选字段
    knowledge = get(fields.get("knowledge", "knowledge"), "知识点", "考点", "tag") or "综合"
    explain = get(fields.get("explain", "explain"), "解析", "analysis", "explanation")
    difficulty = get(fields.get("difficulty", "difficulty"), "难度") or "1"
    try:
        diff = int(float(difficulty))
    except ValueError:
        diff = 1
    diff = min(3, max(1, diff))

    warnings = []
    if len(stem) > WARN_STEM_LEN:
        warnings.append("题干偏长(%d字)，会折行" % len(stem))
    longest = max((len(o) for o in options), default=0)
    if longest > MAX_OPTION_LEN:
        raise Reject("选项超过 %d 字，两行放不下" % MAX_OPTION_LEN)
    if longest > WARN_OPTION_LEN:
        warnings.append("选项会折行")

    return {
        "id": "",
        "subject": subject,
        "grade": grade,
        "knowledge": knowledge,
        "stem": stem,
        "options": options,
        "answer": answer,
        # 空解析写 ""，不能写 null：Android 的 org.json 对 JSON null
        # 调 optString 会返回字符串 "null"，界面上就真的显示 null 两个字
        "explain": explain,
        "difficulty": diff,
    }, warnings


def convert_records(records, profile, answer_base="auto"):
    """批量转换。返回 (题目列表, 丢弃统计, 告警统计)。"""
    if answer_base == "auto":
        field = profile.get("fields", {}).get("answer", "answer")
        raw = []
        for r in records:
            for k in (field, "answer", "答案"):
                if k in r:
                    raw.append(r[k])
                    break
        answer_base = detect_answer_base(raw)

    seen_exact = set()
    seen_norm = set()
    ok, rejects, warns = [], Counter(), Counter()
    for rec in records:
        try:
            q, w = build_question(rec, profile, answer_base)
        except Reject as e:
            rejects[str(e)] += 1
            continue
        key = norm_text(q["stem"])
        if q["stem"] in seen_exact:
            rejects["题干完全重复"] += 1
            continue
        if key in seen_norm:
            rejects["题干归一化后重复"] += 1
            continue
        seen_exact.add(q["stem"])
        seen_norm.add(key)
        ok.append(q)
        for x in w:
            warns[x] += 1
    return ok, rejects, warns, answer_base


def assign_ids(questions, taken=None):
    """按 subject + grade 顺序编号，避开已占用的 id。"""
    taken = set(taken or ())
    seq = defaultdict(int)
    for q in questions:
        prefix = "%s-g%d-" % (q["subject"], q["grade"])
        while True:
            qid = "%s%04d" % (prefix, seq[prefix])
            seq[prefix] += 1
            if qid not in taken:
                taken.add(qid)
                q["id"] = qid
                break
    return questions


def answer_letter(q):
    a = q.get("answer")
    return "ABCD"[a] if isinstance(a, int) and 0 <= a < 4 else "?"


def rebalance_questions(questions, seed=20261107):
    """把每道题的正确答案挪到不同位置，按 (科目, 年级) 分桶各自均分。

    原理很简单：保留正确选项的内容，只改它站哪一格，其余选项相对顺序不动。
    解析文本里不能出现「选 A」这类写法，否则重排后会对不上——
    convert 时会扫一遍，发现这种解析就整个跳过不改。
    """
    rng = random.Random(seed)
    buckets = defaultdict(list)
    for q in questions:
        buckets[(q.get("subject"), q.get("grade"))].append(q)

    before, after, skipped = Counter(), Counter(), 0
    for _, group in buckets.items():
        for q in group:
            before[answer_letter(q)] += 1
        order = list(group)
        rng.shuffle(order)
        counter = defaultdict(int)
        for q in order:
            opts = list(q.get("options") or [])
            width = len(opts)
            ans = q.get("answer")
            if width < 2 or not isinstance(ans, int) or not 0 <= ans < width:
                continue
            if q.get("explain") and EXPLAIN_LETTER_RE.search(q["explain"]):
                skipped += 1
                continue
            target = counter[width] % width
            counter[width] += 1
            if target == ans:
                continue
            correct = opts[ans]
            others = [o for i, o in enumerate(opts) if i != ans]
            q["options"] = others[:target] + [correct] + others[target:]
            q["answer"] = target
        for q in group:
            after[answer_letter(q)] += 1
    return before, after, skipped


def fmt_dist(counter):
    total = sum(counter.values()) or 1
    return "  ".join("%s %d(%d%%)" % (k, counter[k], round(counter[k] * 100.0 / total))
                     for k in "ABCD" if counter[k])


# ---------------------------------------------------------------- 子命令

def load_bank(path):
    data = json.loads(read_text(path))
    if not isinstance(data, dict) or not isinstance(data.get("questions"), list):
        raise SystemExit("不是合法题库文件（缺 questions 数组）：%s" % path)
    return data


def cmd_convert(args):
    profile = {"fields": {}}
    if args.profile:
        profile = json.loads(read_text(args.profile))
    records = read_records(args.input, args.records_key)
    if args.limit:
        records = records[:args.limit]

    questions, rejects, warns, base = convert_records(
        records, profile, args.answer_base)

    out("-" * 60)
    out("源文件 %s" % args.input)
    out("记录 %d 条，答案基准判定为 %s" % (len(records), base))
    out("通过 %d 条，丢弃 %d 条" % (len(questions), sum(rejects.values())))
    if rejects:
        out("丢弃原因：")
        for reason, n in rejects.most_common():
            out("  %-34s %d" % (reason, n))
    if warns:
        out("告警（不丢弃）：")
        for reason, n in warns.most_common():
            out("  %-34s %d" % (reason, n))

    if questions:
        dist = Counter((q["subject"], q["grade"]) for q in questions)
        out("分布：" + "，".join("%s%d年级 %d" % (SUBJECTS.get(s, s), g, n)
                               for (s, g), n in sorted(dist.items())))
    out("-" * 60)

    if args.dry_run:
        out("--dry-run，未写文件")
        return 0
    if not args.output:
        raise SystemExit("没给 -o/--output")
    if not questions:
        raise SystemExit("一条都没通过，不写空文件")

    assign_ids(questions)
    payload = OrderedDict()
    payload["version"] = 2
    payload["source"] = args.source or profile.get("name") or os.path.basename(args.input)
    payload["license"] = args.license or profile.get("license") or "UNKNOWN"
    payload["retrievedAt"] = profile.get("retrievedAt") or datetime.date.today().isoformat()
    payload["transformNote"] = "由 tools/import_bank.py convert 生成；仅题干与选项，未含原图/音频"
    payload["questions"] = questions
    with open(args.output, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    out("已写入 %s（%d 道）" % (args.output, len(questions)))
    dist = Counter(answer_letter(q) for q in questions)
    out("答案分布：%s" % fmt_dist(dist))
    if questions and max(dist.values()) * 100.0 / len(questions) > 45:
        out("  ⚠ 正确答案太集中，建议对这个文件跑一次 rebalance 再 merge")
    return 0


def cmd_merge(args):
    base = load_bank(args.base)
    existing = base["questions"]
    seen = {norm_text(q.get("stem", "")) for q in existing}
    taken = {q.get("id") for q in existing}
    added, dropped = [], Counter()

    for path in args.extras:
        part = load_bank(path)
        for q in part["questions"]:
            key = norm_text(q.get("stem", ""))
            if not key:
                dropped["空题干"] += 1
                continue
            if key in seen:
                dropped["与已有题目重复"] += 1
                continue
            seen.add(key)
            q.pop("id", None)
            added.append(q)

    assign_ids(added, taken)
    merged = list(existing) + added
    base["version"] = 2
    base["questions"] = merged
    base["mergedAt"] = args.stamp or ""

    out("基线 %d 道 + 新增 %d 道 = %d 道" % (len(existing), len(added), len(merged)))
    if dropped:
        for k, v in dropped.most_common():
            out("  跳过 %s %d" % (k, v))
    if args.dry_run:
        out("--dry-run，未写文件")
        return 0
    target = args.output or args.base
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        json.dump(base, f, ensure_ascii=False, indent=2)
        f.write("\n")
    out("已写入 %s" % target)
    return 0


def cmd_export(args):
    bank = load_bank(args.input)
    rows = bank["questions"]
    header = ["id", "subject", "grade", "knowledge", "stem",
              "optionA", "optionB", "optionC", "optionD",
              "answer", "explain", "difficulty"]
    with open(args.output, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for q in rows:
            opts = list(q.get("options") or []) + ["", "", "", ""]
            ans = q.get("answer", 0)
            letter = "ABCD"[ans] if isinstance(ans, int) and 0 <= ans < 4 else ""
            w.writerow([
                q.get("id", ""), q.get("subject", ""), q.get("grade", ""),
                q.get("knowledge", ""), q.get("stem", ""),
                opts[0], opts[1], opts[2], opts[3],
                letter, q.get("explain") or "", q.get("difficulty", 1),
            ])
    out("已导出 %d 道到 %s（utf-8-sig，Excel 直接打开）" % (len(rows), args.output))
    return 0


def cmd_rebalance(args):
    bank = load_bank(args.input)
    questions = bank["questions"]
    before, after, skipped = rebalance_questions(questions, args.seed)

    out("重排前：%s" % fmt_dist(before))
    out("重排后：%s" % fmt_dist(after))
    if skipped:
        out("跳过 %d 道（解析里写了「选 A」这类，重排会对不上）" % skipped)

    if args.dry_run:
        out("--dry-run，未写文件")
        return 0
    target = args.output or args.input
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        json.dump(bank, f, ensure_ascii=False, indent=2)
        f.write("\n")
    out("已写入 %s" % target)
    return 0


def cmd_check(args):
    bank = load_bank(args.input)
    qs = bank["questions"]
    problems = Counter()
    per_cell = Counter()
    stems_norm = defaultdict(list)
    ids = Counter()

    for i, q in enumerate(qs):
        ids[q.get("id", "")] += 1
        subj = q.get("subject")
        grade = q.get("grade")
        if subj not in SUBJECTS:
            problems["科目不在白名单"] += 1
            subj = None
        if not isinstance(grade, int) or not 1 <= grade <= 9:
            problems["年级越界"] += 1
            grade = None
        opts = q.get("options") or []
        if not MIN_OPTIONS <= len(opts) <= MAX_OPTIONS:
            problems["选项数不在 2-4"] += 1
        elif any(not str(o).strip() for o in opts):
            problems["选项有空项"] += 1
        ans = q.get("answer")
        if not isinstance(ans, int) or not 0 <= ans < max(1, len(opts)):
            problems["答案越界"] += 1
        stem = str(q.get("stem") or "").strip()
        if not stem:
            problems["缺题干"] += 1
        elif len(stem) > MAX_STEM_LEN:
            problems["题干超长"] += 1
        if stem and has_context_dependency(stem):
            problems["依赖图片/上下文"] += 1
        if q.get("explain") in (None, "null"):
            problems["解析为 null（会显示成 null 两个字）"] += 1
        if subj and grade:
            per_cell[(subj, grade)] += 1
        stems_norm[norm_text(stem)].append(i)

    dup_groups = [v for v in stems_norm.values() if len(v) > 1]
    dup_count = sum(len(v) - 1 for v in dup_groups)

    out("=" * 66)
    out("题库：%s" % args.input)
    out("版本 %s · 来源 %s · 许可 %s · 共 %d 道"
        % (bank.get("version", "?"), bank.get("source", "?"),
           bank.get("license", "?"), len(qs)))
    out("=" * 66)

    grades = list(range(1, 10))
    out("%-6s %s  %s" % ("科目", "".join("%5s" % g for g in grades), "  合计"))
    total = 0
    for key, label in SUBJECTS.items():
        counts = [per_cell.get((key, g), 0) for g in grades]
        if not any(counts):
            continue
        out("%-6s %s  %5d" % (label, "".join("%5s" % (c or "·") for c in counts), sum(counts)))
        total += sum(counts)
    out("%-6s %s  %5d" % ("合计", " " * 45, total))

    dist = Counter(answer_letter(q) for q in qs)
    out("")
    out("答案分布：%s" % fmt_dist(dist))
    peak = max(dist.values()) if dist else 0
    if len(qs) >= 20 and peak * 100.0 / len(qs) > 45:
        out("  ⚠ 答案过于集中在 %s，孩子一路按同一个键就能过 —— 跑 rebalance" %
            max(dist, key=dist.get))

    if problems or dup_count:
        out("")
        out("问题：")
        for k, v in problems.most_common():
            out("  %-24s %d" % (k, v))
        if dup_count:
            out("  %-24s %d（%d 组）" % ("重复题干", dup_count, len(dup_groups)))
    else:
        out("")
        out("结构检查：全部通过（无非法字段、无重复题干）")

    # 覆盖度：默认每 10 分钟一组、每组 3 道、日限 180 分钟 -> 18 组 = 54 道/天。
    # QuizEngine 里数学占 55% 走生成器，剩 45%（约 24 道）才从题库抽，
    # 默认两门非数学科目均分 -> 单科每天约 12 道。
    target = args.target
    per_day = args.per_day
    out("")
    out("覆盖度（目标单科单年级 ≥ %d 道，按单科每天 %d 道算能撑几天）" % (target, per_day))
    rows = []
    for (subj, grade), n in sorted(per_cell.items()):
        if n < target:
            rows.append((SUBJECTS[subj], grade, n, n // per_day))
    if not rows:
        out("  所有组合均达标")
    else:
        for label, g, n, days in rows:
            out("  %-6s %d年级  %4d 道  缺 %3d  约 %d 天转一圈"
                % (label, g, n, target - n, days))
    return 0


# ---------------------------------------------------------------- main

def main(argv=None):
    p = argparse.ArgumentParser(
        description="题库转换 / 体检 / 合并（详见 docs/QUIZ_FORMAT.md）")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("check", help="体检题库")
    c.add_argument("input")
    c.add_argument("--target", type=int, default=150, help="单科单年级目标题量")
    c.add_argument("--per-day", type=int, default=12, help="该科目每天消耗多少道，默认 12")
    c.set_defaults(func=cmd_check)

    e = sub.add_parser("export", help="题库导出为 CSV")
    e.add_argument("input")
    e.add_argument("-o", "--output", required=True)
    e.set_defaults(func=cmd_export)

    v = sub.add_parser("convert", help="源文件转成 bank.json")
    v.add_argument("input")
    v.add_argument("--profile", help="字段映射 JSON，见 tools/profiles/template.json")
    v.add_argument("--records-key", help="JSON 里题目数组所在的键名")
    v.add_argument("--answer-base", choices=["auto", "0", "1"], default="auto",
                   help="答案数字是 0 基还是 1 基，默认自动判断")
    v.add_argument("--limit", type=int, help="只转前 N 条，先小样看看")
    v.add_argument("--source", help="写进 source 字段的来源名")
    v.add_argument("--license", help="写进 license 字段，如 MIT / CC BY-NC 4.0 / 自编")
    v.add_argument("--dry-run", action="store_true")
    v.add_argument("-o", "--output")
    v.set_defaults(func=cmd_convert)

    r = sub.add_parser("rebalance", help="打散正确答案的位置")
    r.add_argument("input")
    r.add_argument("-o", "--output", help="不写则原地改")
    r.add_argument("--seed", type=int, default=20261107)
    r.add_argument("--dry-run", action="store_true")
    r.set_defaults(func=cmd_rebalance)

    m = sub.add_parser("merge", help="合并到基线题库")
    m.add_argument("base")
    m.add_argument("extras", nargs="+")
    m.add_argument("-o", "--output")
    m.add_argument("--stamp", help="写进 mergedAt 的日期，如 2026-11-07")
    m.add_argument("--dry-run", action="store_true")
    m.set_defaults(func=cmd_merge)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
