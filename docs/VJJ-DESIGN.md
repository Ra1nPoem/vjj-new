# VJJ-NEW 设计与协作契约（MC 26.3 · Fabric）

> 本文件是本次多 agent 会话的**唯一接口契约**。实现者按本文件的类名/方法名/日志/动作/按钮 id 写代码；
> 工作区：`D:\MINE`（Windows，PowerShell 7，UTF-8）。最终交付见 §10。

## 0. 背景与目标（用户需求）

在 Minecraft **26.3 / Fabric** 上，把工作区已有的三个 mod 合并成**一个**整合式 RPG 演出 mod `VJJ-NEW`：

| 已有 mod | 作用 | 在新 jar 里的归宿 |
|---|---|---|
| `rpgcam` | 运镜（13 个样式 + 服务端权威 + 客户端接管相机） | 源码原样整合（包 `com.rpgcam`），并新增关键帧运镜编辑器 |
| `rpganim` | 14 个数据驱动玩家/战斗动作，客户端驱动玩家模型骨骼 | 源码原样整合（包 `com.rpganim`），并新增假人播放 |
| `rpgmodel` | 骨骼 rig 定义 + 绑定校验 API（rpganim 硬前置） | 源码原样整合（包 `com.rpgmodel`） |

**新增功能（本次要做的）**：

1. **变焦 / 放大缩小**：客户端按住变焦键 FOV 缩放 + 滚轮调倍率；服务端可强制变焦（`/vjj zoom`、面板）。
2. **可编辑运镜**：关键帧镜头（位置/朝向/FOV/缓动/时长），OP 在可视化界面里新建、捕获当前机位、删帧、调时长、播放、**保存**到 `config/vjjnew/config.json`。
3. **强制玩家镜头**：播放时接管目标玩家相机（选项：广播给所有玩家）。
4. **锁死玩家行动**：服务端每 tick 位置校正 + 客户端锁输入（移动/跳/攻击/使用/破坏）。
5. **隐藏玩家**：服务端 invisibility，用于“假人替代本体”。
6. **玩家假人替代**：播放动画时在目标位置生成 `vjjnew:dummy` 实体（玩家模型 + 目标皮肤 + 骨骼动画），本体隐藏 + 锁行动。
7. **动画独立板块**：OP 可视化界面里选动作/目标，播放真实动作或生成假人播放。
8. **可视化操作界面**：`/vjj panel`（OP，F6 也可）打开；主页 → 运镜编辑器 / 动画板块 / 设置与保存；所有编辑由服务端保存、重载后仍在。

> GitHub 仓库 `Ra1nPoem/rpgcore` 在本环境网络不可达（DNS 被重定向 + 无外网）；本交付以工作区已开发的
> rpgcam/rpganim/rpgmodel 源码为基线实现全部需求。若之后能提供该仓库 zip，可再对齐其 RPG 核心数据。

## 1. 工程与环境事实（已验证）

| 项 | 值 |
|---|---|
| 工程 | `D:\MINE\vjjnew`（loom 1.18-SNAPSHOT，`splitEnvironmentSourceSets()`） |
| MC / Loader / Fabric API / Java | 26.3 / 0.19.5 / 0.163.0+26.3 / **25**（`JAVA_HOME=C:\Users\op\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon`） |
| 构建 | `cd D:\MINE\vjjnew; .\gradlew.bat build --console=plain`（全量访问已开启，可写 `~/.gradle`；首次会启动 daemon） |
| 产物 | `build\libs\vjj-new-1.0.0.jar`（＋ `-api.jar` 薄 API 兼容包） |
| 测试服 | `D:\MINE\testserver`（25566 / RCON 25575，world 独立） |
| 客户端 | loom `runClient`（`D:\MINE\vjjnew\run`），**同一时刻只允许一个游戏客户端** |
| 现有参考 | `rpgcam`（相机/网络/autotest）、`rpganim`（动画/骨骼）、`npcmod`（自定义实体+渲染器）、`questguide`（Screen/按钮） |

### 1.1 26.3 关键 API 事实（已 javap/真机验证，不要按 1.20 记忆写）

- 客户端入口类实现 `ClientModInitializer`；网络 `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()`（双端注册）。
- `Screen`：无 `render(GuiGraphics,...)`；覆盖 `extractRenderState(GuiGraphicsExtractor,int,int,float)`；
  按钮输入 `mouseClicked(MouseButtonEvent, boolean)`（**SDL 左键 = 1**）；键盘 `keyPressed(KeyEvent)`；
  `isPauseScreen()` 默认 true 要覆盖成 false；打开界面 `mc.gui.setScreen(...)`（**无 `Minecraft.screen` 字段**）。
- 绘制：`graphics.fill(x1,y1,x2,y2,color)` / `graphics.outline(...)` / `graphics.text(font, Component, x, y, color)` /
  `graphics.centeredText(...)` / `enableScissor/disableScissor`。
- 相机：姿态在 `Camera#update(DeltaTracker)` TAIL 覆写；FOV 在 `Camera#calculateFov(F)F` RETURN 改；
  改位姿后必须用 `CameraInvoker` 重算剔除视锥（rpgcam 已做）。
- 自定义实体：`EntityType.Builder.of(...)` + `FabricDefaultAttributeRegistry.register`；客户端
  `EntityRendererRegistry.register(type, Renderer::new)`；渲染器可 `HumanoidMobRenderer<T, S, M>` +
  `ModelLayers.PLAYER` + `HumanoidModel`（npcmod 已验证）。
- 皮肤：`PlayerInfo.getSkin()` → `net.minecraft.world.entity.player.PlayerSkin`，
  `skin.body().texturePath()` 是 `Identifier` 贴图；服务端同步皮肤用 UUID（两个 long）+ 玩家名。
