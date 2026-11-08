#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从种子数据生成内置题库（语文 / 英语），再交给 import_bank.py 校验合并。

为什么不手写上千道题：
1. 手写「字 → 拼音」上千条必然会错几条，而拼音可以用 pypinyin 算，不会错；
2. 一个知识点换几种问法（拼音 / 声调 / 声母）比重复抄同一道题更有用；
3. 加题只需要往 tools/data/*.py 里塞一行，不用重复写题干和选项。

用法：
    pip install pypinyin
    python tools/gen_bank.py            # 生成到 tmp/gen.json
    python tools/gen_bank.py --stats    # 只看会生成多少道、分布如何

第三方依赖 pypinyin 只在生成时用，APK 里没有它。
"""

import argparse
import json
import os
import random
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "data"))

import chinese_data as ZH            # noqa: E402
import english_data as EN           # noqa: E402

try:
    from pypinyin import lazy_pinyin, pinyin, Style
    from pypinyin.contrib.tone_convert import to_tone
    HAVE_PINYIN = True
except ImportError:
    HAVE_PINYIN = False

MAX_STEM = 60
MAX_OPTION = 30

TONE_NAMES = ["第一声", "第二声", "第三声", "第四声"]

# 容易混的声母：干扰项从这里挑，比随机挑「b/q/z」有意义
INITIAL_CONFUSE = {
    "b": ["p", "d", "m"], "p": ["b", "f", "t"], "m": ["n", "b", "f"],
    "f": ["h", "b", "p"], "d": ["t", "b", "g"], "t": ["d", "n", "k"],
    "n": ["l", "m", "r"], "l": ["n", "r", "d"], "g": ["k", "h", "j"],
    "k": ["g", "h", "q"], "h": ["f", "g", "k"], "j": ["q", "x", "z"],
    "q": ["j", "x", "c"], "x": ["j", "q", "s"], "zh": ["z", "ch", "j"],
    "ch": ["c", "zh", "q"], "sh": ["s", "zh", "x"], "r": ["l", "sh", "n"],
    "z": ["zh", "c", "s"], "c": ["ch", "z", "s"], "s": ["sh", "c", "z"],
    "y": ["w", "j", "x"], "w": ["y", "h", "m"],
}


# 韵母：前后鼻音、复韵母之间最容易混，干扰项就在这些里挑
FINAL_CONFUSE = {
    "in": ["ing", "en", "ian"], "ing": ["in", "eng", "iang"],
    "en": ["eng", "an", "in"], "eng": ["en", "ang", "ing"],
    "an": ["ang", "en", "ai"], "ang": ["an", "eng", "iang"],
    "ai": ["ei", "an", "ao"], "ei": ["ai", "ie", "e"],
    "ao": ["ou", "ai", "iao"], "ou": ["ao", "uo", "iu"],
    "i": ["in", "ie", "ü"], "u": ["ü", "ou", "uo"],
    "ü": ["u", "i", "üe"], "iu": ["ou", "u", "iao"],
    "ie": ["ei", "i", "üe"], "uo": ["ou", "o", "ua"],
    "ua": ["uo", "a", "uai"], "ui": ["ei", "uai", "uei"],
    "un": ["ong", "ün", "uen"], "üe": ["ie", "ü", "uo"],
    "ün": ["un", "in", "ü"], "ian": ["iang", "ie", "in"],
    "iang": ["ian", "ang", "ing"], "iao": ["ao", "iu", "ia"],
    "iong": ["ong", "ing", "iang"], "uan": ["uang", "un", "an"],
    "uang": ["uan", "ang", "iang"], "uai": ["ui", "ai", "ua"],
    "ia": ["ie", "a", "ian"], "ong": ["eng", "un", "iong"],
    "e": ["o", "ei", "a"], "o": ["uo", "e", "ou"], "a": ["ia", "ai", "an"],
}


# 拼写题里当干扰项的字母：挑「长得像/读得像」的，比随机挑有意义
LETTER_CONFUSE = {
    "a": "eosu", "b": "dpq", "c": "gkqs", "d": "bpq", "e": "aio", "f": "tvp",
    "g": "qcj", "h": "nkb", "i": "lje", "j": "igq", "k": "cgh", "l": "irjt",
    "m": "nwr", "n": "mhr", "o": "aeuc", "p": "bdq", "q": "pbd", "r": "lnv",
    "s": "zcxs", "t": "fdl", "u": "vwy", "v": "uwy", "w": "vum", "x": "cks",
    "y": "vuj", "z": "sxc",
}


def pick_wrongs(pool, bad, correct, rand):
    """挑 3 个干扰项：先挑和正确答案一样长的，不够再放宽。

    「和『创新』意思相反的是？」如果干扰项里混进「东」「关」这种单字，
    孩子看一眼字数就能排除，题就白出了。
    """
    n = len(correct)
    same_len = [w for w in pool if w not in bad and len(w) == n]
    wrongs = rand.sample(same_len, min(3, len(same_len)))
    if len(wrongs) < 3:
        rest = [w for w in pool if w not in bad and w not in wrongs]
        wrongs += rand.sample(rest, min(3 - len(wrongs), len(rest)))
    return wrongs


def clean(text):
    return re.sub(r"[\s，。、？！：；“”‘’（）(),.?!:;\"']+", "", str(text or ""))


def make(qid, subject, grade, knowledge, stem, correct, wrongs, explain=None, difficulty=1):
    """组装一道题；凑不满 4 个不重复选项就返回 None（宁缺勿错）。"""
    stem = (stem or "").strip()
    correct = (correct or "").strip()
    if not stem or not correct or len(stem) > MAX_STEM or len(correct) > MAX_OPTION:
        return None
    opts = [correct]
    for w in wrongs:
        if len(opts) >= 4:
            break
        w = (w or "").strip()
        if w and w != correct and w not in opts and len(w) <= MAX_OPTION:
            opts.append(w)
    if len(opts) < 4:
        return None
    return {
        "id": qid,
        "subject": subject,
        "grade": grade,
        "knowledge": knowledge,
        "stem": stem,
        "options": opts,
        "answer": 0,          # 生成时正确项固定第一格；最后由 rebalance 打散
        "explain": explain or "",
        "difficulty": difficulty,
    }


# ------------------------------------------------------------------ 语文

def gen_chinese(rand):
    qs = []
    seq = 0

    def add(q):
        nonlocal seq
        if q is None:
            return
        seq += 1
        q["id"] = "chinese-g%d-%04d" % (q["grade"], seq)
        qs.append(q)

    seen = set()
    for grade in sorted(ZH.CHARS):
        for ch in ZH.CHARS[grade]:
            if ch in seen:
                continue
            seen.add(ch)
            readings = pinyin(ch, heteronym=True)[0] if HAVE_PINYIN else []
            # 多音字不出拼音题：问「长」的拼音，答案可以是 cháng 也可以是 zhǎng，
            # 这种题本身就是错的。多音字另有 POLYPHONES 专门出「在这个词里读什么」。
            if len(readings) != 1:
                continue
            py3 = lazy_pinyin(ch, style=Style.TONE3)[0]
            if not py3 or not py3[-1].isdigit():
                continue
            tone = int(py3[-1])
            if tone not in (1, 2, 3, 4):
                continue
            base, correct = py3[:-1], lazy_pinyin(ch, style=Style.TONE)[0]
            if len(correct) > 8:
                continue

            add(make("", "chinese", grade, "拼音",
                     "「%s」的拼音是？" % ch, correct,
                     [to_tone(base + str(t)) for t in (1, 2, 3, 4) if t != tone],
                     explain="%s 读 %s。" % (ch, correct)))

            add(make("", "chinese", grade, "声调",
                     "「%s」读第几声？" % ch, TONE_NAMES[tone - 1],
                     [n for i, n in enumerate(TONE_NAMES) if i != tone - 1],
                     explain="%s 读 %s，%s。" % (ch, correct, TONE_NAMES[tone - 1])))

            fin3 = lazy_pinyin(ch, style=Style.FINALS_TONE3)[0]
            fin = re.sub(r"[0-9]+$", "", fin3).replace("v", "ü")

            ini = lazy_pinyin(ch, style=Style.INITIALS)[0]
            if ini and fin in FINAL_CONFUSE:
                add(make("", "chinese", grade, "韵母",
                         "「%s」的韵母是？" % ch, fin,
                         FINAL_CONFUSE[fin],
                         explain="%s 读 %s，韵母是 %s。" % (ch, correct, fin)))

            if ini and ini in INITIAL_CONFUSE:
                add(make("", "chinese", grade, "声母",
                         "「%s」的声母是？" % ch, ini,
                         INITIAL_CONFUSE[ini],
                         explain="%s 读 %s，声母是 %s。" % (ch, correct, ini)))

    # 量词
    q_pool = sorted({q for pairs in ZH.QUANTIFIERS.values() for _, q in pairs})
    for grade in sorted(ZH.QUANTIFIERS):
        for noun, right in ZH.QUANTIFIERS[grade]:
            wrongs = rand.sample([x for x in q_pool if x != right], 3)
            add(make("", "chinese", grade, "量词",
                     "一（ ）%s，括号里该填哪个字？" % noun, right, wrongs,
                     explain="%s 要用「%s」。" % (noun, right)))

    # 反义词
    all_pairs = [(g, a, b) for g in ZH.ANTONYMS for a, b in ZH.ANTONYMS[g]]
    pair_of = {}
    for g, a, b in all_pairs:
        pair_of.setdefault(a, set()).add(b)
        pair_of.setdefault(b, set()).add(a)
    word_pool = sorted(pair_of)
    for grade in sorted(ZH.ANTONYMS):
        for a, b in ZH.ANTONYMS[grade]:
            bad = pair_of.get(a, set()) | {a}
            wrongs = pick_wrongs(word_pool, bad, b, rand)
            add(make("", "chinese", grade, "反义词",
                     "和「%s」意思相反的词是？" % a, b, wrongs,
                     explain="「%s」和「%s」意思相反。" % (a, b)))

    # 近义词
    syn_pairs = [(g, a, b) for g in ZH.SYNONYMS for a, b in ZH.SYNONYMS[g]]
    syn_of = {}
    for g, a, b in syn_pairs:
        syn_of.setdefault(a, set()).add(b)
        syn_of.setdefault(b, set()).add(a)
    syn_pool = sorted(syn_of)
    for grade in sorted(ZH.SYNONYMS):
        for a, b in ZH.SYNONYMS[grade]:
            bad = syn_of.get(a, set()) | {a}
            wrongs = pick_wrongs(syn_pool, bad, b, rand)
            add(make("", "chinese", grade, "近义词",
                     "和「%s」意思相近的词是？" % a, b, wrongs,
                     explain="「%s」和「%s」意思相近。" % (a, b)))

    # 部首
    r_pool = sorted({r for pairs in ZH.RADICALS.values() for _, r in pairs})
    for grade in sorted(ZH.RADICALS):
        for ch, right in ZH.RADICALS[grade]:
            wrongs = rand.sample([x for x in r_pool if x != right], 3)
            add(make("", "chinese", grade, "部首",
                     "「%s」的部首是？" % ch, right, wrongs,
                     explain="「%s」的部首是「%s」。" % (ch, right)))

    # 成语填空
    idiom_chars = sorted({c for items in ZH.IDIOMS.values() for idiom, _ in items for c in idiom})
    for grade in sorted(ZH.IDIOMS):
        for idiom, idx in ZH.IDIOMS[grade]:
            right = idiom[idx]
            wrongs = rand.sample([c for c in idiom_chars if c != right], 3)
            stem = "「%s（ ）%s」括号里应填哪个字？" % (idiom[:idx], idiom[idx + 1:])
            add(make("", "chinese", grade, "成语",
                     stem, right, wrongs,
                     explain="成语是「%s」。" % idiom, difficulty=2))

    # 同音字填空：字形题，也是小学中段考得最多的一类
    #
    # 干扰项必须是「同音字」才对得上考点（葡 / 蒲 / 匍 / 脯）。拿随机的字当
    # 干扰项，孩子一眼就能排除，等于送分。同音字不够时退一步用同音节
    # （只差声调），仍属「读音相近」，不至于离谱。
    homophone = {}
    syllable = {}
    for ch in {c for v in ZH.CHARS.values() for c in v}:
        if not HAVE_PINYIN:
            break
        if len(pinyin(ch, heteronym=True)[0]) != 1:
            continue
        py = lazy_pinyin(ch, style=Style.TONE)[0]
        raw = lazy_pinyin(ch, style=Style.TONE3)[0]
        syl = re.sub(r"[0-9]+$", "", raw)
        if not py or not syl:
            continue
        homophone.setdefault(py, []).append(ch)
        syllable.setdefault(syl, []).append(ch)

    seen_words = set()
    for grade in sorted(ZH.WORDS):
        for word in ZH.WORDS[grade]:
            if len(word) != 2 or word in seen_words:
                continue
            # 「爷爷」「哥哥」这种叠词不能挖空：另一个字跟答案一模一样，
            # 孩子照着抄就对了，题就废了。
            if word[0] == word[1]:
                continue
            seen_words.add(word)
            for idx in range(2):
                ch = word[idx]
                if len(pinyin(ch, heteronym=True)[0]) != 1:
                    continue
                py = lazy_pinyin(ch, style=Style.TONE)[0]
                syl = re.sub(r"[0-9]+$", "",
                             lazy_pinyin(ch, style=Style.TONE3)[0])
                cands = [c for c in homophone.get(py, []) if c not in word]
                if len(cands) < 3:
                    more = [c for c in syllable.get(syl, [])
                            if c not in word and c not in cands]
                    cands += more
                if len(cands) < 3:
                    continue
                wrongs = rand.sample(cands, 3)
                masked = word[:idx] + "（）" + word[idx + 1:]
                add(make("", "chinese", grade, "同音字",
                         "词语「%s」括号里该填哪个字？" % masked, ch, wrongs,
                         explain="正确写法是「%s」。" % word, difficulty=2))

    # 多音字
    for grade in sorted(ZH.POLYPHONES):
        for word, ch, right in ZH.POLYPHONES[grade]:
            readings = pinyin(ch, heteronym=True)[0] if HAVE_PINYIN else []
            wrongs = [r for r in readings if r != right]
            base = lazy_pinyin(ch, style=Style.TONE3)[0][:-1] if HAVE_PINYIN else ""
            if base:
                wrongs += [to_tone(base + str(t)) for t in (1, 2, 3, 4)]
            add(make("", "chinese", grade, "多音字",
                     "「%s」中的「%s」读作？" % (word, ch), right, wrongs,
                     explain="「%s」里读 %s。" % (word, right), difficulty=2))

    return qs


# ------------------------------------------------------------------ 英语

def plural_of(word):
    if word in EN.IRREGULAR_PLURAL:
        return EN.IRREGULAR_PLURAL[word]
    if word.endswith(("s", "x", "z", "ch", "sh")):
        return word + "es"
    if word.endswith("y") and word[-2] not in "aeiou":
        return word[:-1] + "ies"
    return word + "s"


def gen_english(rand):
    qs = []
    seq = 0

    def add(q):
        nonlocal seq
        if q is None:
            return
        seq += 1
        q["id"] = "english-g%d-%04d" % (q["grade"], seq)
        qs.append(q)

    grades = sorted(EN.WORDS)
    # 词义：干扰项优先取同年级，不够再取相邻年级
    for gi, grade in enumerate(grades):
        others = []
        for g2 in grades:
            if g2 != grade:
                others += [m for _, m in EN.WORDS[g2]]
        for word, meaning in EN.WORDS[grade]:
            same = [m for w, m in EN.WORDS[grade] if m != meaning and w != word]
            wrongs = rand.sample(same, min(3, len(same)))
            while len(wrongs) < 3:
                cand = rand.choice(others)
                if cand != meaning and cand not in wrongs:
                    wrongs.append(cand)
            add(make("", "english", grade, "词义",
                     "「%s」是什么意思？" % word, meaning, wrongs,
                     explain="%s = %s。" % (word, meaning)))

    # 中译英：同一批词反过来问
    for grade in sorted(EN.WORDS):
        for word, meaning in EN.WORDS[grade]:
            same = [w for w, m in EN.WORDS[grade] if m != meaning and w != word]
            wrongs = rand.sample(same, min(3, len(same)))
            while len(wrongs) < 3:
                cand = rand.choice([w for w, _ in EN.WORDS[grade]])
                if cand != word and cand not in wrongs and len(cand) <= MAX_OPTION:
                    wrongs.append(cand)
            add(make("", "english", grade, "词义",
                     "「%s」用英语怎么说？" % meaning, word, wrongs,
                     explain="%s 就是 %s。" % (meaning, word)))

    # 拼写：给中文和挖掉一个字母的英文，选出缺的字母。
    # 只收全小写、长度 ≥3 的词——「English」这种首字母大写的，挖一个字母
    # 会牵出大小写两件事，题目就不干净了。
    for grade in sorted(EN.WORDS):
        for word, meaning in EN.WORDS[grade]:
            if not word.isalpha() or not word.islower() or len(word) < 3:
                continue
            idx = rand.randrange(len(word))
            right = word[idx]
            cands = [c for c in LETTER_CONFUSE.get(right, "abcdefghijklmnopqrstuvwxyz")
                     if c != right]
            if len(cands) < 3:
                continue
            wrongs = rand.sample(cands, 3)
            masked = word[:idx] + "_" + word[idx + 1:]
            add(make("", "english", grade, "拼写",
                     "「%s」拼作 %s，缺的字母是？" % (meaning, masked), right, wrongs,
                     explain="%s = %s。" % (word, meaning)))

    # 复数
    for grade in sorted(EN.NOUNS):
        for word in EN.NOUNS[grade]:
            right = plural_of(word)
            cands = [word + "s", word + "es", word + "ies", right + "s"]
            if word.endswith("y"):
                cands.append(word[:-1] + "ies")
            wrongs = [c for c in cands if c != right]
            add(make("", "english", grade, "名词复数",
                     "「%s」的复数形式是？" % word, right, wrongs,
                     explain="%s → %s。" % (word, right)))

    # 冠词：a / an 各一半，别让孩子总结出「选 an 就对了」
    VOWELS = "aeiou"
    for grade in sorted(EN.NOUNS):
        for word in EN.NOUNS[grade]:
            right = "an" if word[0].lower() in VOWELS else "a"
            add(make("", "english", grade, "冠词",
                     "「%s」该说成？" % word, "%s %s" % (right, word),
                     ["a %s" % word, "an %s" % word, word,
                      "%s %ss" % (right, word)],
                     explain="%s 前面用 %s。" % (word, right)))

    # 简单问答
    for grade in sorted(EN.DIALOGUES):
        all_answers = [a for g in EN.DIALOGUES for _, a in EN.DIALOGUES[g]]
        for question, answer in EN.DIALOGUES[grade]:
            wrongs = []
            for a in rand.sample(all_answers, min(12, len(all_answers))):
                if a != answer and a not in wrongs:
                    wrongs.append(a)
                if len(wrongs) >= 3:
                    break
            add(make("", "english", grade, "日常对话",
                     "「%s」该怎么回答？" % question, answer, wrongs,
                     explain="问 %s，答 %s。" % (question, answer), difficulty=2))

    return qs


# ------------------------------------------------------------------ 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default=os.path.join("tmp", "gen.json"))
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--stats", action="store_true", help="只打印统计，不写文件")
    args = ap.parse_args()

    if not HAVE_PINYIN:
        print("缺少 pypinyin，语文的拼音/声调/声母题会全部跳过：")
        print("    pip install pypinyin")
        return 2

    rand = random.Random(args.seed)
    zh = gen_chinese(rand)
    en = gen_english(rand)
    qs = zh + en

    # 同一套数据可能生成出重复题干，这里先去掉，免得 merge 时才发现
    seen, uniq = set(), []
    for q in qs:
        key = clean(q["stem"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(q)

    cells = Counter((q["subject"], q["grade"]) for q in uniq)
    print("生成 %d 道（去重前 %d）" % (len(uniq), len(qs)))
    for (subj, grade), n in sorted(cells.items()):
        print("  %-8s %d 年级  %4d 道" % (subj, grade, n))

    if args.stats:
        return 0

    payload = {
        "version": 2,
        "source": "自编（本项目原创，禁止从教材/教辅复制）",
        "license": "Apache-2.0",
        "retrievedAt": "2026-11-08",
        "questions": uniq,
    }
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("已写入 %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
