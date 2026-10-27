package com.vjj.dummy;

import com.vjj.VjjNew;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 假人实体类型注册：{@code vjjnew:dummy}（{@code com.vjj.dummy.VjjDummyEntity}）。
 *
 * <p>26.3 生产端无混淆，直接对 official 名注册即可（不需要 refmap/中间名）。
 * 属性必须注册：{@code EntityType.create} 时 {@code Mob} 构造会读取默认属性，
 * 缺了会在生成实体时抛 “Default attributes not registered”。</p>
 *
 * <p>由 {@link VjjDummyServer#init()} 调用一次（{@code com.vjj.VjjNew} 的 common 入口已接好）。</p>
 */
public final class ModEntities {
    public static final String DUMMY_PATH = "dummy";

    /** 实体类型 key：{@code vjjnew:dummy}。 */
    public static final ResourceKey<EntityType<?>> DUMMY_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(VjjNew.MOD_ID, DUMMY_PATH));

    /** 假人实体类型（尺寸/眼睛高度与玩家一致，追踪范围覆盖演出距离）。 */
    public static final EntityType<VjjDummyEntity> DUMMY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            DUMMY_KEY,
            EntityType.Builder.of(VjjDummyEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    // 临时演出道具：不写盘（否则服务器自动存档会把假人带进下次启动，变成永久孤儿）
                    .noSave()
                    .build(DUMMY_KEY));

    private static boolean initialized;

    private ModEntities() {
    }

    /** 默认属性：20 血、不动（行为由 {@link VjjDummyEntity} 自己 tick 跟随 owner）。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.FOLLOW_RANGE, 0.0);
    }

    /** 注册默认属性（可重复调用，只有第一次生效）。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        FabricDefaultAttributeRegistry.register(DUMMY, createAttributes());
        VjjNew.LOGGER.info("[VjjNew] dummy entity registered id={}", DUMMY_KEY.identifier());
    }
}
