package com.vjj.client.dummy;

import com.vjj.dummy.VjjDummyEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * 假人渲染器：{@code vjjnew:dummy} → 原版玩家模型（{@link ModelLayers#PLAYER}）+ 目标玩家皮肤。
 *
 * <p>关键契约：</p>
 * <ul>
 *   <li>{@code extractRenderState} 里必须设置 {@code state.id = entity.getId()}：
 *       rpganim 的 {@code PlayerModelMixin} 在 {@code PlayerModel#setupAnim} TAIL 按
 *       {@code AvatarRenderState.id} 叠加骨骼动作，不设 id 假人不会动；</li>
 *   <li>{@code state.skin} = 解析出的目标玩家皮肤（同步来的 owner UUID → 在线皮肤 / 默认皮肤），
 *       sleeves/pants/jacket/hat 全部可见 → 看起来就是那个玩家；</li>
 *   <li>{@code getTextureLocation} 返回 {@code state.skin.body().texturePath()}。</li>
 * </ul>
 *
 * <p>动画本身不在这里做：{@code com.vjj.client.dummy.VjjDummyClient} 在客户端 tick 里按
 * 实体同步的 {@code animId/animSeq} 启动外部动作时钟，渲染时由 rpganim mixin 自动叠加到
 * 这个 {@link PlayerModel} 上（假人渲染器与玩家渲染器共用同一个 {@code PlayerModel} 类）。</p>
 */
public class VjjDummyRenderer extends HumanoidMobRenderer<VjjDummyEntity, AvatarRenderState, PlayerModel> {
    /** 皮肤解析兜底（理论上 skinFor 永远返回非 null）。 */
    private static final PlayerSkin FALLBACK_SKIN = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();

    public VjjDummyRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(VjjDummyEntity entity, AvatarRenderState state, float partialTick) {
        // 父类填充：位置/朝向/名字牌/装备/行走动画等基础渲染状态
        super.extractRenderState(entity, state, partialTick);
        // rpganim 骨骼动画按 entityId 查找本地动作时钟 —— 必须设置
        state.id = entity.getId();
        UUID owner = entity.getOwnerUuid();
        state.skin = VjjDummyClient.skinFor(owner);
        // 玩家皮肤各层全部可见（假人是「另一个玩家」，不是生物）
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showCape = false;
        state.showExtraEars = false;
        state.isSpectator = false;
        // 首次渲染日志（[VjjNew] dummy-render entity=<id> anim=<id>）
        VjjDummyClient.onDummyRendered(entity);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        PlayerSkin skin = state.skin == null ? FALLBACK_SKIN : state.skin;
        if (skin == null) {
            return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
        }
        return skin.body().texturePath();
    }
}