- 服务端事件：`ServerLifecycleEvents`（STARTING/STARTED/STOPPING）、`ServerTickEvents.END_SERVER_TICK`、
  `ServerPlayConnectionEvents`（JOIN/DISCONNECT）、`CommandRegistrationCallback`。
- 权限：`Commands.LEVEL_GAMEMASTERS.check(source.permissions())`；玩家侧 `player.permissions()`（PermissionSet）。
- 网络回调不在主线程：必须 `context.client().execute(...)`。
- `ServerPlayer#teleportTo(double,double,double)` 存在；`Entity#setInvisible(boolean)`、`setDeltaMovement(...)` 存在；
  `MinecraftServer#getTickCount()`、`getPlayerList()`、`level().getServer()`（**Entity 没有 getServer()**）。

## 2. 文件所有权（避免并发写冲突）

| 归属 | 允许写的路径 | 说明 |
|---|---|---|
| **Lead** | `build.gradle`、`settings.gradle`、`gradle.properties`、`src/main/resources/fabric.mod.json`、`src/main/resources/assets/vjjnew/**`、`src/main/java/com/vjj/VjjNew.java`、`com/vjj/net/**`、`com/vjj/store/**`、`com/vjj/cmd/**`、`com/vjj/scene/**`、`com/vjj/editor/**`、`com/vjj/client/VjjNewClient.java`、`com/vjj/client/VjjClientNet.java`、`com/vjj/client/VjjUiClient.java`、`com/vjj/client/VjjAutotest.java`、`com/vjj/client/state/**`、`com/vjj/client/ui/VjjScreenBase.java`、`com/vjj/client/ui/VjjHubScreen.java`、`com/vjj/client/ui/VjjSettingsScreen.java`、`docs/**`、`README.md`、`scripts/**` | 公共契约/网络/持久化/命令/场景/主界面 |
| **camera-dev** | `src/main/java/com/vjj/camera/**`、`src/client/java/com/vjj/client/camera/**`、`src/client/java/com/vjj/client/ui/CameraEditorScreen.java`、`src/client/resources/vjj.camera.mixins.json`、`src/client/java/com/vjj/mixin/camera/**`；可**修改**已整合的 `com/rpgcam/**`（限 `CameraDirector`/`CameraMixin`/`CameraInvoker`） | 关键帧运镜/变焦/强制镜头/锁行动/隐藏/相机编辑器 |
| **anim-dev** | `src/main/java/com/vjj/dummy/**`、`src/client/java/com/vjj/client/dummy/**`、`src/client/java/com/vjj/client/ui/AnimBoardScreen.java`、`src/client/resources/vjj.dummy.mixins.json`、`src/client/java/com/vjj/mixin/dummy/**`；可**修改** `com/rpganim/client/ClientAnimState.java`（仅新增“外部/假人动作”入口） | 假人实体/渲染/动画/隐藏替代/动画板块 |
| **verifier** | `scripts/verify-vjj.ps1`、`verification/VJJ-REPORT.md`、`evidence/**` | 独立 E2E 与复验报告（不改 mod 源码） |

**规则**：只写自己范围的路径；需要跨界接口时先发消息给 Lead；所有新日志都带 `[VjjNew]`/`[VjjNew-AUTOTEST]` 前缀。

## 3. 模块接口契约（方法签名固定，实现者不要改）

### 3.1 `com.vjj.camera.VjjCameraServer`（camera-dev 实现，Lead 已留桩）

```java
public static void init();
public static void tick(MinecraftServer server);                 // 每 tick：锁行动的位置校正
public static void onPlayerQuit(ServerPlayer player);
public static void onServerStopping(MinecraftServer server);
public static int playClip(ServerPlayer target, String clipId, String source, boolean broadcast); // 返回时长 tick，失败 -1
public static int playClipTo(List<ServerPlayer> targets, CameraClip clip, ServerPlayer anchor, String source);
public static boolean stop(ServerPlayer target, String reason);
public static boolean isPlaying(ServerPlayer target);
public static void requestZoom(ServerPlayer target, double factor, int durationTicks, String reason);
public static void setLocked(ServerPlayer target, boolean locked, String reason);
public static void setHidden(ServerPlayer target, boolean hidden, String reason);
public static boolean isLocked(ServerPlayer target);
public static boolean isHidden(ServerPlayer target);
public static String handleAction(ServerPlayer op, VjjPayloads.EditorActionPayload action); // 未处理返回 null
public static void sendSnapshot(ServerPlayer op, String status);
public static void openEditor(ServerPlayer op, String tab);
public static List<String> targetNames(MinecraftServer server);
```

`handleAction` 必须处理的动作（其余返回 null 由编辑器服务端兜底）：

| action | 语义 |
|---|---|
| `clip_new` | 新建镜头（服务端 store.addClip，返回状态行） |
| `clip_delete` | 删除 `clipId` |
| `clip_select` | 无服务端效果（客户端选择态），返回 null 即可 |
| `clip_duration` | `index` = 新时长（tick） |
| `key_add` | 用包里的 `x/y/z/yaw/pitch/fov`（客户端捕获的机位）+ `tick` 添加关键帧 |
| `key_del` | `index` = 关键帧下标 |
| `key_fov` | `index` = 关键帧下标，`fov` = 新 FOV |
| `key_time` | `index` = 关键帧下标，`tick` = 新时间点 |
| `key_ease` | `index` = 关键帧下标，循环切换 linear/smooth/step |
| `clip_play` | 给 OP 自己播放该镜头（带 settings 的 force/lock/hide 行为） |
| `clip_stop` | 停止 OP 自己的镜头 |

