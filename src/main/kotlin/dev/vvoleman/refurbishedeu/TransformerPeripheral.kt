package dev.vvoleman.refurbishedeu

import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.peripheral.IPeripheral

/**
 * Exposes an EU Transformer to CC: Tweaked as a peripheral of type
 * `eu_transformer`.
 *
 * Every method is `mainThread = true`: they read and write block entity state,
 * which is only safe on the server thread.
 *
 * Writes that would change the on/off state return `false` rather than throwing
 * when the transformer is under redstone control, so a program can react to the
 * refusal. `setControlMode` is deliberately *not* blocked - it is how a computer
 * takes control back.
 */
class TransformerPeripheral(private val blockEntity: TransformerBlockEntity) : IPeripheral {

    override fun getType(): String = "eu_transformer"

    override fun getTarget(): Any = blockEntity

    /**
     * Nullable parameter on purpose. Kotlin would otherwise emit an intrinsic null
     * check here, and CC compares peripherals against null while reconciling
     * attachments - the same crash we already hit with Refurbished's audio map.
     */
    override fun equals(other: IPeripheral?): Boolean =
        other is TransformerPeripheral && other.blockEntity === blockEntity

    private fun require(): TransformerBlockEntity {
        if (blockEntity.isRemoved) throw LuaException("Transformer no longer exists")
        return blockEntity
    }

    // ---- Identity -----------------------------------------------------------------

    /** @return the player-assigned name, or nil if it has never been named. */
    @LuaFunction(mainThread = true)
    fun getName(): String? = require().customName

    /** Pass an empty string to clear the name. */
    @LuaFunction(mainThread = true)
    fun setName(name: String) {
        if (name.length > ModNetwork.MAX_NAME_LENGTH) {
            throw LuaException("Name must be at most ${ModNetwork.MAX_NAME_LENGTH} characters")
        }
        require().setName(null, name)
    }

    /** "low", "medium" or "high". */
    @LuaFunction(mainThread = true)
    fun getTierName(): String = require().tier.id.removeSuffix("_eu_transformer")

    /** The IC2 voltage tier this transformer accepts: 1 = LV, 2 = MV, 3 = HV. */
    @LuaFunction(mainThread = true)
    fun getTier(): Int = TransformerConfig.sinkTier(require().tier)

    // ---- Power state ---------------------------------------------------------------

    @LuaFunction(mainThread = true)
    fun isEnabled(): Boolean = require().enabled

    /** @return false if refused because the transformer is under redstone control. */
    @LuaFunction(mainThread = true)
    fun setEnabled(enabled: Boolean): Boolean = require().setEnabled(enabled)

    /** @return false if refused because the transformer is under redstone control. */
    @LuaFunction(mainThread = true)
    fun toggle(): Boolean = require().togglePower()

    /** "manual" or "redstone". */
    @LuaFunction(mainThread = true)
    fun getControlMode(): String = require().controlMode.id

    @LuaFunction(mainThread = true)
    fun setControlMode(mode: String) {
        val parsed = ControlMode.byId(mode)
            ?: throw LuaException("Unknown control mode '$mode', expected one of: " +
                ControlMode.values().joinToString(", ") { it.id })
        require().setControlMode(parsed)
    }

    // ---- Readouts -------------------------------------------------------------------

    @LuaFunction(mainThread = true)
    fun getStoredEu(): Int = require().storedEu

    @LuaFunction(mainThread = true)
    fun getBufferCapacity(): Int = TransformerConfig.buffer(require().tier)

    /** Everything wired to us, whether or not power can currently reach it. */
    @LuaFunction(mainThread = true)
    fun getConnectedDevices(): Int = require().connectedCount

    /** The subset actually doing work right now. */
    @LuaFunction(mainThread = true)
    fun getActiveDevices(): Int = require().activeCount

    @LuaFunction(mainThread = true)
    fun getMaxDevices(): Int = TransformerConfig.maxDevices(require().tier)

    /** EU per tick at the current load. */
    @LuaFunction(mainThread = true)
    fun getDraw(): Int = require().currentDraw()

    @LuaFunction(mainThread = true)
    fun isOverloaded(): Boolean = require().isNodeOverloaded

    /**
     * Everything above in one table, so a monitor program needs a single call
     * instead of a dozen round trips.
     */
    @LuaFunction(mainThread = true)
    fun getStatus(): Map<String, Any> {
        val be = require()
        return mapOf(
            "name" to (be.customName ?: ""),
            "tier" to be.tier.id.removeSuffix("_eu_transformer"),
            "voltageTier" to TransformerConfig.sinkTier(be.tier),
            "enabled" to be.enabled,
            "controlMode" to be.controlMode.id,
            "overloaded" to be.isNodeOverloaded,
            "storedEu" to be.storedEu,
            "bufferCapacity" to TransformerConfig.buffer(be.tier),
            "connectedDevices" to be.connectedCount,
            "activeDevices" to be.activeCount,
            "maxDevices" to TransformerConfig.maxDevices(be.tier),
            "draw" to be.currentDraw()
        )
    }
}
