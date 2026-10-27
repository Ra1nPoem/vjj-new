package com.rpgmodel.client;

import com.rpgmodel.RpgModel;
import com.rpgmodel.api.Rig;
import com.rpgmodel.api.RpgModelApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 抓取本地玩家 {@link PlayerModel} 每帧的骨骼姿态（由 mixin 在 setupAnim TAIL 调用），供 HUD 显示。
 *
 * <p>humanoid rig 的骨骼名与 {@code PlayerModel} 网格名一一对应；这里维护名字 → public ModelPart 字段的映射，
 * 每次 setupAnim 后读取 {@code xRot/yRot/zRot}（弧度）与 {@code x/y/z}（模型像素）。</p>
 *
 * <p>日志契约（E2E grep）：首帧绑定成功时打 {@code [RpgModel] bind rig=humanoid model=PlayerModel bones=12 missing=0}。</p>
 */
public final class PoseCapture {
    /** 本地玩家使用的 rig id。 */
    public static final String RIG_ID = "humanoid";
    /** 绑定的模型类名（日志用）。 */
    public static final String MODEL_NAME = "PlayerModel";

    /** humanoid rig 里对应 PlayerModel 部件的骨骼名 → 部件访问器（顺序与 rig 一致）。 */
    private static final Map<String, Function<PlayerModel, ModelPart>> PARTS = new LinkedHashMap<>();

    static {
        PARTS.put("head", model -> model.head);
        PARTS.put("hat", model -> model.hat);
        PARTS.put("body", model -> model.body);
        PARTS.put("right_arm", model -> model.rightArm);
        PARTS.put("left_arm", model -> model.leftArm);
        PARTS.put("right_leg", model -> model.rightLeg);
        PARTS.put("left_leg", model -> model.leftLeg);
        PARTS.put("left_sleeve", model -> model.leftSleeve);
        PARTS.put("right_sleeve", model -> model.rightSleeve);
        PARTS.put("left_pants", model -> model.leftPants);
        PARTS.put("right_pants", model -> model.rightPants);
        PARTS.put("jacket", model -> model.jacket);
    }

    /** 单根骨骼的一帧姿态（旋转单位：弧度；位置单位：模型像素）。 */
    public record BonePose(String name, float xRot, float yRot, float zRot, float x, float y, float z) {
    }

    /** 本地玩家的一帧骨骼快照。 */
    public record Snapshot(int entityId, long gameTime, List<BonePose> bones, int bound, int missing) {
    }

    private static volatile Snapshot snapshot;
    private static volatile boolean bindLogged;

    private PoseCapture() {
    }

    /** mixin 在 {@code PlayerModel#setupAnim(AvatarRenderState)} 的 TAIL 调用。 */
    public static void capture(AvatarRenderState state, PlayerModel model) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || state == null || model == null) {
            return;
        }
        if (state.id != client.player.getId()) {
            return;
        }
        List<BonePose> bones = new ArrayList<>(PARTS.size());
        for (Map.Entry<String, Function<PlayerModel, ModelPart>> entry : PARTS.entrySet()) {
            ModelPart part = entry.getValue().apply(model);
            if (part == null) {
                continue;
            }
            bones.add(new BonePose(entry.getKey(), part.xRot, part.yRot, part.zRot, part.x, part.y, part.z));
        }
        int bound = bones.size();
        int missing = expectedBound() - bound;
        long gameTime = client.level == null ? 0L : client.level.getGameTime();
        snapshot = new Snapshot(state.id, gameTime, List.copyOf(bones), bound, Math.max(0, missing));
        if (!bindLogged) {
            bindLogged = true;
            RpgModel.LOGGER.info("[RpgModel] bind rig={} model={} bones={} missing={}",
                    RIG_ID, MODEL_NAME, bound, Math.max(0, missing));
        }
    }

    /** @return rig 里应该映射到模型部件的骨骼数（humanoid 去掉纯层级 root）；rig 未加载时为 0 */
    private static int expectedBound() {
        Rig rig = RpgModelApi.getRig(RIG_ID);
        if (rig == null) {
            return PARTS.size();
        }
        int expected = 0;
        for (String name : rig.boneNames()) {
            if (PARTS.containsKey(name)) {
                expected++;
            }
        }
        return expected;
    }

    /** @return 最新快照；本地玩家未渲染过 PlayerModel 时为 {@code null} */
    public static Snapshot snapshot() {
        return snapshot;
    }

    /** 离开世界时清空（避免 HUD 显示上一个世界的姿态）。 */
    public static void reset() {
        snapshot = null;
    }
}
