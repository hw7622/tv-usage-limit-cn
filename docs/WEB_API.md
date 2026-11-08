# 手机配置页接口（v2.8.0）

电视上跑着一个 `ServerSocket` 手写的小 HTTP 服务（`net/ConfigServer.kt`），
业务逻辑在 `net/ApiHandlers.kt`，网页在 `assets/web/`（`index.html` + `app.css` + `app.js`，零依赖）。

- 默认端口 **8080**，被占用时自动往后试，最多 20 个。
- 入口：电视首页 →「手机配置」，或「家长设置 → 答题门禁 → 手机配置」。
- 退出这个页面服务仍然开着（手机上的页面还在用）。想关掉，回电视点「停止」。
- **20 分钟没有任何请求会自动关掉**，不用记着去关（`ConfigServer.IDLE_STOP_MS`）。

---

## 鉴权

1. `POST /api/login`，body `{"pin":"1470"}`。密码就是电视上的**家长密码**（`ADMIN_PIN`，默认 1234）。
2. 成功后返回 `Set-Cookie: tvt=<token>; HttpOnly; SameSite=Lax`。token 只存在内存里，服务一停全部失效，单条 **12 小时**过期。
3. 之后所有 `/api/*` 请求带上这个 cookie。

- 同一个来源 IP 一分钟错 5 次，锁 60 秒（`429`）。
- **限流按 TCP 连接的真实来源地址算，不认 `X-Forwarded-For`。** 那个头由客户端随便填，
  拿它当 key 等于把限流关掉——4 位数字在局域网里几秒就能穷举完。
- 未登录访问任何 `/api/*`：`401 {"ok":false,"error":"请先输入家长密码"}`。
- 登录响应里带 `pinDefault`，还是 1234 的话前端会在首页提醒一句。

```bash
# 登录并保存 cookie
curl -c ck.txt -X POST -H "Content-Type: application/json" \
     -d '{"pin":"1470"}' http://192.168.3.191:8080/api/login

# 之后带着 cookie
curl -b ck.txt http://192.168.3.191:8080/api/overview
```

## 网络来源限制

判断规则是**白名单式**：列出来的才放行，其余一律 `403`（连页面都不返回）。

| allowRemote | 放行 |
|---|---|
| 关（默认） | 回环、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、IPv6 链路本地 `fe80::/10` |
| 开 | 上面这些 + CGNAT `100.64/10`（Tailscale）+ IPv6 ULA `fc00::/7`（Tailscale 默认在 `fd7a::/48`） |

**公网地址任何情况下都拒绝，`allowRemote` 也不放行。**

这条不是洁癖。国内宽带现在大量下发公网 IPv6，电视自己的 `2408:...` 地址是全网可达的；
再加上有人会把电视放进光猫 DMZ 或者做端口映射。如果「允许远程」的字面意思是「不再限制来源」，
那么家长为了在外面看一眼而打开这个开关，等于把 8080 端口直接挂在互联网上，
防护只剩一个 4 位数字。宁可让远程只走组网工具，也不接受这种交换。

### 另外两道兜底

- **Host 头必须是 IP 字面量**（或 `localhost`、`*.ts.net`、`*.local`）。
  挡 DNS rebinding：恶意网页把自己的域名解析到 `192.168.x.x`，浏览器会当作同源请求并带上 cookie，
  只靠来源 IP 白名单是挡不住的——请求确实来自局域网内。
- **CSP**：页面响应带 `default-src 'self'; script-src 'self'; frame-ancestors 'none'`。
  页面内容都是拼字符串渲染的，万一哪天漏了一个 `esc()`，注入的脚本也跑不起来。

---

## 页面

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 配置页（未登录时显示密码键盘） |
| GET | `/app.js`、`/app.css` | 前端资源 |
| GET | `/favicon.ico` | 204 |

页面路径是白名单，不认任意文件名（防目录穿越）。

---

## 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/login` | 换 token |
| POST | `/api/logout` | 让当前 token 失效 |
| GET | `/api/overview` | 一次拿全：设备、当前档案、额度、今日用量、暂停、作息、答题参数、题库数、错题数 |
| POST | `/api/extend` | 给**今天**加时。`{"minutes":15}`，`0` 表示撤销今天所有加时。上限 240 |
| POST | `/api/pause` | 暂停限制。`{"minutes":30}` 或 `{"until":"tonight"}`（关到今晚 24 点） |
| POST | `/api/resume` | 恢复限制 |
| GET | `/api/report?days=7` | 报表：每日用量、总/日均/最长、上周对比、答题正确率、拦截次数、错题前 10 |
| GET | `/api/profiles` | 所有档案 + 今日用量 + 是否当前 |
| POST | `/api/profile` | 改档案额度。`{"id":1,"dailyLimit":180,"sessionLimit":20,"restDuration":20}`；`-1` 表示不限 |
| POST | `/api/profile/switch` | 切换当前档案 `{"id":3}` |
| GET | `/api/schedule` | 读作息 |
| POST | `/api/schedule` | 写作息，见下 |
| GET | `/api/settings` | 读答题门禁参数 |
| POST | `/api/settings` | 写答题门禁参数（`enabled`/`interval`/`count`/`grade`/`subjects`/`showBadge`），可只传要改的 |
| GET | `/api/config` | 读整机开关 + 所有可访问地址 |
| POST | `/api/config` | 写 `allowRemote` / `showBadge` |
| POST | `/api/pin` | 改家长密码 `{"old":"1470","new":"1234"}`，新密码 4~8 位数字 |
| POST | `/api/quiz/test` | 让电视立刻弹一组题（和遥控器「试一道」同一条广播） |
| GET | `/api/bank/gaps` | 科目 × 年级题量矩阵。`threshold`=8（几乎没题）、`target`=150（撑不住一周）、`gapCount`/`thin` 分别是这两档的格子数 |
| POST | `/api/bank/import?replace=1` | 导入自定义题（JSON 或 CSV），见 [QUIZ_FORMAT.md](QUIZ_FORMAT.md) |
| GET | `/api/bank/export` | 导出自定义题（下载 JSON） |
| POST | `/api/bank/clear` | 清空自定义题（内置题库不受影响） |
| GET | `/api/mistakes` | 错题本（题干、选项、正解、错次/对次、解析） |
| POST | `/api/mistakes/clear` | 清空错题本**和**答题加权记录 |

