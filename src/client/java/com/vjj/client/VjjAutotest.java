package com.vjj.client;

import com.vjj.client.ui.AnimBoardScreen;
import com.vjj.client.ui.CameraEditorScreen;
import com.vjj.client.ui.VjjHubScreen;
import com.vjj.client.ui.VjjScreenBase;
import com.vjj.client.ui.VjjSettingsScreen;
import com.vjj.compat.EmfBridge;
import com.vjj.compat.VjjCompat;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.io.File;
import java.util.ArrayDeque;

/**
 * 客户端自测（{@code -Dvjj.autotest=on} 时启用；正常游玩零副作用），给 E2E 提供：
 * <ul>
 *   <li>进世界 / 界面打开 / 按钮点击 / 截图成功的结构化日志；</li>
 *   <li>自动走一遍可视化面板：hub → 运镜编辑（捕获关键帧）→ 动画板块（生成假人）→ 设置（保存）；</li>
 *   <li>6 张真实客户端截图（每个界面一张 + 保存后一张），文件名与界面严格对应。</li>
 * </ul>
 *
 * <p>日志前缀固定为 {@code [VjjNew-AUTOTEST] }，截图名 {@code vjj-autotest-NN-*.png}。</p>
 *
 * <p><b>稳定性处理（真机踩过的坑）</b>：</p>
 * <ul>
 *   <li>截图是异步 IO：单槽 pending 会丢弃后续请求；这里 FIFO 队列逐张发，且<b>每次切页/点击前先等
 *       当前截图落盘</b>（{@code shotsIdle()}），保证 01-hub/02-camera/03-anim/04-anim-dummy/05-settings
 *       拍到的就是对应界面，不会拍到相邻页；</li>
 *   <li>GUI 按钮在 {@code renderContent} 里注册，刚 {@code setScreen} 的 tick 里
 *       {@code testButtonIds()} 为空（chunk mesh 上传重时可持续多个 tick）：所有“读按钮/点击”都做
 *       “非空 + 最长 N tick”重试，不靠固定 tick 数；</li>
 *   <li>截图本身是“上一帧”语义（26.3 {@link Screenshot}），因此请求后要等界面稳定再切页。</li>
 * </ul>
 */
public final class VjjAutotest {
    private static final String PREFIX = "[VjjNew-AUTOTEST] ";
    private static final int MAX_SHOT_WAIT = 100;
    /** 等界面按钮出现的上限（tick）；超过就记超时并继续流程，避免卡死。 */
    private static final int BUTTON_WAIT_MAX = 160;
    /** 等截图队列排空的上限（tick），超过就记超时并继续。 */
    private static final int SHOT_IDLE_WAIT_MAX = 200;

    private static boolean enabled;
    private static boolean inWorldLogged;
    private static int state;
    private static int wait;

    /** 待截图的文件名队列（FIFO）。 */
    private static final ArrayDeque<String> SHOT_QUEUE = new ArrayDeque<>();
    private static String pendingShot;
    private static int shotWait;
    /** 1.1.0 兼容阶段：进入前用户相机类型（结束后恢复）。 */
    private static CameraType savedCameraType;

    private VjjAutotest() {
    }

