package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRequestTest : PluginTest() {
    @Test
    fun `ordinary players can open only their own chest by command without items or a client mod`() {
        val sender = server.addPlayer("Sender")
        val other = server.addPlayer("Other")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, {})
        server.pluginManager.registerEvents(manager, plugin)
        val command = EnderLinkCommand(plugin, manager)
        plugin.getCommand("enderlink")!!.setExecutor(command)
        assertFalse(sender.isOp)
        for (capacity in EnderChestManager.SIZES) {
            sender.closeInventory()
            resizeMockEnderChest(sender, capacity)
            val item = ItemStack(Material.DIAMOND, 7)
            sender.enderChest.setItem(capacity - 1, item)
            assertTrue(server.dispatchCommand(sender, "enderlink"))
            assertEquals(InventoryType.CRAFTING, sender.openInventory.type)
            server.scheduler.performTicks(2)
            assertSame(sender.enderChest, sender.openInventory.topInventory)
            assertEquals(item, sender.enderChest.getItem(capacity - 1))
            assertTrue(sender.inventory.isEmpty)
            assertTrue(other.enderChest.isEmpty)
            assertEquals(InventoryType.CRAFTING, other.openInventory.type)
        }
        sender.closeInventory()
        command.execute(sender, listOf(other.name))
        command.execute(sender, listOf("size", other.name, "54"))
        command.execute(server.consoleSender, emptyList())
        server.scheduler.performTicks(2)
        assertEquals(InventoryType.CRAFTING, sender.openInventory.type)
        assertEquals(27, other.enderChest.size)
        assertTrue(command.tabComplete(sender, listOf("")).isEmpty())
    }

    @Test
    fun `empty request opens only the sending players native chest without a chest item`() {
        val sender = server.addPlayer("Sender")
        val other = server.addPlayer("Other")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, {})
        server.pluginManager.registerEvents(manager, plugin)
        for (capacity in EnderChestManager.SIZES) {
            sender.closeInventory()
            resizeMockEnderChest(sender, capacity)
            val item = ItemStack(Material.DIAMOND, 7)
            sender.enderChest.setItem(capacity - 1, item)
            manager.onPluginMessageReceived(EnderChestManager.OPEN_CHANNEL, sender, byteArrayOf())
            assertEquals(InventoryType.CRAFTING, sender.openInventory.type)
            server.scheduler.performTicks(2)
            assertSame(sender.enderChest, sender.openInventory.topInventory)
            assertEquals(item, sender.openInventory.topInventory.getItem(capacity - 1))
            assertTrue(sender.inventory.isEmpty)
            assertTrue(other.enderChest.isEmpty)
            assertEquals(InventoryType.CRAFTING, other.openInventory.type)
        }
    }

    @Test
    fun `command and client requests cannot bypass cursor menu spectator permission or open event checks`() {
        val player = server.addPlayer("Sender")
        val permission = player.addAttachment(plugin, "enderlink.open", true)
        var attempts = 0
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, {})
        val command = EnderLinkCommand(plugin, manager)
        server.pluginManager.registerEvents(manager, plugin)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.HIGHEST)
            fun open(event: InventoryOpenEvent) {
                if (event.inventory.type == InventoryType.ENDER_CHEST) {
                    attempts++
                    event.isCancelled = true
                }
            }
        }, plugin)
        manager.onPluginMessageReceived("enderlink:open_v2", player, byteArrayOf())
        manager.onPluginMessageReceived(EnderChestManager.OPEN_CHANNEL, player, byteArrayOf(1))
        server.scheduler.performTicks(2)
        assertEquals(0, attempts)
        val requests = listOf<() -> Unit>(
            { manager.onPluginMessageReceived(EnderChestManager.OPEN_CHANNEL, player, byteArrayOf()) },
            { command.execute(player, emptyList()) },
        )
        fun request(change: () -> Unit) {
            for (send in requests) {
                permission.setPermission("enderlink.open", true)
                player.gameMode = GameMode.SURVIVAL
                player.setItemOnCursor(null)
                player.closeInventory()
                send()
                change()
                server.scheduler.performTicks(2)
                assertEquals(0, attempts)
            }
        }
        request { permission.setPermission("enderlink.open", false) }
        permission.setPermission("enderlink.open", true)
        request { player.setItemOnCursor(ItemStack(Material.DIAMOND)) }
        player.setItemOnCursor(null)
        request { player.openInventory(server.createInventory(null, 9)) }
        request { player.gameMode = GameMode.SPECTATOR }
        player.gameMode = GameMode.SURVIVAL
        for ((index, send) in requests.withIndex()) {
            player.closeInventory()
            send()
            server.scheduler.performTicks(2)
            assertEquals(index + 1, attempts)
            assertEquals(InventoryType.CRAFTING, player.openInventory.type)
        }
    }
}
