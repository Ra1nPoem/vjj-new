package com.rpganim.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * RpgAnim 的全部网络包（手写编解码；长度上限显式限制）。
 * <ul>
 *   <li>S2C：sync（内容同步）/ play（开始动作）/ stop（停止动作）/ cutscene（过场开始/结束）</li>
 *   <li>C2S：emote（客户端键位请求）/ done（客户端收尾回执，服务端只记日志）</li>
 * </ul>
 * 26.3 fabric-api 的 payload 类型注册在 {@link NetRegistration}（双端 common init 调用一次）。
 */
public final class ModPayloads {
    public static final String NAMESPACE = "rpganim";
    /** content.json 上限（远小于 1MB 包上限）。 */
    public static final int MAX_JSON = 1_048_576;
    public static final int MAX_DURATION = 6000;

    private static final int MAX_ID = 64;
    private static final int MAX_SOURCE = 32;
    private static final int MAX_REASON = 64;
    private static final int MAX_DETAILS = 160;

    private ModPayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static int clampDuration(int durationTicks) {
        if (durationTicks <= 0) {
            return -1;
        }
        return Math.min(durationTicks, MAX_DURATION);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：内容同步（玩家进服时发一次；json = content.json 原文）
    // ─────────────────────────────────────────────────────────────────────
    public record SyncPayload(String json, int animations, int cutscenes) implements CustomPacketPayload {
        public static final Type<SyncPayload> TYPE = new Type<>(id("sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.json() == null ? "" : payload.json(), MAX_JSON);
                    buf.writeVarInt(Math.max(0, payload.animations()));
                    buf.writeVarInt(Math.max(0, payload.cutscenes()));
                },
                buf -> new SyncPayload(buf.readUtf(MAX_JSON), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<SyncPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：开始/替换一个实体的动作
    // ─────────────────────────────────────────────────────────────────────
    public record PlayPayload(int entityId, String animId, int durationTicks, boolean loop, String source)
            implements CustomPacketPayload {
        public static final Type<PlayPayload> TYPE = new Type<>(id("play"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlayPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.entityId());
                    buf.writeUtf(payload.animId() == null ? "" : payload.animId(), MAX_ID);
                    buf.writeVarInt(clampDuration(payload.durationTicks()));
                    buf.writeBoolean(payload.loop());
                    buf.writeUtf(payload.source() == null ? "" : payload.source(), MAX_SOURCE);
                },
                buf -> {
                    int entityId = buf.readVarInt();
                    String animId = buf.readUtf(MAX_ID);
                    int duration = buf.readVarInt();
                    boolean loop = buf.readBoolean();
                    String source = buf.readUtf(MAX_SOURCE);
                    return new PlayPayload(entityId, animId, duration, loop, source);
                });

        @Override
        public Type<PlayPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：停止一个实体的动作
    // ─────────────────────────────────────────────────────────────────────
    public record StopPayload(int entityId, String animId, String reason) implements CustomPacketPayload {
        public static final Type<StopPayload> TYPE = new Type<>(id("stop"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StopPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.entityId());
                    buf.writeUtf(payload.animId() == null ? "" : payload.animId(), MAX_ID);
                    buf.writeUtf(payload.reason() == null ? "" : payload.reason(), MAX_REASON);
                },
                buf -> new StopPayload(buf.readVarInt(), buf.readUtf(MAX_ID), buf.readUtf(MAX_REASON)));

        @Override
        public Type<StopPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：过场开始/结束（客户端过场 HUD + autotest 截图用）
    // ─────────────────────────────────────────────────────────────────────
    public record CutscenePayload(String cutsceneId, String titleKey, int durationTicks,
                                  boolean starting, String cameraStyle) implements CustomPacketPayload {
        public static final Type<CutscenePayload> TYPE = new Type<>(id("cutscene"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CutscenePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.cutsceneId() == null ? "" : payload.cutsceneId(), MAX_ID);
                    buf.writeUtf(payload.titleKey() == null ? "" : payload.titleKey(), 160);
                    buf.writeVarInt(Math.max(0, Math.min(payload.durationTicks(), MAX_DURATION)));
                    buf.writeBoolean(payload.starting());
                    buf.writeUtf(payload.cameraStyle() == null ? "" : payload.cameraStyle(), MAX_ID);
                },
                buf -> new CutscenePayload(buf.readUtf(MAX_ID), buf.readUtf(160), buf.readVarInt(),
                        buf.readBoolean(), buf.readUtf(MAX_ID)));

        @Override
        public Type<CutscenePayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // C2S：客户端键位请求播放一个 emote 类动作
    // ─────────────────────────────────────────────────────────────────────
    public record EmoteRequestPayload(String animId) implements CustomPacketPayload {
        public static final Type<EmoteRequestPayload> TYPE = new Type<>(id("emote"));

        public static final StreamCodec<RegistryFriendlyByteBuf, EmoteRequestPayload> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeUtf(payload.animId() == null ? "" : payload.animId(), MAX_ID),
                buf -> new EmoteRequestPayload(buf.readUtf(MAX_ID)));

        @Override
        public Type<EmoteRequestPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // C2S：客户端动作结束回执（只用于服务端日志取证，不参与演算）
    // ─────────────────────────────────────────────────────────────────────
    public record DonePayload(int entityId, String animId, int ticksPlayed, boolean interrupted, String details)
            implements CustomPacketPayload {
        public static final Type<DonePayload> TYPE = new Type<>(id("done"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DonePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.entityId());
                    buf.writeUtf(payload.animId() == null ? "" : payload.animId(), MAX_ID);
                    buf.writeVarInt(Math.max(0, payload.ticksPlayed()));
                    buf.writeBoolean(payload.interrupted());
                    buf.writeUtf(payload.details() == null ? "" : payload.details(), MAX_DETAILS);
                },
                buf -> new DonePayload(buf.readVarInt(), buf.readUtf(MAX_ID), buf.readVarInt(),
                        buf.readBoolean(), buf.readUtf(MAX_DETAILS)));

        @Override
        public Type<DonePayload> type() {
            return TYPE;
        }
    }
}
