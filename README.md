# VJJ-NEW

> GitHub: https://github.com/Ra1nPoem/vjj-new  ·  Release: https://github.com/Ra1nPoem/vjj-new/releases

# VJJ-NEW · RPG 演出整合版（Minecraft 26.3 / Fabric）

一个 jar 里整合工作区此前分别开发的三件套，并加上新的**可视化演出编辑**能力：

| 原有 | 在新 jar 里 |
|---|---|
| `rpgcam` 运镜（13 个内置样式、服务端权威、客户端接管相机、过场 HUD） | 源码原样保留（`/cinematic` 等命令继续可用） |
| `rpganim` 动画（14 个数据驱动玩家/战斗动作、骨骼驱动、过场时间轴） | 源码原样保留（`/anim`、`/cutscene` 继续可用） |
| `rpgmodel` 骨骼模型前置（rig/绑定/校验） | 源码原样保留 |

**VJJ-NEW 新增**：

1. **可编辑运镜**：关键帧镜头（位置/朝向/FOV/缓动/时长），OP 在可视化界面里新建、捕获当前机位、删帧、调时长、**保存**到 `config/vjjnew/config.json`。
2. **变焦 / 放大缩小**：客户端按住 `Z` 变焦（滚轮调倍率），服务端也可 `/vjj zoom` 强制变焦；关键帧的 FOV 通道就是镜头内变焦。
3. **强制玩家镜头**：播放时接管目标玩家相机（可广播给所有在线玩家）。
4. **锁死玩家行动**：服务端每 tick 位置校正 + 客户端锁输入（移动/跳跃/攻击/使用）。
5. **隐藏玩家**：服务端 invisibility。
6. **玩家假人替代**：播放动画时在目标位置生成 `vjjnew:dummy`（玩家模型 + 目标皮肤 + 动画骨骼），本体隐藏 + 锁行动。
7. **动画独立板块**：可视化界面里选动作/目标，直接播给真实玩家或生成假人播放。
8. **可视化 OP 面板**：`/vjj panel`（或 OP 按 `F6`）→ 主页 / 运镜编辑器 / 动画板块 / 设置与保存。
9. **玩家动画优化（1.1.0）**：与 **Entity Model Features (EMF) / Fresh Animations: Player Extension / Touch'n Motion** 兼容——
   检测到 EMF 时 VJJ 动作改走 EMF 动画管线（在资源包动画与 T&M 程序化姿态**之后**叠加，VJJ 表情/战斗动作优先可见）；
   设置页可调「EMF 管线兼容 / VJJ 动作优先于外部姿态 / 叠加权重」；动画采样按 tick 缓存减少每帧开销。

> 版本事实：Minecraft **26.3** + Fabric Loader **0.19.5+** + Fabric API **0.163.0+26.3** + **Java 25**。
> 整合后 mod id = `vjjnew`，`provides: rpgcam / rpgmodel / rpganim`，因此旧 `questguide`、`npcmod`
> 的 `isModLoaded` 软联动仍会命中（它们可以继续装在这一套旁边）。
> 1.1.0 的可选动画生态：EMF **3.3.12** / ETF **7.2.6** / EMF Compat Core **2.3.2** / Touch'n Motion **1.0.4** /
> Fresh Animations: Player Extension **v1.1**（不装这些时 VJJ 行为与 1.0.0 一致）。

---

## 1. 安装

1. 客户端与服务端 `.minecraft/mods/`（或服务器 `mods/`）都放**同一个** `vjj-new-1.1.0.jar`；
2. 确认同目录有 **Fabric API**（26.3 对应 `fabric-api-0.163.0+26.3.jar` 或更新），Fabric Loader ≥ 0.19.5、Java 25；
3. 不要再同时装旧的 `rpgcam/rpganim/rpgmodel`（会重复注册实体/网络类型；本 jar 已包含并提供它们）；
4. 想启用「玩家动画优化」时，客户端再加装（均只影响客户端外观）：
   `entity_model_features-3.3.12-26.3-fabric.jar` + `entity_texture_features-7.2.6-26.3-fabric.jar` +
   `emf_compat_core_fabric_26.3_2.3.2.jar` + `touch_n_motion_fabric_26.3_1.0.4.jar`，
   并把 `FA+Player-v1.1.zip` 放进 `resourcepacks/` 启用；服务端不需要装 EMF/ETF/T&M（T&M 装服务端也可，非必需）。
