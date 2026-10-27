package com.rpganim.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 动画内容包（content.json）：动作定义 + 过场定义。
 * <p>服务端从自己 jar 的 classpath 加载后，把 {@link #rawJson()} **原样**通过 {@code SyncPayload}
 * 同步给客户端，由客户端用同一套 {@link #parse(String)} 解析——内容只有一份事实来源。
 * <p>解析是纯逻辑，不依赖客户端类（专用服务器可安全加载）。
 */
public final class AnimContent {
    /** 内置内容在 jar 里的位置。 */
    public static final String RESOURCE_PATH = "/assets/rpganim/content.json";
    private static final int FORMAT = 1;
    private static final int MAX_DURATION = 6000;

    private final String rawJson;
    private final List<AnimDefinition> animations;
    private final List<CutsceneDefinition> cutscenes;
    private final Map<String, AnimDefinition> animationsById;
    private final Map<String, CutsceneDefinition> cutscenesById;

    private AnimContent(String rawJson, List<AnimDefinition> animations, List<CutsceneDefinition> cutscenes) {
        this.rawJson = rawJson;
        this.animations = List.copyOf(animations);
        this.cutscenes = List.copyOf(cutscenes);
        Map<String, AnimDefinition> byAnim = new LinkedHashMap<>();
        for (AnimDefinition definition : this.animations) {
            byAnim.put(definition.id(), definition);
        }
        Map<String, CutsceneDefinition> byCutscene = new LinkedHashMap<>();
        for (CutsceneDefinition definition : this.cutscenes) {
            byCutscene.put(definition.id(), definition);
        }
        this.animationsById = Map.copyOf(byAnim);
        this.cutscenesById = Map.copyOf(byCutscene);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 加载
    // ─────────────────────────────────────────────────────────────────────

    /** 空内容（加载失败时的降级值；命令仍可用但无动作）。 */
    public static AnimContent empty() {
        return new AnimContent("{\"format\":1,\"animations\":[],\"cutscenes\":[]}", List.of(), List.of());
    }

    /** 从本 mod 的 classpath 读内置 content.json（服务端启动时调用）。 */
    public static AnimContent loadBundled() {
        try (InputStream in = AnimContent.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new ContentException("bundled content missing: " + RESOURCE_PATH);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ContentException("failed to read " + RESOURCE_PATH, exception);
        }
    }

    /** 解析 content.json 文本；任何内容问题抛 {@link ContentException}（调用方 catch 后降级）。 */
    public static AnimContent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ContentException("content json is empty");
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int format = optInt(root, "format", FORMAT);
            if (format != FORMAT) {
                throw new ContentException("unsupported content format: " + format);
            }
            List<AnimDefinition> animations = new ArrayList<>();
            if (root.has("animations")) {
                for (JsonElement element : asArray(root.get("animations"), "animations")) {
                    animations.add(parseAnimation(asObject(element, "animation")));
                }
            }
            List<CutsceneDefinition> cutscenes = new ArrayList<>();
            if (root.has("cutscenes")) {
                for (JsonElement element : asArray(root.get("cutscenes"), "cutscenes")) {
                    cutscenes.add(parseCutscene(asObject(element, "cutscene")));
                }
            }
            Map<String, Boolean> seenAnimations = new LinkedHashMap<>();
            for (AnimDefinition definition : animations) {
                if (seenAnimations.put(definition.id(), Boolean.TRUE) != null) {
                    throw new ContentException("duplicate animation id: " + definition.id());
                }
            }
            Map<String, Boolean> seenCutscenes = new LinkedHashMap<>();
            for (CutsceneDefinition definition : cutscenes) {
                if (seenCutscenes.put(definition.id(), Boolean.TRUE) != null) {
                    throw new ContentException("duplicate cutscene id: " + definition.id());
                }
            }
            return new AnimContent(json, animations, cutscenes);
        } catch (ContentException exception) {
            throw exception;
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new ContentException("failed to parse content: " + exception.getMessage(), exception);
        }
    }

    private static AnimDefinition parseAnimation(JsonObject object) {
        String id = requireString(object, "id", "animation");
        if (!id.matches("[a-z0-9_./-]+")) {
            throw new ContentException("invalid animation id: " + id);
        }
        String nameKey = optString(object, "nameKey", "anim.rpganim." + id);
        String category = optString(object, "category", AnimDefinition.CATEGORY_EMOTE);
        int duration = optInt(object, "durationTicks", 20);
        if (duration < 1 || duration > MAX_DURATION) {
            throw new ContentException("animation " + id + " durationTicks out of range: " + duration);
        }
        boolean loop = optBoolean(object, "loop", false);
        int priority = optInt(object, "priority", 50);
        int blendIn = Math.max(1, optInt(object, "blendInTicks", 6));
        int blendOut = Math.max(1, optInt(object, "blendOutTicks", 8));
        String rig = optString(object, "rig", "humanoid");
        JsonObject bonesObject = asObject(object.get("bones"), "bones of " + id);
        if (bonesObject.size() == 0) {
            throw new ContentException("animation " + id + " has no bones");
        }
        Map<String, BoneTrack> bones = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : bonesObject.entrySet()) {
            String bone = entry.getKey();
            if (bone.isBlank()) {
                throw new ContentException("animation " + id + " has blank bone name");
            }
            bones.put(bone, parseBoneTrack(bone, asObject(entry.getValue(), "bone " + bone)));
        }
        return new AnimDefinition(id, nameKey, category, duration, loop, priority, blendIn, blendOut, rig, bones);
    }

    private static BoneTrack parseBoneTrack(String bone, JsonObject object) {
        Channel rotation = object.has("rotation") ? parseChannel(Channel.ROTATION, object.get("rotation")) : null;
        Channel position = object.has("position") ? parseChannel(Channel.POSITION, object.get("position")) : null;
        Channel scale = object.has("scale") ? parseChannel(Channel.SCALE, object.get("scale")) : null;
        if (rotation == null && position == null && scale == null) {
            throw new ContentException("bone " + bone + " has no channel");
        }
        return new BoneTrack(bone, rotation, position, scale);
    }

    private static Channel parseChannel(String kind, JsonElement element) {
        JsonArray array = asArray(element, kind);
        List<Keyframe> frames = new ArrayList<>(array.size());
        for (JsonElement frameElement : array) {
            JsonArray frame = asArray(frameElement, kind + " keyframe");
            if (frame.size() < 4) {
                throw new ContentException(kind + " keyframe needs [tick, x, y, z] (got " + frame.size() + ")");
            }
            float tick = frame.get(0).getAsFloat();
            float x = frame.get(1).getAsFloat();
            float y = frame.get(2).getAsFloat();
            float z = frame.get(3).getAsFloat();
            String ease = frame.size() >= 5 ? frame.get(4).getAsString() : Keyframe.LINEAR;
            frames.add(new Keyframe(tick, x, y, z, ease));
        }
        return new Channel(kind, frames);
    }

    private static CutsceneDefinition parseCutscene(JsonObject object) {
        String id = requireString(object, "id", "cutscene");
        String titleKey = optString(object, "titleKey", "cutscene.rpganim." + id);
        int duration = optInt(object, "durationTicks", 140);
        if (duration < 1 || duration > MAX_DURATION) {
            throw new ContentException("cutscene " + id + " durationTicks out of range: " + duration);
        }
        CutsceneCamera camera = null;
        if (object.has("camera")) {
            JsonObject cameraObject = asObject(object.get("camera"), "camera of " + id);
            camera = new CutsceneCamera(
                    requireString(cameraObject, "style", "camera style of " + id),
                    optInt(cameraObject, "at", 0),
                    Math.max(1, optInt(cameraObject, "durationTicks", duration)));
        }
        List<CutsceneStep> steps = new ArrayList<>();
        if (object.has("steps")) {
            for (JsonElement element : asArray(object.get("steps"), "steps of " + id)) {
                JsonObject step = asObject(element, "step of " + id);
                String animId = requireString(step, "anim", "step of " + id);
                steps.add(new CutsceneStep(optInt(step, "at", 0), animId, optBoolean(step, "loop", false)));
            }
        }
        steps.sort((a, b) -> Integer.compare(a.at(), b.at()));
        return new CutsceneDefinition(id, titleKey, duration, camera, steps);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────────────────────────────

    public String rawJson() {
        return rawJson;
    }

    public int byteSize() {
        return rawJson.getBytes(StandardCharsets.UTF_8).length;
    }

    public List<AnimDefinition> animations() {
        return animations;
    }

    public AnimDefinition animation(String id) {
        return id == null ? null : animationsById.get(id);
    }

    public List<CutsceneDefinition> cutscenes() {
        return cutscenes;
    }

    public CutsceneDefinition cutscene(String id) {
        return id == null ? null : cutscenesById.get(id);
    }

    public List<String> animationIds() {
        return List.copyOf(animationsById.keySet());
    }

    public List<String> cutsceneIds() {
        return List.copyOf(cutscenesById.keySet());
    }

    public int animationCount() {
        return animations.size();
    }

    public int cutsceneCount() {
        return cutscenes.size();
    }

    public boolean isEmpty() {
        return animations.isEmpty() && cutscenes.isEmpty();
    }

    /**
     * 每条动作覆盖到的骨骼集合（按 rig 分组），用于启动时用 rpgmodel 校验。
     *
     * @return rig id -> 骨骼名列表（稳定排序）
     */
    public Map<String, List<String>> boneNamesByRig() {
        Map<String, TreeSet<String>> grouped = new LinkedHashMap<>();
        for (AnimDefinition definition : animations) {
            TreeSet<String> bones = grouped.computeIfAbsent(definition.rig(), key -> new TreeSet<>());
            for (String bone : definition.bones().keySet()) {
                bones.add(bone);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // JSON 小工具
    // ─────────────────────────────────────────────────────────────────────

    private static String requireString(JsonObject object, String key, String context) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new ContentException("missing string '" + key + "' in " + context);
        }
        return object.get(key).getAsString();
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsInt();
    }

    private static boolean optBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    private static JsonArray asArray(JsonElement element, String context) {
        if (element == null || !element.isJsonArray()) {
            throw new ContentException("expected array for " + context);
        }
        return element.getAsJsonArray();
    }

    private static JsonObject asObject(JsonElement element, String context) {
        if (element == null || !element.isJsonObject()) {
            throw new ContentException("expected object for " + context);
        }
        return element.getAsJsonObject();
    }
}
