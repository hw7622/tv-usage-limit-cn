# 交接文档（换电脑继续开发看这一份）

最后更新：2026-11-07 · 对应版本 **v2.7.6-cn**（versionCode 44）

这份文档的目的：**在一台干净电脑上，从零把项目跑起来、装进电视、并且不踩已经踩过的坑。**
实现细节看代码注释（注释密度很高，关键决策都写在类头），这里只写「怎么用」和「别踩什么」。

---

## 1. 项目是什么

Android TV 上的儿童观看时间管理：每日额度 / 单场额度 / 强制休息 / 应用级限制，到点暂停播放、退回桌面、显示倒计时。
在 v2.6 之后新增**答题门禁**：看够一段时间弹一组题，全答对才放行（类似「通过测试才能继续看」）。

| 项 | 值 |
|---|---|
| 包名 | `com.example.tvlimit` |
| minSdk / targetSdk | 26 / 34 |
| 语言 / 构建 | Kotlin，Gradle（Kotlin DSL），视图绑定 |
| 代码规模 | 33 个 Kotlin 文件，约 7600 行 |
| 签名 | **debug 签名**（无 keystore，`assembleDebug` 直接可装） |
| 上游 | [aleung/tv-usage-limit](https://github.com/aleung/tv-usage-limit)，Apache-2.0 |
| 仓库 | https://github.com/hw7622/tv-usage-limit-cn |

---

## 2. 环境搭建（新电脑）

### 需要的东西

- JDK 17（不要用 JDK 21，AGP 版本不匹配）
- Android SDK：platform 34、build-tools 34.0.0
- 不配签名也能构建——发布物是 debug 签名，个人使用足够

### 一步步来

```bash
git clone https://github.com/hw7622/tv-usage-limit-cn.git
cd tv-usage-limit-cn

# 1) 指向 JDK 17 和 SDK（路径换成自己的）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
# Windows Git Bash 用 C:/... 形式，例如：
# export JAVA_HOME="C:/Users/you/tools/jdk-17.0.20.1+1"
# export ANDROID_HOME="C:/Users/you/tools/sdk"

# 2) 构建（依赖已缓存可加 --offline，首次别加）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 上如果 `./gradlew` 报找不到 java，就是 `JAVA_HOME` 没生效，检查是否用了 `C:\...` 反斜杠被 shell 吃掉。

首次构建约 1–3 分钟，增量 30 秒左右。

---

## 3. 装到电视上

### 3.1 普通电视

```bash
adb connect <TV_IP>:5555          # 电视先打开「开发者选项 → 网络调试」
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.tvlimit/.ui.MainActivity
```

**装完必须 `am start` 一次**：包被 force-stop 后处于 stopped 状态，`JobScheduler` 的看门狗不会自己跑，
服务不起来 = 限额全程失效，而且界面看不出异常。

### 3.2 TCL 等有侧载校验的固件

直接 `adb install` 会报 `INSTALL_FAILED_VERIFICATION_FAILURE`，日志里能看到 tag `OverseasAppConfig`。
绕过流程（在 TCL 上实测通过）：

```bash
adb push app-debug.apk /data/local/tmp/v.apk
adb shell pm disable-user --user 0 com.android.packageinstaller
adb shell pm install -r -g /data/local/tmp/v.apk     # -g 直接给全运行时权限
adb shell pm enable --user 0 com.android.packageinstaller   # 一定要恢复，否则电视装不了别的
adb shell am start -n com.example.tvlimit/.ui.MainActivity
```

装之前先备份（下一节），装完核对 `dumpsys package com.example.tvlimit | grep versionCode`。

### 3.3 装完必查三件事

```bash
# ① 版本对不对
adb shell dumpsys package com.example.tvlimit | grep -E "versionCode|versionName"

# ② 前台服务真的在跑（不是只有 ServiceRecord）
adb shell dumpsys activity services com.example.tvlimit | grep isForeground=true

# ③ 计时在动（隔 1 分钟再看，d 应该涨）
adb shell run-as com.example.tvlimit cat shared_prefs/com.example.tvlimit_preferences.xml | grep last_tick_state
# last_tick_state 里：s=本场已用 d=今日已用 r=是否受限 top=当前前台 scope=是否在计时范围
```

`startForegroundCount=0` 或没有 `isForeground=true` = 服务没真正运行，是「装完不生效」的典型症状。

---

## 4. 数据备份与回滚（动真机前必须做）

应用是 debug 签名，`run-as` 可用，直接读私有目录：

```bash
# 备份配置
adb shell run-as com.example.tvlimit cat shared_prefs/com.example.tvlimit_preferences.xml > prefs_before.xml

# 备份数据库（主库 + WAL，只拷主库会丢最近数据）
adb shell "run-as com.example.tvlimit cat databases/tv_limit_database"     > db.db
adb shell "run-as com.example.tvlimit cat databases/tv_limit_database-wal" > db.db-wal
```

注意：`adb` 的本地路径在 `MSYS_NO_PATHCONV=1` 下要用 `C:/...`，用 `/c/...` 会报 `cannot stat`。

回滚就是把上面反过来：推回 `/data/local/tmp`，`run-as` 覆盖写回 `shared_prefs` / `databases`，然后 `am force-stop` + `am start`。
**必须在 force-stop 之后写文件**，否则 SharedPreferences 内存副本会把你的改动覆盖掉。

电视上现存的旧版本回滚包放在工作区的 `tmp/backup/`（不在仓库里，因为 `*.apk` 被忽略）：
- `tvlimit-2.5.26-cn-rollback.apk`（没有答题门禁的版本）

---

## 5. 代码地图

按「我要改什么」来找：

| 想改 | 去哪里 |
|---|---|
| 计时、额度判定、拦截、前台判定 | `service/TimeTrackingService.kt`（2971 行，全项目核心，多数逻辑都在这里） |
| 答题页界面与判题 | `service/QuizGate.kt`（全屏悬浮窗，不是 Activity） |
| 答题参数（间隔/题量/年级/科目） | `quiz/QuizSettings.kt`（SharedPreferences，不进 Room） |
| 一次答题会话的队列规则 | `quiz/QuizSession.kt` |
| 出题来源 | `quiz/QuestionBank.kt`（题库）、`quiz/MathQuestionGenerator.kt`（数学现算） |
| 内置题库数据 | `app/src/main/assets/quiz/bank.json` |
| 家长自己加的题（导入的） | `quiz/BankStore.kt`（`filesDir/custom_bank.json`，同 id 覆盖内置） |
| 手机配置服务（局域网 HTTP） | `net/ConfigServer.kt`（手写 ServerSocket，无依赖） |
| 手机配置页面 | `app/src/main/assets/web/index.html`（单页，随 APK 打包，不联网加载） |
| 家长设置首页 | `ui/AdminActivity.kt` |
| 答题设置页 | `ui/QuizSettingsActivity.kt` |
| 孩子看到的剩余时间页 | `ui/MainActivity.kt` |
| 拦截页 | `ui/AppBlockedActivity.kt` |
| 运行诊断页 | `ui/DiagnosticsActivity.kt` |
| 事件日志（排查全靠它） | `util/EventLog.kt` |
| 遥控器焦点/缩放动画 | `ui/TvFocus.kt` |
| 选项弹窗（年级、题量、时长） | `ui/OptionDialog.kt` |
| 密码键盘 | `ui/PinPadDialog.kt` |

数据层很小：`data/Profile.kt`（档案）、`data/UsageLog.kt`（每日用量）、`data/LimitControl.kt`（暂停限制等开关）、`data/AppDatabase.kt`（Room，`fallbackToDestructiveMigration`）。

**数据库不要轻易加字段**：`fallbackToDestructiveMigration` 会把孩子的历史用量一起清空。
需要新参数就放 SharedPreferences（`QuizSettings` 就是这么做的，类头有说明）。

---

## 6. 已知的坑（都是真机踩出来的，别重复怀疑）

1. **TV 应用不要用 `focusableInTouchMode`**
   它在遥控器场景毫无用处，但会让触摸/鼠标下的第一次点击只 `requestFocus()`、不派发 `onClick` ——
   表现就是「每个数字要点两下」。全工程已经清掉，只有答题悬浮窗的根布局保留（那个需要）。
   代码里要聚焦用 `TvFocus.focus(view)`：先 `requestFocus()`，失败再 `requestFocusFromTouch()`。

2. **`ProcessLifecycleOwner` 只派发 `ON_STOP`**
   从自家页面直接用遥控器跳到别的应用（不经过桌面）时只走 `onPause`，`isAppInForeground` 会卡在 `true`，
   计时永久停住且无报错。靠 `startSelfHealLoop()` 每 30 秒自愈：用 `peekForegroundPackage()`（90 秒 resume 窗口）
   交叉核对，发现前台是别人就强制释放标记。

3. **前台判定不能看「最近一次 resume 事件」**
   连续播片后系统不再产生事件，会判定不到前台 → 到点不拦。现在是 **resumed 栈 + 增量事件游标**，
   栈顶即前台，且跳过系统 UI / 皮肤层（`com.tcl.walleve` 这类透明包）。

4. **拦截页必须用悬浮窗 + 先 goHome**
   电视视频走独立硬件层，压在普通 Activity 之上，只看得到声音和暂停。顺序是：先挂 `TYPE_APPLICATION_OVERLAY`，
   再 `goHome()` 释放视频层。反过来会看到「遮罩下面还在播」。

5. **国产固件 `FreezeManager` 会冻结刚启动的进程**
   表现是 `startForegroundService` 请求永不执行、`dumpsys` 里连 ServiceRecord 都没有。
   兜底是 `WakeupActivity`：开机广播、覆盖安装、看门狗发现服务没跑时，把应用短暂顶到前台再起服务。

6. **额度用完时 `isLimitReached()` 会抢先 return**
   答题门禁、拦截、休息三者的判定顺序在 `maybeQuizGate()` 和 `tick()` 里，改判定顺序前先读那段注释。
   「试一道」按钮走 `force=true` 绕过这些 return，否则家长在额度用完时点了毫无反应。

7. **关屏会被误记成「监控中断」**
   唤醒顺序是 `noteScreen(true)` → `tick()`，tick 读 prefs 时证据已被覆盖。用 `PREF_SCREEN_OFF_AT`
   判断「这段是关屏」而不是 gap。

8. **adb server 在某些环境会被回收**
   每条命令都 `adb connect` 一次，或者把 connect 和操作放进同一条命令里；跨调用依赖连接状态会随机失败。

---

## 7. 调试

```bash
adb logcat TvLimit:V *:S                                    # 只看本应用
adb logcat | grep -E "quiz_|block|selfheal|tick"            # 关键词过滤

# 结构化事件日志（排查首选，比 logcat 耐看）
adb shell run-as com.example.tvlimit tail -50 files/logs/events-$(date +%Y%m%d).jsonl

# 关键事件名
# tick / block / quiz_show / quiz_wrong / quiz_pass / quiz_reset / quiz_abandon /
# quiz_clear / selfheal / gap / svc_start / wakeup
```

`EventLog.event()` 的每个事件都带 `t`（毫秒时间戳）和 `at`（可读时间），
`block` 事件里 `ms` 是拦截耗时，`ok=false` 说明挂窗失败。

家长密码：默认 `1234`。真机目前是 `1470`（这台电视上改过，不在代码里）。

---

## 8. 题库（当前状态）

- 位置：`app/src/main/assets/quiz/bank.json`，120 道，含 `chinese/english/science/physics/chemistry/biology/history/geography/politics`
- 数学不入库：`MathQuestionGenerator` 按年级现算（进位加法、退位减法、表内乘法等），不会重复
- 出题入口：`QuizEngine.next(ctx)`，55% 概率走数学，其余从题库按年级/科目抽
- **扩充计划和开源题库选型见 [ROADMAP.md](ROADMAP.md)** —— 那是下一步最该做的事

---

## 9. 当前版本状态

已上真机验证（TCL，1920×1080 / density 240）：

- 拦截：`block how=overlay ok=true ms≈200`，1 秒退回桌面
- 答题：一组 3 道、逐题推进、答错追加、全对放行、`quiz_reset why=passed`
- 设置页五张卡一屏可见；题量选项可滚到「10 道」
- 配置迁移：vc43→44 覆盖安装后 `quiz_interval_minutes=10`、`quiz_questions_per_session=3` 自动生效，答题开关不丢

**尚未在真机上验证**：真实的「看满 10 分钟自动弹题」（当天额度已用完，计时不走）。清单见 ROADMAP 的 P1。

---

## 10. 发布流程

```bash
# 1) 改版本号：app/build.gradle.kts 的 versionCode / versionName
# 2) 构建
./gradlew assembleDebug
# 3) 重命名产物（约定）
cp app/build/outputs/apk/debug/app-debug.apk tvlimit-<versionName>.apk
# 4) 打 tag 并推
git tag v2.7.6-cn && git push origin main --tags
# 5) 传到 Releases（gh CLI 已登录时）
gh release create v2.7.6-cn tvlimit-2.7.6-cn.apk --title "v2.7.6-cn" --notes "..."
```

Releases 里只放 APK，不放 keystore（本来也没有）。
