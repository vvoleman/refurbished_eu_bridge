package dev.vvoleman.refurbishedeu

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import java.util.function.Supplier

/**
 * Refurbished has a generic MessageSetName that would have worked here, but it
 * rides on MrCrayfish's Framework networking rather than a documented addon API.
 * A private channel is a few lines and doesn't couple us to their internals.
 */
object ModNetwork {

    private const val PROTOCOL = "1"

    val CHANNEL: SimpleChannel = NetworkRegistry.ChannelBuilder
        .named(RefurbishedEuBridge.id("main"))
        .clientAcceptedVersions { it == PROTOCOL }
        .serverAcceptedVersions { it == PROTOCOL }
        .networkProtocolVersion { PROTOCOL }
        .simpleChannel()

    /** Names longer than this are refused outright rather than silently truncated. */
    const val MAX_NAME_LENGTH = 32

    fun register() {
        CHANNEL.registerMessage(
            0,
            SetNamePacket::class.java,
            { msg, buf -> msg.encode(buf) },
            { buf -> SetNamePacket.decode(buf) },
            { msg, ctx -> msg.handle(ctx) }
        )
    }
}

class SetNamePacket(private val pos: BlockPos, private val name: String) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeUtf(name, ModNetwork.MAX_NAME_LENGTH)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            val player = context.sender ?: return@enqueueWork
            val level = player.level
            // Never trust a client-supplied position: check it is loaded and in reach.
            if (!level.isLoaded(pos)) return@enqueueWork
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0) return@enqueueWork
            val be = level.getBlockEntity(pos)
            if (be is TransformerBlockEntity) {
                be.setName(player, name)
            }
        }
        context.packetHandled = true
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): SetNamePacket =
            SetNamePacket(buf.readBlockPos(), buf.readUtf(ModNetwork.MAX_NAME_LENGTH))
    }
}
