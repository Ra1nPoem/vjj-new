package com.vjj.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * VJJ-NEW 的全部自定义网络包（26.3：S2C 类型双端都要注册，否则静默丢包）。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>可视化界面的“编辑”走通用 {@link EditorActionPayload}（一个 C2S 包覆盖所有按钮动作），
 *       服务端回 {@link EditorDataPayload} 整包快照；这样 GUI 加按钮不需要加新包；</li>
 *   <li>演出走独立的 {@link ClipPlayPayload} / {@link ClipStopPayload}（带整段镜头 JSON，
 *       客户端可脱离服务端独立演算、按时长自行收尾）；</li>
 *   <li>变焦 / 锁行动各自一个小包，便于日志断言。</li>
 * </ul>
 */
public final class VjjPayloads {
    public static final String NAMESPACE = "vjjnew";
    /** 字符串上限（clipsJson 可能较大：20 段 × 10 关键帧 ≈ 12KB，留足余量）。 */
    public static final int MAX_JSON = 262144;
    public static final int MAX_TEXT = 256;
    public static final int MAX_DURATION = 6000;

    private VjjPayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：打开可视化面板（OP 专用；服务端已验证权限）
    // ─────────────────────────────────────────────────────────────────────
    /**
     * @param sessionId 本次编辑会话 id（服务端自增；客户端动作回包必须带回，防止旧界面操作新会话）
     * @param tab       打开后默认页签：{@code hub|camera|anim|settings}
     */
    public record EditorOpenPayload(int sessionId, boolean op, String tab) implements CustomPacketPayload {
        public static final Type<EditorOpenPayload> TYPE = new Type<>(id("editor_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditorOpenPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.sessionId());
                    buf.writeBoolean(payload.op());
                    buf.writeUtf(payload.tab() == null ? "hub" : payload.tab(), 32);
                },
                buf -> new EditorOpenPayload(buf.readVarInt(), buf.readBoolean(), buf.readUtf(32)));

