package com.rpganim.command;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * 两个命令树的共享小工具（权限谓词 / 目标解析 / 名字）。
 *
 * <p>权限约定（设计文档 §5.4）：{@code play}/{@code stop}/{@code demo} 需要 GM=2；
 * {@code list}/{@code info}/{@code status}/{@code emote} 所有人可用。</p>
 */
final class CommandSupport {
    /** GM（权限等级 2）：与 rpgcam 的 CameraCommands 写法一致。 */
    static final Predicate<CommandSourceStack> ADMIN =
            source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());

    private CommandSupport() {
    }

    /** 显式目标优先；否则用命令执行者（控制台没有执行者 → null）。 */
    static ServerPlayer resolveTarget(CommandSourceStack source, ServerPlayer explicitTarget) {
        return explicitTarget != null ? explicitTarget : source.getPlayer();
    }

    static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
