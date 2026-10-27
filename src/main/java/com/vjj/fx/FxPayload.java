package com.vjj.fx;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C：在世界坐标触发一次视觉效果。
 *
 * @param typeId         {@link FxType#id}
 * @param x,y,z          世界坐标
 * @param color          ARGB 颜色（客户端取 RGB 用于粒子；屏幕效果取 ARGB）
 * @param scale          整体缩放
 * @param durationTicks  持续 tick（0=用类型默认值）
 * @param seed           随机种子（让多次触发的形态不同/可复现）
 */
public record FxPayload(String typeId, double x, double y, double z,
                        int color, float scale, int durationTicks, long seed) implements CustomPacketPayload {
    public static final Type<FxPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vjjnew", "fx"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.typeId() == null ? "" : payload.typeId(), 32);
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeInt(payload.color());
                buf.writeFloat(payload.scale());
                buf.writeVarInt(payload.durationTicks());
                buf.writeLong(payload.seed());
            },
            buf -> new FxPayload(buf.readUtf(32), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readInt(), buf.readFloat(), buf.readVarInt(), buf.readLong()));

    @Override
    public Type<FxPayload> type() {
        return TYPE;
    }

    public FxType effect() {
        return FxType.byId(typeId);
    }
}
