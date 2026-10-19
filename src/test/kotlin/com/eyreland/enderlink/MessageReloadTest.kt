package com.eyreland.enderlink

import com.eyreland.enderlink.application.EnderChestManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.inventory.ItemStack
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageReloadTest : PluginTest() {
    private fun file(language: String) = File(plugin.dataFolder, "lang/$language.properties")
    private fun text(component: Component?) = PlainTextComponentSerializer.plainText().serialize(requireNotNull(component))
    private fun message(locale: Locale, key: String) = text(Messages.text(locale, key))
    private fun command() = EnderLinkCommand(plugin, EnderChestManager(plugin, ::resizeMockEnderChest, {})) { _, action, _ -> action() }

    @Test
    fun `reload reads overrides without overwriting files and restores missing defaults`() {
        assertTrue(file("en_us").isFile)
        assertTrue(file("zh_cn").isFile)
        file("en_us").writeText("enderlink.capacity.query={0}'s storage has {1} slots.\n")
        file("zh_cn").writeText("enderlink.capacity.query={0} 的随身仓库有 {1} 格\n")
        Messages.reload(plugin)
        assertEquals("enderlink.capacity.query={0}'s storage has {1} slots.\n", file("en_us").readText())

        val player = server.addPlayer("Owner").also {
            it.setLocale(Locale.SIMPLIFIED_CHINESE)
            it.addAttachment(plugin, "enderlink.size.query", true)
        }
        val command = command()
        command.execute(player, listOf("size", player.name))
        assertEquals("Owner 的随身仓库有 27 格", text(player.nextComponentMessage()))
        player.setLocale(Locale.FRANCE)
        command.execute(player, listOf("size", player.name))
        assertEquals("Owner's storage has 27 slots.", text(player.nextComponentMessage()))
        assertEquals("[打开]", message(Locale.SIMPLIFIED_CHINESE, "open"))

        file("zh_cn").writeText("")
        assertTrue(file("en_us").delete())
        Messages.reload(plugin)
        assertTrue(file("en_us").isFile)
        command.execute(player, listOf("size", player.name))
        assertEquals("Owner's ender chest has 27 slots.", text(player.nextComponentMessage()))
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        command.execute(player, listOf("size", player.name))
        assertEquals("Owner 的末影箱容量为 27 格", text(player.nextComponentMessage()))
    }

    @Test
    fun `invalid or unreadable language files preserve all active translations`() {
        file("en_us").writeText("enderlink.open=[Stored]\n")
        file("zh_cn").writeText("enderlink.open=[已保存]\n")
        Messages.reload(plugin)
        file("en_us").writeText("enderlink.open=[Changed]\n")
        for (invalid in listOf("enderlink.open={0", "enderlink.open=\\uZZZZ")) {
            file("zh_cn").writeText(invalid)
            assertFailsWith<IllegalArgumentException> { Messages.reload(plugin) }
            assertEquals("[Stored]", message(Locale.US, "open"))
            assertEquals("[已保存]", message(Locale.SIMPLIFIED_CHINESE, "open"))
        }
        assertTrue(file("zh_cn").delete())
        assertTrue(file("zh_cn").mkdir())
        assertFailsWith<IOException> { Messages.reload(plugin) }
        assertEquals("[Stored]", message(Locale.US, "open"))
        assertEquals("[已保存]", message(Locale.SIMPLIFIED_CHINESE, "open"))
        assertFalse(server.isStopping)
    }

    @Test
    fun `reload permission and completion are independent of capacity management`() {
        val player = server.addPlayer("Owner").also { it.setLocale(Locale.SIMPLIFIED_CHINESE) }
        val operator = server.addPlayer("Operator").also { it.setOp(true) }
        val command = command()
        assertTrue(command.tabComplete(player, listOf("")).isEmpty())
        assertEquals(listOf("reload"), command.tabComplete(operator, listOf("RE")))
        player.addAttachment(plugin, "enderlink.size", true)
        command.execute(player, listOf("reload"))
        assertEquals("你没有重载语言文件的权限", text(player.nextComponentMessage()))
        assertEquals(listOf("size"), command.tabComplete(player, listOf("")))

        player.addAttachment(plugin, "enderlink.size", false)
        player.addAttachment(plugin, "enderlink.reload", true)
        assertEquals(listOf("reload"), command.tabComplete(player, listOf("")))
        assertTrue(command.tabComplete(player, listOf("size", "")).isEmpty())
        assertTrue(command.tabComplete(player, listOf("reload", "")).isEmpty())
        file("zh_cn").writeText("enderlink.reload.success=新文案已生效\n")
        command.execute(player, listOf("reload", "extra"))
        assertEquals("用法: /enderlink [reload | size <在线玩家> [27|36|45|54]]", text(player.nextComponentMessage()))
        assertEquals("语言文件已重载", message(Locale.SIMPLIFIED_CHINESE, "reload.success"))

        resizeMockEnderChest(player, 54)
        val inventory = player.enderChest
        val item = ItemStack(Material.DIAMOND, 7)
        inventory.setItem(53, item)
        player.openInventory(inventory)
        command.execute(player, listOf("RELOAD"))
        assertEquals("新文案已生效", text(player.nextComponentMessage()))
        assertSame(inventory, player.openInventory.topInventory)
        assertEquals(54, inventory.size)
        assertEquals(item, inventory.getItem(53))
        assertFalse(server.isStopping)
    }

    @Test
    fun `console and RCON can reload and failures report the retained messages`() {
        val rcon = Proxy.newProxyInstance(RemoteConsoleCommandSender::class.java.classLoader,
            arrayOf(RemoteConsoleCommandSender::class.java)) { _, method, args ->
            method.invoke(server.consoleSender, *(args ?: emptyArray()))
        } as RemoteConsoleCommandSender
        val command = command()
        for (sender in listOf(server.consoleSender, rcon)) {
            assertEquals(listOf("reload"), command.tabComplete(sender, listOf("rel")))
            command.execute(sender, listOf("reload"))
            assertEquals("Language files reloaded.", text(server.consoleSender.nextComponentMessage()))
        }
        plugin.logger.level = Level.OFF
        file("en_us").writeText("enderlink.reload.failed=This should not be loaded.\n")
        file("zh_cn").writeText("enderlink.open={broken")
        command.execute(server.consoleSender, listOf("reload"))
        assertEquals("Could not reload language files. Current messages have been kept. Check the server log.",
            text(server.consoleSender.nextComponentMessage()))
        assertEquals("[Open]", message(Locale.US, "open"))
        assertFalse(server.isStopping)
    }
}
