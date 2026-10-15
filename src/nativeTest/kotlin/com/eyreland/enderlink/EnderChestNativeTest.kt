package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import com.eyreland.enderlink.platform.NativePlayerData
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.kyori.adventure.text.Component
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.HashedStack
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.SlotRanges
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.spigotmc.SpigotConfig
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

/** 仅用于空白测试服。真实客户端每次被踢出后重连；阶段 2 前重启服务器。 */
class EnderChestNativeTest : JavaPlugin(), Listener {
    private lateinit var manager: EnderChestManager
    private val phaseKey = NamespacedKey("enderlink", "native_test_phase")
    private var cancelOpen = false
    private var openAttempts = 0
    private val commandHints = ConcurrentLinkedQueue<String>()

    override fun onEnable() {
        check(server.ip == "127.0.0.1" && !server.onlineMode) { "只能在绑定本机的空白离线测试服运行" }
        check(server.pluginManager.isPluginEnabled("enderlink"))
        manager = EnderChestManager(this)
        server.pluginManager.registerEvents(this, this)
        logger.info("ENDER_TEST_READY")
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun cancel(event: InventoryOpenEvent) {
        if (event.inventory.type == InventoryType.ENDER_CHEST) openAttempts++
        if (cancelOpen) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun join(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.runDelayed(this, { _ ->
            runTest {
                when (player.persistentDataContainer.getOrDefault(phaseKey, PersistentDataType.INTEGER, 0)) {
                    0 -> checkTitles(player)
                    1 -> {
                        verifySaved(player)
                        logger.info("ENDER_TEST_RELOGIN_OK")
                        player.health = 0.0
                        player.scheduler.runDelayed(this, { _ ->
                            player.spigot().respawn()
                            player.scheduler.runDelayed(this, { _ -> runTest {
                                verifySaved(player)
                                player.persistentDataContainer.set(phaseKey, PersistentDataType.INTEGER, 2)
                                NativePlayerData.saveVerified(player)
                                logger.info("ENDER_TEST_RESPAWN_OK_RESTART_REQUIRED")
                                server.shutdown()
                            } }, null, 20)
                        }, null, 5)
                    }
                    2 -> {
                        verifySaved(player)
                        player.enderChest.clear()
                        player.persistentDataContainer.set(phaseKey, PersistentDataType.INTEGER, 3)
                        NativePlayerData.saveVerified(player)
                        logger.info("ENDER_TEST_RESTART_AND_CLEAR_OK")
                        player.kick(Component.text("rejoin"))
                    }
                    3 -> {
                        check(player.enderChest.size == 54 && player.enderChest.isEmpty)
                        player.sendMessage(Component.text("ENDER_TEST_PASS"))
                        logger.info("ENDER_TEST_PASS")
                        server.shutdown()
                    }
                    else -> error("测试存档阶段无效")
                }
            }
        }, null, 20)
    }

    private fun checkTitles(player: Player) {
        val handle = (player as CraftPlayer).handle
        val packets = ConcurrentLinkedQueue<ClientboundOpenScreenPacket>()
        handle.connection.connection.channel.pipeline().addLast("ender_title_probe", object : ChannelOutboundHandlerAdapter() {
            override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                if (message is ClientboundOpenScreenPacket) packets.add(message)
                if (message is ClientboundSystemChatPacket && message.content.string.contains("/enderlink")) {
                    commandHints.add(message.content.string)
                }
                context.write(message, promise)
            }
        })
        fun next(index: Int) {
            if (index == EnderChestManager.SIZES.size * 2) {
                manager.setSize(player, 27)
                player.sendMessage(Component.text("ENDER_TITLE_CASES_DONE"))
                firstSession(player)
                return
            }
            val size = EnderChestManager.SIZES[index / 2]
            val command = index % 2 == 1
            manager.setSize(player, size)
            packets.clear()
            val source = if (command) "command" else "block"
            player.sendMessage(Component.text("ENDER_TITLE_EXPECT:$source:$size:END"))
            if (command) {
                check(!player.isOp && player.inventory.isEmpty)
                NativeClient.send(player, listOf(ServerboundChatCommandPacket("enderlink")))
            } else openBlock(player)
            var ticks = 0
            player.scheduler.runAtFixedRate(this, { task -> runTest {
                val packet = packets.poll()
                if (packet == null) {
                    check(++ticks < 100) { "$source $size did not open" }
                    return@runTest
                }
                task.cancel()
                val title = checkNotNull(packet.title.contents as? TranslatableContents) { "$source $size title: ${packet.title}" }
                check(title.key == "container.enderchest") { "$source $size title: ${packet.title}" }
                check(player.openInventory.topInventory == player.enderChest)
                check(player.openInventory.topInventory.size == size)
                logger.info("ENDER_TITLE_NATIVE_PASS source=$source rows=${size / 9} key=${title.key}")
                player.closeInventory()
                player.inventory.clear()
                next(index + 1)
            } }, null, 1, 1)
        }
        next(0)
    }

    private fun firstSession(player: Player) {
        checkBackpackHint(player) { checkRemoteAccess(player) { checkCreativeAccess(player) {
            (player as CraftPlayer).handle.connection.connection.channel.pipeline().remove("ender_title_probe")
            checkMenusAndSave(player)
        } } }
    }

    private fun checkBackpackHint(player: Player, complete: () -> Unit) {
        player.inventory.setItem(0, ItemStack(Material.ENDER_CHEST, 3))
        val menu = (player as CraftPlayer).handle.inventoryMenu
        val hints = commandHints.size
        val attempts = openAttempts
        NativeClient.send(player, listOf(ServerboundContainerClickPacket(menu.containerId, menu.stateId,
            36, 1, ContainerInput.PICKUP, Int2ObjectOpenHashMap(), HashedStack.EMPTY)))
        var ticks = 0
        player.scheduler.runAtFixedRate(this, { task -> runTest {
            if (commandHints.size > hints) {
                task.cancel()
                check(player.openInventory.type == InventoryType.CRAFTING && openAttempts == attempts)
                check(player.inventory.getItem(0) == ItemStack(Material.ENDER_CHEST, 3) && player.itemOnCursor.type.isAir)
                player.inventory.clear()
                logger.info("ENDER_BACKPACK_HINT_NATIVE_PASS")
                complete()
            } else check(++ticks < 100) { "背包右键未提示指令" }
        } }, null, 1, 1)
    }

    private fun checkRemoteAccess(player: Player, complete: () -> Unit) {
        player.closeInventory()
        player.inventory.clear()
        manager.setSize(player, 54)
        player.enderChest.setItem(53, ItemStack(Material.DIAMOND, 7))
        val request = net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
            net.minecraft.network.protocol.common.custom.DiscardedPayload(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("enderlink", "open_v1"), byteArrayOf(),
            ),
        )
        cancelOpen = true
        NativeClient.send(player, listOf(request))
        player.scheduler.runDelayed(this, { runTest {
            check(player.openInventory.type == InventoryType.CRAFTING) { "原生事件必须阻止客户端请求" }
            cancelOpen = false
            val previous = openAttempts
            NativeClient.send(player, listOf(request, request))
            var ticks = 0
            player.scheduler.runAtFixedRate(this, { task -> runTest {
                if (player.openInventory.type == InventoryType.ENDER_CHEST) {
                    task.cancel()
                    check(player.openInventory.topInventory == player.enderChest && player.enderChest.size == 54)
                    check(player.enderChest.getItem(53) == ItemStack(Material.DIAMOND, 7))
                    check(player.inventory.isEmpty && openAttempts == previous + 1)
                    player.closeInventory()
                    player.enderChest.clear()
                    manager.setSize(player, 27)
                    logger.info("ENDER_REMOTE_NATIVE_PASS")
                    complete()
                } else check(++ticks < 100) { "客户端开箱请求未打开原生菜单" }
            } }, null, 1, 1)
        } }, null, 20)
    }

