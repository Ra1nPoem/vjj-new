package com.vjj.subtitle;

import com.vjj.VjjNew;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * 世界字幕实体类型注册：{@code vjjnew:subtitle}。
 *
 * <p>26.3 生产端无混淆，直接注册 official 名；字幕实体不是 Mob，不需要默认属性表。</p>
 */
public final class ModSubtitleEntities {
    public static final String PATH = "subtitle";

    public static final ResourceKey<EntityType<?>> SUBTITLE_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(VjjNew.MOD_ID, PATH));

    public static final EntityType<VjjSubtitleEntity> SUBTITLE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            SUBTITLE_KEY,
            EntityType.Builder.of(VjjSubtitleEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(16)
                    .updateInterval(20)
                    .build(SUBTITLE_KEY));

    private static boolean initialized;

    private ModSubtitleEntities() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        VjjNew.LOGGER.info("[VjjNew] subtitle entity registered id={}", SUBTITLE_KEY.identifier());
    }
}