播放日志契约（`playClip`/`playClipTo` 内必打）：

```
[VjjNew] clip-play player=<name> clip=<id> duration=<n> keys=<n> source=<command|scene|gui|api> broadcast=<b>
[VjjNew] clip-stop player=<name> clip=<id> ticks=<n> reason=<...>
```

变焦/锁/隐藏日志（`requestZoom`/`setLocked`/`setHidden` 内必打）：

```
[VjjNew] zoom-request player=<name> factor=<f> duration=<n> reason=<...>
[VjjNew] lock-set player=<name> locked=<b> reason=<...>
[VjjNew] hide-set player=<name> hidden=<b> reason=<...>
```

客户端（camera-dev 负责）：
- `com.vjj.client.camera.VjjCameraClient`：实现 `onClipPlay(ClipPlayPayload)`（开始演算关键帧镜头）、
  `onClipStop`、`tick`、`reset`、`isActive`、`status`（**必须**记录
  `[VjjNew] clip-start style=<clipId> duration=<n> keys=<n> force=<b>` 与
  `[VjjNew] clip-pose clip=<id> t=<0..1> pos=<x,y,z> rot=<yaw,pitch> fov=<f> playerPos=<x,y,z>` 每 10t 一条、
  `[VjjNew] clip-end clip=<id> ticks=<n> reason=<finished|stop|replaced> restored=1`）。
- 关键帧插值规则：按 tick 找相邻两帧，`linear`/`smooth`（smoothstep）/`step` 缓动，位置与朝向都插值，
  FOV 插值；`relative` 模式 = 锚点（目标玩家眼睛）+ 偏移，`world` 模式 = 绝对坐标；首个关键帧前/末尾帧后 clamp。
- 相机接管复用 `com.rpgcam` 的 `CameraMixin`/`CameraInvoker` 机制：可以**修改** `com.rpgcam.client.CameraDirector`
  增加“关键帧镜头”活动态（与旧 cue 并存，旧 13 样式和 `/cinematic` 命令必须继续可用）。
- `com.vjj.client.camera.VjjZoomClient`：`registerKeybind()`（默认 `Z` 按住变焦，滚轮改倍率，倍率 1.0–20.0，
  从 `VjjEditorState.settings().zoomFactor` 读初始值；变焦时 HUD 不需额外画面）；`modifyFov(float)` 返回最终 FOV
  （与强制变焦取更强的一个），FOV mixin 加到 `com.rpgcam.mixin.CameraMixin#rpgcam$modifyFov` 之后。
  日志：`[VjjNew] zoom-client factor=<f> mode=<key|forced|off>`（变化时）。
- `com.vjj.client.camera.VjjInputLock`：`onLock(LockPayload)` 记录状态；锁定时让移动/跳跃/攻击/使用/破坏失效
  （客户端 mixin 或 `LocalPlayer` tick 清零输入；放 `com.vjj.mixin.camera`）；日志
  `[VjjNew] lock-client locked=<b> reason=<...>`。
- **强制镜头**：`ClipPlayPayload.forceCamera=true` 时，即使没有关键帧也接管相机（关键帧镜头本身就是接管）；
  `force=false` 时保留玩家视角（用于“只广播演出”场景可选，默认 true）。

### 3.2 `com.vjj.dummy.VjjDummyServer` / 假人（anim-dev 实现，Lead 已留桩）

```java
public static void init();                    // 注册 EntityType vjjnew:dummy + 属性
public static void tick(MinecraftServer server);
public static void onPlayerQuit(ServerPlayer player);
public static void onServerStopping(MinecraftServer server);
public static int spawnForScene(ServerPlayer actor, String animId, boolean loop); // 在 actor 位置生成，返回实体 id，失败 -1
public static int clearScene(ServerPlayer actor);   // 清掉该 actor 关联的全部假人，返回数量
public static int countFor(ServerPlayer actor);
public static boolean playAnim(int dummyEntityId, String animId, boolean loop);
public static String handleAction(ServerPlayer op, VjjPayloads.EditorActionPayload action); // 未处理返回 null
public static List<String> listFor(ServerPlayer actor);
public static List<String> animationIds();    // = rpganim ContentStore.animationIds()（14 个）
```

- 假人实体：`com.vjj.dummy.VjjDummyEntity extends PathfinderMob`（无 AI、NoGravity、无敌、静音、persistenceRequired），
  同步数据（`SynchedEntityData`）：`animId(String)`、`animSeq(int)`（重播计数）、`ownerUuidMost/Least(long)`、
  `ownerName(String)`、`loop(boolean)`。
- 客户端渲染器：`HumanoidMobRenderer<VjjDummyEntity, AvatarRenderState, PlayerModel>`（`context.bakeLayer(ModelLayers.PLAYER)`），
  `extractRenderState` 里设置 `state.id = entity.getId()`（**必须**，rpganim 的 `PlayerModelMixin` 按 id 叠加骨骼）、
  解析 `PlayerSkin`（优先 `mc.getConnection().getPlayerInfo(uuid).getSkin()`，否则 `DefaultPlayerSkin.get(uuid)`），
  `state.skin = skin`，`state.showHat/showJacket/showLeftSleeve/... = true`；`getTextureLocation` 返回 `state.skin.body().texturePath()`。
