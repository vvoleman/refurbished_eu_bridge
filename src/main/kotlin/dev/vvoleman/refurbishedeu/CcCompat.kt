package dev.vvoleman.refurbishedeu

import dan200.computercraft.api.ForgeComputerCraftAPI
import dan200.computercraft.api.peripheral.IPeripheral
import dan200.computercraft.api.peripheral.IPeripheralProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.common.util.NonNullSupplier

/**
 * CC: Tweaked integration, kept in its own class so it is only ever loaded when
 * ComputerCraft is actually present - see the guard in [RefurbishedEuBridge].
 * Touching any of these types without the mod installed would throw
 * NoClassDefFoundError at class-load time, before any check of ours could run.
 *
 * Registration goes through ForgeComputerCraftAPI rather than attaching
 * `Capabilities.CAPABILITY_PERIPHERAL` directly: the capability lives under CC's
 * `shared` package, which is internal, while the provider is the documented API.
 */
object CcCompat {

    fun register() {
        ForgeComputerCraftAPI.registerPeripheralProvider(
            IPeripheralProvider { level, pos, _ ->
                val be = level.getBlockEntity(pos)
                if (be is TransformerBlockEntity) {
                    LazyOptional.of(NonNullSupplier { TransformerPeripheral(be) as IPeripheral })
                } else {
                    LazyOptional.empty()
                }
            }
        )
    }
}
