package com.vjj.fx;

import com.vjj.VjjNew;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** 奥术法阵实体类型注册：{@code vjjnew:arcane_circle}。 */
public final class ModFxEntities {
    public static final String PATH = "arcane_circle";

    public static final ResourceKey<EntityType<?>> ARCANE_CIRCLE_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(VjjNew.MOD_ID, PATH));

    public static final EntityType<ArcaneCircleEntity> ARCANE_CIRCLE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ARCANE_CIRCLE_KEY,
            EntityType.Builder.of(ArcaneCircleEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(32)
                    .updateInterval(2)
                    .noSave()
                    .build(ARCANE_CIRCLE_KEY));

    private static boolean initialized;

    private ModFxEntities() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        VjjNew.LOGGER.info("[VjjNew] fx entity registered id={}", ARCANE_CIRCLE_KEY.identifier());
    }
}