- 假人动画：客户端 `com.vjj.client.dummy.VjjDummyClient` 在实体首次出现/`animSeq` 变化时，调用
  `ClientAnimState.startExternalAnim(entityId, animId, durationTicks, loop, "dummy")`（anim-dev 在
  `com.rpganim.client.ClientAnimState` 新增该方法：复用 ActiveAnim 时钟 + 采样，但**不**发 C2S DonePayload、
  **不**打印 `anim-end` 干扰原玩家动画断言，日志用 `[VjjNew]` 前缀）。若内容里没有该动作，退化为静止。
  渲染日志：`[VjjNew] dummy-anim entity=<id> anim=<id> duration=<n> loop=<b>`。
- `spawnForScene` 服务端日志：`[VjjNew] dummy-spawn player=<name> dummy=<id> anim=<id> pos=<x,y,z> result=ok`。
- 假人清理日志：`[VjjNew] dummy-clear player=<name> removed=<n>`。
- 假人动作切换日志：`[VjjNew] dummy-set-anim dummy=<id> anim=<id> loop=<b>`。
- `handleAction` 必须处理：`anim_play`（s1=animId，s2=目标玩家名，空=op 自己；调用 `RpgAnimApi.play`）、
  `anim_stop`、`dummy_spawn`（s1=animId，s2=目标）、`dummy_remove`、`dummy_anim`（s1=animId）。

### 3.3 公共层（Lead 已实现，实现者直接调用）

- `com.vjj.net.VjjPayloads`：`EditorOpenPayload/EditorDataPayload/EditorActionPayload/ClipPlayPayload/ClipStopPayload/ZoomPayload/LockPayload/UiEventPayload`。
- `com.vjj.net.VjjNet.send(ServerPlayer, payload)` / `broadcastAll(MinecraftServer, payload)`。
- `com.vjj.store.VjjConfigStore`：`get().settings()/clips()/clip(id)/mutableClip(id)/addClip(id,name)/removeClip(id)/save()/load()/clipsJson()/settingsJson()/parseClip(json)/clipJson(clip)`。
- `com.vjj.store.CameraClip` / `CameraKey` / `VjjSettings` / `VjjConfig`。
- `com.vjj.editor.VjjEditorService`：GUI 动作总路由（`save/reload/set_*/scene_play/scene_stop` 在公共层处理，
  其余转 `VjjCameraServer.handleAction` → `VjjDummyServer.handleAction`）。
- `com.vjj.scene.VjjSceneManager`：场景编排（镜头 + 隐藏 + 锁 + 假人；tick 收尾）。
- `com.vjj.client.state.VjjEditorState`：客户端面板数据（`clips()/settings()/anims()/targets()/status()/selectedClip()/selectedKeyIndex()/selectedAnimId()/targetName()`）。
- `com.vjj.client.VjjUiClient`：`send/sendAction/sendCapturePose/sendAnimPlay/sendDummySpawn/sendZoomTest/sendUiEvent/onEditorOpen/onEditorData`。
- `com.vjj.client.ui.VjjScreenBase`：自绘按钮基类（`button()/clearButtons()/panel()/label()/drawButtons()/onButton()/testClick()/testButtonIds()`）。
- `com.vjj.client.VjjClientNet`：S2C 分发（已经接好 `VjjCameraClient.onClipPlay/onClipStop`、`VjjZoomClient.onZoom`、`VjjInputLock.onLock`）。

## 4. 可视化界面（按钮 id 契约 + 交互）

服务端 `/vjj panel [hub|camera|anim|settings]`（OP）→ `EditorOpenPayload` → 客户端对应 Screen。
流：点击按钮 → `VjjUiClient` 发 `EditorActionPayload` → 服务端校验 OP/session → 处理 → 回 `EditorDataPayload` 整包快照 → 界面重绘。

| 界面 | 必须存在的按钮 id（`testButtonIds()` 可见） | 行为 |
|---|---|---|
| `VjjHubScreen`（Lead） | `hub:camera` `hub:anim` `hub:settings` `hub:scene` `hub:save` `hub:close` | 切页 / 播放演示场景 / 保存 |
| `CameraEditorScreen`（camera-dev） | `camera:capture` `camera:key_del` `camera:duration_minus` `camera:duration_plus` `camera:new` `camera:delete` `camera:play` `camera:stop` `camera:scene` `camera:save` `camera:back` `camera:close` | §3.1 动作 |
| `AnimBoardScreen`（anim-dev） | `anim:play` `anim:stop` `anim:dummy_spawn` `anim:dummy_remove` `anim:target` `anim:save` `anim:back` `anim:close`；动作列表行 `anim:select:<id>` | §3.2 动作 |
| `VjjSettingsScreen`（Lead） | `settings:force` `settings:lock` `settings:hide` `settings:dummy` `settings:broadcast` `settings:zoom_enabled` `settings:zoom_down` `settings:zoom_up` `settings:save` `settings:reload` `settings:back` `settings:close` | 设置开关 → `set_*` 动作；保存/重载 |

autotest 会依次打开 hub → camera → anim → settings，点击 `hub:camera`、`camera:capture`、`anim:dummy_spawn`、
`settings:save`，并截图 6 张（`vjj-autotest-01..06-*.png`）。**这些按钮 id / 截图路径不能改。**

## 5. 命令契约（Lead 已实现，视觉/E2E 共用）

见 `com.vjj.cmd.VjjCommands` 的 javadoc（`/vjj help` 可列）。关键：`panel/status/data/list/save/reload/new/delete/duration/
key add|addat|del|list/play/stop/scene/scenestop/lock/hide/zoom/anim list|play|stop/dummy spawn|remove|list|anim/demo`。

## 6. 验证契约（E2E，verifier 实现 `scripts\verify-vjj.ps1`）

真实专用服 25566 + 真实 loom 客户端（quickPlay 连服），RCON 驱动 + 双端日志断言 + 截图。必测场景（≥40 断言）：

