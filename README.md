# tv-usage-limit-cn

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20TV%208.0%2B-green.svg)](#环境要求)
[![Upstream](https://img.shields.io/badge/upstream-aleung%2Ftv--usage--limit-lightgrey.svg)](https://github.com/aleung/tv-usage-limit)

Android TV / 电视盒子上的**儿童观看时间管理**：家长设定每日额度、单场额度和强制休息时长，到点后自动暂停播放、退回桌面并显示剩余休息时间的倒计时。

本项目是 [aleung/tv-usage-limit](https://github.com/aleung/tv-usage-limit) 的中文改造版，界面全中文，并修复了原版在国产电视（TCL 等）上「到点不拦」「装完不生效」等问题。

![拦截页：还要休息 17 分 59 秒](docs/images/03-blocked-countdown.png)

---

## 功能

- **双档案**：儿童（受限）/ 家长（不限），切换到家长档案需要密码
- **每日额度**：一天累计上限，用完当天不再放行
- **单场额度**：一次连续观看的上限，到点强制休息
- **应用级限制**：只限制勾选的应用，其余应用照常使用；不勾选则为整机限时
- **剩余时间角标**：悬浮在桌面右上角的「还剩 N 分」，可关闭
- **全屏拦截页**：暂停播放、退回桌面，显示「还 要 休 息 X 分 XX 秒」并逐秒递减
- **家长入口**：拦截页上的「我是家长」可直接输密码切换档案
- **遥控器可操作**：所有输入（含密码）都支持方向键 + 确认键，不依赖软键盘

<table>
<tr>
<td><img src="docs/images/01-desktop.png" alt="桌面右上角剩余时间角标"></td>
<td><img src="docs/images/02-screensaver.png" alt="屏保状态同样受管"></td>
</tr>
<tr>
<td align="center">桌面右上角：还剩 18 分</td>
<td align="center">屏保状态也在计时</td>
</tr>
</table>

---

## 环境要求

- Android 8.0（API 26）及以上，Android TV / Google TV / 电视盒子
- 「使用情况访问」权限（必需）
- 「悬浮窗 / 显示在其他应用上层」权限（必需）
- 「设备管理」权限（可选，仅用于到点自动关屏）

---

## 安装

### 方式一：下载 APK

到 [Releases](../../releases) 下载最新的 `tvlimit-*.apk`。把 APK 拷到 U 盘，插到电视，用电视自带的文件管理器安装。

> 发布物为 **debug 签名**，个人家庭使用足够；如需分发请自行用正式证书重签。

### 方式二：ADB

```bash
adb connect <TV_IP>:5555     # 先在电视「开发者选项」里打开网络调试
adb install -r tvlimit-2.5.26-cn.apk
```

部分国产电视固件会拒绝 `adb install`（`INSTALL_FAILED_VERIFICATION_FAILURE`），此时改用电视自带的「多屏互动 / 应用推送」，或直接用 U 盘安装。

**如果已装过上游原版，请先卸载** —— 包名相同（`com.example.tvlimit`）但签名不同：

```bash
adb uninstall com.example.tvlimit
```

### 方式三：从源码构建

需要 JDK 17 与 Android SDK（platform 34 / build-tools 34.0.0）：

```bash
git clone https://github.com/hw7622/tv-usage-limit-cn.git
cd tv-usage-limit-cn
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 首次启动

装完直接打开不会生效，必须先授权，缺任何一项都会「看起来开了、其实没人管」：

1. **使用情况访问权限**（必需）
   设置 → 应用 → 特殊权限 → 使用情况访问 → 允许本应用。
   没有它无法判断当前前台应用，应用级限制与到点拦截都不会工作。
2. **悬浮窗权限**（必需）
   用于显示剩余时间角标与全屏拦截页。
3. **设备管理权限**（可选）
   授权后到点自动关屏。国产电视会周期性撤销该权限，撤销后仅不关屏，拦截照常。

建议同时把本应用加入系统的「电池优化白名单 / 自启动白名单」，否则计时服务可能被系统回收。

默认家长 PIN 为 `1234`，首次进入家长设置后请立即修改。

---

## 配置说明

家长设置中可以编辑每个档案的三项参数：

| 参数 | 说明 |
|---|---|
| 每日上限 | 一天累计可观看时长，用完当天锁定 |
| 单场上限 | 一次连续可观看时长，到点进入休息 |
| 休息时长 | 单场到点后必须休息的时长 |

「限制应用」页决定计时范围：

- 勾选若干应用 → 只有这些应用受限制，且**共享**同一份额度
- 一个都不勾选 → 整机限时，屏幕在使用即计时

切换口径时今日用量的处理规则：

| 切换方向 | 今日已用时长 |
|---|---|
| 整机 → 仅限应用 | 清零重新计算（两种口径不可换算） |
| 仅限应用 → 整机 | 沿用，不清零 |
| 应用列表增删 | 沿用，不清零 |

---

## 相对上游的改动

### 界面中文化

- 全部布局、字符串、Toast、动态文案改为中文；档案名 `Child/Parent` → `儿童/家长`
- 新增遥控器可操作的九宫格密码控件（`PinPadDialog`），替换三处系统输入框
- 移除主界面上的默认 PIN 提示

### 到点不拦（两层原因）

| 问题 | 现象 | 修法 |
|---|---|---|
| 前台判定依赖「最近 60 秒内时间戳最大的 resume 事件」 | 刚打开能拦；连续播片后系统不再产生事件 → 判定不到前台 → 到点不拦 | 改为 **resumed 栈** + 增量事件游标，栈顶即前台 |
| TCL 皮肤层 `com.tcl.walleve` 随视频一起 resume，时间戳更新 | 前台被判定成皮肤层 → 不在受限集合 → 不拦 | 系统 UI / 皮肤层按**透明包**跳过 |

### 拦截页被视频画面遮挡

现象是到点只暂停了播放，遮罩不出现，按一下遥控器才出来。原因是电视播放时视频走独立硬件层，压在普通应用窗口之上。

改为以 `TYPE_APPLICATION_OVERLAY` 全屏悬浮窗显示，并且挂载后主动退回桌面释放视频层。配套细节：

- 最短显示 8 秒，避免被系统拉回上一个应用时提示只闪一下
- 1 秒心跳自愈：窗口被系统摘除时自动补回，额度恢复时自动撤销，焦点丢失时重新抢回

### 装完新版本后完全不生效

```
FreezeManager: doFreeze <pid> pkg com.example.tvlimit status: HighUsedApp, FreezeApp
```

部分国产固件的 `FreezeManager` 会在进程启动约 6 秒后冻结它，`startForegroundService` 请求永不执行（`dumpsys` 中连 `ServiceRecord` 都没有），限额与拦截全部失效且无任何提示。

修法是新增透明的 `WakeupActivity`：开机广播、覆盖安装、15 分钟看门狗发现服务未运行时，用它把应用短暂顶到前台再启动服务。

### 应用级限制

上游只有整机限时。本版新增「只限制勾选的应用」，并明确了两种口径切换时今日用量的处理规则（见上文表格）。

### 其它修复

- 状态持久化节流，进程被杀时强制落盘，避免丢失「本场已看时长」
- 休息结束时同步清空本场分钟数；恢复时如发现「已满额但无休息进行中」的残留状态，视为脏数据清零
- 监控协程把协程取消当作错误记录的日志问题
- 跨线程读写的关键字段加 `@Volatile`
- `AppDatabase` 首次初始化并发导致的重复建档

---

## 已知限制

- **系统冻结策略仍在**：若看门狗同样被冻结且长期无人打开本应用，最长可能有 15 分钟空窗（`JobScheduler` 周期下限）。日常使用有前台服务与开机自启，影响很小。
- **设备管理权限会被部分固件撤销**：撤销后到点不会真正关屏，只弹拦截页并暂停播放。
- **debug 签名**：发布物使用 debug 构建，便于 `run-as` 调试；正式分发请自行配置签名。
- **两套界面**：整机模式与「仅限应用」模式使用不同布局，大号数字休息页出现在整机模式。

---

## 调试

```bash
adb logcat TvLimit:V *:S                                  # 只看本应用日志
adb shell dumpsys activity services com.example.tvlimit    # 服务状态
```

排查「该拦却没拦」时，先确认服务真的在运行：

```bash
adb shell dumpsys activity services com.example.tvlimit | grep -E "isForeground|startForegroundCount"
```

`startForegroundCount=0` 表示服务只创建了记录、从未真正运行，是「装完不生效」的典型症状。

运行状态也可以从 prefs 读取（debug 构建）：

```bash
adb shell run-as com.example.tvlimit cat shared_prefs/com.example.tvlimit_preferences.xml
```

其中 `s=本场已用 d=今日已用 r=是否到限额 top=当前前台应用 scope=是否在计时范围内`。

---

## 参与贡献

欢迎提交 issue 与 pull request。报告问题时请附上：电视型号、系统版本、`adb logcat TvLimit:V *:S` 的相关片段、以及 `dumpsys activity services` 的输出。

---

## 许可与致谢

- 上游项目：[aleung/tv-usage-limit](https://github.com/aleung/tv-usage-limit)，作者 Leo Liang
- 本项目遵循 [Apache License 2.0](LICENSE)，保留原作者版权声明，修改部分同样以 Apache-2.0 发布
- 修改声明详见 [NOTICE](NOTICE)

```
Copyright 2026 Leo Liang <leoliang@gmail.com>
Modifications Copyright 2026 tv-usage-limit-cn contributors
```
