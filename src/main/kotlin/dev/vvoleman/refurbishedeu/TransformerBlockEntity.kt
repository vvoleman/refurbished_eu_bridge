package dev.vvoleman.refurbishedeu

import com.mrcrayfish.furniture.refurbished.blockentity.ElectricitySourceBlockEntity
import com.mrcrayfish.furniture.refurbished.blockentity.ILevelAudio
import com.mrcrayfish.furniture.refurbished.blockentity.IHomeControlDevice
import com.mrcrayfish.furniture.refurbished.blockentity.INameable
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
import net.minecraft.server.level.ServerPlayer
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
    ILevelAudio,
    INameable,
    IHomeControlDevice {

    /** Derived from the block, so a single block entity class serves all tiers. */
    val tier: TransformerTier
        get() = (blockState.block as? TransformerBlock)?.tier ?: TransformerTier.LOW

    /** Player-assigned label, shown above the block and in the GUI. */
    var customName: String? = null
        private set

    var storedEu: Int = 0
        private set

    private var netRegistered: Boolean = false

    /** Who owns the on/off state. */
    var controlMode: ControlMode = ControlMode.MANUAL
        private set

    /** The hand-set state, remembered across a trip through REDSTONE mode. */
    private var manualEnabled: Boolean = true

    /**
     * Cached redstone reading. IC2 calls getRequestedEnergy()/acceptEnergy() from
     * its own energy net pass rather than from our ticker, so resolving `enabled`
     * must not mean six neighbour lookups on every call.
     */
    private var redstonePowered: Boolean = false

    val enabled: Boolean
        get() = when (controlMode) {
            ControlMode.MANUAL -> manualEnabled
            ControlMode.REDSTONE -> redstonePowered
        }

    /** Last measured counts, refreshed on the load-scan interval. */
    var connectedCount: Int = 0
        private set

    var activeCount: Int = 0
        private set

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
            DATA_BUFFER_MAX -> TransformerConfig.buffer(tier)
            DATA_TIER -> TransformerConfig.sinkTier(tier)
            DATA_MAX_DEVICES -> TransformerConfig.maxDevices(tier)
            DATA_CONTROL_MODE -> controlMode.ordinal
            else -> 0
        }

        /**
         * Unused in practice - the client menu is built on a SimpleContainerData,
         * so sync writes land there rather than here, and every server-side change
         * goes through the methods below. Left as a no-op rather than a
         * half-correct setter: DATA_ENABLED reports the *effective* state, which
         * has no single field to write it back to.
         */
        override fun set(index: Int, value: Int) = Unit

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

    override fun getSinkTier(): Int = TransformerConfig.sinkTier(tier)

    /** Devices this transformer can power before Refurbished flags it overloaded. */
    override fun getMaxPowerableNodes(): Int = TransformerConfig.maxDevices(tier)

    /** Direct wrench links. Matched to the device cap so neither is a hidden bottleneck. */
    override fun getNodeMaximumConnections(): Int = TransformerConfig.maxDevices(tier)

    override fun getRequestedEnergy(): Int =
        if (!enabled) 0 else (TransformerConfig.buffer(tier) - storedEu).coerceAtLeast(0)

    override fun acceptEnergy(direction: Direction?, amount: Int, voltage: Int): Int {
        if (!enabled) return amount
        val room = (TransformerConfig.buffer(tier) - storedEu).coerceAtLeast(0)
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

        // neighborChanged() handles ordinary redstone edits the instant they happen.
        // This is the safety net for anything that changes the signal without
        // notifying us, so it can run on the slow interval rather than every tick -
        // hasNeighborSignal() is six block lookups.
        if (controlMode == ControlMode.REDSTONE &&
            level.gameTime % TransformerConfig.loadCheckInterval() == 0L
        ) {
            refreshRedstone()
        }

        if (!enabled) {
            if (isNodePowered) setNodePowered(false)
            if (level.gameTime % TransformerConfig.loadCheckInterval() == 0L) {
                connectedCount = countConnectedDevices()
                activeCount = 0
            }
            return
        }

        // Refurbished's own ElectricityTicker calls earlyNodeTick() on us every
        // tick (its BlockEntityMixin registers any IElectricityNode on setLevel),
        // and that default already does the overload check and pushes power to
        // every reachable module. All we owe it is an honest powered flag.
        // maxOf(1, ..) matters: standbyEuPerTick is allowed to be 0, and a bare
        // `storedEu >= 0` would keep the network live on an empty buffer forever.
        val energised = storedEu >= maxOf(1, TransformerConfig.standbyEu())
        if (energised != isNodePowered) setNodePowered(energised)
        if (!energised) return

        // earlyNodeTick() switches us off if the network is oversized, and it will
        // never re-evaluate while the flag is set - its whole body is guarded on
        // !isNodeOverloaded(). Nothing else clears it and it persists to NBT, so
        // we have to re-check here or the block stays overloaded forever.
        if (isNodeOverloaded) {
            if (level.gameTime % TransformerConfig.loadCheckInterval() == 0L) {
                if (!searchNodeNetwork(false).overloaded()) {
                    setNodeOverloaded(false)
                } else {
                    connectedCount = countConnectedDevices()
                    activeCount = 0
                }
            }
            return
        }

        if (level.gameTime % TransformerConfig.loadCheckInterval() == 0L) {
            recountNetwork()
        }

        storedEu = (storedEu - currentDraw()).coerceAtLeast(0)
        setChanged()
    }

    fun currentDraw(): Int =
        if (!enabled) 0 else TransformerConfig.standbyEu() +
            activeCount * TransformerConfig.euPerActive()

    /**
     * Every node physically linked to us that occupies a slot in the network cap,
     * regardless of whether power can currently reach it.
     *
     * This has to count exactly what Refurbished weighs against
     * getMaxPowerableNodes(), or the GUI promises headroom the network does not
     * have. ISourceNode.searchNodeNetwork() ends in
     * `nodes.size() > getMaxPowerableNodes()` over every visited node that is not
     * a source - lightswitches very much included, since a switch is a node on the
     * network even though it consumes nothing. Excluding them here read one low
     * per switch, so a cap of 8 showed "7/8" on a network the eighth device would
     * overload.
     *
     * searchNodeNetwork() filters on canPowerTraverseNode(), so a branch behind a
     * switched-off lightswitch vanishes from it - which is right for billing but
     * wrong for a "connected devices" readout. The one-arg IElectricityNode
     * .searchNodes() passes `n -> true` for both predicates and walks the whole
     * graph, so it still sees them. That makes this count a ceiling on the number
     * Refurbished tests: never lower, and higher only for devices that would join
     * the moment their switch is flicked on - which is the safe direction to err.
     */
    private fun countConnectedDevices(): Int {
        var connected = 0
        for (node in IElectricityNode.searchNodes(this)) {
            // searchNodes() seeds its visited set with the start node, so we are
            // in our own results. Filtering on isSourceNode drops us and also any
            // generator or second transformer sharing the network - none of those
            // are "devices", and Refurbished's cap check skips them the same way.
            if (node.isSourceNode) continue
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

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        TransformerMenu(id, inventory, this, dataAccess, worldPosition)

    /**
     * Implementing INameable means Refurbished's own systems (its HomeControl
     * computer app, for instance) can rename the transformer too, not just our GUI.
     */
    override fun setName(player: ServerPlayer?, name: String?) {
        val trimmed = name?.trim().orEmpty()
        customName = trimmed.take(ModNetwork.MAX_NAME_LENGTH).ifEmpty { null }
        setChanged()
        // ContainerData carries ints only, so the name reaches clients through the
        // block update packet instead.
        sendUpdate()
    }

    // ---- Control ------------------------------------------------------------------------

    /**
     * @return false if the transformer is under redstone control, where the on/off
     *   state belongs to the world and nothing else may set it.
     */
    fun setEnabled(value: Boolean): Boolean {
        if (controlMode != ControlMode.MANUAL) return false
        if (manualEnabled == value) return true
        manualEnabled = value
        afterEnabledChanged()
        return true
    }

    fun togglePower(): Boolean = setEnabled(!enabled)

    fun cycleControlMode() {
        setControlMode(controlMode.next())
    }

    fun setControlMode(mode: ControlMode) {
        if (controlMode == mode) return
        controlMode = mode
        // Read the world immediately so the GUI doesn't show a stale state for the
        // tick between the switch and the next serverTick().
        if (mode == ControlMode.REDSTONE) refreshRedstone()
        afterEnabledChanged()
    }

    private fun refreshRedstone() {
        val level = this.level ?: return
        val signal = level.hasNeighborSignal(worldPosition)
        if (signal != redstonePowered) {
            redstonePowered = signal
            afterEnabledChanged()
        }
    }

    /** Called by the block whenever a neighbouring block updates. */
    fun onNeighbourChanged() {
        val level = this.level ?: return
        if (level.isClientSide) return
        if (controlMode == ControlMode.REDSTONE) refreshRedstone()
    }

    private fun afterEnabledChanged() {
        if (!enabled && isNodePowered) setNodePowered(false)
        // Mirrors the vanilla generator: switching back on is also a way out of
        // an overload, provided the network is actually within limits again.
        if (enabled && isNodeOverloaded) {
            val level = this.level
            if (level != null && !level.isClientSide && !searchNodeNetwork(false).overloaded()) {
                setNodeOverloaded(false)
            }
        }
        setChanged()
        sendUpdate()
    }

    // ---- Refurbished: Home Control app ---------------------------------------------------

    /**
     * Makes the transformer appear in the Computer's Home Control app. Their
     * HomeControl.findDevices() walks the electricity network and keeps any *node*
     * implementing this interface - and we are the node, so nothing else is needed.
     *
     * Under redstone control the two setters go quiet. Home Control keeps listing
     * us and keeps showing the live state; it just can't drive it, including from
     * its "all on"/"all off" buttons.
     */
    override fun getDevicePos(): BlockPos = worldPosition

    override fun isDeviceEnabled(): Boolean = enabled

    override fun toggleDeviceState() {
        togglePower()
    }

    override fun setDeviceState(state: Boolean) {
        setEnabled(state)
    }

    override fun getDeviceName(): Component =
        customName?.let { Component.literal(it) } ?: blockState.block.name

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

    /**
     * Nullable on purpose: Refurbished stores audio in a fastutil custom-hash map
     * whose Strategy.equals compares candidates against null for empty slots. A
     * non-null Kotlin parameter compiles to an intrinsic null check and crashes
     * the client tick the moment the map is probed.
     */
    override fun isAudioEqual(other: ILevelAudio?): Boolean =
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

    /**
     * Name and control state, shared by disk saves and the block update packet.
     *
     * Both halves of the on/off state travel, not just the effective result:
     * Refurbished's Home Control app renders from the *client's* block entity and
     * calls isDeviceEnabled() on it, so the client has to be able to work the
     * answer out the same way the server does.
     */
    private fun writeControlState(tag: CompoundTag) {
        tag.putBoolean("Enabled", manualEnabled)
        tag.putBoolean("Redstone", redstonePowered)
        tag.putString("ControlMode", controlMode.id)
        customName?.let { tag.putString(TAG_CUSTOM_NAME, it) }
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        writeControlState(tag)
        return tag
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        customName = if (tag.contains(TAG_CUSTOM_NAME)) {
            tag.getString(TAG_CUSTOM_NAME).ifEmpty { null }
        } else null
        storedEu = tag.getInt("StoredEu")
        manualEnabled = !tag.contains("Enabled") || tag.getBoolean("Enabled")
        redstonePowered = tag.getBoolean("Redstone")
        controlMode = if (tag.contains("ControlMode")) {
            ControlMode.byId(tag.getString("ControlMode")) ?: ControlMode.MANUAL
        } else ControlMode.MANUAL
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("StoredEu", storedEu)
        writeControlState(tag)
    }

    /** ContainerData reaches an open screen only; everything else needs this. */
    private fun sendUpdate() {
        val level = this.level ?: return
        if (level.isClientSide) return
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    companion object {
        const val TAG_CUSTOM_NAME = "CustomName"

        const val DATA_STORED_EU = 0
        const val DATA_CONNECTED = 1
        const val DATA_ACTIVE = 2
        const val DATA_ENABLED = 3
        const val DATA_OVERLOADED = 4
        const val DATA_DRAW = 5
        const val DATA_BUFFER_MAX = 6
        const val DATA_TIER = 7
        const val DATA_MAX_DEVICES = 8
        const val DATA_CONTROL_MODE = 9
        const val DATA_SIZE = 10
    }
}