5. 战斗系统第一人称动画：客户端加 **Punchy**（`punchy-2.8g-fabric-26.3.jar`，**一键整合包已内置**）；
   VJJ 自动检测并把状态显示在 `/vjjc firstperson` 与设置页，未装时行为不变（见 §3.6）。

## 2. 可视化操作（OP）

```
/vjj panel            # 打开演出控制台（hub）
/vjj panel camera     # 直接打开运镜编辑器
/vjj panel anim       # 直接打开动画板块
/vjj panel settings   # 直接打开设置与保存
```

界面流程：主页 → 运镜编辑（左侧选镜头、右侧选关键帧、`捕获当前机位` 添加帧、`时长 ±`、`播放运镜`、`播放完整场景`、`保存配置`）
→ 动画板块（选动作/目标、`播放动作给目标`、`生成假人并播放`、`移除全部假人`）
→ 设置与保存（强制镜头 / 锁行动 / 隐藏玩家 / 假人替代 / 广播 / 变焦倍率 / `保存配置`）。

服务端会二次校验 OP 权限；客户端改界面不能提权。所有修改要 **保存** 才写入文件。

## 2.5 测试地图（1.1.0 更新）

整合包自带 `saves\VJJ-NEW 测试地图`，双击 `启动测试地图.bat` 直接进入；所有演示由命令方块 + 按钮驱动，**按钮只对最近玩家（@p）生效**。

- 南墙（z=10）三排（y=141/144/147）：运镜、变焦、锁行动、隐藏、假人、动画、面板等 27 个旧站点；
- 南墙顶排（y=150）：**特效库 / 奥术 UI / 宇宙之刃** 10 个站点：
  - 特效：`ring` 光环、`vortex` 星涡、`beam` 光柱、`shockwave` 冲击波、`aura` 灵气、`rune` 符文阵、`cosmic` 寰宇；
  - 大型奥术法阵（`vjj fx circle 8A5CFF 2.0`，15 秒，自动泛光/色散/屏幕扭曲）；
  - 奥术特效工坊（`/vjj panel fx`）；
  - 宇宙之刃（`/give @p vjjnew:cosmic_sword`）。
- 中央地板按钮（z=6）：**UI 字幕 / 世界字幕** 演示与清理：
  - `subtitle uistyle` 屏幕百分比定位（下中/左上）、`subtitle here` 悬空、`subtitle wall` 贴墙、`clearui`、`clearworld`；
- 中央地板按钮（z=2）：**后处理** 手动测试：`arcane_bloom`、`arcane_warp`、`posteffect clear`、清理法阵/字幕。
- 说明板：出生点附近 + 左侧信息栏；组标题、版本、命令速查都直接画在地图里。

