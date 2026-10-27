# RpgCamera · 工作契约（v1，2026-10-23）

> 本文件是 Lead 与队友之间的实现契约。**改日志格式 / 改命令输出 / 改包字段 = 改契约**，必须同步
> `scripts/verify-camera.ps1` 与 README，并在交付说明里写明。

## 0. 目标

给 `D:\MINE` 这个 MC 26.3 / Fabric 工作区新增一个 **运镜（cinematic camera）mod `rpgcam`**：

1. 13 个 RPG 常用镜头样式（环绕/推/拉/摇臂/对话/首领登场/任务完成/奖励/胜利/倒地/手持/聚焦/降临）；
2. 服务端权威调度（`/cinematic` 命令 + 给其它 mod 用的 `RpgCameraApi`）；
3. 客户端真正接管相机（位置/朝向/FOV/剔除视锥），过场 HUD（上下黑边 + 样式标题 + 进度条 + 黑场淡入淡出），
   演出期间隐藏原版 HUD 与第一人称手；
4. **与工作区现有 mod `questguide` 联动**：任务完成/领奖时自动触发 `quest_complete` / `reward` 运镜，
   镜头 overlay 上带任务名；
5. 机器可验证的端到端证据（真实专用服 + 真实 loom 客户端 + 截图）。

技术结论（已用 javap/字节码确认，见 `.toolchain/camera-recon/`）：

- MC 26.3 是 **official 名 / 无混淆**（日志 `Mappings not present!`），Mixin 直接写 official 名；
- 相机姿态收敛在 `net.minecraft.client.Camera#update(DeltaTracker)`（fov → 剔除视锥 → `Projection.setupPerspective`），
  **在 TAIL 注入**即可接管位置/朝向；`calculateFov(F)F` 的 RETURN 可改 FOV（投影/剔除/画面一致）；
- `Camera#setPosition(Vec3)` / `setRotation(float yRot, float xRot)` 是 protected，可 `@Shadow`；
  `createProjectionMatrixForCulling()` / `prepareCullFrustum(Matrix4fc, Matrix4f, Vec3)` 是 private，用
  `CameraInvoker`（`@Invoker` 接口 mixin）调用；
- `Hud#extractRenderState(GuiGraphicsExtractor, DeltaTracker)` **HEAD** 里：`@Shadow private boolean isHidden` 置 true
  → 下一行原版写 `GuiRenderState.isHudHidden` → 原版 HUD、第一人称手、准星全部消失；
  同一个 HEAD 里用 `GuiGraphicsExtractor#fill/centeredText` 画黑边标题（原版 HUD 隐藏时依然可见）；
- 网络：`PayloadTypeRegistry.clientboundPlay()/serverboundPlay()`；`ClientPlayNetworking` / `ServerPlayNetworking`；
- 命令：`CommandRegistrationCallback.EVENT`（fabric-command-api-v2）；权限 `Commands.LEVEL_GAMEMASTERS.check(source.permissions())`；
- 服务端 tick：`ServerTickEvents.END_SERVER_TICK`；
- 截图：`Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, cb)`，文件在 `<gameDir>/screenshots/`；
- 客户端 tick：`ClientTickEvents.END_CLIENT_TICK`；键位 `KeyMappingHelper.registerKeyMapping` + `KeyMapping.Category.register`。

## 1. 目录 / 所有权（写作用域，勿越界）

```
D:\MINE\rpgcam\                         # 新 mod（Lead 建好、已 build 绿）
  build.gradle / settings.gradle / gradle.properties / gradlew*   [Lead]
  src/main/resources/fabric.mod.json, dist?                        [Lead]
  src/main/java/com/rpgcam/
    RpgCamera.java                                                [Lead]
    cue/{CueSpec,MotionType,AnchorType,LookType}.java             [Lead，接口冻结]
    cue/CueRegistry.java                                          [Lead，样式表可提意见但由 Lead 改]
    net/**, api/**                                                [Lead]
    server/CameraCueManager.java, CameraCommands.java             [Lead]
    integration/**（如新增）                                      [Lead]
  src/main/resources/assets/rpgcam/lang/**                        [Lead]
  src/client/java/** + src/client/resources/**                    [A: camera-client]
  docs/**                                                         [各自任务文档]
D:\MINE\questguide\                     # 现有 mod（已有 E2E 45/45 PASS）
  src/main/java/com/questguide/**  build.gradle  fabric.mod.json  README   [B: questguide-interop]
D:\MINE\scripts\
  e2e.ps1 / serverctl.ps1 / verify-*.ps1        [只读复用；Lead 可小修]
  verify-camera.ps1（新建）                     [C: verifier]
D:\MINE\evidence\**, D:\MINE\verification\**   [各自任务的取证目录]
```

