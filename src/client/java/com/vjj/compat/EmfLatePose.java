package com.vjj.compat;

import com.rpganim.client.ClientAnimState;
import com.vjj.client.VjjUiClient;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

import java.util.HashMap;
import java.util.Map;

/**
 * EMF 动画的「末层姿态」注入点（1.1.0 真机校准后的最终方案）。
 *
 * <p><b>为什么需要它</b>：Entity Model Features 在 26.3 的渲染流程里是在
 * {@code LivingEntityRenderer.submit(...)} 内部、调用完 {@code model.setupAnim(state)} 之后
 * 才把资源包动画应用回模型（EMF 的 {@code falseAnimation} 注入在 {@code EntityModel.setupAnim}
 * 调用之后）。而 VJJ 原来的 {@code PlayerModelMixin} 注入在 {@code setupAnim} 的 TAIL——
 * 比 EMF 的动画更早，随后会被 EMF 覆盖。因此 EMF 环境下：
 * </p>
 * <ul>
 *   <li>{@code PlayerModelMixin} 跳过叠加（见那边的 EMF 分支）；</li>
 *   <li>由 {@code com.vjj.compat.mixin.LivingEntityRendererEmfPoseMixin} 在
 *       {@code submit} 的同一注入点（{@code setupAnim} 之后、优先级低于 EMF → 排在 EMF 之后执行）
 *       调用本类，把 VJJ 骨骼偏移叠到 EMF/Touch'n Motion 已摆好的姿态之上 → VJJ 动作最后生效。</li>
 * </ul>
 *
 * <p>用 {@link AvatarRenderState#id} 直接匹配 VJJ 本地动作（玩家与 VJJ 假人共用 PlayerModel 渲染），
 * 不需要再从 EMF 实体反查，也不依赖 EMF 的 biped pose copy 回调（真机验证该回调在玩家动画路径上不触发）。</p>
 */
public final class EmfLatePose {
    private static final int LOG_INTERVAL_TICKS = 8;
    private static final Map<Integer, Integer> LAST_LOG = new HashMap<>();
    private static boolean laneLogged;

    private EmfLatePose() {
    }

    /**
     * 在 EMF 完成 {@code setupAnim} 之后的 {@code submit} 阶段调用。
     *
     * @return 是否真的叠加了 VJJ 动作
     */
    public static boolean apply(LivingEntityRenderState state, EntityModel<?> model) {
        try {
            if (!(state instanceof AvatarRenderState avatarState)) {
                return false;
            }
            if (!(model instanceof PlayerModel playerModel)) {
                return false;
            }
            if (!EmfBridge.isModelEmfAnimated(playerModel)) {
                // 非 EMF 动画模型：走原来的 setupAnim TAIL 路径，避免重复叠加
                return false;
            }
            int entityId = avatarState.id;
            if (entityId <= 0) {
                return false;
            }
            if (!ClientAnimState.applyToExternalModel(entityId, playerModel, 1.0F, "emf-late")) {
                return false;
            }
            if (!laneLogged) {
                laneLogged = true;
                VjjCompat.logAnimLane("emf-late", entityId);
            }
            logThrottled(entityId);
            return true;
        } catch (Throwable throwable) {
            EmfBridge.logLateFailure(throwable);
            return false;
        }
    }

    private static void logThrottled(int entityId) {
        int tick = net.minecraft.client.Minecraft.getInstance().player == null
                ? 0 : net.minecraft.client.Minecraft.getInstance().player.tickCount;
        Integer last = LAST_LOG.get(entityId);
        if (last != null && tick - last < LOG_INTERVAL_TICKS) {
            return;
        }
        if (LAST_LOG.size() > 128) {
            LAST_LOG.clear();
        }
        LAST_LOG.put(entityId, tick);
        ClientAnimState.ActiveAnim active = ClientAnimState.active(entityId);
        VjjUiClient.LOGGER.info("[VjjNew] compat-emf-late entity={} anim={} lane=emf-late",
                entityId, active == null ? "-" : active.animId);
    }
}
