package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import org.bukkit.command.Command
import org.bukkit.command.TabExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class EnderLinkCommand(
    private val plugin: JavaPlugin,
    private val manager: EnderChestManager,
    private val onPlayer: (Player, () -> Unit, () -> Unit) -> Unit = { player, action, retired ->
        if (player.scheduler.run(plugin, { action() }, Runnable { retired() }) == null) retired()
    },
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        execute(sender, args.toList())
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>) =
        tabComplete(sender, args.toList())

    fun execute(sender: CommandSender, args: List<String>) {
        if (args.isEmpty() && sender is Player) return manager.requestOpen(sender)
        val query = args.size == 2 && args[0] == "size"
        if (!authorized(sender, query)) return sender.error("你没有末影箱管理权限")
        if (args.size !in 2..3 || args[0] != "size") return sender.usage("/enderlink size <在线玩家> [27|36|45|54]")
        val size = args.getOrNull(2)?.toIntOrNull()
        if (args.size == 3 && size !in EnderChestManager.SIZES) return sender.error("格数必须为 27、36、45 或 54")
        val target = plugin.server.getPlayerExact(args[1]) ?: return sender.error("玩家不在线")
        onPlayer(target, {
            if (!authorized(sender, query)) {
                reply(sender) { sender.error("末影箱管理权限已失效") }
            } else if (!target.isOnline) {
                reply(sender) { sender.error("玩家已离线") }
            } else if (size == null) {
                val current = target.enderChest.size
                reply(sender) { sender.info("${target.name} 的末影箱容量为 $current 格") }
            } else {
                val result = runCatching { manager.setSize(target, size) }
                reply(sender) {
                    result.fold(
                        onSuccess = { sender.success("${target.name} 的末影箱容量已设为 $size 格") },
                        onFailure = { sender.error(it.message ?: "末影箱容量修改失败") },
                    )
                }
            }
        }, { reply(sender) { sender.error("玩家已离线") } })
    }

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (!authorized(sender, args.size <= 2)) return emptyList()
        val values = when (args.size) {
            1 -> listOf("size")
            2 -> if (args[0] == "size") plugin.server.onlinePlayers.map { it.name } else emptyList()
            3 -> if (args[0] == "size") EnderChestManager.SIZES.map(Int::toString) else emptyList()
            else -> emptyList()
        }
        return values.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    private fun authorized(sender: CommandSender, query: Boolean = false) = when (sender) {
        is ConsoleCommandSender, is RemoteConsoleCommandSender -> true
        is Player -> sender.hasPermission(if (query) "enderlink.size.query" else "enderlink.size")
        else -> false
    }

    private fun reply(sender: CommandSender, action: () -> Unit) {
        if (sender is Player) onPlayer(sender, action, {}) else action()
    }

    private fun CommandSender.error(message: String) = sendMessage(Component.text(message, NamedTextColor.RED))
    private fun CommandSender.info(message: String) = sendMessage(Component.text(message, NamedTextColor.WHITE))
    private fun CommandSender.success(message: String) = info(message)
    private fun CommandSender.usage(message: String) = sendMessage(Component.text("用法: $message", NamedTextColor.YELLOW))
}
