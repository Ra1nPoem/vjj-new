package com.rpgcam.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * RpgCamera 的网络包。全部手写编码，长度上限显式限制（服务端发、客户端收；
 * 客户端回执走 C2S，仅用于服务端日志取证，不参与镜头演算）。
 */
public final class ModPayloads {
    public static final String NAMESPACE = "rpgcam";

    private ModPayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：开始一段镜头
    // ─────────────────────────────────────────────────────────────────────
    /**
     * @param styleId        样式 id
     * @param durationTicks  时长（tick）
     * @param anchorEntityId 目标实体 id（-1 表示不用实体锚点，用玩家或坐标）
     * @param hasAnchorPos   是否带固定坐标锚点
     * @param anchorX/Y/Z    固定坐标锚点
     * @param label          overlay 上显示的一行文字（例如任务名；空串表示用样式标题）
     * @param seed           抖动/漂移的随机种子（服务端定，保证同一次 cue 可复现）
     */
    public record CuePayload(String styleId,
                             int durationTicks,
                             int anchorEntityId,
                             boolean hasAnchorPos,
                             double anchorX, double anchorY, double anchorZ,
                             String label,
                             int seed) implements CustomPacketPayload {
        public static final int MAX_DURATION = 6000;

        public static final Type<CuePayload> TYPE = new Type<>(id("cue"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CuePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.styleId(), 32);
                    buf.writeVarInt(payload.durationTicks());
                    buf.writeVarInt(payload.anchorEntityId());
                    buf.writeBoolean(payload.hasAnchorPos());
                    buf.writeDouble(payload.anchorX());
                    buf.writeDouble(payload.anchorY());
                    buf.writeDouble(payload.anchorZ());
                    buf.writeUtf(payload.label() == null ? "" : payload.label(), 96);
                    buf.writeInt(payload.seed());
                },
                buf -> {
                    String styleId = buf.readUtf(32);
                    int duration = Math.clamp(buf.readVarInt(), 1, MAX_DURATION);
                    int anchorId = buf.readVarInt();
                    boolean hasPos = buf.readBoolean();
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    double z = buf.readDouble();
                    String label = buf.readUtf(96);
                    int seed = buf.readInt();
                    return new CuePayload(styleId, duration, anchorId, hasPos, x, y, z, label, seed);
                });

        @Override
        public Type<CuePayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：结束镜头（服务端权威兜底；正常结束时客户端先按 duration 自行结束）
    // ─────────────────────────────────────────────────────────────────────
    public record StopPayload(String styleId, String reason) implements CustomPacketPayload {
        public static final Type<StopPayload> TYPE = new Type<>(id("stop"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StopPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.styleId() == null ? "" : payload.styleId(), 32);
                    buf.writeUtf(payload.reason() == null ? "" : payload.reason(), 32);
                },
                buf -> new StopPayload(buf.readUtf(32), buf.readUtf(32)));

        @Override
        public Type<StopPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // C2S：客户端回执（镜头结束/被中断）
    // ─────────────────────────────────────────────────────────────────────
    public record DonePayload(String styleId, int ticksPlayed, boolean interrupted, String details)
            implements CustomPacketPayload {
        public static final Type<DonePayload> TYPE = new Type<>(id("done"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DonePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.styleId() == null ? "" : payload.styleId(), 32);
                    buf.writeVarInt(Math.max(0, payload.ticksPlayed()));
                    buf.writeBoolean(payload.interrupted());
                    buf.writeUtf(payload.details() == null ? "" : payload.details(), 160);
                },
                buf -> new DonePayload(buf.readUtf(32), buf.readVarInt(), buf.readBoolean(), buf.readUtf(160)));

        @Override
        public Type<DonePayload> type() {
            return TYPE;
        }
    }
}
