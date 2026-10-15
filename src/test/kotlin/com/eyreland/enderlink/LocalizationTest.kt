package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationTest : PluginTest() {
    private fun text(message: Component?) = PlainTextComponentSerializer.plainText().serialize(requireNotNull(message))

    @Test
    fun `bundled translations are complete and unknown locales fall back to English`() {
        val bundles = listOf("en_us", "zh_cn").map { language ->
            requireNotNull(javaClass.getResourceAsStream("/lang/$language.properties"))
                .reader(Charsets.UTF_8).use(::PropertyResourceBundle)
        }
        assertEquals(bundles[0].keySet(), bundles[1].keySet())
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            for (locale in listOf(Locale.US, Locale.UK, Locale.FRANCE, Locale.ROOT)) {
                val message = Messages.text(locale, "capacity.query", Component.text("Target"), Component.text(54))
                assertEquals("Target's ender chest has 54 slots.", PlainTextComponentSerializer.plainText().serialize(message))
            }
        } finally {
            Locale.setDefault(previous)
        }
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            for (key in bundles[0].keySet()) {
                val message = Messages.text(locale, key.removePrefix("enderlink."), Component.text("Target"), Component.text(54))
                assertFalse(PlainTextComponentSerializer.plainText().serialize(message).contains("enderlink."), key)
            }
        }
    }

    @Test
    fun `command responses use the sender language at reply time rather than the target language`() {
        val operator = server.addPlayer("Operator").also {
            it.setLocale(Locale.SIMPLIFIED_CHINESE)
            it.addAttachment(plugin, "enderlink.size", true)
        }
        val target = server.addPlayer("Target").also { it.setLocale(Locale.US) }
        val actions = ArrayDeque<() -> Unit>()
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {})
        val command = EnderLinkCommand(plugin, manager) { _, action, _ -> actions.add(action) }

        command.execute(operator, listOf("size", target.name))
        actions.removeFirst().invoke()
        actions.removeFirst().invoke()
        assertEquals("Target 的末影箱容量为 27 格", text(operator.nextComponentMessage()))

        command.execute(operator, listOf("size", target.name, "54"))
        actions.removeFirst().invoke()
        operator.setLocale(Locale.US)
        target.setLocale(Locale.SIMPLIFIED_CHINESE)
        actions.removeFirst().invoke()
        assertEquals("Set Target's ender chest capacity to 54 slots.", text(operator.nextComponentMessage()))
        assertEquals(54, target.enderChest.size)

        target.enderChest.setItem(53, ItemStack(Material.DIAMOND))
        command.execute(operator, listOf("size", target.name, "27"))
        actions.removeFirst().invoke()
        actions.removeFirst().invoke()
        val error = operator.nextComponentMessage()!!
        assertEquals(NamedTextColor.RED, error.color())
        assertEquals("Remove items from the slots beyond the requested capacity first.", PlainTextComponentSerializer.plainText().serialize(error))
        assertEquals(54, target.enderChest.size)

        operator.setLocale(Locale.SIMPLIFIED_CHINESE)
        command.execute(operator, listOf("size", target.name, "27"))
        actions.removeFirst().invoke()
        actions.removeFirst().invoke()
        assertEquals("请先取出超出目标容量的物品", text(operator.nextComponentMessage()))
    }

    @Test
    fun `console and RCON use English independently of the target locale`() {
        val target = server.addPlayer("Target").also { it.setLocale(Locale.SIMPLIFIED_CHINESE) }
        val command = EnderLinkCommand(plugin, EnderChestManager(plugin, ::resizeMockEnderChest, {})) { _, action, _ -> action() }
        val rcon = Proxy.newProxyInstance(RemoteConsoleCommandSender::class.java.classLoader,
            arrayOf(RemoteConsoleCommandSender::class.java)) { _, method, args ->
            method.invoke(server.consoleSender, *(args ?: emptyArray()))
        } as RemoteConsoleCommandSender
        for (sender in listOf(server.consoleSender, rcon)) {
            command.execute(sender, listOf("size", target.name))
            assertEquals("Target's ender chest has 27 slots.", text(server.consoleSender.nextComponentMessage()))
        }
    }

    @Test
    fun `validation and offline responses are localized`() {
        val player = server.addPlayer("Owner").also { it.setLocale(Locale.SIMPLIFIED_CHINESE) }
        val command = EnderLinkCommand(plugin, EnderChestManager(plugin, ::resizeMockEnderChest, {})) { _, action, _ -> action() }
        command.execute(player, listOf("size", player.name))
        assertEquals("你没有末影箱管理权限", text(player.nextComponentMessage()))
        player.addAttachment(plugin, "enderlink.size", true)
        for ((args, expected) in listOf(
            listOf("size") to "用法: /enderlink size <在线玩家> [27|36|45|54]",
            listOf("size", player.name, "28") to "格数必须为 27、36、45 或 54",
            listOf("size", "Missing") to "玩家不在线",
        )) {
            command.execute(player, args)
            assertEquals(expected, text(player.nextComponentMessage()))
        }
        player.openInventory(player.enderChest)
        command.execute(player, listOf("size", player.name, "54"))
        assertEquals("末影箱已打开，请先关闭", text(player.nextComponentMessage()))
        assertEquals(27, player.enderChest.size)
    }

    @Test
    fun `save failures retain rollback and shutdown behavior with localized feedback`() {
        val operator = server.addPlayer("Operator").also {
            it.setLocale(Locale.SIMPLIFIED_CHINESE)
            it.addAttachment(plugin, "enderlink.size", true)
        }
        val target = server.addPlayer("Target").also { it.setLocale(Locale.US) }
        var attempts = 0
        val manager = EnderChestManager(plugin, ::resizeMockEnderChest, {
            if (attempts++ == 0) error("disk unavailable")
        })
        EnderLinkCommand(plugin, manager) { _, action, _ -> action() }
            .execute(operator, listOf("size", target.name, "54"))
        assertEquals("末影箱容量保存失败，已恢复原容量", text(operator.nextComponentMessage()))
        assertEquals(27, target.enderChest.size)
        assertEquals(2, attempts)
        assertFalse(server.isStopping)

        plugin.logger.level = Level.OFF
        val failing = EnderChestManager(plugin, ::resizeMockEnderChest, { error("disk unavailable") })
        EnderLinkCommand(plugin, failing) { _, action, _ -> action() }
            .execute(operator, listOf("size", target.name, "54"))
        assertEquals("末影箱容量保存失败，无法确认原容量已恢复，服务器正在关闭", text(operator.nextComponentMessage()))
        assertTrue(server.isStopping)
    }

    @Test
    fun `unexpected failures send a translated message without exposing exception details`() {
        val player = server.addPlayer("Owner").also {
            it.setLocale(Locale.US)
            it.addAttachment(plugin, "enderlink.size", true)
        }
        plugin.logger.level = Level.OFF
        val manager = EnderChestManager(plugin, { _, _ -> error("internal player data path") }, {})
        EnderLinkCommand(plugin, manager) { _, action, _ -> action() }
            .execute(player, listOf("size", player.name, "54"))
        assertEquals("Could not change the ender chest capacity. Please contact an administrator.", text(player.nextComponentMessage()))
    }
}