1. 启动：双端 `provides` 生效（`/vjj status` 可用）、`[VjjNew] initialized`、client `[VjjNew] client initialized`。
2. 运镜编辑器（命令面）：`/vjj new e2e` → `/vjj key addat e2e 0 ...` → `/vjj duration e2e 120` → `/vjj save` →
   检查 `[VjjNew] config-save`、`config/vjjnew/config.json` 存在且 JSON 含 `e2e`、`clip-play ... keys=2`。
3. 播放：`/vjj play e2e` → 服务端 `clip-play`、客户端 `clip-start` + `clip-pose`（fov 数值变化=变焦）+ `clip-end`。
4. 锁行动：`/vjj lock on` → 服务端 `lock-set locked=true`、客户端 `lock-client locked=true`；玩家尝试移动后服务端位置不漂移
   （RCON `data get entity <player> Pos` 前后对比，容差 ≤0.5）。
5. 隐藏：`/vjj hide on` → `hide-set hidden=true`，`/vjj hide off` 复原。
6. 变焦：`/vjj zoom 3` → 客户端 `zoom-receive`/`zoom-client`（FOV 变小）；`/vjj zoom 1` 复原。
7. 假人：`/vjj dummy spawn emote_wave` → 服务端 `dummy-spawn ... result=ok`、客户端 `dummy-anim`、实体被注册（`/vjj dummy list`）；
   截图里能看到玩家假人（autotest 第三人称），`/vjj dummy remove` → `dummy-clear`。
8. 场景：`/vjj demo`（或 `/vjj scene demo`）→ `scene-start ... dummy=<id> hide=true lock=true` → 结束后
   `scene-end ... restored=1`，`/vjj status` 无残留。
9. 可视化界面：客户端 autotest `vjj.autotest=on` → `screen-open screen=hub/camera/anim/settings`、
   6 张 `vjj-autotest-*.png` 文件非空（bytes>0）、`ui-action`/`ui-event` 服务端日志、`settings:save` 后 `config-save`。
10. 收尾：停服/掉线后 `scene-end reason=...`、不崩、无异常堆栈；服务端日志无 `ERROR`/`Exception`。

## 7. 日志总表（E2E grep 用，实现必须打全）

服务端 `[VjjNew]`：`initialized`、`config-load/config-save/config-clip-add/config-clip-delete`、`editor-open`、`ui-action`、`ui-event`、
`clip-play/clip-stop`、`zoom-request`、`lock-set`、`hide-set`、`scene-start/scene-end`、`dummy-spawn/dummy-clear/dummy-set-anim`。

客户端 `[VjjNew]`：`client initialized`、`editor-open/editor-data`、`ui-open`、`ui-action-send`、`ui-test-click`、
`clip-start/clip-pose/clip-end`、`zoom-receive/zoom-client`、`lock-receive/lock-client`、`dummy-render/dummy-anim`。

autotest `[VjjNew-AUTOTEST]`：`enabled`、`in-world`、`command-sent`、`screen-open`、`click`、`shot-requested/shot-ok/shot-TIMEOUT`、`done`。

## 8. 已知约束

- 客户端与服务端都要装同一个 `vjj-new-1.0.0.jar`（自定义实体/相机接管都在客户端）。
- 旧 `questguide`/`npcmod` 仍可安装：本 mod `provides: rpgcam/rpgmodel/rpganim`，它们的 `isModLoaded` 软链会命中。
- 旧的 `/cinematic`、`/anim`、`/cutscene` 命令**必须继续可用**（整合不能破坏旧功能）。
- 同一时刻只跑一个游戏客户端；E2E 前先确认 25566/25575 未被占用。
- 不做数据包/热重载内容（动画内容仍是 rpganim 的 content.json；镜头配置在 config 文件里）。

## 9. 交付物

```
D:\MINE\vjjnew\build\libs\vjj-new-1.0.0.jar
D:\MINE\dist\vjj-new-1.0.0.jar（复制）
D:\MINE\vjjnew\README.md、docs\VJJ-DESIGN.md（本文件）、docs\e2e\...
D:\MINE\scripts\verify-vjj.ps1、D:\MINE\verification\VJJ-REPORT.md、D:\MINE\evidence\verify-vjj-<ts>\
```

## 10. 实现落点（交付时补充：实际类/文件与行为细节）

