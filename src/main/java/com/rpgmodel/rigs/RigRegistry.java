package com.rpgmodel.rigs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rpgmodel.RpgModel;
import com.rpgmodel.api.AttachPoint;
import com.rpgmodel.api.Rig;
import com.rpgmodel.api.RigBone;
import com.rpgmodel.api.RpgModelApi;
import com.rpgmodel.api.ValidationReport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rig 注册表：从 jar 内 classpath 资源 {@code /assets/rpgmodel/rigs.json} 加载骨骼模型定义，
 * 同时实现 {@link RpgModelApi.Backend} 供其它 mod 查询。
 *
 * <p>加载失败一律降级（记日志 + 空表），绝不抛到游戏主流程。</p>
 */
public final class RigRegistry implements RpgModelApi.Backend {
    /** rigs.json 的格式版本。 */
    public static final int FORMAT = 1;
    /** classpath 资源路径（dev 与生产一致）。 */
    public static final String RESOURCE_PATH = "/assets/rpgmodel/rigs.json";

    private static final RigRegistry INSTANCE = new RigRegistry();

    private final Map<String, Rig> rigs = new LinkedHashMap<>();
    private boolean loaded;

    private RigRegistry() {
    }

    public static RigRegistry get() {
        return INSTANCE;
    }

    // ───────────────────────── 加载 ─────────────────────────

