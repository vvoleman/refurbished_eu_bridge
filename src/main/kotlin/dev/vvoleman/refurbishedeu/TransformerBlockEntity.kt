package dev.vvoleman.refurbishedeu

import com.mrcrayfish.furniture.refurbished.blockentity.ElectricitySourceBlockEntity
import com.mrcrayfish.furniture.refurbished.blockentity.ILevelAudio
import com.mrcrayfish.furniture.refurbished.blockentity.IProcessingBlock
import com.mrcrayfish.furniture.refurbished.blockentity.LightswitchBlockEntity
import com.mrcrayfish.furniture.refurbished.electricity.IElectricityNode
import com.mrcrayfish.furniture.refurbished.client.audio.AudioManager
import ic2.api.energy.EnergyNet
import ic2.api.energy.tile.IEnergyEmitter
import ic2.api.energy.tile.IEnergySink
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/**
 * Accepts EU from an IC2 Classic grid and acts as a power *source* on a
 * Refurbished electricity network.
 *
 * Two things are deliberately kept separate:
 *
 *  - **Energising** the network (`setNodePowered`) happens whenever the buffer
 *    holds at least the standby cost. Refurbished appliances gate their own work
 *    behind `powered`, so if we de-energised whenever nothing was running, a
 *    stove with valid ingredients could never start: no power -> can't process
 *    -> reads as idle -> no power.
 *
 *  - **Billing** EU scales with real work: a standby trickle plus a per-appliance
 *    cost for each connected device actually doing something.
 */
