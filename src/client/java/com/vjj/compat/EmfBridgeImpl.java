package com.vjj.compat;

import com.vjj.client.VjjUiClient;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import org.slf4j.Logger;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.models.animation.state.EMFBipedPose;

/**
 * EMF 兼容桥的**实现类**（唯一允许引用 EMF 类型的类）。
 *
 * <p>只在 {@link EmfBridge} 确认 {@code entity_model_features} 已安装后才会被类加载；
 * EMF 未安装时本类所有引用都不会触发，VJJ 仍可正常启动。</p>
 *
 * <p><b>真机校准结论（26.3 + EMF 3.3.12）</b>：EFM 的 {@code onBipedPoseCopyEnd} 在玩家动画路径上
 * 不会触发（该回调只用于需要 biped pose 的图层/旧渲染分支），所以 VJJ 姿态不能靠它。
 * EMF 实际是在 {@code LivingEntityRenderer#submit} 内部、{@code setupAnim} 之后才应用资源包动画；
 * 因此 VJJ 的最终叠加走 {@link EmfLatePose}（{@code LivingEntityRendererEmfPoseMixin}，同一注入点、
 * 更低 mixin priority → 排在 EMF 之后）。本类保留 hook 注册用于：</p>
 * <ul>
 *   <li>校验 EMF API 可用性与版本（{@link #apiVersion()}）；</li>
 *   <li>兼容性探测 / GUI 状态（{@link EmfBridge#statusLine()} 里的 {@code hook=on}）；</li>
 *   <li>未来 EMF 版本若恢复 biped pose copy，可在回调里做诊断日志（当前不再施加姿态，避免与 late 层重复叠加）。</li>
 * </ul>
 */
final class EmfBridgeImpl {
    private static final Logger LOGGER = VjjUiClient.LOGGER;

    /** 强引用 hook 实例（EMF 内部也持有，这里避免任何 GC/生命周期疑虑）。 */
    private static Hook hook;

    private EmfBridgeImpl() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 初始化 / 查询
    // ─────────────────────────────────────────────────────────────────────

    /** EMF API 版本（EMF 3.3.12 → 11）。 */
    static int apiVersion() {
        return EMFAnimationApi.getApiVersion();
    }

    /** 注册 EMF 动画 hook（只作为可用性/未来兼容挂点，不在这里施加 VJJ 姿态）；返回是否注册成功。 */
    static boolean init() throws Exception {
        Hook instance = new Hook();
        boolean registered = EMFAnimationApi.registerAnimationHook(instance);
        if (registered) {
            hook = instance;
            LOGGER.info("[VjjNew] compat-emf-hook registered api={}", EMFAnimationApi.getApiVersion());
        } else {
            LOGGER.warn("[VjjNew] compat-emf-hook-fail api={} reason=register-returned-false",
                    EMFAnimationApi.getApiVersion());
        }
        return registered;
    }

    /** 该模型是否由 EMF 动画驱动（EMF 未装/未注册时不会被调用）。 */
    static boolean isModelEmfAnimated(Object model) {
        if (!(model instanceof EntityModel<?> entityModel)) {
            return false;
        }
        return EMFAnimationApi.isModelAnimatedByEMF(entityModel);
    }

    // ─────────────────────────────────────────────────────────────────────
    // EMF hook：只做诊断挂点（姿态叠加由 EmfLatePose 负责）
    // ─────────────────────────────────────────────────────────────────────

    private static final class Hook extends EMFAnimationApi.EMFAnimationHook {
        private int bipedCallbacks;

        @Override
        public void onBipedPoseCopyEnd(EMFBipedPose pose, HumanoidModel<?> model, boolean flag) {
            // 真机抽样：26.3 玩家动画路径上不触发；保留低频诊断，便于未来 EMF 版本变化时排查。
            if (bipedCallbacks < 4) {
                bipedCallbacks++;
                LOGGER.info("[VjjNew] compat-emf-biped-callback model={} api={}",
                        model == null ? "-" : model.getClass().getSimpleName(), EMFAnimationApi.getApiVersion());
            }
        }
    }
}
