package com.eyreland.enderlink.platform

import io.papermc.paper.connection.PaperCommonConnection
import io.papermc.paper.connection.PaperPlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConfigurationConnection
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.IntLists
import net.minecraft.core.NonNullList
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.minecraft.server.network.config.PrepareSpawnTask
import net.minecraft.util.StringRepresentable
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.PlayerEnderChestContainer
import net.minecraft.world.inventory.SlotRange
import net.minecraft.world.inventory.SlotRanges
import net.minecraft.world.item.ItemStack
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftInventoryView
import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryView

/** 保留容器与事件视图，按实际原生接口检查扩容能力。 */
object NativeEnderChest {
    private val sizeField = SimpleContainer::class.java.getDeclaredField("size").apply { isAccessible = true }
    private val itemsField = SimpleContainer::class.java.getDeclaredField("items").apply { isAccessible = true }
    private val rowsField = ChestMenu::class.java.getDeclaredField("containerRows").apply { isAccessible = true }
    private val typeField = AbstractContainerMenu::class.java.getDeclaredField("menuType").apply { isAccessible = true }
    private val listenerField = PaperCommonConnection::class.java.getDeclaredField("packetListener").apply { isAccessible = true }
    private val spawnField = ServerConfigurationPacketListenerImpl::class.java.getDeclaredField("prepareSpawnTask").apply { isAccessible = true }

    fun initialize() {
        // 在接收玩家前实际验证 final 实例字段写入，禁止静默退回 27 格。
        val probe = SimpleContainer(27)
        resize(probe, 54)
        resize(probe, 27)
        extendSlotRanges()
    }

    fun prepareLoad(connection: PlayerConfigurationConnection) {
        check(connection is PaperPlayerConfigurationConnection)
        val listener = listenerField.get(connection) as ServerConfigurationPacketListenerImpl
        val spawn = spawnField.get(listener) as PrepareSpawnTask
        val network = listener.connection
        // 配置完成的登录校验紧邻 Ready.createPlayer。复用它已有的预创建玩家入口，
        // 不注册已禁用的 PlayerSpawnLocationEvent，也不设置 handledLegacyLoginEvent。
        val player = network.savedPlayerForLegacyEvents ?: ServerPlayer(
            (Bukkit.getServer() as CraftServer).server, spawn.spawnWorld, listener.owner, listener.clientInformation,
        )
        resize(player.enderChestInventory, 54)
        network.savedPlayerForLegacyEvents = player
    }

    fun resize(player: Player, size: Int) = resize((player as CraftPlayer).handle.enderChestInventory, size)

    private fun resize(container: SimpleContainer, size: Int) {
        val oldSize = container.containerSize
        require((size until oldSize).all { container.getItem(it).isEmpty }) { "Ender chest slots beyond the requested capacity still contain items" }
        val items = NonNullList.withSize(size, ItemStack.EMPTY)
        for (slot in 0 until minOf(oldSize, size)) items[slot] = container.getItem(slot)
        val previous = itemsField.get(container)
        try {
            itemsField.set(container, items)
            sizeField.setInt(container, size)
            check(container.containerSize == size && container.contents.size == size) { "Could not resize the native ender chest to $size slots" }
        } catch (failure: Exception) {
            itemsField.set(container, previous)
            sizeField.setInt(container, oldSize)
            throw failure
        }
    }

    fun adaptMenu(view: InventoryView) {
        // Bukkit openInventory 使用的 CraftContainer 已按实际大小创建；方块开箱仍创建三行 ChestMenu。
        val menu = (view as? CraftInventoryView<*, *>)?.handle as? ChestMenu ?: return
        val container = menu.container as? PlayerEnderChestContainer ?: return
        val rows = container.containerSize / 9
        if (menu.rowCount == rows) return
        val type = when (rows) {
            3 -> MenuType.GENERIC_9x3
            4 -> MenuType.GENERIC_9x4
            5 -> MenuType.GENERIC_9x5
            6 -> MenuType.GENERIC_9x6
            else -> error("Invalid ender chest capacity")
        }
        val layout = ChestMenu(type, menu.containerId, (view.player as CraftPlayer).handle.inventory, container, rows)
        rowsField.setInt(menu, rows)
        typeField.set(menu, type)
        menu.slots = layout.slots
        menu.lastSlots = layout.lastSlots
        menu.remoteSlots = layout.remoteSlots
        check(menu.rowCount == rows && menu.type == type && menu.slots.size == container.containerSize + 36)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extendSlotRanges() {
        val slots = SlotRanges::class.java.getDeclaredField("SLOTS").apply { isAccessible = true }
            .get(null) as MutableList<SlotRange>
        val lookup = SlotRanges::class.java.getDeclaredField("NAME_LOOKUP").apply { isAccessible = true }.get(null)
        // 26.2 的名称解析器是 HashMap::get；原地更新，保留所有已持有解析器的调用者。
        val names = lookup.javaClass.declaredFields.single { Map::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(lookup) as MutableMap<String, SlotRange>
        val wildcard = slots.single { it.serializedName == "enderchest.*" }
        // 数据包可能已经持有通配符及其 slots 列表，必须扩展原列表，不能替换 SlotRange。
        val wildcardSlots = IntLists.UnmodifiableList::class.java.getDeclaredField("list")
            .apply { isAccessible = true }.get(wildcard.slots()) as IntList
        check(wildcardSlots == IntList.of(*(200 until 227).toList().toIntArray()))
        for (slot in 227 until 254) wildcardSlots.add(slot)
        for (slot in 27 until 54) {
            val name = "enderchest.$slot"
            check(name !in names) { "Another plugin has modified the ender chest slot selectors" }
            slots.add(SlotRange.of(name, IntList.of(200 + slot)))
        }
        slots.filter { it.serializedName.startsWith("enderchest.") }.forEach { names[it.serializedName] = it }
        // 保留 CODEC 对象，只重建其内部解析器；追加槽位不改变其他槽位的压缩编号。
        val codecField = StringRepresentable.StringRepresentableCodec::class.java.getDeclaredField("codec")
            .apply { isAccessible = true }
        val extended = StringRepresentable.fromValues { slots.toTypedArray() }
        codecField.set(SlotRanges.CODEC, codecField.get(extended))
        check(SlotRanges.nameToIds("enderchest.53")?.slots() == IntList.of(253))
        check(SlotRanges.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("enderchest.*")).getOrThrow().size() == 54)
    }
}