class TransformerBlockEntity(pos: BlockPos, state: BlockState) :
    ElectricitySourceBlockEntity(RefurbishedEuBridge.EU_TRANSFORMER_BE.get(), pos, state),
    IEnergySink,
    MenuProvider,
    ILevelAudio {

    private var storedEu: Int = 0
    private var netRegistered: Boolean = false
    private var enabled: Boolean = true

    /** Last measured counts, refreshed on the load-scan interval. */
    private var connectedCount: Int = 0
    private var activeCount: Int = 0

    /**
     * Synced to the open screen. Note vanilla sends these as shorts, so every
     * value here must stay under 32767 - which is why BUFFER_EU is 10,000.
     */
    val dataAccess: ContainerData = object : ContainerData {
        override fun get(index: Int): Int = when (index) {
            DATA_STORED_EU -> storedEu
            DATA_CONNECTED -> connectedCount
            DATA_ACTIVE -> activeCount
            DATA_ENABLED -> if (enabled) 1 else 0
            DATA_OVERLOADED -> if (isNodeOverloaded) 1 else 0
            DATA_DRAW -> currentDraw()
            else -> 0
        }

        override fun set(index: Int, value: Int) {
            if (index == DATA_ENABLED) enabled = value != 0
        }

        override fun getCount(): Int = DATA_SIZE
    }

    // ---- Refurbished: powered state ------------------------------------------------

    override fun isNodePowered(): Boolean {
        val state = blockState
        return state.hasProperty(BlockStateProperties.POWERED) &&
            state.getValue(BlockStateProperties.POWERED)
    }

    override fun setNodePowered(powered: Boolean) {
        val state = blockState
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            level?.setBlock(
                worldPosition,
                state.setValue(BlockStateProperties.POWERED, powered),
                Block.UPDATE_ALL
            )
        }
    }

    // ---- IC2 Classic: energy sink ---------------------------------------------------

    override fun getSinkTier(): Int = TransformerConfig.SINK_TIER

    override fun getRequestedEnergy(): Int =
        if (!enabled) 0 else (TransformerConfig.BUFFER_EU - storedEu).coerceAtLeast(0)

    override fun acceptEnergy(direction: Direction?, amount: Int, voltage: Int): Int {
        if (!enabled) return amount
        val room = (TransformerConfig.BUFFER_EU - storedEu).coerceAtLeast(0)
        val taken = minOf(room, amount)
        storedEu += taken
        return amount - taken // IC2 expects the *unaccepted* remainder back
    }

    /**
     * Always true, even when switched off. Returning false makes IC2 drop us from
     * the grid entirely and the cable visibly disconnects; refusing the energy in
     * acceptEnergy() and asking for none keeps us wired but idle, which is what a
     * breaker should look like.
     */
    override fun canAcceptEnergy(emitter: IEnergyEmitter?, direction: Direction?): Boolean = true

    // ---- Tick ------------------------------------------------------------------------

    fun serverTick() {
        val level = this.level ?: return
        if (level.isClientSide) return

        if (!netRegistered) {
            EnergyNet.INSTANCE.addTile(this)
            netRegistered = true
        }

        if (!enabled) {
            if (isNodePowered) setNodePowered(false)
            if (level.gameTime % LOAD_CHECK_INTERVAL == 0L) {
                connectedCount = countConnectedDevices()
                activeCount = 0
            }
            return
        }

        // Refurbished's own ElectricityTicker calls earlyNodeTick() on us every
        // tick (its BlockEntityMixin registers any IElectricityNode on setLevel),
        // and that default already does the overload check and pushes power to
        // every reachable module. All we owe it is an honest powered flag.
        val energised = storedEu >= TransformerConfig.STANDBY_EU_PER_TICK
        if (energised != isNodePowered) setNodePowered(energised)
        if (!energised) return

        // earlyNodeTick() switches us off if the network is oversized. Don't bill
        // for a network we aren't actually powering.
        if (isNodeOverloaded) return

        if (level.gameTime % LOAD_CHECK_INTERVAL == 0L) {
            recountNetwork()
        }

        storedEu = (storedEu - currentDraw()).coerceAtLeast(0)
        setChanged()
    }

    private fun currentDraw(): Int =
        if (!enabled) 0 else TransformerConfig.STANDBY_EU_PER_TICK +
            activeCount * TransformerConfig.EU_PER_ACTIVE_APPLIANCE

    /**
     * Every device physically linked to us, regardless of whether power can
     * currently reach it.
     *
     * searchNodeNetwork() filters on canPowerTraverseNode(), so a branch behind a
     * switched-off lightswitch vanishes from it - which is right for billing but
     * wrong for a "connected devices" readout. The one-arg IElectricityNode
     * .searchNodes() passes `n -> true` for both predicates and walks the whole
     * graph, so it still sees them.
     */
    private fun countConnectedDevices(): Int {
        var connected = 0
        for (node in IElectricityNode.searchNodes(this)) {
            // searchNodes() seeds its visited set with the start node, so we are
            // in our own results. Filtering on isSourceNode drops us and also any
            // generator or second transformer sharing the network - none of those
            // are "devices".
            if (node.isSourceNode) continue
            // A switch is a control gate, not a consumer.
            if (node.nodeOwner is LightswitchBlockEntity) continue
            connected++
        }
        return connected
    }

    /**
     * Refresh both counts: everything linked, and how much of it is doing work.
     *
     * Only power-reachable devices can be active, so appliances behind an off
     * lightswitch correctly contribute to "connected" but never to "active".
     */
    private fun recountNetwork() {
        connectedCount = countConnectedDevices()
        var active = 0
        for (node in searchNodeNetwork(false).nodes()) {
            when (val owner = node.nodeOwner) {
                is LightswitchBlockEntity -> {}
                // Appliances count only while mid-process.
                is IProcessingBlock -> if (owner.processingTime > 0) active++
                // Anything else (lamps, TVs, third-party modules) counts as load.
                else -> if (owner !== this) active++
            }
        }
        activeCount = active
    }

    // ---- Menu -------------------------------------------------------------------------

    override fun getDisplayName(): Component = Component.translatable("block.refurbished_eu.eu_transformer")

    override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        TransformerMenu(id, inventory, this, dataAccess)

    fun togglePower() {
        enabled = !enabled
        setChanged()
        if (!enabled && isNodePowered) setNodePowered(false)
    }

    // ---- Audio --------------------------------------------------------------------------

    /**
     * Refurbished's AudioManager owns the looping sound: it starts one instance
     * per distinct ILevelAudio and drops it once canPlayAudio() goes false, so
     * all we do is offer ourselves every client tick.
     */
    fun clientTick() {
        AudioManager.get().playLevelAudio(this)
    }

    override fun getSound(): SoundEvent = RefurbishedEuBridge.EU_TRANSFORMER_HUM.get()

    override fun getSource(): SoundSource = SoundSource.BLOCKS

    override fun getAudioPosition(): Vec3 = Vec3.atCenterOf(worldPosition)

    override fun canPlayAudio(): Boolean = !isRemoved && isNodePowered

    /** Quieter than the generator - this is a wall transformer, not an engine. */
    override fun getAudioVolume(): Float = 0.4f

    override fun getAudioPitch(): Float = 0.8f

    override fun getAudioHash(): Int = worldPosition.hashCode()

    override fun isAudioEqual(other: ILevelAudio): Boolean =
        other is TransformerBlockEntity && other.worldPosition == worldPosition

    // ---- Energy net registration ------------------------------------------------------

    override fun onLoad() {
        super.onLoad()
        val level = this.level ?: return
        if (!level.isClientSide && !netRegistered) {
            EnergyNet.INSTANCE.addTile(this)
            netRegistered = true
        }
    }

    private fun unregister() {
        if (netRegistered) {
            EnergyNet.INSTANCE.removeTile(this)
            netRegistered = false
        }
    }

    override fun setRemoved() {
        unregister()
        super.setRemoved()
    }

    override fun onChunkUnloaded() {
        unregister()
        super.onChunkUnloaded()
    }

    // ---- Persistence -------------------------------------------------------------------

    override fun load(tag: CompoundTag) {
        super.load(tag)
        storedEu = tag.getInt("StoredEu")
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled")
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("StoredEu", storedEu)
        tag.putBoolean("Enabled", enabled)
    }

    companion object {
        const val DATA_STORED_EU = 0
        const val DATA_CONNECTED = 1
        const val DATA_ACTIVE = 2
        const val DATA_ENABLED = 3
        const val DATA_OVERLOADED = 4
        const val DATA_DRAW = 5
        const val DATA_SIZE = 6

        /** Re-scan for active load twice a second instead of every tick. */
        private const val LOAD_CHECK_INTERVAL = 10L
    }
}
