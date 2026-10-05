# Eta 交接文件（离屏虚拟屏 + 上下文窗口修复）

> 生成时间：2026-10-05 16:40（设备本地时间，UTC+8）
> 面向对象：接手继续开发的 Agent / 人
> 本文件不进入 git 提交，仅作为工作区交接材料；如需随分支走，可自行 `git add`。

---

## 1. 一句话现状

在用户 fork 的 Eta 上做了两件事：**① 修好 3.1.0 的上下文窗口阻塞（已完成并装机验证）**；
**② 给 Eta 加"虚拟屏可观察 + 可操作"能力（多屏定位已完成并装机验证；离屏守护进程已写完、CI 绿、已装机，
但守护进程在真机上启动时报 `InvocationTargetException`，尚未跑通）**。

---

## 2. 仓库与分支

| 项 | 值 |
| --- | --- |
| 官方仓库（只读） | `upstream` = https://github.com/Mangi-11/Eta |
| 用户 fork（可写） | `origin` = https://github.com/xxnmmd/Eta |
| 工作分支 | `fix/context-window-v3.1.0`（已推送，跟踪 origin） |
| 基线 | tag `v3.1.0` = `84d42dc` |
| 当前 HEAD | `04ba4e5` |
| 本地工作区 | Linux 环境 `/workspace/Eta-v3.1.0`（= Android 侧 `/data/local/tmp/eta/Eta-v3.1.0`） |
| CI | `.github/workflows/context-window-fix-build.yml`（本分支专属）＋官方 `android-release.yml` |

### 分支上的提交（v3.1.0 之后，从旧到新）

```
eacf2a2  fix: allow conversations without configured context window
2bc4502  test: cover compaction policy with configured window
66cebfe  ci: build signed release APK for the context window fix
fd6fbf6  fix: use catalog context window and stop blocking messages without one
6985e11  feat(device): target screens other than the primary display
e465aa1  fix(accessibility): use the public per-display screenshot API
719964c  fix(device): avoid JVM signature clash between json helpers
6951e1c  feat(device): create and manage a virtual display for agent control
e269d70  fix(ui): give the new screen tools their own icons
8726ebd  feat(display): offscreen virtual display owned by a root daemon
04ba4e5  fix(display): import android.os.Process in the daemon
```

> 注：`6985e11 / e465aa1 / 719964c` 不是本轮 Agent 写的（应该是用户或另一个会话），内容是多屏定位基础实现。

---

## 3. 设备与环境事实（都已实测确认）