    public static void init() {
        String value = System.getProperty("vjj.autotest", "off");
        enabled = value != null && !"off".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value);
        if (enabled) {
            log("enabled property=" + value);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void tick(Minecraft minecraft) {
        if (!enabled || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!inWorldLogged) {
            inWorldLogged = true;
            log("in-world player=" + minecraft.player.getGameProfile().name()
                    + " pos=" + minecraft.player.blockPosition());
            state = 1;
            wait = 0;
        }
        pollScreenshot(minecraft);
        Screen screen = minecraft.gui.screen();
        switch (state) {
            case 1 -> {
                // 等世界稳定（加载地形）后再开界面
                wait++;
                if (wait >= 80) {
                    minecraft.getConnection().sendCommand("vjj panel hub");
                    log("command-sent cmd=\"vjj panel hub\"");
                    transition(2);
                }
            }
            case 2 -> {
                // hub：等界面 + 按钮就绪后截图（01 = hub 界面本身）
                wait++;
                if (screen instanceof VjjHubScreen hub && buttonsReady(hub)) {
                    log("screen-open screen=hub buttons=" + hub.testButtonIds() + " waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-01-hub.png");
                    transition(3);
                } else if (wait > BUTTON_WAIT_MAX) {
                    log("screen-timeout screen=hub waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-01-hub.png");
                    transition(3);
                }
            }
            case 3 -> {
                // 等 01 落盘后再点进运镜编辑器（保证截图拍到 hub）
                wait++;
                boolean clicked = false;
                if (screen instanceof VjjHubScreen hub && buttonsReady(hub) && shotsIdle() && wait >= 4) {
                    log("click button=hub:camera ok=" + hub.testClick("hub:camera"));
                    clicked = true;
                }
                if (clicked || wait > BUTTON_WAIT_MAX) {
                    if (!clicked) {
                        log("click-timeout button=hub:camera waited=" + wait);
                    }
                    transition(4);
                }
            }
            case 4 -> {
                // camera：就绪后排队 02，再点“捕获当前机位”（仍在同一界面）
                wait++;
                if (screen instanceof CameraEditorScreen camera && buttonsReady(camera) && wait >= 4) {
                    log("screen-open screen=camera buttons=" + camera.testButtonIds() + " waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-02-camera.png");
                    log("click button=camera:capture ok=" + camera.testClick("camera:capture"));
                    transition(5);
                } else if (wait > BUTTON_WAIT_MAX) {
                    log("screen-timeout screen=camera waited=" + wait);
                    transition(5);
                }
            }
            case 5 -> {
                // 等 02 落盘后再切动画板块
                wait++;
                if (shotsIdle() && wait >= 4) {
                    minecraft.getConnection().sendCommand("vjj panel anim");
                    log("command-sent cmd=\"vjj panel anim\"");
                    transition(6);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("shots-timeout before=anim waited=" + wait);
                    minecraft.getConnection().sendCommand("vjj panel anim");
                    log("command-sent cmd=\"vjj panel anim\"");
                    transition(6);
                }
            }
            case 6 -> {
                // anim：就绪后排队 03，再点“生成假人并播放”
                wait++;
                if (screen instanceof AnimBoardScreen anim && buttonsReady(anim) && wait >= 4) {
                    log("screen-open screen=anim buttons=" + anim.testButtonIds() + " waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-03-anim.png");
                    log("click button=anim:dummy_spawn ok=" + anim.testClick("anim:dummy_spawn"));
                    transition(7);
                } else if (wait > BUTTON_WAIT_MAX) {
                    log("screen-timeout screen=anim waited=" + wait);
                    transition(7);
                }
            }
            case 7 -> {
                // 等 03 落盘 + 假人动画起播，再排队 04（04 = 动画板块 + 假人）
                wait++;
                if (shotsIdle() && wait >= 10) {
                    requestShot(minecraft, "vjj-autotest-04-anim-dummy.png");
                    transition(8);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("shots-timeout before=anim-dummy waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-04-anim-dummy.png");
                    transition(8);
                }
            }
            case 8 -> {
                // 等 04 落盘后再切设置页
                wait++;
                if (shotsIdle() && wait >= 4) {
                    minecraft.getConnection().sendCommand("vjj panel settings");
                    log("command-sent cmd=\"vjj panel settings\"");
                    transition(9);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("shots-timeout before=settings waited=" + wait);
                    minecraft.getConnection().sendCommand("vjj panel settings");
                    log("command-sent cmd=\"vjj panel settings\"");
                    transition(9);
                }
            }
            case 9 -> {
                // settings：就绪后排队 05，再点“保存配置”
                wait++;
                if (screen instanceof VjjSettingsScreen settings && buttonsReady(settings) && wait >= 4) {
                    log("screen-open screen=settings buttons=" + settings.testButtonIds() + " waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-05-settings.png");
                    log("click button=settings:save ok=" + settings.testClick("settings:save"));
                    transition(10);
                } else if (wait > BUTTON_WAIT_MAX) {
                    log("screen-timeout screen=settings waited=" + wait);
                    transition(10);
                }
            }
            case 10 -> {
                // 等 05 落盘 + 保存回包刷新界面，再排队 06（06 = 保存后的设置页）
                wait++;
                if (shotsIdle() && wait >= 10) {
                    requestShot(minecraft, "vjj-autotest-06-saved.png");
                    transition(11);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("shots-timeout before=saved waited=" + wait);
                    requestShot(minecraft, "vjj-autotest-06-saved.png");
                    transition(11);
                }
            }
            case 11 -> {
                // 等 06 落盘再收界面，保证 6 张截图与界面一一对应
                wait++;
                if (shotsIdle() && wait >= 6) {
                    minecraft.gui.setScreen(null);
                    log("done screenshots=6 ui=closed shots_pending=" + pendingShot
                            + " queue=" + SHOT_QUEUE.size());
                    transition(12);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    minecraft.gui.setScreen(null);
                    log("done screenshots=6 ui=closed shots_timeout=1 pending=" + pendingShot);
                    transition(12);
                }
            }
            case 12 -> {
                // 1.1.0 玩家动画优化兼容阶段：检测到 EMF 或 Touch'n Motion 才跑，
                // 第三人称拍 idle → 发 /vjj anim play emote_wave → 拍 emote，供 E2E 做像素差分。
                boolean compat = VjjCompat.emf() || VjjCompat.tnm();
                if (!compat) {
                    log("compat-flow skipped reason=no-external-anim emf=" + VjjCompat.emf()
                            + " tnm=" + VjjCompat.tnm());
                    state = 20;
                } else {
                    savedCameraType = minecraft.options.getCameraType();
                    log("compat-flow start emf=" + VjjCompat.emf() + " tnm=" + VjjCompat.tnm()
                            + " etf=" + VjjCompat.etf() + " fa_player=" + VjjCompat.faPlayer()
                            + " bridge=" + EmfBridge.isHookRegistered() + " pipe=" + EmfBridge.statusLine());
                    transition(13);
                }
            }
            case 13 -> {
                // 切第三人称并等画面稳定后拍 idle 基线
                wait++;
                if (wait == 2) {
                    minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    log("compat-third-person type=THIRD_PERSON_BACK");
                }
                if (wait >= 14) {
                    requestShot(minecraft, "vjj-compat-idle.png");
                    transition(14);
                }
            }
            case 14 -> {
                // idle 落盘后发表情动作（玩家需为 OP；由 E2E 在进世界后 op）
                wait++;
                if (shotsIdle() && wait >= 4) {
                    minecraft.getConnection().sendCommand("vjj anim play emote_wave");
                    log("compat-emote-cmd cmd=\"vjj anim play emote_wave\"");
                    transition(15);
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("shots-timeout before=compat-emote waited=" + wait);
                    minecraft.getConnection().sendCommand("vjj anim play emote_wave");
                    log("compat-emote-cmd cmd=\"vjj anim play emote_wave\"");
                    transition(15);
                }
            }
            case 15 -> {
                // 等动作起播并到达幅度峰值帧附近再拍 emote
                wait++;
                if (wait >= 14) {
                    requestShot(minecraft, "vjj-compat-emote.png");
                    transition(16);
                }
            }
            case 16 -> {
                wait++;
                if (shotsIdle() && wait >= 6) {
                    if (savedCameraType != null) {
                        minecraft.options.setCameraType(savedCameraType);
                    }
                    log("compat-flow done shots=2 pipe=" + EmfBridge.statusLine());
                    state = 20;
                } else if (wait > SHOT_IDLE_WAIT_MAX) {
                    log("compat-flow done shots=2 timeout=1");
                    state = 20;
                }
            }
            default -> {
                // 完成
            }
        }
    }

    private static void transition(int next) {
        state = next;
        wait = 0;
    }

    /** 界面按钮是否已经注册好（Screen 的按钮在 renderContent 里注册，首帧前为空表）。 */
    private static boolean buttonsReady(VjjScreenBase screen) {
        return screen != null && !screen.testButtonIds().isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 截图队列（grab 取“上一帧” + 异步 IO；轮询文件大小判定完成后再发下一张）
    // ─────────────────────────────────────────────────────────────────────

    private static void requestShot(Minecraft minecraft, String fileName) {
        if (fileName.equals(pendingShot) || SHOT_QUEUE.contains(fileName)) {
            return;
        }
        SHOT_QUEUE.addLast(fileName);
        log("shot-queued=" + fileName + " queue=" + SHOT_QUEUE.size());
        pumpShots(minecraft);
    }

    private static void pumpShots(Minecraft minecraft) {
        if (pendingShot != null) {
            return;
        }
        String fileName = SHOT_QUEUE.pollFirst();
        if (fileName == null) {
            return;
        }
        File file = shotFile(minecraft, fileName);
        if (file.isFile() && !file.delete()) {
            log("shot-warn=" + fileName + " reason=delete-failed");
        }
        pendingShot = fileName;
        shotWait = 0;
        try {
            Screenshot.grab(minecraft.gameDirectory, fileName, minecraft.gameRenderer.mainRenderTarget(), 1,
                    message -> VjjUiClient.LOGGER.info(PREFIX + "screenshot-callback {} {}",
                            fileName, message.getString()));
            log("shot-requested=" + fileName);
        } catch (RuntimeException exception) {
            VjjUiClient.LOGGER.error(PREFIX + "screenshot failed {}", fileName, exception);
            pendingShot = null;
            pumpShots(minecraft);
        }
    }

    private static void pollScreenshot(Minecraft minecraft) {
        if (pendingShot == null) {
            pumpShots(minecraft);
            return;
        }
        shotWait++;
        File file = shotFile(minecraft, pendingShot);
        if (file.isFile() && file.length() > 0L) {
            log("shot-ok=" + pendingShot + " bytes=" + file.length() + " path=" + file.getAbsolutePath());
            pendingShot = null;
            shotWait = 0;
            pumpShots(minecraft);
        } else if (shotWait > MAX_SHOT_WAIT) {
            log("shot-TIMEOUT=" + pendingShot);
            pendingShot = null;
            pumpShots(minecraft);
        }
    }

    private static boolean shotsIdle() {
        return pendingShot == null && SHOT_QUEUE.isEmpty();
    }

    private static File shotFile(Minecraft minecraft, String fileName) {
        return new File(new File(minecraft.gameDirectory, "screenshots"), fileName);
    }

    private static void log(String message) {
        VjjUiClient.LOGGER.info(PREFIX + message);
    }
}