| 功能 | 实际落点 |
|---|---|
| 关键帧播放（服务端） | `com.vjj.camera.VjjCameraServer`（活动表 `PLAYS`、`startPlay`、`stop`、`clip-play/clip-stop` 日志；到点/掉线/停服收尾） |
| 关键帧播放（客户端） | `com.rpgcam.client.CameraDirector` 新增 clip 活动态（clip > cue；relative=锚点眼睛+偏移、world=绝对、linear/smooth/step、yaw 最短弧；`clip-start/clip-pose/clip-end`） |
| FOV 叠加顺序 | `com.rpgcam.mixin.CameraMixin#rpgcam$modifyFov` → `CameraDirector.modifyFov` 之后调 `VjjZoomClient.modifyFov`（clip FOV / 变焦倍率取更强） |
| 变焦键位/滚轮 | `com.vjj.client.camera.VjjZoomClient` + `com.vjj.mixin.camera.MouseScrollZoomMixin`（`MouseHandler.onScroll`；Z 按住、滚轮 ×1.12） |
| 锁死行动 | 服务端 `VjjCameraServer` 每 tick 位置/速度/朝向校正（`lock-correct` 每 10t 节流）；客户端 `VjjInputLock` 把 `LocalPlayer.input` 换成空 `ClientInput` + `com.vjj.mixin.camera.MinecraftInputLockMixin` HEAD 取消 `Minecraft.handleKeybinds`（攻击/使用/破坏） |
| 隐藏玩家 | 服务端 `ServerPlayer#setInvisible`（`hide-set`） |
| 玩家假人实体 | `com.vjj.dummy.VjjDummyEntity` + `ModEntities`（`vjjnew:dummy`，`noSave()`、无 AI、无敌、7 个同步字段） |
| 假人渲染 | `com.vjj.client.dummy.VjjDummyRenderer`：`HumanoidMobRenderer<VjjDummyEntity, AvatarRenderState, PlayerModel>`，`state.id` 供 rpganim 骨骼按 id 叠加，皮肤 `PlayerInfo.getSkin()` → `DefaultPlayerSkin` 回落 |
| 假人动画 | `com.vjj.client.dummy.VjjDummyClient` + `ClientAnimState.startExternalAnim(...)`（外部时钟，不发 C2S DonePayload，日志走 `[VjjNew] dummy-anim*`） |
| 场景编排 | `com.vjj.scene.VjjSceneManager`：hide+lock+dummy+clip，`duration+20t` 收尾；掉线/停服恢复（`scene-start/scene-end restored=1`） |
| 可视化面板 | 服务端 `com.vjj.editor.VjjEditorService` 会话/动作路由 + `*_Payload` 整包快照；客户端 `VjjHubScreen` / `CameraEditorScreen` / `AnimBoardScreen` / `VjjSettingsScreen`（自绘，`VjjScreenBase`） |
| 面板快捷键 | `com.vjj.client.VjjPanelKey`（默认 **F6**，发送 `/vjj panel`；服务端二次校验 OP） |
| 捕获机位语义 | GUI：客户端发**真实相机位置**（`GameRenderer.mainCamera().position()`），服务端对 relative 镜头换算成“相对眼睛偏移”；命令 `/vjj key add`（无坐标）：服务端模拟“背后 3 格 + 抬头 0.3”的第三人称机位；`/vjj key addat` 的显式坐标按镜头坐标空间直接存（relative=偏移，world=绝对） |
| 配置 | `VjjConfigStore` → `config/vjjnew/config.json`（原子替换写；`config-load/config-save` 日志含 bytes/keys） |
| 自动测试 | `com.vjj.client.VjjAutotest`（hub→camera→anim→settings，点 `hub:camera`/`camera:capture`/`anim:dummy_spawn`/`settings:save`，6 张截图 `vjj-autotest-01..06-*.png`） |