| 项 | 值 |
| --- | --- |
| 机型 / 系统 | 22021211RC，Android 16（SDK 36），HyperOS（`BP2A.250605.031.A3`） |
| Root | APatch（`/data/adb/ap/bin/busybox`） |
| Eta 包名 / UID | `io.github.mangi.eta` / `u0_a444`（10444） |
| Eta 签名 | `a82c1fdf`（fork 的 release key；官方 release 是 `bf729daf`，**两者不能互相覆盖安装**） |
| 已安装版本 | versionName 3.1.0，versionCode 2026100201 |
| **当前已安装 APK** | sha256 `b086521d…7bf7`（= 含离屏守护进程的最新构建） |
| 装机方式 | `pm install -r`，签名一致可直接覆盖 |
| ⚠️ 安装路径限制 | `pm install` **不能用 `/sdcard/...`**：system_server 读不了 FUSE 路径（`avc: denied … u:object_r:fuse:s0`）。必须用 `/data/local/tmp/...` |
| 磁盘/路径映射 | Linux 环境 `/workspace` **就是** Android 的 `/data/local/tmp/eta` |
| Android 侧无 python3 | 需要 python 时用 Linux 环境 |
| Linux 环境 | Debian，openjdk 25；apktool/smali/baksmali 在 `/opt/eta/apk-analysis/current/bin` |
| 无本地 Android SDK | **本地不能跑 gradle 构建**，所有编译/测试必须走 GitHub Actions |
| CI Secrets（fork 已配置） | `ETA_RELEASE_KEYSTORE_BASE64`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD` |
| GitHub Token | Linux 环境变量 `GITHUB_TOKEN`（classic PAT，含 `repo`/`workflow` 权限）。**不要写进文件或 git config** |

---

## 4. 已完成并验证的部分

### 4.1 上下文窗口修复（用户核心诉求）

问题：3.1.0 把"上下文窗口"改成必须手填，自建/中转模型（无窗口元数据）会**完全无法对话**：
UI 拦截发送 + 运行时 `requireContextWindow()` 抛错。

改动：
- `AgentModelClient.validate()` 不再强制要求窗口；
- `AgentContextSession` 窗口未知时跳过自动压缩（手动压缩仍明确报错）；
- `AgentRuntimePolicy` 窗口未知时不下发 `autoCompactionEnabled`；
- `AgentRuntimeRunExecutor` 角色/记忆预算未知窗口时用 128K 兜底；
- `Model.effectiveContextWindow` 恢复 `override ?: 目录值`；
- 移除 UI "无窗口就拒绝发送" 的拦截；
- 新增/更新单元测试。

验证：CI 全绿（完整 `:app:testDebugUnitTest` + Debug/Release 构建 + apksigner 校验）；
真机升级后数据保留（2.2 GB），选中模型（自建中转、`context_window` 为 NULL）可正常对话。

### 4.2 多屏定位（已装机验证机制）

- `observe_screen` / `tap` / `tap_area` / `long_press` / `swipe` / `scroll` 支持 `display_id`；
- 非主屏手势走 root `input -d <id>`；
- 非主屏截图走无障碍 `takeScreenshot(displayId)`；
- `tap_element` / `long_press_element` 的坐标回退保持在快照所属屏幕；
- 新工具 `list_displays`（列出主屏/副屏/虚拟屏）与 `virtual_display`（create/destroy/status）。

实测结论（重要）：
- `settings put global overlay_display_devices "720x1280/320"` 能立刻创建系统模拟副屏；
- `am start --display <id>`、`input -d <id> tap/swipe` 都能作用到该屏；
- **`screencap -d <id>` / `screenrecord --display-id` 对虚拟屏无效**（只支持物理屏）；
- 无障碍 `takeScreenshot(displayId)` 是虚拟屏截图的可行通道（代码已接，待真机复验）；
- 系统模拟副屏会以浮窗叠加在主屏右上角（`ty=DISPLAY_OVERLAY`，`pkg=android`，**由 system_server 绘制**，
  Eta 无法改它的样式、加悬浮球或最小化；这是用户抱怨"设计有毛病"的根因）。

---

## 5. 进行中的任务：离屏虚拟屏守护进程（未跑通）

### 5.1 设计

Eta 本体（untrusted app）拿不到 `CAPTURE_VIDEO_OUTPUT`（`prot=signature`），所以不能自己建虚拟屏。
方案：由 **root 通过 `app_process` 启动一个守护进程**（uid 0 在 `ActivityManager.checkComponentPermission`
里被直接放行），守护进程自己持有 `ImageReader` 的离屏 Surface → 画面不合成到主屏，也就没有浮窗。

控制面全部走文件（避免跨 uid socket 与 SELinux 限制），目录 `/data/local/tmp/eta/vdisplay`：

| 文件 | 作用 |
| --- | --- |
| `status.json` | pid / display_id / 宽高 / dpi / state |
| `cmd` | 命令 FIFO：`shot` / `info` / `quit` |
| `frame.png` | 最近一帧（`shot` 触发写出） |
| `daemon.log` | 诊断日志 |
| `task_id` | Eta 侧记录 terminal 守护任务 id（用于 daemon_stop） |

### 5.2 已实现的代码

| 文件 | 内容 |
| --- | --- |
| `agent/display/VirtualDisplayDaemon.kt`（新增） | 守护进程：ImageReader + `DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,FLAG_PUBLIC|0x40)`；FIFO 命令循环；PNG 落盘 |
| `agent/device/RootShellDeviceController.kt` | `daemonStatus()` / `daemonSendCommand()` / `captureDaemonScreenshot()`；非主屏截图改为"无障碍优先，失败回退守护进程" |
| `agent/tool/AgentLocalTools.kt` | `virtual_display` 支持 `mode=offscreen`（默认，守护进程）/ `mode=overlay`（系统模拟副屏）；`create/destroy/status` |
| `agent/model/AgentDeviceToolCatalog.kt` | 工具 schema 增加 `mode` 参数 |
| `app/proguard-rules.pro` | `-keep class io.github.mangi.eta.agent.display.VirtualDisplayDaemon { *; }`（R8 会裁掉只被反射引用的类） |

CI：run `37281355380`（commit `04ba4e5`）**全绿**；产物已装机（sha `b086521d…`），
已确认 `VirtualDisplayDaemon` 在安装包的 `classes.dex` 里。

### 5.3 当前阻塞（真机实测）

手动启动守护进程：

```bash
apk=$(dumpsys package io.github.mangi.eta | grep -m1 codePath= | sed 's/.*codePath=//')/base.apk
mkdir -p /data/local/tmp/eta/vdisplay
setsid sh -c "CLASSPATH=$apk app_process /system/bin io.github.mangi.eta.agent.display.VirtualDisplayDaemon \
  --dir /data/local/tmp/eta/vdisplay --width 720 --height 1280 --density 320" &