**纪律**：不要编辑别人作用域里的文件；需要别人改 → 发消息给 Lead。构建可以在自己的 scope 内随时跑
`gradle build`（daemon 会排队，别同时跑 runClient）。

## 2. 构建 / 运行

```powershell
$env:JAVA_HOME='C:\Users\op\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon'
$gradle='D:\MINE\.toolchain\gradle-9.8.2\bin\gradle.bat'
cd D:\MINE\rpgcam;  & $gradle build --console=plain          # 产物 build\libs\rpgcam-1.0.0.jar
& $gradle apiJar --console=plain                              # build\libs\rpgcam-1.0.0-api.jar（questguide compileOnly 用）
cd D:\MINE\questguide; & $gradle build --console=plain
```

- **客户端真实运行（loom runClient）是独占资源**：同一时刻只允许一个客户端/一个人跑。
  Lead 负责调度；队友在自己任务里需要真机跑时先发消息要窗口。
- 测试服：`scripts\serverctl.ps1`（dot-source 后 `Start-TestServer` / `Wait-ServerReady` /
  `Initialize-TestWorld` / `Invoke-Rcon` / `Grant-Op` / `Stop-TestServer`）。端口 25566 / RCON 25575。
- 快速冒烟：`powershell -NoProfile -ExecutionPolicy Bypass -File D:\MINE\scripts\smoke-camera.ps1`
  （起服+客户端+RCON 播一段，证据在 `evidence\smoke-*`）。

## 3. 命令契约

```
/cinematic [help]
/cinematic list
/cinematic info <style>
/cinematic play <style> [target] [duration]     # ADMIN(LEVEL_GAMEMASTERS)；控制台必须给 target
/cinematic stop [target]
/cinematic preview <style> [duration]           # 所有玩家，给自己放预览
/cinematic demo [target]                        # ADMIN，串行播全部样式（间隔 30t）
/cinematic status [target]
```

`duration` 单位 tick，clamp 到 [20, 6000]；`<=0` 用样式默认时长。

## 4. 网络包

- S2C `rpgcam:cue`：`styleId, durationTicks, anchorEntityId(-1=无), hasAnchorPos, anchorX/Y/Z, label, seed`
- S2C `rpgcam:stop`：`styleId, reason`
- C2S `rpgcam:done`：`styleId, ticksPlayed, interrupted, details`（仅日志取证）

## 5. **日志契约（E2E 断言直接 grep，不要随意改）**

服务端（logger `rpgcam`，前缀 `[RpgCamera] `）：

```
cue-sent player=<name> style=<id> duration=<n> source=<command|preview|demo|interop> anchor=<entity:N|pos:x,y,z|player> label="<text>" endTick=<n>
cue-end player=<name> style=<id> duration=<n> source=<...> reason=finished
cue-stop player=<name> style=<id> duration=<n> source=<...> reason=<replaced|command|api|demo-restart>
cue-skip player=<name> style=<id> reason=<no-client-mod|unknown-style|offline>
cue-client-done player=<name> style=<id> ticks=<n> interrupted=<b> details=<...>
demo-start player=<name> total=<n> gap=<n> styles=<csv>
demo-step player=<name> index=<i> total=<n> style=<id> duration=<n>
demo-done player=<name> total=<n>
[裸 log] initialized styles=<n> interop.questguide=<true|false>
```

**source 取值约定**：命令=`command`，玩家预览=`preview`，demo=`demo`，**其它 mod 经 `RpgCameraApi` 调用=`interop`**。
`label` 允许是翻译键（含 `.` 且无空格，例如 `questguide.quest.q_hello.title`），客户端按玩家语言解析；服务端日志原样打印键。

客户端（logger `rpgcam`）：