        @Override
        public Type<EditorOpenPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：可视化面板整包数据（clips / settings / 可选动画 / 在线玩家 / 状态行）
    // ─────────────────────────────────────────────────────────────────────
    public record EditorDataPayload(int sessionId, String clipsJson, String settingsJson,
                                    String animsJson, String targetsJson, String status)
            implements CustomPacketPayload {
        public static final Type<EditorDataPayload> TYPE = new Type<>(id("editor_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditorDataPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.sessionId());
                    buf.writeUtf(payload.clipsJson() == null ? "[]" : payload.clipsJson(), MAX_JSON);
                    buf.writeUtf(payload.settingsJson() == null ? "{}" : payload.settingsJson(), MAX_JSON);
                    buf.writeUtf(payload.animsJson() == null ? "[]" : payload.animsJson(), 8192);
                    buf.writeUtf(payload.targetsJson() == null ? "[]" : payload.targetsJson(), 8192);
                    buf.writeUtf(payload.status() == null ? "" : payload.status(), MAX_TEXT);
                },
                buf -> new EditorDataPayload(buf.readVarInt(), buf.readUtf(MAX_JSON), buf.readUtf(MAX_JSON),
                        buf.readUtf(8192), buf.readUtf(8192), buf.readUtf(MAX_TEXT)));

        @Override
        public Type<EditorDataPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // C2S：可视化面板里的一个动作（按钮点击 / 拖滑块 / 捕获机位 / 播放）
    // ─────────────────────────────────────────────────────────────────────
    /**
     * @param action 动作 id（见 docs/VJJ-DESIGN.md §4；例如 clip_new / key_add / clip_play / set_lock / anim_play）
     * @param clipId 目标镜头 id（空串 = 不适用）
     * @param index  序号（关键帧下标 / 新时长 / 目标序号等，按 action 语义解释）
     * @param tick   时间点（关键帧 tick / 播放时长等）
     * @param x,y,z  坐标（捕获机位 / 世界坐标关键帧）
     * @param yaw    朝向 yaw（度）
     * @param pitch  朝向 pitch（度）
     * @param fov    视场角（度，变焦关键帧）
     * @param s1/s2  字符串参数（玩家名 / 动画 id / 动作附带文本）
     */
    public record EditorActionPayload(int sessionId, String action, String clipId, int index, int tick,
                                      double x, double y, double z, double yaw, double pitch, double fov,
                                      String s1, String s2) implements CustomPacketPayload {
        public static final Type<EditorActionPayload> TYPE = new Type<>(id("editor_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditorActionPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.sessionId());
                    buf.writeUtf(payload.action() == null ? "" : payload.action(), 64);
                    buf.writeUtf(payload.clipId() == null ? "" : payload.clipId(), 64);
                    buf.writeVarInt(payload.index());
                    buf.writeVarInt(payload.tick());
                    buf.writeDouble(payload.x());
                    buf.writeDouble(payload.y());
                    buf.writeDouble(payload.z());
                    buf.writeDouble(payload.yaw());
                    buf.writeDouble(payload.pitch());
                    buf.writeDouble(payload.fov());
                    buf.writeUtf(payload.s1() == null ? "" : payload.s1(), MAX_TEXT);
                    buf.writeUtf(payload.s2() == null ? "" : payload.s2(), MAX_TEXT);
                },
                buf -> new EditorActionPayload(buf.readVarInt(), buf.readUtf(64), buf.readUtf(64),
                        buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT)));

        @Override
        public Type<EditorActionPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：播放一段可编辑运镜（整段 JSON，客户端独立演算）
    // ─────────────────────────────────────────────────────────────────────
    public record ClipPlayPayload(String clipJson, int durationTicks, String label,
                                  boolean forceCamera, boolean lockAction, boolean hidePlayer,
                                  int anchorEntityId, String source) implements CustomPacketPayload {
        public static final Type<ClipPlayPayload> TYPE = new Type<>(id("clip_play"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClipPlayPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.clipJson() == null ? "" : payload.clipJson(), MAX_JSON);
                    buf.writeVarInt(Math.clamp(payload.durationTicks(), 1, MAX_DURATION));
                    buf.writeUtf(payload.label() == null ? "" : payload.label(), MAX_TEXT);
                    buf.writeBoolean(payload.forceCamera());
                    buf.writeBoolean(payload.lockAction());
                    buf.writeBoolean(payload.hidePlayer());
                    buf.writeVarInt(payload.anchorEntityId());
                    buf.writeUtf(payload.source() == null ? "" : payload.source(), 64);
                },
                buf -> new ClipPlayPayload(buf.readUtf(MAX_JSON),
                        Math.clamp(buf.readVarInt(), 1, MAX_DURATION), buf.readUtf(MAX_TEXT),
                        buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readUtf(64)));

        @Override
        public Type<ClipPlayPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：停止运镜（服务端兜底；客户端也会按时长自行结束）
    // ─────────────────────────────────────────────────────────────────────
    public record ClipStopPayload(String reason) implements CustomPacketPayload {
        public static final Type<ClipStopPayload> TYPE = new Type<>(id("clip_stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClipStopPayload> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeUtf(payload.reason() == null ? "" : payload.reason(), 64),
                buf -> new ClipStopPayload(buf.readUtf(64)));

        @Override
        public Type<ClipStopPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：强制变焦（服务端请求客户端把 FOV 压到 factor 倍；durationTicks<=0 表示常驻直到 unlock）
    // ─────────────────────────────────────────────────────────────────────
    public record ZoomPayload(double factor, int durationTicks, String reason) implements CustomPacketPayload {
        public static final Type<ZoomPayload> TYPE = new Type<>(id("zoom"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ZoomPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeDouble(Math.clamp(payload.factor(), 0.1, 20.0));
                    buf.writeVarInt(Math.clamp(payload.durationTicks(), -1, MAX_DURATION));
                    buf.writeUtf(payload.reason() == null ? "" : payload.reason(), 64);
                },
                buf -> new ZoomPayload(buf.readDouble(), buf.readVarInt(), buf.readUtf(64)));

        @Override
        public Type<ZoomPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2C：锁死/解锁玩家行动（客户端锁输入；服务端同时做位置校正）
    // ─────────────────────────────────────────────────────────────────────
    public record LockPayload(boolean locked, String reason) implements CustomPacketPayload {
        public static final Type<LockPayload> TYPE = new Type<>(id("lock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LockPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBoolean(payload.locked());
                    buf.writeUtf(payload.reason() == null ? "" : payload.reason(), 64);
                },
                buf -> new LockPayload(buf.readBoolean(), buf.readUtf(64)));

        @Override
        public Type<LockPayload> type() {
            return TYPE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // C2S：UI 事件（纯取证：哪个界面点了哪个按钮；服务端只记日志）
    // ─────────────────────────────────────────────────────────────────────
    public record UiEventPayload(String event, String detail) implements CustomPacketPayload {
        public static final Type<UiEventPayload> TYPE = new Type<>(id("ui_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UiEventPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.event() == null ? "" : payload.event(), 64);
                    buf.writeUtf(payload.detail() == null ? "" : payload.detail(), MAX_TEXT);
                },
                buf -> new UiEventPayload(buf.readUtf(64), buf.readUtf(MAX_TEXT)));

        @Override
        public Type<UiEventPayload> type() {
            return TYPE;
        }
    }
}