```

结果（`/data/local/tmp/eta/vdisplay/daemon.log`）：

```
[234358890] daemon start pid=30340 size=720x1280 density=320
[234359207] daemon failed: java.lang.reflect.InvocationTargetException: null
[234359207] daemon exited
```

即 **`ActivityThread.systemMain()` 调用失败**（`systemContext()` 里抛出的 InvocationTargetException，
真正原因被吞掉了，因为日志只打了 `message`）。没有生成 `status.json`，`dumpsys display` 也没有新 display。

### 5.4 下一步（按顺序）

1. **补全异常链日志**：把 `catch` 里的 `error.cause`/`cause.cause` 全打出来（现在只打 message），
   再跑一次 CI + 真机，确认 `systemMain()` 失败的真实原因（大概率是 hidden API 限制或
   `attach()` 在非 zygote 进程里的前置条件）。
2. **准备 Context 获取的多级回退**（建议按顺序尝试并逐条记日志）：
   - `ActivityThread.systemMain()` → `getSystemContext()`
   - `ActivityThread.systemMain()` → `getSystemUiContext()`
   - `ContextImpl.createSystemContext(activityThread)`（反射）
   - `ActivityThread.currentActivityThread()`（app_process 下通常为 null，仅探测）
   - 若全部失败：改走隐藏 API `DisplayManagerGlobal.getInstance().createVirtualDisplayWrapper(
     VirtualDisplayConfig, IVirtualDisplayCallback, flags)`（需要 AIDL stub + HiddenApiBypass）
3. **确认 HiddenApiBypass 是否真的生效**：`HiddenApiBypass.addHiddenApiExemptions("L")` 目前用
   `runCatching` 静默包着；要记录成功/失败。若在 app_process 中无效，需要
   `-Xhidden-api-policy:disabled` 之类的启动参数（app_process 支持 `--runtime-args` 透传）。
4. 守护进程能起来后，验证：
   - `cat /data/local/tmp/eta/vdisplay/status.json` 有 display_id；
   - `printf 'shot\n' > /data/local/tmp/eta/vdisplay/cmd` 后 `frame.png` 出现且是合法 PNG；
   - `am start --display <id>` 能起应用；`input -d <id> tap` 生效；
   - 通过 Eta 自己的 `virtual_display create` / `list_displays` / `observe_screen(display_id=…)` 端到端跑通。
5. 完成后清掉旧的系统模拟副屏设置：`settings delete global overlay_display_devices`
   （当前该设置为 `null`，已无遗留浮窗）。
6. （可选）把这两块改动整理成对官方仓库的 PR；上下文窗口部分已在 issue #115 / PR #116 下留言说明。

---

## 6. 常用命令速查

```bash
# ---- Linux 环境（/workspace）----
cd /workspace/Eta-v3.1.0
git log --oneline -12                       # 看提交
git push origin fix/context-window-v3.1.0   # 触发 CI（需要 token，见下）