- 地图内也带一份 `README-测试地图.txt`（整合包 `saves\VJJ-NEW 测试地图\` 下），含全部站点/命令速查。

> 开发环境重建设施：启动服务端（`run-server`，RCON 25578 / `vjjdev`）后运行
> `D:\MINE\scripts\build-vjj-testmap.ps1`，再按 `scripts\patch-testmap-leveldat-wrap.py` 修 level.dat、打包。

## 3. 命令（无 GUI 环境 / 演出脚本 / E2E）

```
/vjj help
/vjj status | list | data | save | reload
/vjj new <id> [name]                创建镜头
/vjj delete <id>                    删除镜头
/vjj duration <clip> <ticks>        改时长
/vjj key add <clip> <tick>          把执行者当前机位存成关键帧
/vjj key addat <clip> <tick> <x> <y> <z> <yaw> <pitch> <fov>
/vjj key del <clip> <index>
/vjj key list <clip>
/vjj play <clip> [target]           只放运镜
/vjj stop [target]
/vjj scene <clip> [target] [anim]   完整场景：镜头+隐藏本体+锁行动+假人动画
/vjj scenestop [target]
/vjj lock <on|off> [target]
/vjj hide <on|off> [target]
/vjj zoom <factor> [target] [ticks]
/vjj anim list | anim play <anim> [target] | anim stop [target]
/vjj dummy spawn [anim] [target] | dummy remove [target] | dummy list
/vjj demo [target]                  演示场景（demo 镜头 + emote_wave）
/vjj compat                         打印服务端侧动画生态版本与提示（客户端用 /vjjc compat）
```

旧的 `/cinematic`、`/anim`、`/cutscene` 命令树保持可用。

## 3.5 玩家动画优化（1.1.0，可选）

| 组件 | 实测版本 | 作用 | 装哪边 |
|---|---|---|---|
| Entity Model Features (EMF) | 3.3.12 | OptiFine CEM 玩家模型/动画管线 | 仅客户端 |
| Entity Texture Features (ETF) | 7.2.6 | EMF 必需前置 | 仅客户端 |
| EMF Compat Core | 2.3.2 | Touch'n Motion 必需前置（IK/模型空间） | 仅客户端 |
| Touch'n Motion | 1.0.4 | 与环境交互的程序化小动作（手扶墙/踩台阶/按钮/开门/骑乘/钓鱼/受惊…） | 客户端（服务端可选） |
| Fresh Animations: Player Extension | v1.1 | 玩家走路/待机基础动画资源包 | 客户端 `resourcepacks/` |

- **检测**：客户端启动日志 `[VjjNew] compat-scan emf=… etf=… tnm=… emf_core=… fa_player=… emf_api=…`；
  设置与保存页底部会显示检测结果与当前动画管线。
- **管线**：检测到 EMF 时，VJJ 在 EMF 的渲染管线里**最后一层**叠加动作——`LivingEntityRenderer.submit` 中
  `setupAnim` 之后（与 EMF 动画同一注入点、更低 mixin priority → 后执行，见 `vjj.compat.mixins.json`），
  因此表情/战斗动作不会被 EMF 资源包动画或 Touch'n Motion 程序化姿态覆盖；EMF 的 animation hook 只用于
  API 版本校验/诊断。没有 EMF 时退回原生 mixin 叠加（与 1.0.0 一致）。
- **可调项**（`/vjj panel settings`）：`EMF 管线兼容`、`VJJ 动作优先于外部姿态`、`叠加权重 ±`（0.2–1.0）。
- **客户端命令**：`/vjjc compat` 打印本机检测与当前管线；**服务端命令**：`/vjj compat`。
- **日志（E2E 取证）**：`compat-scan`、`compat-emf-hook registered api=<n>`、`anim-lane model=emf|emf-late|mixin`、
  `compat-emf-late entity=<id> anim=<id> lane=emf-late`、`anim-opt cache=hit|miss … lane=emf-late`。
- 不装这一套时行为与 1.0.0 完全一致（普通 E2E 333 断言不受影响）。
- 真机实测（候选 1.1.0）：这 5 件 + VJJ 在 MC 26.3 客户端共存启动无崩溃；第三人称 idle→emote 玩家区域
  像素差约 **8.2%**（854×480 截图），证明 EMF 环境下 VJJ 表情动作视觉生效；EMF 环境下 VJJ 假人也自动走
  late 层（同一注入点按 `AvatarRenderState.id` 匹配）。

## 3.6 第一人称动画（Punchy 联动，1.1.0 内置整合包）

| 组件 | 实测版本 | 作用 | 装哪边 |
|---|---|---|---|
| Punchy! (FPA) | 2.8g | 第一人称手臂/手持物动画：攻击、挖掘、使用、拉弓、装填、换手、手臂物理/粒子 | 仅客户端（**整合包已内置**） |

- **定位**：VJJ-NEW 不重复实现第一人称动画；战斗系统的第一人称部分直接复用 Punchy。VJJ 只做
  **检测 / 状态展示 / 最小桥接**，Punchy 未装时 VJJ 行为不变（第一人称保持原版）。
- **检测**：`compat-scan` 新增 `punchy=<b> punchy_version=<ver>`；客户端 `/vjjc compat` 与
  `/vjjc firstperson` 可查看，设置页底部显示 `Punchy 2.8g（开/关）`；启动日志 `compat-punchy loaded version=… enabled=…`。
- **桥接**（`com.vjj.compat.PunchyBridge` + `PunchyBridgeImpl`，守卫式反射/编译期依赖）：
  `isLoaded()` / `isEnabled()` / `version()` / `playThirdPerson(clip)`（调用 Punchy 的第三人称手动动画，
  给假人/被观察玩家用；第一人称攻击/挖掘等动画由 Punchy 自动播放，VJJ 不插手）。
- **后续战斗系统**：新增第一人称动作时，推荐用 Punchy 的资源包动画格式追加 clip（`punchy/animations/*.json`
  + `punchy:tuning` 配置），或给武器打 tag/属性让 Punchy 走对应 action；VJJ 战斗逻辑只需要发对应的
  攻击/使用事件。`PunchyBridge.playThirdPerson` 可用于让其他观察者/假人同步第三人称战斗动作。
- **真机验证**：整合包（含 Punchy 2.8g）启动 `Loading 55 mods` 含 `punchy 2.8g`；
  `compat-scan … punchy=true punchy_version=2.8g`、`compat-punchy loaded version=2.8g enabled=true`；
  第一人称 idle vs 攻击帧手臂区域像素差 **≈50%（容差 30）**，证明 Punchy 第一人称动画在 VJJ 场景下正常播放；
  与 VJJ 的相机/假人/EMF 管线共存无崩溃。

## 3.7 字幕系统（UI 字幕 + 世界字幕，1.1.0 新增）

两种形式，均由 OP/管理员用命令控制（`/subtitle` 或 `/vjj subtitle`）：

**① UI 字幕**：显示在玩家屏幕上，每句台词可各自指定位置/颜色/缩放（屏幕百分比 0-100），
无黑框背景板，自动淡入淡出：

```mcfunction
/subtitle ui <玩家> <x%> <y%> <时长t> <文本...>                    # 白色 1.0 倍
/subtitle uistyle <玩家> <x%> <y%> <时长t> <颜色> <倍率> <文本...>   # 指定颜色/缩放
/subtitle say <玩家> <文本...>                                      # 下方居中默认
/subtitle clearui [<玩家>]
```

**② 世界字幕**：贴墙 / 悬空 / 漂浮均可，固定位置与朝向、不随玩家转动；前后两面都渲染
（走到后面也能看到），且**不是原版 text_display、没有黑框背景板**：

```mcfunction
/subtitle world <x> <y> <z> <yaw> <pitch> <倍率> <颜色> <文本...>
/subtitle here <文本...>      # 玩家眼前 3 格、正对玩家
/subtitle wall <文本...>      # 视线 8 格内贴墙、正对玩家
/subtitle clearworld
/subtitle list
```

- 颜色：`white/gold/red/aqua/...` 或 16 进制（`FFD700`，命令里**不要写 `#`**）。
- UI 字幕时长 0 = 常驻直到 `clearui`；世界字幕写盘（服务器重启后保留）。
- 实现：`com.vjj.subtitle.*`（实体/命令/网络包）+ `com.vjj.client.subtitle.*`
  （HUD 与自定义渲染器 `SubmitNodeCollector#submitText`，不调用背景板绘制）；世界字幕为
  `vjjnew:subtitle` 实体，每条命令生成正面+背面两份单面实体，因此背面也正向可读。
- 报告与截图：`verification\VJJ-SUBTITLE-REPORT.md`、`evidence\vjj-subtitle-20261026\`。

## 3.8 特效库 + 奥术渲染 UI（模仿 ArcaneVortex，1.1.0 新增）

参考 ArcaneVortex（1.20.1 Forge，ARR）的「自研 shader + 星云/发光 + 圆角渐变 Dialog UI」视觉与分层，
在 26.3 Fabric 上**原创实现**（不调用/不复制其代码与素材）：

- **特效库**：10 种效果 `ring / vortex / beam / shockwave / aura / rune / flash / cosmic / burst / prism`；
  服务端 `/vjj fx ...` 广播，客户端 `FxManager` 按类型程序化生成粒子（彩色尘埃 + END_ROD + 电火花 + 附魔），
  `ArcaneFxOverlay` 负责全屏闪光/星云覆盖，`ArcaneToastHud` 显示符文吐司。
- **奥术 UI**：`ArcaneUi`（程序化星云/星空背景、发光面板、旋转符文、渐变按钮、色板）+ `FxLabScreen`
  奥术特效工坊（10 特效预览、颜色、缩放、时长）。
- **大型奥术法阵**：`/vjj fx at <x> <y> <z> circle [color] [scale]` 生成实体渲染的 30s 法阵（4 层符文环/光带/光柱/悬浮碎晶），不是粒子拼贴；
- **多级后处理**：法阵附近自动串联 `vjjnew:arcane_bloom`（泛光/色散/暗角）+ `vjjnew:arcane_warp`（屏幕涟漪扭曲/边缘辉光，原创 GLSL）；也可手动 `/posteffect add @s <id>`；
- **宇宙之刃**：`/give @s vjjnew:cosmic_sword`（16×16 星空剑贴图 + 附魔流光 + 合成配方；手持星尘光环 + 挥砍拖尾 + 命中爆裂）；
- **入口**：`/vjj panel fx`（OP）或 `/vjjc fxlab` 打开工坊；`/vjjc fxstop` 清空。
  命令：`/vjj fx <type> [color] [scale]`、`/vjj fx at <x y z> ...`、`/vjj fx player <targets> ...`。
- 贴图全部程序化生成（`scripts/gen-vjj-fx-textures.py`），原创；当前不使用自定义 GLSL（后续可加 26.3 RenderPipeline/PostChain）。
- 报告与截图：`verification\VJJ-FX-REPORT.md`、`evidence\vjj-arcane-20261026\`。

## 3.9 大型奥术法阵 / 多级后处理 / 宇宙之刃（1.1.0 追加）

### 大型奥术法阵（实体渲染，不是粒子拼贴）

```mcfunction
/vjj fx at <x> <y> <z> circle [color] [scale]
# 示例：在坐标处生成 15 秒、2 倍大小的法阵
/vjj fx at 0 139.05 0 circle 8A5CFF 2.0
```

- 新实体 `vjjnew:arcane_circle` + `ArcaneCircleRenderer`：4 层 1024×1024 原创符文贴图（外环符环/符文带/内层六芒星/径向光线）、
  旋转能量三角刃面、24 段外环刀片、8 向 + 中心 4 平面体积光柱、16 颗环绕悬浮碎晶、星屑；
- 服务端每 2t 广播环境尘埃/END_ROD 粒子；默认 300t（15s），结束后实体与后处理一起移除。

### 多级后处理（26.3 post_effect + 原创 GLSL）

- `vjjnew:arcane_bloom`：径向亮部泛光、色散、星云紫调、暗角、呼吸脉冲；
- `vjjnew:arcane_warp`：屏幕径向涟漪扭曲、色散、边缘能量辉光、冷紫调；
- 法阵对 **64 格内玩家自动同时添加两个后处理**，法阵结束自动移除；
- 手动命令：

```mcfunction
/posteffect add @s vjjnew:arcane_bloom
/posteffect add @s vjjnew:arcane_warp
/posteffect clear @s
```

### 宇宙之刃 `vjjnew:cosmic_sword`

- 16×16 原创星空剑贴图 + `item/handheld` 模型 + 附魔流光组件；
- 合成：下界合金剑 + 4 紫水晶碎片 + 末影珍珠（3×3 有序配方，`data/vjjnew/recipe/cosmic_sword.json`）；
- 客户端 `CosmicSwordClient`：手持腰部星尘/电光光环、挥砍紫青拖尾、命中星尘/END_ROD/电火花 + 暴击音效；
- 获取：`/give @s vjjnew:cosmic_sword`。

> 资源：`verification\VJJ-FX-REPORT.md`（§8-13）；截图 `evidence\vjj-arcane-20261026\`。

## 3.10 BloomSystem 风格渲染器（HUD/时间线/彩虹棱镜，1.1.0 追加）

> 目标栈 Veil/NebulaFX/Cascade 仅支持 1.21.1，当前包运行在 26.3；本节为 26.3 上的**架构等价实现**，
> 详见 `verification\VJJ-BLOOMSYSTEM-REPORT.md`。完整迁移需另建 1.21.1 工程并提供 ImGuiMC 依赖。

- **彩虹棱镜碎片**：法阵碎片提升到 48 颗（`BloomConfig.maxPrismShards`），逐颗 HSL 色相滚动 + 独立轨道/大小/漂浮；
- **HUD 彩虹文字 + 三角渐变面板**：`com.vjj.bloom.BloomHud`，检测 48 格内法阵后自动播放时间线：
  - 三角渐变面板 + 发光边缘 + 边缘星光；
  - 逐字符彩虹外发光标题「BLOOM SYSTEM / 棱镜法阵 · PRISM RITUAL」；
  - 阶段（展开/充能/爆发/维持/淡出）+ 彩虹进度条；
- **时间线**：`BloomTimeline`（Cascade 风格：阶段/缓动/回调/进度），无大量 if-else 状态机；
- **配置占位**：`BloomConfig`（bloom 强度、色散强度、粒子上限、HUD/总开关，预留 YACL 对接）；
- **后处理**：复用 `arcane_bloom` + `arcane_warp` 双 post_effect（亮部泛光/色散/屏幕扭曲/暗角），
  无 Canvas/FREX、无自建 FBO/VAO 链，符合 Sodium/Iris 兼容红线。

## 4. 配置

`config/vjjnew/config.json`（服务端；OP 保存/`/vjj save` 写入；重载 `/vjj reload` 或重启服务器生效）：

```json
{
  "format": 1,
  "settings": {
    "forceCamera": true, "lockAction": true, "hidePlayer": true, "spawnDummy": true,
    "broadcastCamera": false, "zoomEnabled": true, "zoomFactor": 4.0,
    "defaultDurationTicks": 200, "defaultAnim": "emote_wave", "targetMode": "self"
  },
  "clips": [
    {
      "id": "demo", "name": "默认演示镜头", "durationTicks": 200, "loop": false, "mode": "relative",
      "keys": [
        {"tick": 0,   "x": 0.0, "y": 0.6, "z": 3.5, "yaw": 0.0,  "pitch": 0.0, "fov": 70.0, "ease": "smooth"},
        {"tick": 60,  "x": 2.5, "y": 1.2, "z": 3.0, "yaw": -30.0, "pitch": 5.0, "fov": 55.0, "ease": "smooth"},
        {"tick": 120, "x": 0.0, "y": 0.8, "z": 2.2, "yaw": 0.0,  "pitch": 0.0, "fov": 40.0, "ease": "smooth"}
      ]
    }
  ]
}
```

- `mode: relative`：关键帧坐标是相对**锚点眼睛**的偏移；`world`：世界绝对坐标（捕获机位模式）。
- `fov` 越小画面越放大（变焦）；`ease`: `linear | smooth | step`。

## 5. 构建

```powershell
$env:JAVA_HOME='C:\Users\op\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon'
cd D:\MINE\vjjnew
.\gradlew.bat clean build --offline --console=plain
# 产物：build\libs\vjj-new-1.1.0.jar（+ -api.jar 兼容旧 compileOnly）
```

> 本机依赖已缓存，构建用 `--offline` 最稳（1.1.0 的 EMF/ETF compileOnly jar 在工程 `libs/` 下，已随工程提供）。

## 6. 验证（最终交付状态）

见 `docs/VJJ-DESIGN.md` §6 / §7 的 E2E 契约与日志总表；一条命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\MINE\scripts\verify-vjj.ps1
# 取证：evidence\verify-vjj-<ts>\{summary.md,results.json,server-latest.log,client-latest.log,screenshots\}
# 独立复验报告：verification\VJJ-REPORT.md
```

**最终独立复验（2026-10-25，VJJ 1.1.0）**：同一 jar 分别跑普通轮与 `-Compat` 兼容轮（真实 Fabric 26.3 专用服 25566 + 真实 loom 客户端
quickPlay 连服 + `-Dvjj.autotest=on`）：普通轮（无动画生态）**PASS 333/333**；兼容轮（EMF 3.3.12 + ETF 7.2.6 + T&M 1.0.4 + EMF Compat Core 2.3.2 + FA Player v1.1 真机）**PASS 410/410**（333 基础 + 77 兼容专项，含玩家区域像素差 3.28% > 1% 硬断言）：

| 轮次 | 证据目录 | 结果 |
|---|---|---|
| 普通轮（无动画生态） | `evidence\verify-vjj-20261025-192213\` | PASS 333/333 |
| 兼容轮（EMF+T&M+FA 真机） | `evidence\verify-vjj-compat-20261025-193002\` | PASS 410/410 |

- 交付 jar：`dist\vjj-new-1.1.0.jar`，size=**2200147**，sha256=`D2D61CB2078800FBEB7C3F11FA980A8F963AE79A55A4B152A0A21B36BE03D0F4`；
  脚本内 `clean build --offline` 可复现同一 sha（`install.server-jar-sha-match` PASS）。
- 覆盖：双端加载/provides、配置从零创建+落盘+reload 持久化、运镜 CRUD/播放/FOV 插值、锁行动+传送校正、隐藏、
  变焦、假人生成/动画/清理、完整场景、可视化面板 4 屏 + 16 个设置按钮 + C2S 通道、6 张 GUI 截图、断线/停服收尾；
  兼容轮另覆盖 4 mod 检测、late-pose 管线、像素差硬断言、`anim stop` 收尾与 mods/options 还原。
- 6 张截图与界面严格对应（`vjj-autotest-01-hub` … `06-saved`，1280×720，均经人眼目检）；
  兼容轮额外 2 张 `vjj-compat-idle/emote.png`（1280×720，玩家区域像素差 3.28%）。
- 1.1.0 设置页已按 640×360 逻辑像素做紧凑布局（原 16 按钮重叠/裁切问题已修复，见兼容轮截图）。

## 7. 已知限制

- 客户端/服务端都要装同一个 jar；只装一侧时演出不显示（服务端记降级日志，不崩）。
- 假人使用玩家皮肤时：皮肤优先取在线玩家列表（`PlayerInfo`），离线/查不到时回落默认皮肤；slim（Alex）皮肤用宽模型渲染（外观略宽）。
- 与光影/第三方玩家模型 mod 未做专项兼容测试；假人是自定义实体，不参与玩家战斗结算。
- 镜头编辑是服务端配置（不是数据包）；`loop` 字段已保留但目前长镜头按一次播放处理。
- 锁行动是“服务端位置校正 + 客户端输入锁”双保险；极端延迟下客户端可能看到轻微回弹（这是服务端权威的正常表现）。

## 8. 许可

MIT。