    /** 从当前 mod 的 classpath 读取 rigs.json 并解析。 */
    public boolean loadFromClasspath() {
        try (InputStream in = RpgModel.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                RpgModel.LOGGER.error("[RpgModel] rigs resource missing path={}", RESOURCE_PATH);
                return false;
            }
            return load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            RpgModel.LOGGER.error("[RpgModel] rigs resource read failed path={}", RESOURCE_PATH, exception);
            return false;
        }
    }

    /**
     * 解析 rigs.json 文本。
     *
     * @param json JSON 文本
     * @return 是否至少成功加载一个 rig
     */
    public boolean load(String json) {
        rigs.clear();
        loaded = false;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int format = root.has("format") ? root.get("format").getAsInt() : FORMAT;
            if (format != FORMAT) {
                RpgModel.LOGGER.warn("[RpgModel] rigs format={} expected={} (best effort)", format, FORMAT);
            }
            JsonArray array = root.getAsJsonArray("rigs");
            if (array == null) {
                throw new IllegalStateException("missing rigs array");
            }
            for (JsonElement element : array) {
                try {
                    Rig rig = parseRig(element.getAsJsonObject());
                    if (rigs.putIfAbsent(rig.id(), rig) != null) {
                        RpgModel.LOGGER.error("[RpgModel] duplicate rig id={} skipped", rig.id());
                    }
                } catch (Exception exception) {
                    RpgModel.LOGGER.error("[RpgModel] rig skipped error={}", exception.getMessage());
                }
            }
            loaded = !rigs.isEmpty();
        } catch (Exception exception) {
            RpgModel.LOGGER.error("[RpgModel] rigs parse failed", exception);
            rigs.clear();
            loaded = false;
        }
        return loaded;
    }

    private static Rig parseRig(JsonObject object) {
        String id = requireString(object, "id");
        String nameKey = object.has("nameKey") ? object.get("nameKey").getAsString() : "rig.rpgmodel." + id;
        JsonArray boneArray = object.getAsJsonArray("bones");
        if (boneArray == null || boneArray.isEmpty()) {
            throw new IllegalArgumentException("rig " + id + " has no bones");
        }

        Map<String, String> parentByName = new LinkedHashMap<>();
        Map<String, float[]> pivotByName = new LinkedHashMap<>();
        Map<String, float[]> rotationByName = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (JsonElement element : boneArray) {
            JsonObject bone = element.getAsJsonObject();
            String name = requireString(bone, "name");
            if (parentByName.containsKey(name)) {
                throw new IllegalArgumentException("rig " + id + " duplicate bone " + name);
            }
            boolean hasParent = bone.has("parent") && !bone.get("parent").isJsonNull();
            parentByName.put(name, hasParent ? bone.get("parent").getAsString() : null);
            pivotByName.put(name, readVec3(bone, "pivot", 0.0F, 0.0F, 0.0F));
            rotationByName.put(name, readVec3(bone, "rotation", 0.0F, 0.0F, 0.0F));
            order.add(name);
        }

        // 父骨骼必须存在；沿 parent 链不能有环。
        for (String name : order) {
            String parent = parentByName.get(name);
            if (parent != null && !parentByName.containsKey(parent)) {
                throw new IllegalArgumentException("rig " + id + " bone " + name + " unknown parent " + parent);
            }
            Set<String> visited = new HashSet<>();
            String current = name;
            while (current != null && visited.add(current)) {
                current = parentByName.get(current);
            }
            if (current != null) {
                throw new IllegalArgumentException("rig " + id + " bone cycle at " + name);
            }
        }

        Map<String, List<String>> childrenByName = new LinkedHashMap<>();
        for (String name : order) {
            childrenByName.put(name, new ArrayList<>());
        }
        for (String name : order) {
            String parent = parentByName.get(name);
            if (parent != null) {
                childrenByName.get(parent).add(name);
            }
        }

        Map<String, RigBone> bones = new LinkedHashMap<>();
        for (String name : order) {
            float[] pivot = pivotByName.get(name);
            float[] rotation = rotationByName.get(name);
            bones.put(name, new RigBone(name, parentByName.get(name),
                    pivot[0], pivot[1], pivot[2],
                    rotation[0], rotation[1], rotation[2],
                    childrenByName.get(name)));
        }

        String rootBone = object.has("rootBone") && !object.get("rootBone").isJsonNull()
                ? object.get("rootBone").getAsString() : null;
        if (rootBone != null && !bones.containsKey(rootBone)) {
            throw new IllegalArgumentException("rig " + id + " unknown rootBone " + rootBone);
        }
        if (rootBone == null) {
            for (String name : order) {
                if (parentByName.get(name) == null) {
                    rootBone = name;
                    break;
                }
            }
        }

        List<AttachPoint> attachPoints = new ArrayList<>();
        JsonArray attachArray = object.getAsJsonArray("attachPoints");
        if (attachArray != null) {
            for (JsonElement element : attachArray) {
                JsonObject attach = element.getAsJsonObject();
                String attachId = requireString(attach, "id");
                String bone = requireString(attach, "bone");
                if (!bones.containsKey(bone)) {
                    throw new IllegalArgumentException("rig " + id + " attachPoint " + attachId + " unknown bone " + bone);
                }
                float[] offset = readVec3(attach, "offset", 0.0F, 0.0F, 0.0F);
                attachPoints.add(new AttachPoint(attachId, bone, offset[0], offset[1], offset[2]));
            }
        }

        return new Rig(id, nameKey, rootBone, List.copyOf(order), bones, attachPoints);
    }

    private static String requireString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing field " + key);
        }
        return object.get(key).getAsString();
    }

    private static float[] readVec3(JsonObject object, String key, float defX, float defY, float defZ) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return new float[]{defX, defY, defZ};
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array == null || array.size() < 3) {
            return new float[]{defX, defY, defZ};
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    // ───────────────────────── 统计 ─────────────────────────

    public int rigCount() {
        return rigs.size();
    }

    public int totalBoneCount() {
        int total = 0;
        for (Rig rig : rigs.values()) {
            total += rig.boneNames().size();
        }
        return total;
    }

    /** @return 日志用的 {@code humanoid:13, quadruped:7} 形式摘要 */
    public String describeRigs() {
        StringBuilder builder = new StringBuilder();
        for (Rig rig : rigs.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(rig.id()).append(':').append(rig.boneNames().size());
        }
        return builder.toString();
    }

    // ───────────────────────── RpgModelApi.Backend ─────────────────────────

    @Override
    public String version() {
        return RpgModel.version();
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public List<String> rigIds() {
        return List.copyOf(rigs.keySet());
    }

    @Override
    public boolean hasRig(String rigId) {
        return rigId != null && rigs.containsKey(rigId);
    }

    @Override
    public Rig getRig(String rigId) {
        return rigId == null ? null : rigs.get(rigId);
    }

    @Override
    public List<String> boneNames(String rigId) {
        Rig rig = getRig(rigId);
        return rig == null ? List.of() : rig.boneNames();
    }

    @Override
    public ValidationReport validateBones(String rigId, Collection<String> bones) {
        Collection<String> input = bones == null ? List.of() : bones;
        Rig rig = getRig(rigId);
        List<String> known = new ArrayList<>(input.size());
        List<String> unknown = new ArrayList<>();
        for (String bone : input) {
            if (rig != null && rig.bones().containsKey(bone)) {
                known.add(bone);
            } else {
                unknown.add(bone);
            }
        }
        ValidationReport report = new ValidationReport(input.size(), known, unknown);
        RpgModel.LOGGER.info("[RpgModel] validate rig={} checked={} unknown={}",
                rigId, report.checked(), report.unknownBones().size());
        return report;
    }

    @Override
    public List<AttachPoint> attachPoints(String rigId) {
        Rig rig = getRig(rigId);
        return rig == null ? List.of() : rig.attachPoints();
    }
}
