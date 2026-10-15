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
import java.util.logging.Level

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
        if (!authorized(sender, query)) return sender.error("command.denied")
        if (args.size !in 2..3 || args[0] != "size") {
            return sender.sendMessage(Messages.text(sender, "command.usage").color(NamedTextColor.YELLOW))
        }
        val size = args.getOrNull(2)?.toIntOrNull()
        if (args.size == 3 && size !in EnderChestManager.SIZES) return sender.error("capacity.invalid")
        val target = plugin.server.getPlayerExact(args[1]) ?: return sender.error("player.offline")
        onPlayer(target, {
            if (!authorized(sender, query)) {
                reply(sender) { sender.error("command.revoked") }
            } else if (!target.isOnline) {
                reply(sender) { sender.error("player.offline") }
            } else if (size == null) {
                val current = target.enderChest.size
                reply(sender) { sender.info("capacity.query", Component.text(target.name), Component.text(current)) }
            } else {
                val result = runCatching { manager.setSize(target, size) }
                reply(sender) {
                    result.fold(
                        onSuccess = { sender.info("capacity.updated", Component.text(target.name), Component.text(size)) },
                        onFailure = { failure ->
                            if (failure !is EnderChestManager.CapacityException) {
                                plugin.logger.log(Level.SEVERE, "Could not change ender chest capacity for ${target.name}", failure)
                            }
                            sender.error((failure as? EnderChestManager.CapacityException)?.messageKey ?: "capacity.failed")
                        },
                    )
                }
            }
        }, { reply(sender) { sender.error("player.offline") } })
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

    private fun CommandSender.error(key: String) = sendMessage(Messages.text(this, key).color(NamedTextColor.RED))
    private fun CommandSender.info(key: String, vararg args: Component) =
        sendMessage(Messages.text(this, key, *args).color(NamedTextColor.WHITE))
}
