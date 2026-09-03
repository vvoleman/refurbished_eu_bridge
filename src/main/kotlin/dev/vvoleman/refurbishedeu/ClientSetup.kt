package dev.vvoleman.refurbishedeu

import com.mrcrayfish.furniture.refurbished.client.renderer.blockentity.ElectricBlockEntityRenderer
import dev.vvoleman.refurbishedeu.mail.MailmanRenderer
import dev.vvoleman.refurbishedeu.mail.ParcelScreen
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * Client-only wiring. Refurbished draws electricity nodes and the wrench-linking
 * overlay from a block entity renderer, so without that registration the
 * transformer has no visible node and can't be linked to anything.
 */
object ClientSetup {

    fun register(bus: IEventBus) {
        bus.addListener(::onRegisterRenderers)
        bus.addListener(::onClientSetup)
        // The hover label is a HUD overlay, so it listens on the Forge bus.
        TransformerHudOverlay.register(FORGE_BUS)
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(RefurbishedEuBridge.EU_TRANSFORMER_BE.get()) { context ->
            ElectricBlockEntityRenderer<TransformerBlockEntity>(context)
        }
        // MailmanEntity is not a Villager, so VillagerRenderer's fixed <Villager, VillagerModel<Villager>>
        // generics refuse it. MailmanRenderer is a HumanoidMobRenderer bound to MailmanEntity that
        // swaps the inherited steve.png for the mod's own skin.
        event.registerEntityRenderer(RefurbishedEuBridge.MAILMAN.get(), ::MailmanRenderer)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            MenuScreens.register(RefurbishedEuBridge.EU_TRANSFORMER_MENU.get()) {
                menu, inventory, title -> TransformerScreen(menu, inventory, title)
            }
            MenuScreens.register(RefurbishedEuBridge.PARCEL_MENU.get()) {
                menu, inventory, title -> ParcelScreen(menu, inventory, title)
            }
        }
    }
}
