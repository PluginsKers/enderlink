package com.eyreland.enderlink

import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.inventory.InventoryMock

/** MockBukkit 没有原生容器，保留库存对象并扩展它的槽位数组。 */
fun resizeMockEnderChest(player: Player, size: Int) {
    val inventory = player.enderChest
    val items = inventory.contents.copyOf(size)
    InventoryMock::class.java.getDeclaredField("items").apply { isAccessible = true }.set(inventory, items)
}