```
cue-start style=<id> duration=<n> label="<text>" anchorEntity=<id|-> anchorPos=<x,y,z|-> local=<b>
camera-type saved=<CameraType> forced=third_person_back        # 演出开始临时切第三人称（让玩家模型可见）
pose-apply style=<id> t=<0.000> pos=<x,y,z> rot=<yaw,pitch> fov=<f|vanilla> playerPos=<x,y,z>
camera-type restored=<CameraType>                              # 结束/中断恢复用户原相机类型
cue-end style=<id> ticks=<n> reason=<finished|stop:...|local-stop> restored=1 local=<b>
client receivers registered
```

> 26.3 只在第三人称渲染本地玩家模型，所以演出期间必须临时切 `THIRD_PERSON_BACK`，否则环绕/推拉镜头里看不到角色。
> `camera-type *` 两行是这条链路的机器证据（E2E 也断言它）；`pose-apply` 同 tick 多帧只记一条、每 10 tick 一条。

autotest（`-Drpgcam.autotest=on`，logger `rpgcam`，前缀 `[RpgCamera-AUTOTEST] `）：

```
in-world player=<name> pos=BlockPos{...}
cue-begin style=<id> duration=<n> local=<b>
shot-requested=rpgcam-<NN>-<style>.png
shot-ok=rpgcam-<NN>-<style>.png bytes=<n> path=<abs>
cue-finished last=<file|->
```

questguide ↔ rpgcam 联动（questguide logger）：`[QuestGuide] interop-cinematic ...`（由 B 定义并在契约里补充，
建议：`interop-cinematic player=<name> quest=<id> style=<id> result=<duration|-1>`）。

## 6. 样式目录（13 个，`CueRegistry`）

| id | 中文 | motion | anchor | look | 用途 |
|---|---|---|---|---|---|
| orbit | 环绕展示 | ORBIT | PLAYER | ANCHOR_EYE | 展示角色/武器 |
| dolly_in | 推进特写 | DOLLY | PLAYER | ANCHOR_EYE | 揭示/压迫 |
| dolly_out | 拉远揭示 | DOLLY | PLAYER | ANCHOR_EYE | 场面揭示 |
| crane_up | 摇臂上升 | CRANE | PLAYER | ANCHOR_CENTER | 场景/群像 |
| dialogue | 对话镜头 | DOLLY | TARGET | ANCHOR_EYE | NPC 对话 |
| boss_intro | 首领登场 | DOLLY | TARGET | ANCHOR_EYE | 首领战开场 |
| quest_complete | 任务完成 | ORBIT | PLAYER | ANCHOR_EYE | **questguide 联动** |
| reward | 奖励特写 | HOVER | PLAYER | ANCHOR_EYE | **questguide 联动** |
| victory | 胜利时刻 | ORBIT | PLAYER | ANCHOR_EYE | 战斗胜利 |
| death | 倒地镜头 | DROP | PLAYER | ANCHOR_CENTER | 死亡 |
| handheld | 手持跟拍 | HANDHELD | PLAYER | ANCHOR_EYE | 探索跟拍 |
| zoom | 聚焦观察 | HOVER | TARGET | ANCHOR_EYE | 观察远处目标 |
| arrival | 降临 | CRANE | PLAYER | ANCHOR_CENTER | 开场/重生 |

参数语义见 `CueSpec` javadoc。改样式表 = 改 `CueRegistry`（Lead 所有，提需求）。

## 7. 验收标准（交付质量门槛）

1. `gradle build` 干净通过；两个 jar 都能加载（服务端日志有 mod 列表含 `rpgcam 1.0.0` / `questguide 1.0.0`）。
2. 真机 E2E：`/cinematic play` 触发后客户端日志出现 `cue-start` + 多次 `pose-apply`（相机位姿 != 玩家位姿）
   + `cue-end ... restored=1`；服务端有 `cue-sent`/`cue-end` 与 `cue-client-done`。
3. **任务联动**：给玩家 8 个橡木原木 → questguide `quest-completed` → 服务端日志出现
   `source=interop` 的 `cue-sent ... style=quest_complete`，客户端收到 `cue-start style=quest_complete`。
4. 截图：至少 5 张不同样式的真实客户端截图（`run/screenshots/rpgcam-*.png`），肉眼可见黑边+标题+镜头不在第一人称。
5. 独立复验（另一个 agent 跑）+ 证据落盘（`evidence/`、`verification/`），不允许只报结论不给原始日志。
6. README 含：安装、命令、样式表、与 questguide 的联动方式、构建/验证步骤、26.3 踩坑。