    private fun checkCreativeAccess(player: Player, complete: () -> Unit) {
        val previousMode = player.gameMode
        player.gameMode = GameMode.CREATIVE
        player.inventory.clear()
        player.inventory.heldItemSlot = 0
        var sequence = 0
        fun await(label: String, ready: () -> Boolean, after: () -> Unit) {
            var ticks = 0
            player.scheduler.runAtFixedRate(this, { task -> runTest {
                if (ready()) { task.cancel(); after() }
                else check(++ticks < 100) { "$label timed out" }
            } }, null, 1, 1)
        }
        fun next(index: Int) {
            if (index == EnderChestManager.SIZES.size * 2) {
                player.gameMode = previousMode
                manager.setSize(player, 27)
                player.sendMessage(Component.text("CREATIVE_ENDER_CASES_DONE"))
                checkBlockAccess(player, sequence, complete)
                return
            }
            val size = EnderChestManager.SIZES[index / 2]
            val hand = if (index % 2 == 0) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            manager.setSize(player, size)
            player.inventory.setItemInMainHand(ItemStack(if (hand == InteractionHand.MAIN_HAND) Material.ENDER_CHEST else Material.AIR, 32))
            player.inventory.setItemInOffHand(ItemStack(Material.ENDER_CHEST, 16))
            val before = player.inventory.contents.map { it?.clone() }
            val hints = commandHints.size
            val attempts = openAttempts
            player.sendMessage(Component.text("CREATIVE_ENDER_EXPECT:${hand.name}:$size:END"))
            val packets = mutableListOf(ServerboundUseItemPacket(hand, ++sequence, 0f, -90f))
            if (hand == InteractionHand.MAIN_HAND) packets.add(ServerboundUseItemPacket(InteractionHand.OFF_HAND, ++sequence, 0f, -90f))
            NativeClient.send(player, packets)
            await("creative $hand $size hint", { commandHints.size > hints }) {
                check(player.openInventory.topInventory != player.enderChest && openAttempts == attempts)
                check(player.inventory.contents.toList() == before) { "creative air use changed items" }
                logger.info("CREATIVE_ENDER_NATIVE_PASS hand=$hand rows=${size / 9} items=unchanged")
                player.sendMessage(Component.text("CREATIVE_ENDER_CASE_PASS:${hand.name}:$size:END"))
                player.closeInventory()
                player.inventory.clear()
                // 分隔双手输入批次，避免触发原版交互限流。
                player.scheduler.runDelayed(this, { runTest { next(index + 1) } }, null, 2)
            }
        }
        next(0)
    }

