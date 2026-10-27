package com.vjj.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vjj.VjjNew;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * VJJ-NEW 配置存储：<b>服务端权威</b>的一份 {@code config/vjjnew/config.json}
 * （OP 在可视化界面里编辑后保存；重进服务器/读档后仍在）。
 *
 * <p>读写都在服务端主线程调用（命令 / 网络回调都切到主线程），因此内部用 {@code synchronized}
 * 只为防御性并发（autotest / 日志线程可能读取快照）。</p>
 *
 * <p>日志契约（E2E 依赖）：</p>
 * <pre>
 * [VjjNew] config-load path=&lt;abs&gt; clips=&lt;n&gt; settings=&lt;settings.toString()&gt;
 * [VjjNew] config-save path=&lt;abs&gt; clips=&lt;n&gt; bytes=&lt;n&gt; keys=&lt;total&gt;
 * [VjjNew] config-clip-add id=&lt;id&gt; total=&lt;n&gt;
 * [VjjNew] config-clip-delete id=&lt;id&gt; total=&lt;n&gt; result=&lt;ok|missing&gt;
 * </pre>
 */
public final class VjjConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final VjjConfigStore INSTANCE = new VjjConfigStore();

    private VjjConfig config = new VjjConfig();
    private boolean loaded;

    private VjjConfigStore() {
    }

    public static VjjConfigStore get() {
        return INSTANCE;
    }

    /** 配置文件绝对路径（服务端 config 目录）。 */
    public Path filePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("vjjnew").resolve("config.json");
    }

    public synchronized VjjConfig config() {
        return this.config;
    }

    public synchronized VjjSettings settings() {
        return this.config.settings;
    }

    /** 全部镜头快照（只读副本，按保存顺序）。 */
    public synchronized List<CameraClip> clips() {
        List<CameraClip> copies = new ArrayList<>();
        for (CameraClip clip : this.config.clips) {
            copies.add(clip.copy());
        }
        return copies;
    }

    public synchronized List<String> clipIds() {
        List<String> ids = new ArrayList<>();
        for (CameraClip clip : this.config.clips) {
            ids.add(clip.id);
        }
        return ids;
    }

    /** 找到镜头（返回内部可变对象；只在服务端主线程用）。 */
    public synchronized CameraClip mutableClip(String id) {
        return this.config.findClip(id);
    }

    public synchronized CameraClip clip(String id) {
        CameraClip clip = this.config.findClip(id);
        return clip == null ? null : clip.copy();
    }

    /** 添加镜头；id 为空时自动生成。返回新镜头（内部对象，主线程用）。 */
    public synchronized CameraClip addClip(String id, String name) {
        String base = sanitizeId(id);
        if (base.isEmpty()) {
            base = "clip" + (this.config.clips.size() + 1);
        }
        String unique = base;
        int suffix = 2;
        while (this.config.findClip(unique) != null) {
            unique = base + "_" + suffix++;
        }
        CameraClip clip = new CameraClip(unique, name == null || name.isBlank() ? unique : name);
        clip.durationTicks = this.config.settings.defaultDurationTicks;
        clip.mode = "relative";
        clip.ensureUsable();
        this.config.clips.add(clip);
        VjjNew.LOGGER.info("[VjjNew] config-clip-add id={} total={}", clip.id, this.config.clips.size());
        return clip;
    }

    public synchronized boolean removeClip(String id) {
        CameraClip clip = this.config.findClip(id);
        if (clip == null) {
            VjjNew.LOGGER.info("[VjjNew] config-clip-delete id={} total={} result=missing", id,
                    this.config.clips.size());
            return false;
        }
        this.config.clips.remove(clip);
        VjjNew.LOGGER.info("[VjjNew] config-clip-delete id={} total={} result=ok", id, this.config.clips.size());
        return true;
    }

    /** 规范化 clip id：只允许 [a-z0-9_-]，其余字符变下划线，限长 48。 */
    public static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length() && builder.length() < 48; i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            builder.append(ok ? c : '_');
        }
        return builder.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 读 / 写
    // ─────────────────────────────────────────────────────────────────────

    /** 服务端启动时调用：文件不存在则创建默认配置（不覆盖已有文件）。 */
    public synchronized void load() {
        Path path = filePath();
        VjjConfig parsed = null;
        try {
            if (Files.isRegularFile(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                parsed = GSON.fromJson(json, VjjConfig.class);
                if (parsed == null) {
                    VjjNew.LOGGER.warn("[VjjNew] config-load path={} result=empty-json defaulting", path);
                }
            } else {
                VjjNew.LOGGER.info("[VjjNew] config-load path={} result=missing defaulting", path);
            }
        } catch (Throwable throwable) {
            VjjNew.LOGGER.error("[VjjNew] config-load path={} result=failed error={}", path, throwable.toString());
        }
        if (parsed == null) {
            parsed = new VjjConfig();
            parsed.clips.add(defaultClip(parsed.settings));
        }
        parsed.normalize();
        if (parsed.clips.isEmpty()) {
            parsed.clips.add(defaultClip(parsed.settings));
        }
        this.config = parsed;
        this.loaded = true;
        int keys = totalKeys(parsed);
        VjjNew.LOGGER.info("[VjjNew] config-load path={} clips={} keys={} settings={}",
                path.toAbsolutePath(), parsed.clips.size(), keys, parsed.settings);
        if (!Files.isRegularFile(path)) {
            save();
        }
    }

    /** 保存当前配置（原子替换）；返回是否成功。 */
    public synchronized boolean save() {
        Path path = filePath();
        try {
            this.config.normalize();
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this.config);
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            VjjNew.LOGGER.info("[VjjNew] config-save path={} clips={} bytes={} keys={}",
                    path.toAbsolutePath(), this.config.clips.size(), bytes, totalKeys(this.config));
            return true;
        } catch (Throwable throwable) {
            VjjNew.LOGGER.error("[VjjNew] config-save path={} result=failed error={}", path, throwable.toString());
            return false;
        }
    }

    /** 已 load 过（服务端启动后为 true）。 */
    public synchronized boolean isLoaded() {
        return this.loaded;
    }

    /** GUI 用：全部镜头 JSON（数组）。 */
    public synchronized String clipsJson() {
        return GSON.toJson(this.config.clips);
    }

    /** GUI 用：设置 JSON（对象）。 */
    public synchronized String settingsJson() {
        return GSON.toJson(this.config.settings);
    }

    /** 反序列化工具（客户端解析 EditorDataPayload 用）。 */
    public static CameraClip[] parseClips(String json) {
        if (json == null || json.isBlank()) {
            return new CameraClip[0];
        }
        CameraClip[] parsed = GSON.fromJson(json, CameraClip[].class);
        return parsed == null ? new CameraClip[0] : parsed;
    }

    public static VjjSettings parseSettings(String json) {
        if (json == null || json.isBlank()) {
            return new VjjSettings();
        }
        VjjSettings parsed = GSON.fromJson(json, VjjSettings.class);
        if (parsed == null) {
            parsed = new VjjSettings();
        }
        parsed.normalize();
        return parsed;
    }

    /** 反序列化单个镜头（客户端播放用）。 */
    public static CameraClip parseClip(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        CameraClip clip = GSON.fromJson(json, CameraClip.class);
        if (clip != null) {
            clip.normalize();
        }
        return clip;
    }

    /** 序列化单个镜头（服务端下发播放用）。 */
    public static String clipJson(CameraClip clip) {
        return GSON.toJson(clip);
    }

    private static int totalKeys(VjjConfig config) {
        int total = 0;
        for (CameraClip clip : config.clips) {
            total += clip.keys == null ? 0 : clip.keys.size();
        }
        return total;
    }

    /** 默认演示镜头：相对眼睛的环绕 + 推进 + 拉远，跑起来就能在 GUI 里看到三段变焦。 */
    public static CameraClip defaultClip(VjjSettings settings) {
        CameraClip clip = new CameraClip("demo", "默认演示镜头");
        clip.durationTicks = Math.max(120, settings.defaultDurationTicks);
        clip.mode = "relative";
        clip.keys.add(new CameraKey(0, 0.0, 0.6, 3.5, 0.0F, 0.0F, 70.0F, "smooth"));
        clip.keys.add(new CameraKey(60, 2.5, 1.2, 3.0, -30.0F, 5.0F, 55.0F, "smooth"));
        clip.keys.add(new CameraKey(120, 0.0, 0.8, 2.2, 0.0F, 0.0F, 40.0F, "smooth"));
        return clip;
    }
}
