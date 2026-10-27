package com.vjj.store;

/**
 * VJJ-NEW 全局演出设置（由 OP 在可视化界面里修改，保存到 config/vjjnew/config.json）。
 *
 * <p>这些开关决定“播放一段镜头/场景”时的默认行为：</p>
 * <ul>
 *   <li>{@link #forceCamera}：强制玩家镜头（接管相机，玩家不能自由转视角）；</li>
 *   <li>{@link #lockAction}：锁死玩家行动（服务端每 tick 校正位置 + 客户端锁输入）；</li>
 *   <li>{@link #hidePlayer}：隐藏玩家本体（服务端 invisibility），用于假人替代；</li>
 *   <li>{@link #spawnDummy}：播放动画时生成玩家假人替代本体；</li>
 *   <li>{@link #zoomEnabled} / {@link #zoomFactor}：客户端变焦（按键/滚轮，放大缩小）；</li>
 *   <li>{@link #emfBridge}（1.1.0）：检测到 Entity Model Features 时，VJJ 骨骼动作改走 EMF
 *       动画管线（在资源包动画 + Touch'n Motion 程序化姿态之后再叠加，避免被覆盖）；关掉则退回原生 mixin 叠加；</li>
 *   <li>{@link #externalPosePriority}（1.1.0）：VJJ 动作播放时对同一骨骼优先于 EMF/T&M 的外部姿态
 *       （默认开；关掉则只做柔和叠加）；</li>
 *   <li>{@link #overlayWeight}（1.1.0）：外部动画环境下 VJJ 骨骼偏移的叠加权重（0.2–1.0，可调柔化）。</li>
 * </ul>
 */
public final class VjjSettings {
    public boolean forceCamera = true;
    public boolean lockAction = true;
    public boolean hidePlayer = true;
    public boolean spawnDummy = true;
    /** 广播镜头：true = 所有在线玩家一起看这段运镜（过场），false = 只有目标玩家。 */
    public boolean broadcastCamera = false;
    public boolean zoomEnabled = true;
    public double zoomFactor = 4.0;
    /** 新建镜头默认时长（tick）。 */
    public int defaultDurationTicks = 200;
    /** 动画板块默认动作（假人替代 / 场景动画用）。 */
    public String defaultAnim = "emote_wave";
    /** 目标选择：{@code self} 或 {@code all}。 */
    public String targetMode = "self";
    /** 1.1.0：EMF 动画管线兼容桥（检测到 EMF 时把 VJJ 动作叠到资源包动画之上）。 */
    public boolean emfBridge = true;
    /** 1.1.0：VJJ 动作对外部（EMF/T&M）姿态的骨骼优先级。 */
    public boolean externalPosePriority = true;
    /** 1.1.0：外部动画环境下 VJJ 叠加权重（0.2–1.0）。 */
    public double overlayWeight = 1.0;

    public VjjSettings copy() {
        VjjSettings copy = new VjjSettings();
        copy.forceCamera = this.forceCamera;
        copy.lockAction = this.lockAction;
        copy.hidePlayer = this.hidePlayer;
        copy.spawnDummy = this.spawnDummy;
        copy.broadcastCamera = this.broadcastCamera;
        copy.zoomEnabled = this.zoomEnabled;
        copy.zoomFactor = this.zoomFactor;
        copy.defaultDurationTicks = this.defaultDurationTicks;
        copy.defaultAnim = this.defaultAnim;
        copy.targetMode = this.targetMode;
        copy.emfBridge = this.emfBridge;
        copy.externalPosePriority = this.externalPosePriority;
        copy.overlayWeight = this.overlayWeight;
        return copy;
    }

    public void normalize() {
        if (this.zoomFactor < 1.0) {
            this.zoomFactor = 1.0;
        }
        if (this.zoomFactor > 20.0) {
            this.zoomFactor = 20.0;
        }
        if (this.defaultDurationTicks < 20) {
            this.defaultDurationTicks = 20;
        }
        if (this.defaultDurationTicks > 6000) {
            this.defaultDurationTicks = 6000;
        }
        if (this.defaultAnim == null || this.defaultAnim.isBlank()) {
            this.defaultAnim = "emote_wave";
        }
        if (this.targetMode == null || (!"all".equals(this.targetMode) && !"self".equals(this.targetMode))) {
            this.targetMode = "self";
        }
        if (this.overlayWeight < 0.2) {
            this.overlayWeight = 0.2;
        }
        if (this.overlayWeight > 1.0) {
            this.overlayWeight = 1.0;
        }
    }

    @Override
    public String toString() {
        return "settings[forceCamera=" + this.forceCamera + " lockAction=" + this.lockAction
                + " hidePlayer=" + this.hidePlayer + " spawnDummy=" + this.spawnDummy
                + " broadcast=" + this.broadcastCamera + " zoom=" + this.zoomEnabled + "/" + this.zoomFactor
                + " defaultAnim=" + this.defaultAnim + " targetMode=" + this.targetMode
                + " emfBridge=" + this.emfBridge + " posePriority=" + this.externalPosePriority
                + " overlayWeight=" + this.overlayWeight + "]";
    }
}
