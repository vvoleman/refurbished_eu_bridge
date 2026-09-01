package dev.vvoleman.refurbishedeu

import com.mrcrayfish.furniture.refurbished.client.renderer.blockentity.ElectricBlockEntityRenderer
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

/**
 * Client-only wiring. Refurbished draws electricity nodes and the wrench-linking
 * overlay from a block entity renderer, so without that registration the
 * transformer has no visible node and can't be linked to anything.
 */
object ClientSetup {

    fun register(bus: IEventBus) {
        bus.addListener(::onRegisterRenderers)
        bus.addListener(::onClientSetup)
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(RefurbishedEuBridge.EU_TRANSFORMER_BE.get()) { context ->
            ElectricBlockEntityRenderer<TransformerBlockEntity>(context)
        }
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            MenuScreens.register(RefurbishedEuBridge.EU_TRANSFORMER_MENU.get()) {
                menu, inventory, title -> TransformerScreen(menu, inventory, title)
            }
        }
    }
}
