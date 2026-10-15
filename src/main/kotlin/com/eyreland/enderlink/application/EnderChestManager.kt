package com.eyreland.enderlink.application

import com.eyreland.enderlink.platform.NativeEnderChest
import com.eyreland.enderlink.platform.NativePlayerData
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.logging.Level

class EnderChestManager(
    private val plugin: JavaPlugin,
    private val resize: (Player, Int) -> Unit = NativeEnderChest::resize,
    private val save: (Player) -> Unit = NativePlayerData::saveVerified,
    private val adaptMenu: (InventoryView) -> Unit = { NativeEnderChest.adaptMenu(it) },
) : Listener, PluginMessageListener {
    private val capacityKey = NamespacedKey("enderlink", "ender_chest_size")
    private val legacyCapacityKey = NamespacedKey("conflux", "ender_chest_size")
    private val hintKey = NamespacedKey("enderlink", "command_hint_seen")

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != OPEN_CHANNEL || message.isNotEmpty()) return
        requestOpen(player)
    }

    fun requestOpen(player: Player) {
        player.scheduler.run(plugin, {
            if (!player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR) return@run
            if (!player.hasPermission("enderlink.open")) return@run
            if (player.openInventory.type != InventoryType.CRAFTING && player.openInventory.type != InventoryType.CREATIVE) return@run
            if (!player.itemOnCursor.type.isAir) return@run
            player.openInventory(player.enderChest)
        }, null)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun beforeLoad(event: PlayerConnectionValidateLoginEvent) {
        if (!event.isAllowed) return
        val connection = event.connection as? PlayerConfigurationConnection ?: return
        try {
            NativeEnderChest.prepareLoad(connection)
        } catch (failure: Throwable) {
            event.kickMessage(Component.text("末影箱加载失败，请联系管理员"))
            fatal(failure)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun afterLoad(event: PlayerJoinEvent) {
        try {
            val player = event.player
            check(player.enderChest.size == 54) { "末影箱加载前扩容未执行" }
            val data = player.persistentDataContainer
            val storedKey = if (data.has(capacityKey)) capacityKey else legacyCapacityKey
            val capacity = if (data.has(storedKey)) {
                requireNotNull(data.get(storedKey, PersistentDataType.INTEGER)) { "末影箱容量存档类型无效" }
            } else 27
            require(capacity in SIZES) { "末影箱容量存档无效：$capacity" }
            resize(player, capacity)
            if (data.has(legacyCapacityKey)) {
                if (capacity == 27) data.remove(capacityKey) else data.set(capacityKey, PersistentDataType.INTEGER, capacity)
                data.remove(legacyCapacityKey)
                save(player)
            }
        } catch (failure: Throwable) {
            fatal(failure)
            throw failure
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoinHint(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.run(plugin, {
            if (!player.isOnline || !player.hasPermission("enderlink.open")) return@run
            val data = player.persistentDataContainer
            if (data.has(hintKey)) return@run
            showCommandHint(player)
            data.set(hintKey, PersistentDataType.BYTE, 1.toByte())
        }, null)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        if (event.inventory.type != InventoryType.ENDER_CHEST) return
        try {
            adaptMenu(event.view)
            event.titleOverride(Component.translatable("container.enderchest"))
        } catch (failure: Throwable) {
            event.isCancelled = true
            fatal(failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUse(event: PlayerInteractEvent) {
        if (event.item?.type != Material.ENDER_CHEST || event.useItemInHand() == Event.Result.DENY) return
        val player = event.player
        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking || player.gameMode == GameMode.SPECTATOR) return
            // 完整取消让服务端同步客户端预扣的物品。
            event.isCancelled = true
            showCommandHint(player)
            return
        }
        if (event.action != Action.RIGHT_CLICK_AIR || player.gameMode != GameMode.CREATIVE) return
        event.setUseItemInHand(Event.Result.DENY)
        showCommandHint(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpenSound(event: InventoryOpenEvent) {
        // 方块末影箱已有原版音效；无方块位置的随身菜单才补音效。
        if (event.inventory.type != InventoryType.ENDER_CHEST || event.inventory.location != null) return
        val player = event.player as? Player ?: return
        player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f, 1.0f)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.type != InventoryType.ENDER_CHEST || event.inventory.location != null) return
        val player = event.player as? Player ?: return
        player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5f, 1.0f)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.click != ClickType.RIGHT || event.currentItem?.type != Material.ENDER_CHEST) return
        if (!event.cursor.type.isAir) return
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        if (view.type != InventoryType.CRAFTING && view.type != InventoryType.CREATIVE) return
        if (event.clickedInventory != player.inventory) return
        event.isCancelled = true
        showCommandHint(player)
    }

    private fun showCommandHint(player: Player) {
        player.sendMessage(Component.text("使用 /enderlink 或背包内的 Ender Link 按钮打开末影箱，无需携带物品", NamedTextColor.YELLOW)
            .clickEvent(ClickEvent.runCommand("/enderlink")))
    }

    fun setSize(player: Player, capacity: Int) {
        require(capacity in SIZES) { "格数必须为 27、36、45 或 54" }
        val inventory = player.enderChest
        check(inventory.viewers.isEmpty()) { "末影箱已打开，请先关闭" }
        val oldSize = inventory.size
        if (oldSize == capacity) return
        require((capacity until oldSize).all { inventory.getItem(it)?.type?.isAir != false }) { "请先取出超出目标容量的物品" }
        val data = player.persistentDataContainer
        val oldValue = data.get(capacityKey, PersistentDataType.INTEGER)
        resize(player, capacity)
        try {
            if (capacity == 27) data.remove(capacityKey) else data.set(capacityKey, PersistentDataType.INTEGER, capacity)
            save(player)
        } catch (failure: Throwable) {
            try {
                resize(player, oldSize)
                if (oldValue == null) data.remove(capacityKey) else data.set(capacityKey, PersistentDataType.INTEGER, oldValue)
                save(player)
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
                fatal(failure)
                throw IllegalStateException("末影箱容量保存失败，无法确认原容量已恢复，服务器正在关闭", failure)
            }
            throw IllegalStateException("末影箱容量保存失败，已恢复原容量", failure)
        }
    }

    private fun fatal(failure: Throwable) {
        plugin.logger.log(Level.SEVERE, "末影箱发生异常，为避免物品丢失，正在关闭服务器", failure)
        plugin.server.shutdown()
    }

    companion object {
        const val OPEN_CHANNEL = "enderlink:open_v1"
        val SIZES = listOf(27, 36, 45, 54)
    }
}