    private fun checkBlockAccess(player: Player, initialSequence: Int, complete: () -> Unit) {
        val handle = (player as CraftPlayer).handle
        val previousMode = player.gameMode
        val previousSneaking = player.isSneaking
        val acknowledgements = ConcurrentLinkedQueue<Int>()
        val pipeline = handle.connection.connection.channel.pipeline()
        pipeline.addLast("ender_block_probe", object : ChannelOutboundHandlerAdapter() {
            override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                if (message is ClientboundBlockChangedAckPacket) acknowledgements.add(message.sequence())
                context.write(message, promise)
            }
        })
        var sequence = initialSequence
        fun await(label: String, ready: () -> Boolean, after: () -> Unit) {
            var ticks = 0
            player.scheduler.runAtFixedRate(this, { task -> runTest {
                if (ready()) { task.cancel(); after() }
                else check(++ticks < 100) { "$label timed out" }
            } }, null, 1, 1)
        }
        fun next(index: Int) {
            if (index == 4) {
                pipeline.remove("ender_block_probe")
                player.gameMode = previousMode
                player.isSneaking = previousSneaking
                player.sendMessage(Component.text("ENDER_BLOCK_CASES_DONE"))
                complete()
                return
            }
            val mode = if (index < 2) GameMode.SURVIVAL else GameMode.CREATIVE
            val hand = if (index % 2 == 0) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            val slot = if (hand == InteractionHand.MAIN_HAND) org.bukkit.inventory.EquipmentSlot.HAND else org.bukkit.inventory.EquipmentSlot.OFF_HAND
            player.gameMode = mode
            player.inventory.clear()
            player.inventory.setItem(slot, ItemStack(Material.ENDER_CHEST))
            val base = player.location.block.getRelative(2, -1, 0)
            val placed = base.getRelative(0, 1, 0)
            base.type = if (mode == GameMode.CREATIVE) Material.COMMAND_BLOCK else Material.STONE
            placed.type = Material.AIR
            fun use() = NativeClient.send(player, listOf(ServerboundUseItemOnPacket(hand,
                BlockHitResult(Vec3(base.x + 0.5, base.y + 1.0, base.z + 0.5), Direction.UP,
                    BlockPos(base.x, base.y, base.z), false), ++sequence)))
            player.isSneaking = false
            val attempts = openAttempts
            val hints = commandHints.size
            // 先同步测试初始状态，后续必须再收到取消放置产生的库存同步。
            handle.inventoryMenu.sendAllDataToRemote()
            val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), handle.registryAccess())
            val expected = try {
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, handle.getItemInHand(hand))
                Base64.getEncoder().encodeToString(ByteArray(buffer.readableBytes()).also(buffer::readBytes))
            } finally { buffer.release() }
            val label = "${mode.name}:${hand.name}"
            player.sendMessage(Component.text("ENDER_BLOCK_EXPECT:$label:$expected:END"))
            use()
            await(label, { commandHints.size > hints && acknowledgements.any { it >= sequence } }) {
                check(placed.type == Material.AIR)
                check(player.inventory.getItem(slot) == ItemStack(Material.ENDER_CHEST))
                check(handle.containerMenu === handle.inventoryMenu && openAttempts == attempts)
                player.sendMessage(Component.text("ENDER_BLOCK_CASE_PASS:$label:END"))
                player.isSneaking = true
                use()
                await("$mode $hand sneaking placement", { placed.type == Material.ENDER_CHEST }) {
                    check(handle.containerMenu === handle.inventoryMenu)
                    if (mode == GameMode.CREATIVE) check(player.inventory.getItem(slot) == ItemStack(Material.ENDER_CHEST))
                    else check(player.inventory.getItem(slot).type.isAir)
                    placed.type = Material.AIR
                    player.inventory.clear()
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    private fun checkMenusAndSave(player: Player) {
        check(player.enderChest.size == 27)
        val handle = (player as CraftPlayer).handle
        val reference = player.enderChest
        val nativeReference = handle.enderChestInventory
        for (size in EnderChestManager.SIZES) {
            manager.setSize(player, size)
            check(reference === player.enderChest && nativeReference === handle.enderChestInventory)
            check(reference.type == InventoryType.ENDER_CHEST && reference.contents.size == size)
            check(reference.toList().size == size)
            check(SlotRanges.nameToIds("enderchest.*")!!.slots().count { handle.getSlot(it) != null } == size)
            reference.setItem(size - 1, ItemStack(Material.DIAMOND, 7))
            openBlock(player)
            val menu = handle.containerMenu as ChestMenu
            check(menu.rowCount * 9 == size && menu.slots.size == size + 36)
            check(player.openInventory.topInventory == reference)
            check(reference.holder == player && reference.location?.block?.type == Material.ENDER_CHEST)
            expectFailure { manager.setSize(player, if (size == 54) 27 else 54) }
            menu.clicked(size - 1, 0, ContainerInput.QUICK_MOVE, handle)
            check(reference.isEmpty && player.inventory.contents.filterNotNull().sumOf { if (it.type == Material.DIAMOND) it.amount else 0 } == 7)
            player.inventory.clear()
            player.inventory.setItemInOffHand(ItemStack(Material.EMERALD, 8))
            menu.clicked(size - 1, 40, ContainerInput.SWAP, handle)
            check(reference.getItem(size - 1)?.amount == 8 && player.inventory.itemInOffHand.type.isAir)
            reference.clear()
            menu.carried = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 8)
            menu.clicked(-999, 0, ContainerInput.QUICK_CRAFT, handle)
            menu.clicked(size - 2, 1, ContainerInput.QUICK_CRAFT, handle)
            menu.clicked(size - 1, 1, ContainerInput.QUICK_CRAFT, handle)
            menu.clicked(-999, 2, ContainerInput.QUICK_CRAFT, handle)
            check(reference.getItem(size - 2)?.amount == 4 && reference.getItem(size - 1)?.amount == 4)
            player.closeInventory()
            check(reference.viewers.isEmpty())
            reference.clear()
            check(player.openInventory(reference)!!.topInventory.size == size)
            player.closeInventory()
            cancelOpen = true
            try { openBlock(player) } finally { cancelOpen = false }
            check(handle.containerMenu === handle.inventoryMenu && reference.viewers.isEmpty())
        }
        reference.setItem(53, ItemStack(Material.DIAMOND))
        expectFailure { manager.setSize(player, 27) }
        check(reference.size == 54 && reference.getItem(53)?.type == Material.DIAMOND)
        reference.clear()
        manager.setSize(player, 27)
        manager.setSize(player, 54)
        SpigotConfig.disablePlayerDataSaving = true
        try { expectFailure { NativePlayerData.saveVerified(player) } } finally { SpigotConfig.disablePlayerDataSaving = false }
        reference.clear()
        reference.setItem(53, ItemStack(Material.DIAMOND, 7).apply {
            itemMeta = itemMeta.apply { displayName(Component.text("Ender checkpoint")) }
        })
        player.persistentDataContainer.set(phaseKey, PersistentDataType.INTEGER, 1)
        NativePlayerData.saveVerified(player)
        player.loadData()
        verifySaved(player)
        logger.info("ENDER_TEST_MENUS_AND_SAVE_OK")
        player.kick(Component.text("rejoin"))
    }

    private fun verifySaved(player: Player) {
        check(player.enderChest.size == 54)
        val item = player.enderChest.getItem(53) ?: error("额外槽位物品丢失")
        check(item.type == Material.DIAMOND && item.amount == 7)
        check(item.itemMeta.displayName() == Component.text("Ender checkpoint"))
    }

    private fun openBlock(player: Player) {
        val handle = (player as CraftPlayer).handle
        val block = player.location.block.getRelative(1, 0, 0)
        block.type = Material.ENDER_CHEST
        block.getRelative(0, 1, 0).type = Material.AIR
        val pos = BlockPos(block.x, block.y, block.z)
        val level = handle.level()
        level.getBlockState(pos).useWithoutItem(level, handle, BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false))
    }

    private fun expectFailure(action: () -> Unit) {
        check(runCatching(action).isFailure) { "操作应被拒绝" }
    }

    private fun runTest(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ENDER_TEST_FAIL", failure)
            server.shutdown()
        }
    }
}