错误统一是 `{"ok":false,"error":"中文原因"}`，配合 4xx 状态码。

### 作息格式

```json
{
  "enabled": true,
  "weekday": [[1020, 1170], [1140, 1260]],
  "weekend": [[480, 1260]]
}
```

- 时间单位是**从零点起的分钟数**，`1020` = 17:00，`1260` = 21:00，`1440` = 24:00。
- 也可以写成 `[{"start":1020,"end":1170}]`，两种都认。
- `start > end` 表示跨夜（`[1260, 420]` = 21:00 到次日 07:00）。
- 某一天不填 = 那一天不限时。`enabled=true` 但两天空着会被拒绝保存（否则电视会全天锁死）。
- 最多 3 段（界面上限，接口不限制）。

作息生效时的行为和「今天额度用完」走同一条拦截路径，但文案不同：
孩子看到的是「现在不是看电视的时间」+ 今天的可看时段，而不是「休息 20 分钟」。
家长要在这个时段看，用密码「临时关闭限制」即可。

### 加时的语义

`/api/extend` 只影响**今天**：`extra_minutes_today` 配 `extra_minutes_date`，跨天自动失效。
同一天连续加时是叠加的，但总加时封顶 240 分钟（防手滑点成「今天不设限」）。
传 `0` 会把今天的加时清零 —— 家长点错了能退回去。

---

## 远程访问

不做任何内网穿透。可选方案，按门槛从低到高：

| 方案 | 需要什么 | 适合谁 |
|---|---|---|
| **Tailscale**（推荐） | 电视和手机各装一个 App，登录同一账号 | 所有人。免费版够用，P2P 直连，不用公网 IP |
| ZeroTier | 同上，需要建一个网络 ID | 想自己掌控网络的人 |
| frp / Cloudflare Tunnel | 一台公网服务器 + 域名 | 本来就有 VPS 的人 |

Tailscale 的地址是 `100.64.0.0/10`，ZeroTier 常用 `10.x` / `172.x`（后者本来就在局域网白名单里，
所以 ZeroTier 用户不开这个开关也能连）。
打开「允许远程」后，电视「手机配置」页会把检测到的 `100.x` 地址一并显示出来。

> 电视上装 Tailscale 需要侧载 APK（Android TV 没有 Play 商店版）。装完在电视上登录一次即可，
> 之后开机自启。不想装的，就不要打开这个开关 —— 局域网模式已经是完整功能。

---

## 真机踩过的坑

1. **`QuizStats.clear()` 会清空整个 prefs 文件**。它原本遍历 `all.keys` 删除除 `cleared_at` 之外的一切，
   而这个 prefs 文件同时也是家长密码、答题参数、作息、加时的家 ——
   在手机上一点「清空错题」，PIN 回到 1234、答题门禁被关、作息和加时全部消失。
   v2.8.0 已改成只删含 `|` 的错题 key（`subject|knowledge`），并真机验证过。
2. **服务不会随开机自启**。这是有意的：一个能给电视加时、改额度的 HTTP 端口，
   不该在没人看着的时候一直开着。要用的时候从首页进一次。
3. **升级 APK 后 token 全部失效**，手机页面会跳回登录页。重新输密码即可。
4. **二维码内容不要带 `localhost` 或 `127.0.0.1`**：手机扫了打不开。
   服务会用第一个局域网网卡地址（`ConfigServer.lanIp()`），双网卡时优先 `eth0`。
5. **CSV 导入列名大小写不敏感，但列顺序不能乱**（按表头名字匹配，不是按位置）。
   最少需要 `subject,grade,stem,optionA,optionB`。
6. **限流 key 用了 `X-Forwarded-For`，等于没限流**。这个头是客户端自己填的，
   每次换一个值就能无限试 PIN；顺带还能往 `fails` 表里灌随机 key 把内存吃满。
   现在只用 `socket.inetAddress`，并且表超过 256 条会回收。**任何「按 IP 限流」都不该信这个头。**
7. **「允许远程」原来写成了「不再限制来源」**。看着方便，实际是把 8080 挂到公网上——
   国内宽带大量下发公网 IPv6，电视的 `2408:` 地址是全网可达的，光猫 DMZ 也一样。
   现在改成只在白名单里追加 CGNAT 和 ULA，公网永远 403。
8. **没有 Host 校验就挡不住 DNS rebinding**。攻击页面把自己的域名解析到内网 IP，
   浏览器认为是同源、连 cookie 一起发过去，来源 IP 白名单形同虚设。
   现在 Host 必须是 IP 字面量或受信任的组网域名。
