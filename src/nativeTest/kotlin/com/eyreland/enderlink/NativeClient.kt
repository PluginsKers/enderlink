package com.eyreland.enderlink

import io.netty.buffer.Unpooled
import net.kyori.adventure.text.Component
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.network.protocol.game.ServerGamePacketListener
import org.bukkit.GameMode
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.Base64

internal object NativeClient {
    fun send(player: Player, packets: List<Packet<in ServerGamePacketListener>>) {
        val codec = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator((player as CraftPlayer).handle.registryAccess())) {
            player.gameMode == GameMode.CREATIVE
        }.codec()
        val frames = FriendlyByteBuf(Unpooled.buffer())
        try {
            packets.forEach { packet ->
                val bytes = Unpooled.buffer()
                try { codec.encode(bytes, packet); frames.writeVarInt(bytes.readableBytes()); frames.writeBytes(bytes) } finally { bytes.release() }
            }
            val bytes = ByteArray(frames.readableBytes()).also(frames::readBytes)
            player.sendMessage(Component.text("NATIVE_WIRE:${Base64.getEncoder().encodeToString(bytes)}:END"))
        } finally { frames.release() }
    }
}
