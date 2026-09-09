package dev.vvoleman.refurbishedeu

import com.mrcrayfish.furniture.refurbished.electricity.IElectricityNode
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * The devices on a transformer's network, as the client can see them.
 *
 * Refurbished syncs node links to tracking clients - that is how its block
 * entity renderer draws the connection lines and picks a powered or neutral node
 * model - so the same graph walk the block entity does on the server can be done
 * here, with no extra packets.
 *
 * What the client knows per device is whether it is *receiving power*, which is
 * not the same thing as the server's "working" count: a lamp under power is
 * working, a microwave under power need not be baking anything. So the roster is
 * deliberately named after power, and the working count stays a server number
 * shown as text beside it.
 */
class DeviceRoster private constructor(val devices: List<Device>) {

    class Device(val name: Component, val pos: BlockPos, val powered: Boolean)

    val poweredCount: Int get() = devices.count { it.powered }

    operator fun get(index: Int): Device? = devices.getOrNull(index)

    /**
     * Whether this roster can be trusted to describe the same set the server
     * counted. A device in an unloaded chunk, or a link that arrived a tick ago,
     * makes the two disagree - and a grid that maps devices to squares is worse
     * than useless if the mapping is wrong, so the caller falls back to drawing
     * bare counts instead.
     */
    fun agreesWith(connectedCount: Int): Boolean = devices.size == connectedCount

    companion object {

        val EMPTY = DeviceRoster(emptyList())

        /**
         * Walks the network from [transformer] on the client.
         *
         * Sorted by position so a device keeps the same square from one scan to
         * the next: the point of the grid is that its pattern means something
         * over time, which a list that reorders itself could never do.
         */
        fun scan(transformer: TransformerBlockEntity): DeviceRoster {
            val nodes = try {
                IElectricityNode.searchNodes(transformer)
            } catch (e: Exception) {
                // The walk crosses block entities the client may be mid-way
                // through syncing. Degrading to bare counts is a blemish; taking
                // the whole screen down with an exception every frame is not.
                return EMPTY
            }

            val devices = nodes.asSequence()
                // Drops the transformer itself, plus any generator or second
                // transformer on the network - the same nodes the server's own
                // count skips.
                .filter { !it.isSourceNode }
                .sortedWith(
                    compareBy({ it.nodePosition.y }, { it.nodePosition.x }, { it.nodePosition.z })
                )
                .map { node ->
                    Device(
                        name = deviceName(node),
                        pos = node.nodePosition,
                        // isNodePowered, not isNodeReceivingPower: the latter is a
                        // plain server-side field that writeNodeNbt never sends,
                        // so on the client it reads false for everything. This one
                        // comes from the device's own synced state - the POWERED
                        // block state property for lamps, switches and the like,
                        // and an update-tag field for the appliances - and it is
                        // what Refurbished itself trusts to colour a live wire.
                        powered = node.isNodePowered
                    )
                }
                .toList()

            return DeviceRoster(devices)
        }

        private fun deviceName(node: IElectricityNode): Component {
            val owner = node.nodeOwner ?: return Component.empty()
            return owner.blockState.block.name
        }
    }
}
