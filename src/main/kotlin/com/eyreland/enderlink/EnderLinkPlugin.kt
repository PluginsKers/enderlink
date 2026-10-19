package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import com.eyreland.enderlink.platform.NativeEnderChest
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

class EnderLinkPlugin : JavaPlugin() {
    override fun onLoad() {
        try {
            NativeEnderChest.initialize()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Ender chest storage preflight failed; shutting down the server", failure)
            server.shutdown()
            throw failure
        }
    }

    override fun onEnable() {
        try {
            Messages.reload(this)
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Could not load language files; using bundled messages", failure)
        }
        val manager = EnderChestManager(this)
        server.pluginManager.registerEvents(manager, this)
        server.messenger.registerIncomingPluginChannel(this, EnderChestManager.OPEN_CHANNEL, manager)
        val command = requireNotNull(getCommand("enderlink")) { "enderlink command" }
        val executor = EnderLinkCommand(this, manager)
        command.setExecutor(executor)
        command.tabCompleter = executor
    }

    override fun onDisable() {
        // 移除加载前扩容钩子后继续接收玩家会截断 EnderItems，禁止热卸载。
        if (!server.isStopping) server.shutdown()
        server.messenger.unregisterIncomingPluginChannel(this)
    }
}