# 带 token 推送（不要把 token 写进 remote/配置文件）
askpass=$(mktemp); printf '%s\n' '#!/bin/sh' 'case "$1" in' \
  '  *Username*) printf "%s\n" "x-access-token" ;;' \
  '  *Password*) printf "%s\n" "$GITHUB_TOKEN" ;;' '  *) exit 1 ;;' 'esac' > "$askpass"
chmod 700 "$askpass"
GIT_ASKPASS="$askpass" GIT_TERMINAL_PROMPT=0 git push origin fix/context-window-v3.1.0
rm -f "$askpass"

# 查 CI 状态
curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" \
  'https://api.github.com/repos/xxnmmd/Eta/actions/runs?branch=fix%2Fcontext-window-v3.1.0&per_page=3' \
  | python3 -c 'import json,sys;[print(r["id"],r["head_sha"][:7],r["status"],r.get("conclusion")) for r in json.load(sys.stdin)["workflow_runs"]]'

# 下载产物
artifact_id=<从上面 artifacts API 取>
curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/xxnmmd/Eta/actions/artifacts/$artifact_id/zip" -o /workspace/eta-build-out/artifact.zip
unzip -o -q /workspace/eta-build-out/artifact.zip -d /workspace/eta-build-out/extracted

# ---- Android 环境（root）----
# 安装（必须从 /data/local/tmp，不能从 /sdcard）
pm install -r /data/local/tmp/eta/eta-build-out/extracted/app-release.apk
# 校验装机内容
installed=$(dumpsys package io.github.mangi.eta | grep -m1 codePath= | sed 's/.*codePath=//')
sha256sum "$installed/base.apk"

# 模拟副屏（对照方案，会浮在主屏上）
settings put global overlay_display_devices "720x1280/320"
settings delete global overlay_display_devices

# 屏幕/窗口排查
dumpsys display | grep -oE 'mDisplayId=[0-9]+' | sort -u
dumpsys window windows | grep -A 6 '叠加视图'
```

---

## 7. 关键结论 / 踩过的坑

1. **root 不是"权限越高越能做"**：`CAPTURE_VIDEO_OUTPUT` 是 signature 权限，普通 App 永远拿不到；
   root（uid 0）能绕过校验，但**必须自己持有离屏 Surface 并常驻**，否则没有渲染目标 = 没画面。
2. 系统"模拟副屏"（`overlay_display_devices`）的浮窗由 **system_server** 绘制，第三方无法改造，
   也没有悬浮球/最小化；要"不占主屏"只能走自建离屏 Surface。
3. `screencap -d` / `screenrecord --display-id` **不支持虚拟屏**；虚拟屏截图要么走无障碍
   `takeScreenshot(displayId)`，要么由持有 Surface 的进程自己出图。
4. R8 会裁掉"只被反射引用"的类：app_process 入口类必须写 `-keep`。
5. `pm install` 的 APK 路径必须在 `/data/local/tmp`；`/sdcard` 会被 system_server 的 SELinux 拒读。
6. fork 的 release key（`a82c1fdf`）与官方（`bf729daf`）不同 → fork 构建**无法覆盖安装官方包**，
   反之亦然。用户当前装的是 fork 包。
7. Android 侧没有 python3，也没有 Android SDK；脚本分析放 Linux 环境，构建放 CI。
8. 本会话多次出现：terminal 会话被回收（用 `open_and_exec` 更稳）、GitHub API 偶发 SSL EOF（加重试即可）。

---

## 8. 清理项（可选）

- `/workspace/fwcheck/`：从设备 `framework.jar` 提取的 dex 与 smali（排查 API 时用的，可删）。
- `/workspace/patch_*.py`：本轮补丁脚本（可删）。
- `/workspace/eta-build-out/`：CI 产物与解包结果（可删，需要时重新下）。
- `/sdcard/Download/`：`Eta-3.1.0-*.apk` 多个历史构建，可只留最新。
- 虚拟屏相关系统设置当前已是 `null`，无遗留。

---

## 9. 给接手者的一句话

**上下文窗口修复已可用**；虚拟屏功能"多屏定位"部分可用，"离屏守护进程"卡在
`ActivityThread.systemMain()` 调用失败 —— 先补全异常链日志 + 多级 Context 回退，
再按 §5.4 的真机验证清单逐项过一遍，就能收口。
