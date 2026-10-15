package com.eyreland.enderlink.platform

import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueOutput
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.spigotmc.SpigotConfig

object NativePlayerData {
    /** saveData 吞掉 I/O 错误，必须核对当前存档，不能使用 dat_old。 */
    fun saveVerified(player: Player) {
        check(!SpigotConfig.disablePlayerDataSaving) { "服务端禁止保存玩家存档" }
        val handle = (player as CraftPlayer).handle
        val problems = ProblemReporter.Collector()
        val output = TagValueOutput.createWithContext(problems, handle.registryAccess())
        handle.saveWithoutId(output)
        check(problems.isEmpty) { "玩家存档序列化失败：${problems.report}" }
        val expected = output.buildResult()
        player.saveData()
        val path = (player.server as CraftServer).server.playerList.playerIo.playerDir.toPath().resolve("${player.uniqueId}.dat")
        val stored = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
        for (key in listOf("Inventory", "EnderItems", "XpLevel", "XpP", "XpTotal", "BukkitValues")) {
            check(stored.get(key) == expected.get(key)) { "保存后的玩家数据与预期不一致：$key" }
        }
    }
}
