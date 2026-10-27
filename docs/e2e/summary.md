# RpgCamera E2E 摘要（机器判定）

> 本文是 `scripts\verify-camera.ps1` 一次完整真机运行的**汇总**；完整 221 条逐项结果、原始日志与截图在
> `D:\MINE\evidence\verify-camera-20261023-182254\`（固定副本 `evidence\verify-camera-final\`）。
> 结论只来自该目录里的原始日志/回包/PNG 字节，不做人工“看着像”。

## 结论

| 项 | 值 |
|---|---|
| 时间 | 2026-10-23 18:25:12 +08:00（耗时 138s） |
| 模式 | 真实 Fabric 26.3 专用服 + 真实 loom runClient（1280×720） |
| 结果 | **PASS 221 / 221** |
| 证据 | `evidence\verify-camera-20261023-182254\`（固定副本 `evidence\verify-camera-final\`，28 文件 / 10 张截图） |
| 复现 | `powershell -NoProfile -ExecutionPolicy Bypass -File D:\MINE\scripts\verify-camera.ps1` |

环境：Minecraft **26.3** / Fabric Loader **0.19.5** / Fabric API **0.163.0+26.3** / Java **25.0.1** / Loom **1.18.4** / Windows PowerShell **5.1**。

## 覆盖范围（221 条断言，全部 PASS）

1. **构建**：rpgcam `gradle build` + `apiJar`、questguide `gradle build`（compileOnly 薄 api jar）全部 exit=0；jar 落地。
2. **静态审计**（jar + 源码两层）：
   - rpgcam jar：id / main+client entrypoint / mixin config（`rpgcam.client.mixins.json`，environment=client）/
     `suggests questguide` / 三个 mixin class / 双语 lang（zh_cn 13 个样式键）/ API jar 只含 `com/rpgcam/api/**`；
   - questguide jar：id / `suggests rpgcam` / `QuestInterop`+`RpgCameraHook` 存在 / **未打包任何 `com/rpgcam/*`**；
   - 源码 soft-dependency 隔离：import `com.rpgcam.*` / 调 `RpgCameraApi.play` 的文件**只有 `RpgCameraHook.java`**；
     `build.gradle` 只有 compileOnly api jar；`CameraMixin` 目标 `Camera.update(DeltaTracker)` + `calculateFov(F)`；
     `HudMixin` 目标 `Hud.extractRenderState` + `@Shadow isHidden`。
3. **双 mod 加载**：服务端与客户端 mod 列表均含 `rpgcam 1.0.0` + `questguide 1.0.0`；
   `initialized styles=13 interop.questguide=true`；questguide `interop rpgcam linked: version=1.0.0 enabled=true`。
4. **命令面**：`/cinematic list` 13 样式、`info` 参数行、`status`、控制台负例（缺 target / 未知样式 / duration<20 被拒）、
   `stop` 空闲态不崩、`preview`（source=preview）、`demo`（total=13，≥2 step，stop 后不再推进）。
5. **6 个代表样式逐段验证**（每段：cue-start → ≥2 条 pose-apply 且相机离玩家 >0.5 → cue-end restored=1
   → 服务端 cue-end/cue-client-done → autotest 截图 >5000B；FOV 样式数值落区间且非 vanilla）：

   | 样式 | 时长 | pose 采样 | 相机-玩家最大距离 | FOV 实测（期望） | 截图字节 |
   |---|---|---|---|---|---|
   | orbit | 120 | 16 | 6.72 | 70.0..70.0 | 1,250,976 |
   | dolly_in | 100 | 14 | 8.68 | 52.0..72.0 | 961,102 |
   | zoom | 100 | 13 | 6.77 | 28.0..70.0（fov-zoomed PASS） | 908,423 |
   | dialogue | 100 | 13 | 4.36 | 58.0..60.0 | 1,040,322 |
   | quest_complete | 100 | 14 | 5.67 | 62.0..65.0 | 992,512 |
   | death | 100 | 13 | 4.68 | 72.0..81.9 | 1,036,996 |

6. **QuestGuide 联动**（端到端真实触发，不是手改状态）：
   - RCON `give <player> oak_log 8` → `[QuestGuide] quest-completed ... quest=q_hello`；
   - 服务端 `cue-sent ... style=quest_complete duration=100 source=interop label="questguide.quest.q_hello.title"`；
   - 客户端 `cue-start style=quest_complete` → 约 100 tick 后 `cue-end ... restored=1`；
   - RCON 领取奖励 → `interop-cinematic ... style=reward result=70` → 服务端 `source=interop` reward cue →
     客户端 reward cue restored；奖励物品 `oak_planks` 进背包、经验 `level 1 / 3 points`；
   - 负例：领取未完成/不可领任务时不出现任何 `interop-cinematic` / `cue-sent` 行。
7. **收尾**：客户端 JVM 按命令行清理、停服、25566/25575 释放、无残留 JVM。

## 截图（1280×720，真实客户端）

`evidence\verify-camera-final\screenshots\` 共 10 张，覆盖 7 个不同样式：

`rpgcam-01-orbit.png` / `rpgcam-02-dolly_in.png` / `rpgcam-03-zoom.png` / `rpgcam-04-dialogue.png` /
`rpgcam-05-quest_complete.png` / `rpgcam-06-death.png` / `rpgcam-07-orbit.png` / `rpgcam-09-quest_complete.png` /
`rpgcam-10-reward.png` / `rpgcam-11-orbit.png`

文档引用版本（`rpgcam\docs\screenshots\`）：`rpgcam-01-orbit.png`、`rpgcam-quest_complete.png`、
`rpgcam-dialogue.png`、`rpgcam-reward.png`、`rpgcam-zoom.png`、`rpgcam-death.png`、`rpgcam-dolly_in.png`。
每张都肉眼可见：上下黑边 + 中文样式标题 + 进度条 + 原版 HUD 隐藏 + **第三人称可见的角色**。

## 复现要点 / 已知环境限制

- 客户端 run 目录首次启动的 onboarding（`onboardAccessibility`、多人警告、tutorial）会挡住 `--quickPlayMultiplayer`，
  脚本已自动用 `questguide\run\options.txt` 预置；
- 联动验证要求**客户端也装 questguide**（脚本会自动把 `questguide-1.0.0.jar` 拷进 `rpgcam\run\mods\`）；
- 验证期间检测到**另一个 DSH 会话**在同一工作区反复起 `verify-pcl-instance` / `e2e.ps1`（抢 25566/GPU），
  本轮的 221/221 是在清理窗口后取得的；复现时若端口/GPU 被占会 preflight 直接退出（不会假绿）；
- 脚本自身修过两处（见 `verification\CAMERA-REPORT.md`）：XP 断言按 26.3 语义改为 `level 1 + 3 points`、
  preview 后补等服务端 cue-end 消除空闲态竞态——**都不是放宽断言**。

## 原始证据文件

`summary.md`(221 条表格)、`results.json`、`server-latest.log` + `server-console.out.log`、
`client-latest.log` + `client-debug-tail.log`、`client-rpgcam-lines.txt`、`rcon-transcript.log`、
`static-audit.txt`、`environment.txt`、`screenshots\`。
