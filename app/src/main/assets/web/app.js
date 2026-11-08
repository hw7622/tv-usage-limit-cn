/* 电视时间管家 · 手机配置页
   单文件、零依赖。所有数据都来自电视上的那个 60KB 小服务，
   手机只是显示器 —— 关掉页面不会丢任何东西。 */
(function () {
  "use strict";

  var SUBJECT_LABEL = {
    math: "数学", chinese: "语文", english: "英语", science: "科学",
    physics: "物理", chemistry: "化学", biology: "生物",
    history: "历史", geography: "地理", politics: "道德与法治"
  };
  var SUBJECT_ORDER = ["math", "chinese", "english", "science", "physics", "chemistry", "biology", "history", "geography", "politics"];
  var GRADE_CN = ["一", "二", "三", "四", "五", "六", "七", "八", "九"];

  var $ = function (s) { return document.querySelector(s); };

  var state = {
    overview: null,
    tab: "today",
    reportDays: 7,
    schedule: null,
    pin: ""
  };

  // ===== 基础工具 =====

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function toast(msg, bad) {
    var t = $("#toast");
    t.textContent = msg;
    t.className = "toast show" + (bad ? " bad" : "");
    clearTimeout(t._h);
    t._h = setTimeout(function () { t.className = "toast"; }, 2400);
  }

  function needLogin() {
    $("#login").classList.remove("hidden");
    $("#app").classList.add("hidden");
    state.pin = "";
    renderDots();
  }

  async function api(path, opt) {
    var o = Object.assign({ credentials: "same-origin", headers: {} }, opt || {});
    var res = await fetch(path, o);
    var ct = res.headers.get("content-type") || "";
    var data = ct.indexOf("json") >= 0 ? await res.json() : await res.text();
    if (res.status === 401) { needLogin(); throw new Error("请先输入家长密码"); }
    if (!res.ok) throw new Error((data && data.error) || ("出错了 " + res.status));
    return data;
  }

  function post(path, body) {
    return api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    });
  }

  function hhmm(min) {
    var m = Math.max(0, Math.min(1440, min | 0));
    return ("0" + Math.floor(m / 60)).slice(-2) + ":" + ("0" + (m % 60)).slice(-2);
  }

  function toMin(v) {
    if (!v) return 0;
    var p = v.split(":");
    return (parseInt(p[0], 10) || 0) * 60 + (parseInt(p[1], 10) || 0);
  }

  function fmtMinutes(m) {
    if (m < 0) return "不限";
    if (m < 60) return m + " 分钟";
    var h = Math.floor(m / 60), r = m % 60;
    return h + " 小时" + (r ? " " + r + " 分钟" : "");
  }

  // ===== 登录 =====

  function renderDots() {
    var n = Math.max(4, state.pin.length);
    var html = "";
    for (var i = 0; i < n; i++) html += '<i class="' + (i < state.pin.length ? "on" : "") + '"></i>';
    $("#dots").innerHTML = html;
  }

  async function doLogin() {
    if (!state.pin) return;
    $("#loginErr").textContent = "";
    try {
      await api("/api/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin: state.pin })
      });
      $("#login").classList.add("hidden");
      $("#app").classList.remove("hidden");
      state.pin = "";
      renderDots();
      await refreshAll();
    } catch (e) {
      $("#loginErr").textContent = e.message;
      state.pin = "";
      renderDots();
    }
  }

  function bindLogin() {
    $("#keypad").addEventListener("click", function (e) {
      var b = e.target.closest("button");
      if (!b) return;
      var k = b.getAttribute("data-k");
      if (k === "del") state.pin = state.pin.slice(0, -1);
      else if (k === "ok") { doLogin(); return; }
      else if (state.pin.length < 8) state.pin += k;
      renderDots();
    });
    document.addEventListener("keydown", function (e) {
      if (!$("#login").classList.contains("hidden")) {
        if (/^[0-9]$/.test(e.key) && state.pin.length < 8) { state.pin += e.key; renderDots(); }
        else if (e.key === "Backspace") { state.pin = state.pin.slice(0, -1); renderDots(); }
        else if (e.key === "Enter") doLogin();
      }
    });
    renderDots();
  }

  // ===== 顶栏 =====

  function renderChrome() {
    var o = state.overview;
    if (!o) return;
    // 大标题固定用产品名：直接显示 tcl_mt5879_cn 这种型号，家长会以为连错了设备
    $("#devName").textContent = "电视时间管家";
    $("#devMeta").textContent =
      (o.device.model || "电视") + " · " + (o.device.ip || "未联网") + ":" + o.device.port +
      " · v" + o.device.version +
      (o.device.remoteAllowed ? " · 允许远程" : "");
  }

  // ===== 今日 =====

  function renderToday() {
    var o = state.overview, el = $("#pane-today");
    if (!o) return;
    var p = o.profile;
    if (!p) {
      el.innerHTML = '<div class="card"><div class="empty">还没有选中档案<br>先在电视上选一个再回来</div></div>';
      return;
    }
    var limit = p.dailyLimitWithExtra;
    var used = p.used;
    var remain = p.remain;
    var pct = limit > 0 ? Math.min(100, Math.round(used * 100 / limit)) : 0;
    var barCls = pct >= 100 ? "full" : pct >= 80 ? "warn" : "";
    var numCls = remain < 0 ? "ok" : (remain <= 5 ? "warn" : "ok");

    var tags = [];
    if (o.paused.on) tags.push('<span class="tag sky">限制已暂停 ' + o.paused.remain + ' 分钟</span>');
    if (o.schedule.outsideNow) tags.push('<span class="tag coral">现在不在允许时段</span>');
    if (o.extra > 0) tags.push('<span class="tag sun">今天已加时 ' + o.extra + ' 分钟</span>');
    if (p.restRemain > 0) tags.push('<span class="tag sun">本场已用 ' + p.sessionUsed + ' 分钟</span>');
    if (o.quiz.enabled) tags.push('<span class="tag mint">每 ' + o.quiz.interval + ' 分钟答 ' + o.quiz.count + ' 道</span>');
    if (o.quiz.pending) tags.push('<span class="tag coral">有题没答完</span>');

    el.innerHTML =
      '<div class="card hero">' +
        '<div><span class="num ' + numCls + '">' + (remain < 0 ? "不限" : remain) + '</span>' +
        (remain < 0 ? "" : '<span class="unit">分钟</span>') + '</div>' +
        '<div class="cap">' + esc(p.name) + ' · 今天已看 ' + used + ' 分钟' +
          (limit >= 0 ? ' / 上限 ' + limit + ' 分钟' : '') + '</div>' +
        (limit > 0 ? '<div class="bar"><i class="' + barCls + '" style="width:' + pct + '%"></i></div>' : '') +
        '<div class="tags">' + tags.join("") + '</div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">临时加时</div><div class="card-n">只加今天，明天自动归零</div></div>' +
        '<div class="btns">' +
          '<button class="btn" data-extend="15">+15 分</button>' +
          '<button class="btn" data-extend="30">+30 分</button>' +
          '<button class="btn" data-extend="60">+60 分</button>' +
        '</div>' +
        (o.extra > 0
          ? '<div class="btns" style="margin-top:8px">' +
            '<button class="btn ghost sm" data-extend="0">撤销今天的加时（' + o.extra + ' 分钟）</button></div>'
          : '') +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">家长自己看会儿</div><div class="card-n">暂停期间不计时也不拦</div></div>' +
        (o.paused.on
          ? '<div class="btns"><button class="btn primary wide" data-resume="1">恢复限制</button></div>' +
            '<div class="card-n" style="margin-top:8px;text-align:center">还剩 ' + o.paused.remain + ' 分钟自动恢复</div>'
          : '<div class="btns">' +
              '<button class="btn" data-pause="30">暂停 30 分</button>' +
              '<button class="btn" data-pause="60">暂停 1 小时</button>' +
              '<button class="btn" data-pause="tonight">关到今晚</button>' +
            '</div>') +
      '</div>' +

      '<div class="card">' +
        '<div class="row"><span class="k">单次时长</span><span class="v">' +
          (p.sessionLimit > 0 ? p.sessionLimit + " 分钟" : "不限") + '</span></div>' +
        '<div class="row"><span class="k">休息时长</span><span class="v">' + p.restDuration + ' 分钟</span></div>' +
        '<div class="row"><span class="k">错题知识点</span><span class="v">' + o.wrong.knowledge + ' 个</span></div>' +
        '<div class="row"><span class="k">题库</span><span class="v">' + o.bank.total + ' 道</span></div>' +
      '</div>' +

      '<div class="btns" style="margin-top:4px">' +
        '<button class="btn ghost" data-quiztest="1">在电视上试一组题</button>' +
      '</div>';
  }

  // ===== 用量 =====

  async function loadUsage() {
    var el = $("#pane-usage");
    el.innerHTML = '<div class="empty">读取中…</div>';
    try {
      var r = await api("/api/report?days=" + state.reportDays);
      renderUsage(r.report, r.profile);
    } catch (e) {
      el.innerHTML = '<div class="card"><div class="empty">' + esc(e.message) + '</div></div>';
    }
  }

  function renderUsage(rep, name) {
    var el = $("#pane-usage");
    var days = rep.daily || [];
    var max = 1;
    days.forEach(function (d) { if (d.minutes > max) max = d.minutes; });

    var bars = days.map(function (d) {
      var pct = Math.max(2, Math.round(d.minutes * 100 / max));
      var cls = d.minutes === 0 ? "zero" : (d.minutes >= max * 0.85 ? "hi" : "");
      var label = "";
      if (state.reportDays <= 7) {
        label = d.date.slice(8) + "日";
      } else {
        var idx = days.indexOf(d);
        if (idx % 5 === 0) label = d.date.slice(5).replace("-", "/");
      }
      return '<div class="col" title="' + d.date + ' · ' + d.minutes + ' 分钟">' +
        '<div class="bar-v ' + cls + '" style="height:' + pct + '%"></div>' +
        '<div class="lbl">' + label + '</div></div>';
    }).join("");

    var trend = rep.trend;
    var trendTxt = trend === 0 ? "和上一周期持平"
      : (trend > 0 ? "比上一周期多 " + trend + " 分钟" : "比上一周期少 " + (-trend) + " 分钟");
    var trendCls = trend > 0 ? "coral" : (trend < 0 ? "mint" : "");

    el.innerHTML =
      '<div class="card">' +
        '<div class="card-h"><div class="card-t">每天看了多久</div><div class="card-n">' + esc(name) + '</div></div>' +
        '<div class="btns" style="margin-bottom:10px">' +
          '<button class="btn sm ' + (state.reportDays === 7 ? "primary" : "") + '" data-days="7">近 7 天</button>' +
          '<button class="btn sm ' + (state.reportDays === 30 ? "primary" : "") + '" data-days="30">近 30 天</button>' +
        '</div>' +
        '<div class="chart">' + bars + '</div>' +
        '<div class="card-n" style="text-align:center">' + trendTxt + '</div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">这段时间</div><div class="card-n">' + rep.days + ' 天</div></div>' +
        '<div class="row"><span class="k">总时长</span><span class="v">' + fmtMinutes(rep.total) + '</span></div>' +
        '<div class="row"><span class="k">有看的天数</span><span class="v">' + rep.activeDays + ' 天</span></div>' +
        '<div class="row"><span class="k">看的天里平均</span><span class="v">' + fmtMinutes(rep.avg) + '</span></div>' +
        '<div class="row"><span class="k">最多的一天</span><span class="v">' + fmtMinutes(rep.maxMinutes) +
          ' <small>' + esc(rep.maxDay || "") + '</small></span></div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">答题情况</div><div class="card-n">来自电视上的答题门禁</div></div>' +
        '<div class="row"><span class="k">答对 / 答错</span><span class="v">' + rep.quiz.pass + ' / ' + rep.quiz.wrong + '</span></div>' +
        '<div class="row"><span class="k">正确率</span><span class="v">' +
          (rep.quiz.answered ? rep.quiz.accuracy + "%" : "还没有记录") + '</span></div>' +
        '<div class="row"><span class="k">错过的知识点</span><span class="v">' + rep.quiz.wrongKnowledge + ' 个</span></div>' +
        '<div class="row"><span class="k">被拦次数</span><span class="v">' + rep.blocks + ' 次</span></div>' +
      '</div>' +

      (rep.topWrong && rep.topWrong.length
        ? '<div class="card"><div class="card-h"><div class="card-t">最该补的几道</div>' +
          '<div class="card-n">按错次排序</div></div>' + rep.topWrong.slice(0, 5).map(function (w) {
            return '<div class="item"><div class="item-h"><span>' +
              esc(SUBJECT_LABEL[w.subject] || w.subject) + " · " + esc(w.knowledge) + '</span>' +
              '<span>错 ' + w.wrong + ' 次</span></div>' +
              '<div class="item-q">' + esc(w.stem) + '</div></div>';
          }).join("") + '</div>'
        : "");
  }

  // ===== 额度 =====

  async function loadLimits() {
    var el = $("#pane-limits");
    el.innerHTML = '<div class="empty">读取中…</div>';
    try {
      var r = await api("/api/profiles");
      renderLimits(r.profiles);
    } catch (e) {
      el.innerHTML = '<div class="card"><div class="empty">' + esc(e.message) + '</div></div>';
    }
  }

  function renderLimits(list) {
    var el = $("#pane-limits");
    if (!list.length) {
      el.innerHTML = '<div class="card"><div class="empty">还没有档案</div></div>';
      return;
    }
    el.innerHTML = list.map(function (p) {
      return '<div class="profile ' + (p.current ? "cur" : "") + '" data-pid="' + p.id + '">' +
        '<div class="profile-h">' +
          '<div class="profile-n">' + esc(p.name) +
            (p.current ? '<span class="badge">当前</span>' : '') + '</div>' +
          '<div class="card-n">今天 ' + p.used + ' 分钟</div>' +
        '</div>' +
        '<div class="grid3">' +
          '<div class="field"><label>每天（-1 不限）</label>' +
            '<input class="input" type="number" data-f="dailyLimit" value="' + p.dailyLimit + '"></div>' +
          '<div class="field"><label>单次（-1 不限）</label>' +
            '<input class="input" type="number" data-f="sessionLimit" value="' + p.sessionLimit + '"></div>' +
          '<div class="field"><label>休息（分钟）</label>' +
            '<input class="input" type="number" data-f="restDuration" value="' + p.restDuration + '"></div>' +
        '</div>' +
        '<div class="btns">' +
          '<button class="btn primary sm" data-save="1">保存额度</button>' +
          (p.current ? '' : '<button class="btn sm" data-switch="1">切到这个档案</button>') +
        '</div>' +
      '</div>';
    }).join("") +
      '<div class="card soft"><div class="card-n">「每天」到点后要等到第二天；「单次」到点后休息一会儿就恢复。' +
      '填 0 表示一分钟都不给。</div></div>';
  }

  // ===== 作息 =====

  function renderSchedule() {
    var el = $("#pane-schedule");
    var s = state.schedule || (state.overview && state.overview.schedule);
    if (!s) return;
    state.schedule = JSON.parse(JSON.stringify(s));

    function slots(list, key) {
      var rows = list.map(function (w, i) {
        return '<div class="slot">' +
          '<input type="time" data-key="' + key + '" data-i="' + i + '" data-p="start" value="' + hhmm(w[0]) + '">' +
          '<span class="card-n">到</span>' +
          '<input type="time" data-key="' + key + '" data-i="' + i + '" data-p="end" value="' + hhmm(w[1]) + '">' +
          '<button class="del" data-rm="' + key + '" data-i="' + i + '">×</button>' +
          '</div>';
      }).join("");
      if (!list.length) {
        rows = '<div class="card-n" style="margin-bottom:8px">还没有时段。不填就是这一天全天都能看。</div>';
      }
      var add = list.length < 3
        ? '<button class="btn sm" data-add="' + key + '">+ 加一段时间</button>'
        : '<div class="card-n">最多 3 段</div>';
      return rows + add;
    }

    el.innerHTML =
      '<div class="card">' +
        '<div class="card-h"><div class="card-t">允许看电视的时间</div>' +
          '<label class="card-n" style="display:flex;align-items:center;gap:6px">' +
          '<input type="checkbox" id="schEnabled" ' + (s.enabled ? "checked" : "") + '> 启用</label>' +
        '</div>' +
        '<div class="card-n" style="margin-bottom:10px">只在这几段时间里能看，其他时间会被挡住。' +
          '不填就是全天不限。跨夜可以填 21:00 到 07:00。</div>' +
        '<div class="card-t" style="margin:12px 0 8px">周一到周五</div>' +
        slots(s.weekday, "weekday") +
        '<div class="card-t" style="margin:16px 0 8px">周六、周日</div>' +
        slots(s.weekend, "weekend") +
        '<div class="btns" style="margin-top:14px"><button class="btn primary wide" data-schsave="1">保存作息</button></div>' +
        '<div class="card-n" style="margin-top:10px">' + esc(s.todayLabel || "") + '</div>' +
      '</div>';
  }

  function scheduleChanged() {
    var s = state.schedule;
    if (!s) return;
    s.enabled = $("#schEnabled").checked;
    s.weekday = s.weekday || [];
    s.weekend = s.weekend || [];
    document.querySelectorAll('input[type="time"][data-key]').forEach(function (inp) {
      var key = inp.getAttribute("data-key");
      var i = parseInt(inp.getAttribute("data-i"), 10);
      var p = inp.getAttribute("data-p");
      if (s[key] && s[key][i]) s[key][i][p === "start" ? 0 : 1] = toMin(inp.value);
    });
  }

  async function saveSchedule() {
    scheduleChanged();
    try {
      var r = await post("/api/schedule", {
        enabled: state.schedule.enabled,
        weekday: state.schedule.weekday,
        weekend: state.schedule.weekend
      });
      state.schedule = r;
      state.overview.schedule = r;
      renderSchedule();
      toast("作息已保存");
    } catch (e) {
      toast(e.message, true);
    }
  }

  // ===== 题库 =====

  async function loadBank() {
    var el = $("#pane-bank");
    el.innerHTML = '<div class="empty">读取中…</div>';
    try {
      var gaps = await api("/api/bank/gaps");
      var b = state.overview.bank;
      var head = "<tr><th>科目</th>" + GRADE_CN.map(function (g) { return "<th>" + g + "</th>"; }).join("") + "</tr>";
      var rows = gaps.subjects.map(function (s) {
        var cells = s.counts.map(function (n, i) {
          var grade = i + 1;
          var na = grade < s.from || grade > s.to;
          var cls = na ? "na" : (n === 0 ? "zero" : (n < gaps.threshold ? "low" : "ok"));
          return '<td class="' + cls + '">' + (na ? "·" : n) + "</td>";
        }).join("");
        return "<tr><td class='s'>" + esc(SUBJECT_LABEL[s.subject] || s.subject) + "</td>" + cells + "</tr>";
      }).join("");

      el.innerHTML =
        '<div class="card">' +
          '<div class="card-h"><div class="card-t">题库</div><div class="card-n">数学由程序现出，不占题库</div></div>' +
          '<div class="row"><span class="k">总题数</span><span class="v">' + b.total + ' 道</span></div>' +
          '<div class="row"><span class="k">内置 / 你加的</span><span class="v">' + b.builtin + ' / ' + b.custom + '</span></div>' +
          '<div class="row"><span class="k">缺口</span><span class="v">' + gaps.gapCount + ' 处低于 ' + gaps.threshold + ' 道</span></div>' +
        '</div>' +

        '<div class="card">' +
          '<div class="card-h"><div class="card-t">哪一块题少</div><div class="card-n">红=0 道，黄=不足</div></div>' +
          '<div style="overflow-x:auto"><table class="matrix">' + head + rows + '</table></div>' +
        '</div>' +

        '<div class="card">' +
          '<div class="card-h"><div class="card-t">导入自己的题</div><div class="card-n">JSON 或 CSV</div></div>' +
          '<div class="field"><label>选文件（.json / .csv）</label>' +
            '<input class="input" type="file" id="bankFile" accept=".json,.csv,text/plain"></div>' +
          '<div class="field"><label>或者直接粘贴</label>' +
            '<textarea class="input" id="bankPaste" placeholder=\'[{"subject":"chinese","grade":2,"knowledge":"多音字","stem":"「长大」的「长」读什么？","options":["cháng","zhǎng"],"answer":1}]\'></textarea></div>' +
          '<label class="card-n" style="display:flex;gap:8px;align-items:center;margin-bottom:10px">' +
            '<input type="checkbox" id="bankReplace"> 同名 id 直接覆盖（不给也能覆盖内置题）</label>' +
          '<div class="btns"><button class="btn primary wide" data-import="1">导入</button></div>' +
          '<div class="card-n" id="bankMsg" style="margin-top:10px"></div>' +
          '<div class="card-n" id="bankErrs" style="color:var(--coral);margin-top:6px"></div>' +
        '</div>' +

        '<div class="card">' +
          '<div class="card-h"><div class="card-t">管理</div><div class="card-n">只影响你加的题</div></div>' +
          '<div class="btns">' +
            '<button class="btn" data-export="1">导出我的题</button>' +
            '<button class="btn warn" data-bankclear="1">清空我的题</button>' +
          '</div>' +
        '</div>';
    } catch (e) {
      el.innerHTML = '<div class="card"><div class="empty">' + esc(e.message) + '</div></div>';
    }
  }

  // ===== 错题 =====

  async function loadMistakes() {
    var el = $("#pane-mistakes");
    el.innerHTML = '<div class="empty">读取中…</div>';
    try {
      var r = await api("/api/mistakes");
      if (!r.items.length) {
        el.innerHTML = '<div class="card"><div class="empty">还没有错题<br>孩子答错时这里会自动记下来</div></div>';
        return;
      }
      el.innerHTML =
        '<div class="card">' +
          '<div class="card-h"><div class="card-t">错题本</div>' +
            '<div class="card-n">' + r.count + ' 个知识点</div></div>' +
          r.items.map(function (w) {
            var opts = (w.options || []).map(function (o, i) {
              var mark = i === w.answer ? "✓ " : "";
              return esc(mark + String.fromCharCode(65 + i) + ". " + o);
            }).join("　");
            return '<div class="item">' +
              '<div class="item-h"><span>' + esc(SUBJECT_LABEL[w.subject] || w.subject) +
                " · " + esc(w.knowledge) + " · " + (GRADE_CN[w.grade - 1] || w.grade) + '年级</span>' +
                '<span>错 ' + w.wrong + ' 次' + (w.right ? " / 对 " + w.right + " 次" : "") + '</span></div>' +
              '<div class="item-q">' + esc(w.stem) + '</div>' +
              '<div class="item-a">' + opts + (w.explain ? '<span class="explain">' + esc(w.explain) + '</span>' : '') + '</div>' +
            '</div>';
          }).join("") +
        '</div>' +
        '<div class="btns"><button class="btn warn wide" data-mclear="1">清空错题记录</button></div>';
    } catch (e) {
      el.innerHTML = '<div class="card"><div class="empty">' + esc(e.message) + '</div></div>';
    }
  }

  // ===== 设置 =====

  function renderSettings() {
    var o = state.overview, el = $("#pane-settings");
    var q = o.quiz;
    var intervals = [5, 10, 15, 20, 30, 45, 60];
    var counts = [1, 2, 3, 4, 5, 6, 8, 10];

    function opts(list, cur, unit) {
      return list.map(function (v) {
        return '<option value="' + v + '"' + (v === cur ? " selected" : "") + ">" + v + " " + unit + "</option>";
      }).join("");
    }

    el.innerHTML =
      '<div class="card">' +
        '<div class="card-h"><div class="card-t">答题门禁</div>' +
          '<label class="card-n" style="display:flex;gap:6px;align-items:center">' +
          '<input type="checkbox" id="quizEnabled" ' + (q.enabled ? "checked" : "") + '> 启用</label></div>' +
        '<div class="grid2">' +
          '<div class="field"><label>每隔多久</label><select class="input" id="quizInterval">' +
            opts(intervals, q.interval, "分钟") + '</select></div>' +
          '<div class="field"><label>一次几道</label><select class="input" id="quizCount">' +
            opts(counts, q.count, "道") + '</select></div>' +
        '</div>' +
        '<div class="field"><label>按哪个年级出题</label><select class="input" id="quizGrade">' +
          GRADE_CN.map(function (g, i) {
            return '<option value="' + (i + 1) + '"' + (i + 1 === q.grade ? " selected" : "") + ">" + g + "年级</option>";
          }).join("") + '</select></div>' +
        '<div class="field"><label>出题科目</label><div class="btns" id="quizSubjects">' +
          SUBJECT_ORDER.map(function (k) {
            var on = q.subjects.indexOf(k) >= 0;
            return '<button class="btn sm ' + (on ? "primary" : "") + '" data-sub="' + k + '">' +
              SUBJECT_LABEL[k] + '</button>';
          }).join("") + '</div></div>' +
        '<div class="btns"><button class="btn primary wide" data-qsave="1">保存答题设置</button></div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">显示</div><div class="card-n">电视右下角的小条</div></div>' +
        '<div class="row"><span class="k">显示剩余时间角标</span>' +
          '<label><input type="checkbox" id="showBadge" ' + (o.showBadge ? "checked" : "") + '></label></div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">在外面也能配置</div>' +
          '<div class="card-n">不改路由器、不买服务器</div></div>' +
        '<div class="row"><span class="k">允许非局域网访问</span>' +
          '<label><input type="checkbox" id="allowRemote" ' + (o.device.remoteAllowed ? "checked" : "") + '></label></div>' +
        '<div class="card-n" style="line-height:1.7;margin-top:8px">' +
          '默认只能用家里同一个 WiFi 打开。想在外面看，在电视和手机上都装 Tailscale（或 ZeroTier），' +
          '登录同一个账号，然后打开这个开关，用下面那个 100.x 开头的地址访问。<br>' +
          '不需要公网 IP，也不需要把电视端口映射到外网。' +
        '</div>' +
        '<div class="card-n" id="endpoints" style="margin-top:8px"></div>' +
      '</div>' +

      '<div class="card">' +
        '<div class="card-h"><div class="card-t">改家长密码</div><div class="card-n">电视和网页共用</div></div>' +
        '<div class="field"><label>现在的密码</label><input class="input" type="password" inputmode="numeric" id="pinOld" maxlength="8"></div>' +
        '<div class="field"><label>新密码（4~8 位数字）</label><input class="input" type="password" inputmode="numeric" id="pinNew" maxlength="8"></div>' +
        '<div class="btns"><button class="btn wide" data-pinsave="1">修改密码</button></div>' +
      '</div>' +

      '<div class="card soft">' +
        '<div class="row"><span class="k">设备</span><span class="v">' + esc(o.device.model) + '</span></div>' +
        '<div class="row"><span class="k">版本</span><span class="v">v' + esc(o.device.version) + '</span></div>' +
        '<div class="row"><span class="k">Android</span><span class="v">' + esc(o.device.android) + '</span></div>' +
        '<div class="row"><span class="k">地址</span><span class="v">' + esc(o.device.ip) + ":" + o.device.port + '</span></div>' +
      '</div>';

    // 地址列表单独拉一次（可能刚打开远程开关）
    api("/api/config").then(function (c) {
      $("#endpoints").innerHTML = (c.endpoints || []).map(function (e) {
        return '<div style="margin:2px 0">' + (e.remote ? "远程 · " : "局域网 · ") + esc(e.url) + '</div>';
      }).join("");
    }).catch(function () {});
  }

  async function saveQuizSettings() {
    var subjects = [];
    document.querySelectorAll("#quizSubjects [data-sub]").forEach(function (b) {
      if (b.classList.contains("primary")) subjects.push(b.getAttribute("data-sub"));
    });
    if (!subjects.length) { toast("至少选一科", true); return; }
    try {
      await post("/api/settings", {
        enabled: $("#quizEnabled").checked,
        interval: parseInt($("#quizInterval").value, 10),
        count: parseInt($("#quizCount").value, 10),
        grade: parseInt($("#quizGrade").value, 10),
        subjects: subjects,
        showBadge: $("#showBadge") ? $("#showBadge").checked : undefined
      });
      await refreshOverview();
      toast("已保存");
    } catch (e) {
      toast(e.message, true);
    }
  }

  // ===== CSV =====

  function parseCsv(text) {
    var rows = [], row = [], cell = "", q = false;
    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (q) {
        if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; }
        else if (c === '"') q = false;
        else cell += c;
      } else if (c === '"') q = true;
      else if (c === ",") { row.push(cell); cell = ""; }
      else if (c === "\n") { row.push(cell); rows.push(row); row = []; cell = ""; }
      else if (c !== "\r") cell += c;
    }
    if (cell.length || row.length) { row.push(cell); rows.push(row); }
    return rows.filter(function (r) { return r.some(function (x) { return x.trim() !== ""; }); });
  }

  function csvToQuestions(text) {
    var rows = parseCsv(text);
    if (rows.length < 2) throw new Error("CSV 至少要有表头和一行题");
    var head = rows[0].map(function (h) { return h.trim(); });
    function idx(name) {
      for (var i = 0; i < head.length; i++) {
        if (head[i].toLowerCase() === name.toLowerCase()) return i;
      }
      return -1;
    }
    ["subject", "grade", "stem", "optionA", "optionB"].forEach(function (r) {
      if (idx(r) < 0) throw new Error("CSV 缺列：" + r);
    });
    var out = [];
    for (var i = 1; i < rows.length; i++) {
      var r = rows[i];
      function get(n) { var k = idx(n); return k >= 0 ? (r[k] || "").trim() : ""; }
      var options = ["optionA", "optionB", "optionC", "optionD"].map(get).filter(function (x) { return x !== ""; });
      if (!options.length) continue;
      var ansRaw = get("answer").toUpperCase();
      var answer = 0;
      if (/^[A-D]$/.test(ansRaw)) answer = ansRaw.charCodeAt(0) - 65;
      else if (ansRaw !== "" && !isNaN(+ansRaw)) {
        answer = (+ansRaw >= 1 && +ansRaw <= options.length) ? +ansRaw - 1 : +ansRaw;
      } else {
        var hit = options.indexOf(get("answer"));
        if (hit >= 0) answer = hit;
      }
      out.push({
        id: get("id"),
        subject: get("subject"),
        grade: +get("grade") || 2,
        knowledge: get("knowledge"),
        stem: get("stem"),
        options: options,
        answer: answer,
        explain: get("explain"),
        difficulty: +get("difficulty") || 1
      });
    }
    return out;
  }

  async function doImport() {
    var text = ($("#bankPaste").value || "").trim();
    var file = $("#bankFile").files[0];
    if (!text && file) text = await file.text();
    if (!text) { toast("先选文件或粘贴内容", true); return; }
    if (!/^[\[{]/.test(text.slice(0, 8).trim())) {
      try {
        text = JSON.stringify({ questions: csvToQuestions(text) });
      } catch (e) {
        $("#bankMsg").textContent = e.message;
        return;
      }
    }
    $("#bankMsg").textContent = "导入中…";
    $("#bankErrs").innerHTML = "";
    try {
      var replace = $("#bankReplace").checked ? "?replace=1" : "";
      var r = await api("/api/bank/import" + replace, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: text
      });
      var ret = r.result;
      $("#bankMsg").textContent = "新增 " + ret.added + " 道 · 覆盖 " + ret.updated +
        " 道 · 拒绝 " + ret.rejected + " 道 · 现在共有 " + r.total + " 道";
      $("#bankErrs").innerHTML = (ret.errors || []).map(function (e) { return esc(e); }).join("<br>");
      $("#bankPaste").value = "";
      $("#bankFile").value = "";
      await refreshOverview();
      toast("导入完成");
    } catch (e) {
      $("#bankMsg").textContent = e.message;
      toast("导入失败", true);
    }
  }

  // ===== 数据刷新与页签 =====

  async function refreshOverview() {
    state.overview = await api("/api/overview");
    renderChrome();
    if (state.tab === "today") renderToday();
    if (state.tab === "schedule" && state.overview.schedule) {
      state.schedule = state.overview.schedule;
      renderSchedule();
    }
    if (state.tab === "bank") loadBank();
  }

  async function refreshAll() {
    try {
      await refreshOverview();
    } catch (e) {
      toast(e.message, true);
      return;
    }
    switchTab(state.tab || "today");
  }

  function switchTab(name) {
    state.tab = name;
    document.querySelectorAll(".tab").forEach(function (t) {
      t.classList.toggle("active", t.getAttribute("data-tab") === name);
    });
    document.querySelectorAll(".pane").forEach(function (p) {
      p.classList.toggle("active", p.id === "pane-" + name);
    });
    if (name === "today") renderToday();
    else if (name === "usage") loadUsage();
    else if (name === "limits") loadLimits();
    else if (name === "schedule") renderSchedule();
    else if (name === "bank") loadBank();
    else if (name === "mistakes") loadMistakes();
    else if (name === "settings") renderSettings();
  }

  // ===== 事件 =====

  function bindApp() {
    $("#tabs").addEventListener("click", function (e) {
      var t = e.target.closest(".tab");
      if (t) switchTab(t.getAttribute("data-tab"));
    });

    $("#logoutBtn").addEventListener("click", async function () {
      try { await post("/api/logout"); } catch (e) {}
      needLogin();
    });

    document.addEventListener("click", async function (e) {
      var t = e.target;

      // 加时
      var ex = t.closest("[data-extend]");
      if (ex) {
        var mins = parseInt(ex.getAttribute("data-extend"), 10);
        try {
          var r = await post("/api/extend", { minutes: mins });
          await refreshOverview();
          toast(mins === 0 ? "已撤销今天的加时" : "已加时，今天上限 " + r.limit + " 分钟");
        } catch (err) { toast(err.message, true); }
        return;
      }

      // 暂停
      var pz = t.closest("[data-pause]");
      if (pz) {
        var v = pz.getAttribute("data-pause");
        try {
          await post("/api/pause", v === "tonight" ? { until: "tonight" } : { minutes: parseInt(v, 10) });
          await refreshOverview();
          toast(v === "tonight" ? "已关到今晚" : "已暂停" + v + " 分钟");
        } catch (err) { toast(err.message, true); }
        return;
      }

      if (t.closest("[data-resume]")) {
        try {
          await post("/api/resume");
          await refreshOverview();
          toast("限制已恢复");
        } catch (err) { toast(err.message, true); }
        return;
      }

      if (t.closest("[data-quiztest]")) {
        try {
          await post("/api/quiz/test");
          toast("电视上马上会弹出一组题");
        } catch (err) { toast(err.message, true); }
        return;
      }

      // 用量范围
      var d = t.closest("[data-days]");
      if (d) { state.reportDays = parseInt(d.getAttribute("data-days"), 10); loadUsage(); return; }

      // 额度
      var saveBtn = t.closest("[data-save]");
      if (saveBtn) {
        var card = saveBtn.closest("[data-pid]");
        var body = { id: parseInt(card.getAttribute("data-pid"), 10) };
        card.querySelectorAll("[data-f]").forEach(function (inp) {
          body[inp.getAttribute("data-f")] = parseInt(inp.value, 10);
        });
        try {
          await post("/api/profile", body);
          await loadLimits();
          await refreshOverview();
          toast("额度已保存");
        } catch (err) { toast(err.message, true); }
        return;
      }

      var sw = t.closest("[data-switch]");
      if (sw) {
        var card2 = sw.closest("[data-pid]");
        try {
          var rr = await post("/api/profile/switch", { id: parseInt(card2.getAttribute("data-pid"), 10) });
          await loadLimits();
          await refreshOverview();
          toast("已切到「" + rr.name + "」");
        } catch (err) { toast(err.message, true); }
        return;
      }

      // 作息
      var add = t.closest("[data-add]");
      if (add) {
        scheduleChanged();
        var key = add.getAttribute("data-add");
        state.schedule[key].push([17 * 60, 20 * 60]);
        renderSchedule();
        return;
      }
      var rm = t.closest("[data-rm]");
      if (rm) {
        scheduleChanged();
        state.schedule[rm.getAttribute("data-rm")].splice(parseInt(rm.getAttribute("data-i"), 10), 1);
        renderSchedule();
        return;
      }
      if (t.closest("[data-schsave]")) { saveSchedule(); return; }

      // 题库
      if (t.closest("[data-import]")) { doImport(); return; }
      if (t.closest("[data-export]")) {
        window.location.href = "/api/bank/export";
        return;
      }
      if (t.closest("[data-bankclear]")) {
        if (!confirm("清空你导入的所有题？内置题不受影响。")) return;
        try {
          var cc = await api("/api/bank/clear", { method: "POST" });
          await refreshOverview();
          loadBank();
          toast("已清空，现有 " + cc.total + " 道");
        } catch (err) { toast(err.message, true); }
        return;
      }

      // 错题
      if (t.closest("[data-mclear]")) {
        if (!confirm("清空错题记录？这不会影响孩子的观看时长。")) return;
        try {
          await post("/api/mistakes/clear");
          loadMistakes();
          toast("错题已清空");
        } catch (err) { toast(err.message, true); }
        return;
      }

      // 设置
      var sub = t.closest("[data-sub]");
      if (sub) {
        var on = sub.classList.contains("primary");
        var total = document.querySelectorAll("#quizSubjects .primary").length;
        if (on && total <= 1) { toast("至少留一科", true); return; }
        sub.classList.toggle("primary", !on);
        return;
      }
      if (t.closest("[data-qsave]")) { saveQuizSettings(); return; }

      if (t.closest("[data-pinsave]")) {
        var oldPin = $("#pinOld").value.trim();
        var newPin = $("#pinNew").value.trim();
        try {
          await post("/api/pin", { old: oldPin, new: newPin });
          $("#pinOld").value = "";
          $("#pinNew").value = "";
          toast("密码已修改");
        } catch (err) { toast(err.message, true); }
        return;
      }
    });

    document.addEventListener("change", async function (e) {
      var t = e.target;
      if (t.id === "showBadge") {
        try {
          await post("/api/config", { showBadge: t.checked });
          toast(t.checked ? "角标已打开" : "角标已关闭");
        } catch (err) { toast(err.message, true); }
        return;
      }
      if (t.id === "allowRemote") {
        try {
          var c = await post("/api/config", { allowRemote: t.checked });
          state.overview.device.remoteAllowed = c.allowRemote;
          renderChrome();
          renderSettings();
          toast(c.allowRemote ? "已允许远程访问" : "已限制为局域网");
        } catch (err) {
          t.checked = !t.checked;
          toast(err.message, true);
        }
        return;
      }
      if (t.id === "schEnabled") {
        scheduleChanged();
        return;
      }
    });
  }

  // ===== 启动 =====

  bindLogin();
  bindApp();
  api("/api/overview").then(function (o) {
    state.overview = o;
    $("#login").classList.add("hidden");
    $("#app").classList.remove("hidden");
    renderChrome();
    switchTab("today");
  }).catch(function () {
    // 401 会由 api() 自动切回登录页
  });
})();
