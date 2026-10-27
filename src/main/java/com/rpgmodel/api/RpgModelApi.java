package com.rpgmodel.api;

import java.util.Collection;
import java.util.List;

/**
 * rpgmodel 的只读查询 API（薄契约，编译期由其它 mod {@code compileOnly} 依赖）。
 *
 * <p>本类只做数据查询，不做任何副作用；当 rpgmodel 未安装（api jar 只在编译期出现）时，
 * 调用方不应加载本类。运行期 rpgmodel 加载完成后会通过 {@link #install(Backend)} 注入实现，
 * 在此之前所有查询返回空/降级值。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #getRig(String)} 对于不存在的 rig 返回 {@code null}；</li>
 *   <li>{@link #boneNames(String)} / {@link #attachPoints(String)} / {@link #rigIds()}
 *       从不返回 {@code null}，不存在时返回空 list；</li>
 *   <li>所有返回的集合都是不可变快照，调用方不要修改。</li>
 * </ul>
 */
public final class RpgModelApi {
    /** 本 mod 的 id（Fabric mod id / 命名空间）。 */
    public static final String MOD_ID = "rpgmodel";

    /**
     * 由 rpgmodel 实现注入的桥接口。
     *
     * <p>这是实现细节：普通调用方只使用 {@link RpgModelApi} 的静态方法即可，
     * 不要直接调用 {@link #install(Backend)}。</p>
     */
    public interface Backend {
        String version();

        boolean isLoaded();

        List<String> rigIds();

        boolean hasRig(String rigId);

        Rig getRig(String rigId);

        List<String> boneNames(String rigId);

        ValidationReport validateBones(String rigId, Collection<String> bones);

        List<AttachPoint> attachPoints(String rigId);
    }

    /** 注入的实现（在 rpgmodel 的 mod 初始化阶段设置一次）。 */
    private static volatile Backend backend;

    private RpgModelApi() {
    }

    /**
     * 注入实现（仅 rpgmodel 自身在初始化时调用；其它 mod 不要调用）。
     *
     * @param impl 实现；{@code null} 表示卸载（测试用）
     */
    public static void install(Backend impl) {
        backend = impl;
    }

    private static Backend backend() {
        return backend;
    }

    /** @return 版本号，默认 {@code "1.0.0"} */
    public static String version() {
        Backend impl = backend;
        return impl == null ? "1.0.0" : impl.version();
    }

    /** @return rig 数据是否已从 classpath 加载完成 */
    public static boolean isLoaded() {
        Backend impl = backend;
        return impl != null && impl.isLoaded();
    }

    /** @return 全部 rig id（有序，不可变）；未加载时为空 list */
    public static List<String> rigIds() {
        Backend impl = backend;
        return impl == null ? List.of() : impl.rigIds();
    }

    /** @return 是否存在该 rig */
    public static boolean hasRig(String rigId) {
        Backend impl = backend;
        return impl != null && impl.hasRig(rigId);
    }

    /** @return rig 定义；不存在返回 {@code null} */
    public static Rig getRig(String rigId) {
        Backend impl = backend;
        return impl == null ? null : impl.getRig(rigId);
    }

    /** @return rig 的骨骼名列表（有序）；rig 不存在返回空 list */
    public static List<String> boneNames(String rigId) {
        Backend impl = backend;
        return impl == null ? List.of() : impl.boneNames(rigId);
    }

    /**
     * 校验一组骨骼名是否都属于该 rig。
     *
     * @param rigId rig id
     * @param bones 待校验的骨骼名（可为空集合）
     * @return 校验报告（rig 不存在时 {@code knownBones} 为空、全部进 {@code unknownBones}）
     */
    public static ValidationReport validateBones(String rigId, Collection<String> bones) {
        Backend impl = backend;
        if (impl == null) {
            List<String> checked = bones == null ? List.of() : List.copyOf(bones);
            return new ValidationReport(checked.size(), List.of(), checked);
        }
        return impl.validateBones(rigId, bones);
    }

    /** @return rig 的挂点列表；无挂点/rig 不存在返回空 list */
    public static List<AttachPoint> attachPoints(String rigId) {
        Backend impl = backend;
        return impl == null ? List.of() : impl.attachPoints(rigId);
    }
}