> 环境事实补充：构建必须 `.\gradlew.bat build --offline`（依赖已缓存在 Gradle cache；外部动画生态 jar 已离线放到
> `D:\MINE\testmods\`，工程 `libs/` 里有 EMF/ETF 的 compileOnly 副本）；Fabric 26.3 的 `fabric.mod.json`
> `provides` 只接受**字符串数组**（对象数组会被 loader 拒绝启动）；`GameProfile` 用 `name()`；`Entity` 无 `getServer()`（用 `level().getServer()`）；
> `ServerPlayer` 无 `hasPermissions(int)`（用 `Commands.LEVEL_GAMEMASTERS.check(player.permissions())`）。

## 11. 1.1.0 玩家动画优化（EMF / Touch'n Motion 兼容）契约

### 11.1 背景与依赖版本矩阵（真实 jar，已下载）

| 组件 | mod id | 版本 | 环境 | 说明 |
|---|---|---|---|---|
| Entity Model Features | `entity_model_features` | 3.3.12 (26.3 fabric) | client only | OptiFine CEM 玩家模型/动画管线，`EMFAnimationApi` 对外提供 hook |
| Entity Texture Features | `entity_texture_features` | 7.2.6 (26.3 fabric) | client only | EMF 必需前置 |
| EMF Compat Core | `emf_compat_core` | 2.3.2 (26.3 fabric) | client | Touch'n Motion 必需前置（否则 T&M preLaunch crash） |
| Touch'n Motion | `touch_n_motion` | 1.0.4 (26.3 fabric) | client/* | 与环境交互的程序化小动作；用 EMF hook 的 `onBipedPoseCopyEnd` 追加姿态 |
| Fresh Animations: Player Extension | 资源包 | v1.1 | client | 玩家基础动画资源包（`assets/minecraft/emf/cem/*`） |

### 11.2 实现契约

- **检测**：`com.vjj.compat.VjjCompat`（client）：`init()/tick(Minecraft)`、`emf()/etf()/tnm()/emfCore()/faPlayer()`、
  `emfApiVersion()`、`scanLine()`。资源包检测用 resource manager 里的 `minecraft:emf/cem/player.jem` 与
  `minecraft:emf/cem/a_player_movement.jpm`（FA Player Extension 的特征文件）。日志：
  `[VjjNew] compat-scan emf=<b> etf=<b> tnm=<b> emf_core=<b> fa_player=<b> emf_api=<n>`（启动一次，客户端命令 `/vjjc compat` 可重打）。
- **EMF 管线桥（真机校准后）**：`com.vjj.compat.EmfBridge`（无 EMF 类型引用，守卫/降级）+ `EmfBridgeImpl`（只含 EMF 类型）：
  首个 client tick 里 `EMFAnimationApi.registerAnimationHook(new Hook())` 只用于**校验 API/诊断**（`compat-emf-hook registered api=<n>`）；
  真正的姿态叠加走 `com.vjj.compat.EmfLatePose` + `com.vjj.compat.mixin.LivingEntityRendererEmfPoseMixin`
  （`vjj.compat.mixins.json`）：注入 `LivingEntityRenderer.submit` 里 `EntityModel.setupAnim` 调用之后（与 EMF 自己的
  `falseAnimation` 同一注入点，priority=900 < EMF 1000 → 后执行），由 `AvatarRenderState.id` 匹配 VJJ 本地动作
  （玩家与 VJJ 假人共用 PlayerModel），调 `ClientAnimState.applyToExternalModel(id, model, 1.0F, "emf-late")`。
  真机实测 EMF 的 `onBipedPoseCopyEnd` 在玩家动画路径上不触发（T&M 的提交时机在现代渲染路径也不同），
  因此不能靠该回调；`PlayerModelMixin` 在 EMF 动画模型上跳过 TAIL，避免被 EMF 覆盖/重复施加。
  日志：`compat-emf-hook registered api=<n>`、`anim-lane model=emf entity=<id>`（skip）、
  `anim-lane model=emf-late entity=<id>`、`compat-emf-late entity=<id> anim=<id> lane=emf-late`。
- **叠加权重/优先级**：`VjjSettings`（1.1.0 新字段，旧 config 自动补默认值）：
  `emfBridge=true`、`externalPosePriority=true`、`overlayWeight=1.0`（0.2–1.0）。GUI 设置页开关/± 按钮对应
  EditorAction `set_emf_bridge` / `set_pose_priority` / `set_overlay_weight`。
- **采样缓存**：`ClientAnimState` 按 (entity, anim, tick) 缓存 `AnimSampler.sample` 结果，渲染一帧多路径
  （EMF late 层 / 第三人称 / 假人）复用，日志 `anim-opt cache=hit|miss ... lane=emf-late`（节流）。
- **不装外部动画生态时**：与 1.0.0 行为完全一致（`compat-scan` 全 false、mixin 路径不变），原 333 条 E2E 断言必须保持 PASS。
- **验证**：`scripts\verify-vjj.ps1 -Compat`（真实 EMF/T&M/FA 客户端）：
  加载 4 个兼容 mod + FA 资源包、无 crash、`compat-scan` 全 true、`compat-emf-hook registered`、
  `/vjj anim play emote_wave` 后 `compat-emf-late ... lane=emf-late`、`anim-opt cache=hit`、
  `vjj-compat-idle.png` vs `vjj-compat-emote.png` 玩家区域像素差分 > 阈值（证明动作视觉生效）、GUI/假人/运镜仍可用。
  真机 smoke（1.1.0 候选）：EMF/ETF/T&M/EMF Compat Core + FA 包共存启动 OK，`compat-emf-late` 逐帧叠加、
  假人在 EMF 环境下也走 late 层（同一 mixin 命中），玩家区域 idle→emote 像素差 ≈8.2%（854×480 截图）。

### 11.4 第一人称动画（Punchy）兼容契约

- **定位**：VJJ-NEW 不实现第一人称手臂/手持物动画；战斗系统的第一人称动画直接复用 **Punchy 2.8g（FPA）**。
  VJJ 只做检测、状态展示和一个最小桥接，Punchy 未装时行为与之前完全一致（第一人称保持原版）。
- **检测**：`VjjCompat` 增加 `punchy` / `punchyVersion`（FabricLoader `isModLoaded` + mod metadata）；
  `compat-scan` 行追加 `punchy=<b> punchy_version=<ver>`，启动日志额外 `compat-punchy loaded version=<v> enabled=<b>`。
- **桥接**：`com.vjj.compat.PunchyBridge`（无 Punchy 类型引用，守卫）→ `PunchyBridgeImpl`（唯一引用 Punchy 类型）：
  - `isLoaded()` / `isEnabled()`（读 `punchy.config.PunchyConfig#isModEnabled()`）/ `version()`；
  - `playThirdPerson(clip)` → `punchy.client.animation.PunchyThirdPersonAnimationManager#playManual(clip)`，
    供假人/被观察玩家播放第三人称动作；第一人称攻击/挖掘/使用等由 Punchy 自己按玩家动作播放，VJJ 不插手。
  - 首个 client tick 初始化（等 Punchy 自己的 client entrypoint 完成）；Punchy 未装时不会加载 Impl 类。
- **客户端命令/GUI**：`/vjjc firstperson` 打印 `firstperson punchy=<b> version=<v> enabled=<b>`；
  `/vjjc compat` 与设置页底部显示相同状态（设置页第二行 `动画管线：… · Punchy 2.8g（开）`）。
- **打包**：一键整合包内置 `mods/punchy-2.8g-fabric-26.3.jar` + `config/punchy/punchy_config.json`
  + `config/punchy/triggerable-keybindings.json`；PCL 安装器同样会带过去。
- **真机验证**（整合包）：`Loading 55 mods` 含 `punchy 2.8g`；`compat-scan … punchy=true punchy_version=2.8g`；
  `compat-punchy loaded version=2.8g enabled=true`；第一人称 idle vs 按住左键攻击帧手臂区域像素差 ≈50%（容差 30），
  证明 Punchy 动画在 VJJ 相机/假人/EMF 场景下正常播放，无 crash。
- **后续战斗系统**：推荐用 Punchy 的动画资源包格式（`punchy/animations/*.json` + `punchy:tuning`）追加
  第一人称 combat clip；VJJ 战斗逻辑发攻击/使用事件即可自动触发；`PunchyBridge.playThirdPerson` 用于
  让其他观察者/假人同步第三人称动作。

### 11.5 字幕系统（UI 字幕 + 世界字幕）契约

- **UI 字幕**：服务端命令 `/subtitle ui|uistyle|say <targets> ...` → S2C `vjjnew:subtitle_ui`
  (`text, xFrac, yFrac, durationTicks, color, scale, shadow`) → 客户端 `VjjSubtitleHud`
  （`HudElementRegistry.addLast`）。位置是屏幕比例（0..1），每句独立；`durationTicks=0` 常驻；
  淡入淡出 220ms；`/subtitle clearui` → `vjjnew:subtitle_clear_ui`；断线时客户端自动清空。
- **世界字幕**：命令生成实体 `vjjnew:subtitle`（`VjjSubtitleEntity`，同步 text/color/scale/shadow，
  NBT 写盘；不可见/无碰撞/无重力/无 AI）。客户端 `VjjSubtitleRenderer`：
  按实体 yaw/pitch 固定旋转（非 billboard），用 `SubmitNodeCollector#submitText` 绘制文字，
  **不调用 `submitTextBackground`** → 没有原版黑框；全亮渲染。
- **多方位/背面可见**：字体面是单面的，因此每条世界字幕命令自动生成 **正面实体 + 背面实体**
  （背面 yaw+180、沿法线偏移 0.02 格），两个单面实体分别朝向前后 → 正面/背面都能读到正向文字；
  侧面看是平面边缘（文字平面渲染的正常表现）。
- **固定朝向**：实体 yaw/pitch 决定文字平面；玩家转动不影响。
- **命令**：独立根 `/subtitle` + `/vjj subtitle` 子树；ADMIN（LEVEL_GAMEMASTERS）；
  `world/here/wall/clearworld/list/clearui/say/ui/uistyle`；颜色支持名字与 16 进制（命令参数不能带 `#`）。
- **验证**：真实 Fabric 26.3 专服 + 客户端截图（`evidence\vjj-subtitle-20261026\`）：
  3 句 UI 字幕分别出现在 50%/80%、22%/18%、78%/65% 且无背景板；
  世界字幕 `双面字幕测试` 在正面/背面截图均可见、正向、无黑框；`clearworld/list` 回执正常。
  报告：`verification\VJJ-SUBTITLE-REPORT.md`。

### 11.6 特效库 / 奥术 UI（ArcaneVortex 风格）契约

- 参考 ArcaneVortex 0.6.8（1.20.1 Forge, ARR）：其渲染分层为 `CosmicRender`（自研 shader instance/uniform/glow）、
  `Content/Render`（DepthBuffer/EntityRenderer/OBJ/RainbowLightning）、`Content/UI`（Dialog/AnimationType/
  DialogRenderer/RoundedRectRenderer/GradientConfig）、`Content/Font/TextPopup`、`textures/gui/*`（glow/rune/...）。
  本项目**不安装/不调用/不复制其代码与贴图**，只按同样的视觉方向原创重建。
- 效果库：`com.vjj.fx.{FxType,FxPayload,FxCommands}` + `com.vjj.client.fx.{FxManager,ArcaneFxOverlay,ArcaneToastHud}`；
  服务端命令只广播 payload，客户端用原版粒子（DUST/END_ROD/ELECTRIC_SPARK/ENCHANT）按类型程序化发射，
  屏幕层用 HUD 绘制闪光/星云/星空/暗角。10 种类型：ring/vortex/beam/shockwave/aura/rune/flash/cosmic/burst/prism。
- 奥术 UI：`com.vjj.client.ui.ArcaneUi`（星云/星空背景、发光面板、旋转符文、渐变按钮、色板、分隔线）+
  `FxLabScreen`（10 特效预览 + 颜色/缩放/时长调参）。入口：`/vjj panel fx`（OP）/`/vjjc fxlab`；
  `/vjjc fxstop` 清空。程序化贴图：`assets/vjjnew/textures/ui/{nebula,rune,glow,star,spark}.png`（脚本生成，原创）。
- 多级后处理：`arcane_bloom` + `arcane_warp` 两个 post_effect 串联（各 effect→swap→main 两 pass，共 4 pass）；warp 做径向涟漪/边缘辉光/冷紫调；法阵结束两者一起移除。
- 后处理：`assets/vjjnew/post_effect/arcane_bloom.json` + `shaders/post/arcane_bloom.fsh`（原创 GLSL：径向泛光/色散/暗角/呼吸）；法阵实体对 64 格内玩家 `addPostEffect`，结束移除；实测 `posteffect list` 自动添加/清空。
- 大型法阵：`vjjnew:arcane_circle` 实体 + `ArcaneCircleRenderer`（4 层 1024 原创符文贴图、径向光带、8 向+中心光柱、悬浮碎晶、星屑；`submitCustomGeometry` + `energySwirl`/`lightning`）；`/vjj fx at <x> <y> <z> circle [color] [scale]`，默认 300t。
- 命令：`/vjj fx <type> [color] [scale]`、`/vjj fx at <x> <y> <z> <type> ...`、`/vjj fx player <targets> <type> ...`；
  颜色名或 16 进制（不带 `#`）。
- 宇宙之刃：`vjjnew:cosmic_sword`（16×16 原创星空剑贴图 + item definition/handheld model + 附魔光效组件 + 合成配方），客户端 `CosmicSwordClient` 星尘/电光光环、挥砍拖尾与命中爆裂；获取 `/give @s vjjnew:cosmic_sword`。
- 真机验证（`evidence\vjj-arcane-20261026\`）：FX Lab 正常打开（`ui-open screen=FxLabScreen`）、
  BEAM/COSMIC 等效果可见、客户端 `fx-receive` 日志、无异常；报告 `verification\VJJ-FX-REPORT.md`。
- 后续：可加 26.3 `RenderPipeline`/`PostChain` 原创 GLSL（黑洞/扭曲/后处理）逼近 ArcaneVortex 的 shader 画面，
  同时保持本命令/UI 契约不变。
