package com.rpganim.server;

import com.rpganim.RpgAnim;
import com.rpganim.anim.AnimDefinition;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的战斗触发：伤害/死亡 → 攻击/受击/倒地动作。
 *
 * <ul>
 *   <li>攻击者：{@code AFTER_DAMAGE} 且 {@code source.getEntity()} 是 {@link ServerPlayer} 且实际伤害 &gt; 0
 *       → 按主手物品选 {@code combat_slash}（剑）/ {@code combat_heavy}（斧）/ {@code combat_thrust}（其它）；
 *       每玩家最小间隔 {@link #ATTACK_MIN_INTERVAL_TICKS} tick（{@code source=attack}）。</li>
 *   <li>受害者：同一事件里受害者是 {@link ServerPlayer} → {@code combat_hit}，
 *       每玩家最小间隔 {@link #DAMAGE_MIN_INTERVAL_TICKS} tick（{@code source=damage}）。</li>
 *   <li>死亡：{@code AFTER_DEATH} 且实体是 {@link ServerPlayer} → 先停当前动作（reason=death）再播
 *       {@code defeat}（{@code source=death}）。</li>
 * </ul>
 *
 * <p>动作广播由 {@link AnimServerManager#play} 负责（同维度 64 格内）。
 * 日志为 {@code anim-trigger ...} 行（字段顺序固定，见 docs/contract.md）。</p>
 */
public final class CombatTriggers {
    private static final CombatTriggers INSTANCE = new CombatTriggers();

    public static final int ATTACK_MIN_INTERVAL_TICKS = 8;
    public static final int DAMAGE_MIN_INTERVAL_TICKS = 10;

    private static final String ANIM_ATTACK_SWORD = "combat_slash";
    private static final String ANIM_ATTACK_AXE = "combat_heavy";
    private static final String ANIM_ATTACK_OTHER = "combat_thrust";
    private static final String ANIM_HIT = "combat_hit";
    private static final String ANIM_DEFEAT = "defeat";

    private final Map<UUID, Long> lastAttack = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();

    private CombatTriggers() {
    }

    public static CombatTriggers get() {
        return INSTANCE;
    }

    public void registerEvents() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(this::afterDeath);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 事件入口
    // ─────────────────────────────────────────────────────────────────────

    private void afterDamage(LivingEntity entity, DamageSource source,
                             float baseDamageTaken, float damageTaken, boolean blocked) {
        if (blocked || damageTaken <= 0.0f) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getServer().getTickCount();
        Entity attacker = source.getEntity();

        if (attacker instanceof ServerPlayer attackerPlayer
                && !attackerPlayer.hasDisconnected() && !attackerPlayer.isSpectator()) {
            Long last = lastAttack.get(attackerPlayer.getUUID());
            if (last == null || now - last >= ATTACK_MIN_INTERVAL_TICKS) {
                lastAttack.put(attackerPlayer.getUUID(), now);
                ItemStack weapon = attackerPlayer.getMainHandItem();
                String animId = selectAttackAnimation(weapon);
                RpgAnim.LOGGER.info("[RpgAnim] anim-trigger player={} anim={} source=attack weapon={} target={}",
                        nameOf(attackerPlayer), animId, itemId(weapon), entityTypeId(entity));
                AnimServerManager.get().play(attackerPlayer, animId, -1, "attack");
            }
        }

        if (entity instanceof ServerPlayer victim && !victim.hasDisconnected()) {
            Long last = lastDamage.get(victim.getUUID());
            if (last == null || now - last >= DAMAGE_MIN_INTERVAL_TICKS) {
                lastDamage.put(victim.getUUID(), now);
                RpgAnim.LOGGER.info("[RpgAnim] anim-trigger player={} anim={} source=damage weapon={} target={}",
                        nameOf(victim), ANIM_HIT, itemId(source.getWeaponItem()), entityTypeId(attacker));
                AnimServerManager.get().play(victim, ANIM_HIT, -1, "damage");
            }
        }
    }

    private void afterDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer player) || player.hasDisconnected()) {
            return;
        }
        RpgAnim.LOGGER.info("[RpgAnim] anim-trigger player={} anim={} source=death weapon={} target={}",
                nameOf(player), ANIM_DEFEAT, itemId(source.getWeaponItem()), entityTypeId(source.getEntity()));
        AnimServerManager.get().stop(player, "death");
        AnimServerManager.get().play(player, ANIM_DEFEAT, -1, "death");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 选动作 / 日志字段工具
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 剑 → 挥砍，斧 → 重击，其它（空手/镐/物品）→ 突刺。
     * 若内容包里没有对应动作（自定义内容），退回第一个存在的候选，避免触发空动画。
     */
    private static String selectAttackAnimation(ItemStack stack) {
        String preferred = ANIM_ATTACK_OTHER;
        if (stack != null && !stack.isEmpty()) {
            Holder<Item> holder = stack.typeHolder();
            if (holder.is(ItemTags.SWORDS)) {
                preferred = ANIM_ATTACK_SWORD;
            } else if (holder.is(ItemTags.AXES)) {
                preferred = ANIM_ATTACK_AXE;
            }
        }
        return firstExisting(List.of(preferred, ANIM_ATTACK_SWORD, ANIM_ATTACK_AXE, ANIM_ATTACK_OTHER));
    }

    private static String firstExisting(List<String> candidates) {
        for (String candidate : candidates) {
            AnimDefinition definition = ContentStore.get().animation(candidate);
            if (definition != null) {
                return candidate;
            }
        }
        // 内容完全没有战斗动作时仍然返回首选 id，交给 AnimServerManager 打 anim-denied。
        return candidates.getFirst();
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    private static String entityTypeId(Entity entity) {
        if (entity == null) {
            return "none";
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "unknown" : id.toString();
    }

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
