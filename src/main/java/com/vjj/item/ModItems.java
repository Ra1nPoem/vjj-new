package com.vjj.item;

import com.vjj.VjjNew;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;

/**
 * VJJ-NEW 物品注册。
 *
 * <p>{@code vjjnew:cosmic_sword}：宇宙材质之刃（参考 ArcaneVortex 的宇宙武器视觉，原创贴图/物品）。
 * 客户端 {@code CosmicSwordClient} 会在手持时生成星尘光环与挥砍拖尾。</p>
 */
public final class ModItems {
    public static final ResourceKey<Item> COSMIC_SWORD_KEY =
            ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(VjjNew.MOD_ID, "cosmic_sword"));

    public static final Item COSMIC_SWORD = Registry.register(
            BuiltInRegistries.ITEM,
            COSMIC_SWORD_KEY,
            new Item(new Item.Properties()
                    .setId(COSMIC_SWORD_KEY)
                    .sword(ToolMaterial.NETHERITE, 7.0F, -2.2F)
                    .rarity(Rarity.EPIC)
                    .fireResistant()
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .stacksTo(1)));

    private static boolean initialized;

    private ModItems() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        VjjNew.LOGGER.info("[VjjNew] item registered id={}", COSMIC_SWORD_KEY.identifier());
    }
}
