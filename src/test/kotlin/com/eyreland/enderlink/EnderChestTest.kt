package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.BlockFace
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.InventoryMock
import org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnderChestTest : PluginTest() {
    private val key = NamespacedKey("enderlink", "ender_chest_size")

    @Test
    fun `menu adaptation failure cancels the open event before returning to Bukkit`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, { error("menu unavailable") })
        val event = InventoryOpenEvent(SimpleInventoryViewMock(player, player.enderChest, player.inventory, InventoryType.ENDER_CHEST))
        val previous = plugin.logger.level
        plugin.logger.level = java.util.logging.Level.OFF
        try {
            manager.onOpen(event)
            assertTrue(event.isCancelled)
            assertTrue(server.isStopping)
        } finally { plugin.logger.level = previous }
    }

    @Test
    fun `Bukkit permissions control capacity management without another plugin`() {
        val operator = server.addPlayer("Operator").also { it.setOp(true) }
        val target = server.addPlayer("Target")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        val command = EnderLinkCommand(plugin, manager, { _, action, _ -> action() })
        command.execute(operator, listOf("size", target.name, "54"))
        assertEquals(27, target.enderChest.size)
        val permission = operator.addAttachment(plugin, "enderlink.size", true)
        command.execute(operator, listOf("size", target.name, "54"))
        assertEquals(54, target.enderChest.size)
        permission.setPermission("enderlink.size", false)
        command.execute(operator, listOf("size", target.name, "36"))
        assertEquals(54, target.enderChest.size)
        target.addAttachment(plugin, "enderlink.size", true)
        command.execute(target, listOf("size", target.name, "36"))
        assertEquals(36, target.enderChest.size)
    }

    @Test
    fun `ender chest opening sends a translatable title without changing other or cancelled inventories`() {
        val player = server.addPlayer("Owner")
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        val enderView = SimpleInventoryViewMock(player, player.enderChest, player.inventory, InventoryType.ENDER_CHEST)
        val enderOpen = InventoryOpenEvent(enderView)
        dispatch(enderOpen)
        assertFalse(enderOpen.isCancelled)
        assertEquals("container.enderchest", assertIs<TranslatableComponent>(enderOpen.titleOverride()).key())

        val customTitle = Component.text("Custom container")
        val chest = server.createInventory(null, 9, customTitle)
        val otherOpen = InventoryOpenEvent(SimpleInventoryViewMock(player, chest, player.inventory, InventoryType.CHEST))
            .apply { titleOverride(customTitle) }
        dispatch(otherOpen)
        assertEquals(customTitle, otherOpen.titleOverride())

        val cancelledOpen = InventoryOpenEvent(enderView).apply { isCancelled = true }
        dispatch(cancelledOpen)
        assertTrue(cancelledOpen.isCancelled)
        assertNull(cancelledOpen.titleOverride())
    }

    @Test
    fun `creative air use from either hand suggests the command without opening or consuming items`() {
        val player = server.addPlayer("Owner").also { it.gameMode = GameMode.CREATIVE }
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        resizeMockEnderChest(player, 54)
        val contents = ItemStack(Material.DIAMOND, 7)
        player.enderChest.setItem(53, contents)
        for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            player.closeInventory()
            player.inventory.clear()
            val held = ItemStack(Material.ENDER_CHEST, 3)
            player.inventory.setItem(hand, held)
            val interact = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held, null, BlockFace.SELF, hand)
            assertEquals(Event.Result.DENY, interact.useInteractedBlock())
            assertEquals(Event.Result.DEFAULT, interact.useItemInHand())

            dispatch(interact)
            server.scheduler.performTicks(2)

            assertFalse(player.enderChest.viewers.contains(player))
            assertEquals(contents, player.enderChest.getItem(53))
            assertCommandHint(player.nextComponentMessage()!!)
            assertEquals(held, player.inventory.getItem(hand))
            assertEquals(3, player.inventory.getItem(hand).amount)
        }
    }

    @Test
    fun `creative shortcut leaves other clicks items and survival air use unchanged`() {
        val player = server.addPlayer("Owner")
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        val block = world.getBlockAt(0, 64, 0).also { it.type = Material.STONE }
        for ((mode, action, material) in listOf(
            Triple(GameMode.CREATIVE, Action.LEFT_CLICK_BLOCK, Material.ENDER_CHEST),
            Triple(GameMode.CREATIVE, Action.LEFT_CLICK_AIR, Material.ENDER_CHEST),
            Triple(GameMode.CREATIVE, Action.RIGHT_CLICK_AIR, Material.STONE),
            Triple(GameMode.CREATIVE, Action.RIGHT_CLICK_AIR, Material.AIR),
            Triple(GameMode.SURVIVAL, Action.RIGHT_CLICK_AIR, Material.ENDER_CHEST),
        )) {
            player.gameMode = mode
            val item = ItemStack(material)
            player.inventory.setItemInMainHand(item)
            val clickedBlock = if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) block else null
            val interact = PlayerInteractEvent(player, action, item, clickedBlock, BlockFace.UP, EquipmentSlot.HAND)
            val blockUse = interact.useInteractedBlock()
            val view = player.openInventory
            dispatch(interact)
            server.scheduler.performTicks(2)
            assertSame(view, player.openInventory)
            assertEquals(blockUse, interact.useInteractedBlock())
            assertEquals(Event.Result.DEFAULT, interact.useItemInHand())
        }
    }

    @Test
    fun `block use from either hand suggests the command and cancels placement unless sneaking`() {
        val player = server.addPlayer("Owner")
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        val block = world.getBlockAt(0, 64, 0).also { it.type = Material.CHEST }
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE)) {
            player.gameMode = mode
            for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                player.inventory.clear()
                val held = ItemStack(Material.ENDER_CHEST, 3)
                player.inventory.setItem(hand, held)
                for (sneaking in listOf(false, true)) {
                    player.isSneaking = sneaking
                    for (blockUse in listOf(Event.Result.DEFAULT, Event.Result.DENY)) {
                        player.closeInventory()
                        val view = player.openInventory
                        val interact = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, held, block, BlockFace.UP, hand)
                            .apply { setUseInteractedBlock(blockUse) }
                        dispatch(interact)
                        assertEquals(if (sneaking) Event.Result.DEFAULT else Event.Result.DENY, interact.useItemInHand())
                        assertEquals(if (sneaking) blockUse else Event.Result.DENY, interact.useInteractedBlock())
                        assertSame(view, player.openInventory)
                        server.scheduler.performTicks(2)
                        assertSame(view, player.openInventory)
                        if (sneaking) assertNull(player.nextMessage())
                        else assertCommandHint(player.nextComponentMessage()!!)
                        assertEquals(held, player.inventory.getItem(hand))
                    }
                    player.closeInventory()
                    val view = player.openInventory
                    val denied = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, held, block, BlockFace.UP, hand)
                        .apply { isCancelled = true }
                    dispatch(denied)
                    assertEquals(Event.Result.DENY, denied.useItemInHand())
                    assertEquals(Event.Result.DENY, denied.useInteractedBlock())
                    server.scheduler.performTicks(2)
                    assertSame(view, player.openInventory)
                    assertNull(player.nextMessage())
                }
            }
        }
    }

    @Test
    fun `portable ender chest sounds follow accepted opens and closes without duplicating block sounds`() {
        val player = server.addPlayer("Owner")
        val nearby = server.addPlayer("Nearby")
        var cancelOpen = false
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.HIGHEST)
            fun onOpen(event: InventoryOpenEvent) {
                if (cancelOpen) event.isCancelled = true
            }
        }, plugin)
        // MockBukkit 默认返回持有者位置，原生随身末影箱的位置为 null。
        val portable = object : InventoryMock(player, 27, InventoryType.ENDER_CHEST) {
            override fun getLocation(): Location? = null
        }
        player.openInventory(portable)
        assertSame(portable, player.openInventory.topInventory)
        assertEquals(listOf(Sound.BLOCK_ENDER_CHEST_OPEN.key.key), player.heardSounds.map { it.sound })
        player.closeInventory()
        val expected = listOf(Sound.BLOCK_ENDER_CHEST_OPEN.key.key, Sound.BLOCK_ENDER_CHEST_CLOSE.key.key)
        assertEquals(expected, player.heardSounds.map { it.sound })
        player.heardSounds.forEach {
            assertEquals(SoundCategory.BLOCKS, it.category)
            assertEquals(0.5f, it.volume)
        }
        assertTrue(nearby.heardSounds.isEmpty())

        cancelOpen = true
        player.openInventory(portable)
        assertFalse(portable.viewers.contains(player))
        cancelOpen = false
        val blockInventory = object : InventoryMock(player, 27, InventoryType.ENDER_CHEST) {
            override fun getLocation(): Location = world.getBlockAt(0, 64, 0).location
        }
        player.openInventory(blockInventory)
        player.closeInventory()
        player.openInventory(server.createInventory(null, 9))
        player.closeInventory()
        assertEquals(expected, player.heardSounds.map { it.sound })
    }

    @Test
    fun `denied creative air use does not send the command hint`() {
        val player = server.addPlayer("Owner").also { it.gameMode = GameMode.CREATIVE }
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        player.inventory.setItemInMainHand(ItemStack(Material.ENDER_CHEST, 3))
        val view = player.openInventory
        dispatch(PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, player.inventory.itemInMainHand, null,
            BlockFace.SELF, EquipmentSlot.HAND).apply { setUseItemInHand(Event.Result.DENY) })
        server.scheduler.performTicks(2)
        assertSame(view, player.openInventory)
        assertNull(player.nextMessage())
        assertEquals(ItemStack(Material.ENDER_CHEST, 3), player.inventory.itemInMainHand)
    }
    @Test
    fun `backpack right click suggests the command without opening or consuming items`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, {})
        server.pluginManager.registerEvents(manager, plugin)
        for (size in EnderChestManager.SIZES) {
            resizeMockEnderChest(player, size)
            val contents = ItemStack(Material.DIAMOND, 7)
            player.enderChest.setItem(size - 1, contents)
            for ((slot, rawSlot) in listOf(0 to 36, 9 to 9, 40 to 45)) {
                player.closeInventory()
                val view = openView(player)
                player.inventory.clear()
                player.inventory.setItem(slot, ItemStack(Material.ENDER_CHEST, 3))
                val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.RIGHT, InventoryAction.PICKUP_HALF)

                dispatch(click)

                assertTrue(click.isCancelled)
                assertSame(view, player.openInventory)
                server.scheduler.performTicks(2)
                assertSame(view, player.openInventory)
                assertEquals(contents, player.enderChest.getItem(size - 1))
                assertCommandHint(player.nextComponentMessage()!!)
                assertEquals(ItemStack(Material.ENDER_CHEST, 3), player.inventory.getItem(slot))
                assertTrue(player.itemOnCursor.type.isAir)
            }
        }
    }

    @Test
    fun `only empty cursor right clicks in the players own backpack activate the shortcut`() {
        val player = server.addPlayer("Owner")
        server.pluginManager.registerEvents(EnderChestManager(plugin, ::resizeMockEnderChest, {}, {}), plugin)
        player.inventory.setItem(0, ItemStack(Material.ENDER_CHEST))
        val view = openView(player)
        for (type in listOf(ClickType.LEFT, ClickType.SHIFT_RIGHT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK)) {
            val click = InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, type, InventoryAction.NOTHING)
            dispatch(click)
            assertFalse(click.isCancelled)
        }
        player.setItemOnCursor(ItemStack(Material.DIAMOND))
        val occupiedCursor = InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, ClickType.RIGHT, InventoryAction.SWAP_WITH_CURSOR)
        dispatch(occupiedCursor)
        assertFalse(occupiedCursor.isCancelled)
        player.setItemOnCursor(null)
        val blocked = InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, ClickType.RIGHT, InventoryAction.PICKUP_HALF)
            .apply { isCancelled = true }
        dispatch(blocked)
        view.topInventory.setItem(1, ItemStack(Material.ENDER_CHEST))
        val crafting = InventoryClickEvent(view, InventoryType.SlotType.CRAFTING, 1, ClickType.RIGHT, InventoryAction.PICKUP_HALF)
        dispatch(crafting)
        assertFalse(crafting.isCancelled)
        server.scheduler.performTicks(2)
        assertSame(view, player.openInventory)

        val container = server.createInventory(null, 9)
        container.setItem(0, ItemStack(Material.ENDER_CHEST))
        openView(player, container)
        for (rawSlot in listOf(0, 36)) {
            val click = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.RIGHT, InventoryAction.PICKUP_HALF)
            dispatch(click)
            assertFalse(click.isCancelled)
        }
        server.scheduler.performTicks(2)
        assertSame(container, player.openInventory.topInventory)
        assertNull(player.nextMessage())
    }

    private fun openView(player: PlayerMock, inventory: Inventory = InventoryMock(player, 5, InventoryType.CRAFTING)) =
        object : PlayerInventoryViewMock(player, inventory) {
            // MockBukkit 的 getItem 没有使用玩家槽位映射。
            override fun getItem(rawSlot: Int): ItemStack? = getInventory(rawSlot)?.getItem(convertSlot(rawSlot))
        }.also { player.openInventory(it) }

    @Test
    fun `load resize and failed save keep one inventory and capacity metadata consistent`() {
        val player = server.addPlayer("Owner")
        val inventory = player.enderChest
        var saveFailure: Throwable? = null
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {
            saveFailure?.let { failure ->
                saveFailure = null
                throw failure
            }
        })
        resizeMockEnderChest(player, 54)
        assertEquals(54, inventory.size)
        manager.afterLoad(PlayerJoinEvent(player, ""))
        assertEquals(27, inventory.size)
        for (size in EnderChestManager.SIZES) {
            manager.setSize(player, size)
            assertSame(inventory, player.enderChest)
            assertEquals(size, inventory.size)
        }
        inventory.setItem(53, ItemStack(Material.DIAMOND, 7))
        assertEquals("capacity.occupied", assertFailsWith<EnderChestManager.CapacityException> { manager.setSize(player, 27) }.messageKey)
        assertEquals(7, inventory.getItem(53)?.amount)
        inventory.clear()
        for (failure in listOf(IllegalStateException("save failed"), NoSuchMethodError("saveData"))) {
            saveFailure = failure
            assertFailsWith<IllegalStateException> { manager.setSize(player, 36) }
            assertEquals(54, inventory.size)
            assertEquals(54, player.persistentDataContainer.get(key, PersistentDataType.INTEGER))
        }
        manager.setSize(player, 27)
        assertNull(player.persistentDataContainer.get(key, PersistentDataType.INTEGER))
        player.persistentDataContainer.set(key, PersistentDataType.INTEGER, 45)
        resizeMockEnderChest(player, 54)
        inventory.setItem(44, ItemStack(Material.EMERALD, 3))
        manager.afterLoad(PlayerJoinEvent(player, ""))
        assertEquals(45, inventory.size)
        assertEquals(3, inventory.getItem(44)?.amount)
    }

    @Test
    fun `viewers prevent capacity changes`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        player.openInventory(player.enderChest)
        assertFailsWith<IllegalStateException> { manager.setSize(player, 54) }
        assertEquals(27, player.enderChest.size)
        player.closeInventory()
        manager.setSize(player, 54)
        assertEquals(54, player.enderChest.size)
    }

    @Test
    fun `legacy capacity migrates once without losing extra slots`() {
        val legacyKey = NamespacedKey("conflux", "ender_chest_size")
        val player = server.addPlayer("Owner")
        var saves = 0
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, { saves++ })
        for (size in EnderChestManager.SIZES) {
            player.enderChest.clear()
            resizeMockEnderChest(player, 54)
            player.persistentDataContainer.remove(key)
            player.persistentDataContainer.set(legacyKey, PersistentDataType.INTEGER, size)
            val item = ItemStack(Material.DIAMOND, 7)
            player.enderChest.setItem(size - 1, item)
            val before = saves
            manager.afterLoad(PlayerJoinEvent(player, ""))
            assertEquals(size, player.enderChest.size)
            assertEquals(item, player.enderChest.getItem(size - 1))
            assertFalse(player.persistentDataContainer.has(legacyKey))
            assertEquals(if (size == 27) null else size, player.persistentDataContainer.get(key, PersistentDataType.INTEGER))
            assertEquals(before + 1, saves)
            resizeMockEnderChest(player, 54)
            manager.afterLoad(PlayerJoinEvent(player, ""))
            assertEquals(before + 1, saves)
            assertEquals(item, player.enderChest.getItem(size - 1))
        }
    }

    @Test
    fun `invalid legacy capacity stops loading before shrinking the chest`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        resizeMockEnderChest(player, 54)
        player.enderChest.setItem(53, ItemStack(Material.DIAMOND, 7))
        player.persistentDataContainer.set(NamespacedKey("conflux", "ender_chest_size"), PersistentDataType.INTEGER, 28)
        plugin.logger.level = java.util.logging.Level.OFF
        assertFailsWith<IllegalArgumentException> { manager.afterLoad(PlayerJoinEvent(player, "")) }
        assertEquals(54, player.enderChest.size)
        assertEquals(7, player.enderChest.getItem(53)?.amount)
        assertTrue(server.isStopping)
    }

    @Test
    fun `failed migration save stops the server with items and current capacity intact`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, { error("disk unavailable") })
        resizeMockEnderChest(player, 54)
        player.enderChest.setItem(53, ItemStack(Material.DIAMOND, 7))
        player.persistentDataContainer.set(key, PersistentDataType.INTEGER, 54)
        player.persistentDataContainer.set(NamespacedKey("conflux", "ender_chest_size"), PersistentDataType.INTEGER, 27)
        plugin.logger.level = java.util.logging.Level.OFF
        assertFailsWith<IllegalStateException> { manager.afterLoad(PlayerJoinEvent(player, "")) }
        assertEquals(54, player.enderChest.size)
        assertEquals(54, player.persistentDataContainer.get(key, PersistentDataType.INTEGER))
        assertEquals(7, player.enderChest.getItem(53)?.amount)
        assertTrue(server.isStopping)
    }

    @Test
    fun `command hint is owned by Ender Link and stays seen after rejoin`() {
        val player = server.addPlayer("Owner")
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        repeat(2) { manager.onJoinHint(PlayerJoinEvent(player, "")) }
        server.scheduler.performTicks(2)
        assertCommandHint(player.nextComponentMessage()!!)
        assertNull(player.nextMessage())
        assertTrue(player.inventory.isEmpty)
        EnderChestManager(plugin, ::resizeMockEnderChest, {}).onJoinHint(PlayerJoinEvent(player, ""))
        server.scheduler.performTicks(2)
        assertNull(player.nextMessage())
    }

    private fun assertCommandHint(hint: Component, text: String = "Ender Chest [Open]", label: String = "[Open]", hover: String = "Use /enderlink") {
        assertEquals(text, PlainTextComponentSerializer.plainText().serialize(hint))
        assertNull(hint.clickEvent())
        assertNull(hint.hoverEvent())
        val button = hint.children().single { it.clickEvent() != null }
        assertEquals(label, PlainTextComponentSerializer.plainText().serialize(button))
        assertEquals(NamedTextColor.GREEN, button.color())
        assertEquals(ClickEvent.runCommand("/enderlink"), button.clickEvent())
        assertEquals(HoverEvent.showText(Component.text(hover)), button.hoverEvent())
    }

    @Test
    fun `hints follow the current client language and retain the open action`() {
        val player = server.addPlayer("Owner").also { it.gameMode = GameMode.CREATIVE }
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {}, {})
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        manager.onJoinHint(PlayerJoinEvent(player, ""))
        server.scheduler.performTicks(2)
        assertCommandHint(player.nextComponentMessage()!!, "末影箱 [打开]", "[打开]", "使用 /enderlink")

        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.SIMPLIFIED_CHINESE)) {
            player.setLocale(locale)
            manager.onUse(PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, ItemStack(Material.ENDER_CHEST), null,
                BlockFace.SELF, EquipmentSlot.HAND))
            val hint = player.nextComponentMessage()!!
            if (locale == Locale.SIMPLIFIED_CHINESE) assertCommandHint(hint, "末影箱 [打开]", "[打开]", "使用 /enderlink")
            else assertCommandHint(hint)
        }
    }

    @Test
    fun `command validates trust and arguments and defers mutation to target scheduler`() {
        val player = server.addPlayer("Owner")
        val operator = server.addPlayer("Operator").also { it.setOp(true) }
        val actions = ArrayDeque<() -> Unit>()
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        val command = EnderLinkCommand(plugin, manager, { _, action, _ -> actions.add(action) })
        command.execute(operator, listOf("size", player.name, "54"))
        assertTrue(actions.isEmpty())
        assertEquals("You do not have permission to manage ender chests.", PlainTextComponentSerializer.plainText().serialize(operator.nextComponentMessage()!!))
        command.execute(operator, listOf("size", player.name))
        actions.removeFirst().invoke()
        actions.removeFirst().invoke()
        assertEquals(27, player.enderChest.size)
        assertTrue(operator.nextMessage()!!.contains("27"))
        assertTrue(command.tabComplete(operator, listOf("size", player.name, "")).isEmpty())
        for (args in listOf(listOf("size", "Own", "54"), listOf("size", player.name, "28"), listOf("size", player.name, "27", "extra"))) {
            command.execute(server.consoleSender, args)
            assertTrue(actions.isEmpty())
        }
        command.execute(server.consoleSender, listOf("size", player.name, "54"))
        assertEquals(27, player.enderChest.size)
        actions.removeFirst().invoke()
        assertEquals(54, player.enderChest.size)
        val permission = operator.addAttachment(plugin, "enderlink.size", true)
        command.execute(operator, listOf("size", player.name, "36"))
        permission.setPermission("enderlink.size", false)
        actions.removeFirst().invoke()
        actions.removeFirst().invoke()
        assertEquals(54, player.enderChest.size)
        assertEquals("You no longer have permission to manage ender chests.", PlainTextComponentSerializer.plainText().serialize(operator.nextComponentMessage()!!))
        assertEquals(listOf("54"), command.tabComplete(server.consoleSender, listOf("size", player.name, "5")))
        val rcon = Proxy.newProxyInstance(RemoteConsoleCommandSender::class.java.classLoader,
            arrayOf(RemoteConsoleCommandSender::class.java)) { _, method, args ->
            method.invoke(server.consoleSender, *(args ?: emptyArray()))
        } as RemoteConsoleCommandSender
        command.execute(rcon, listOf("size", player.name, "36"))
        assertEquals(1, actions.size)
        assertEquals(54, player.enderChest.size)
        actions.removeFirst().invoke()
        assertEquals(36, player.enderChest.size)
    }
}
