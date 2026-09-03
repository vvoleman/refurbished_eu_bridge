package dev.vvoleman.refurbishedeu.mail

import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.resources.ResourceLocation

/**
 * Draws the mailman with the mod's own skin.
 *
 * The texture is the only reason this class exists: HumanoidMobRenderer
 * hardcodes getTextureLocation to textures/entity/steve.png, and that is not
 * overridable on an inline construction, so the renderer has to be subclassed
 * to point it somewhere else.
 *
 * PlayerModel rather than a plain HumanoidModel, because HumanoidModel only
 * looks up the seven base parts - it would silently drop the skin's jacket,
 * sleeve and trouser overlays, leaving only the hat. PlayerModel binds those
 * parts too and renders on the translucent type they need.
 */
class MailmanRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<MailmanEntity, PlayerModel<MailmanEntity>>(
        context,
        // false = the wide arm model, matching the 64x64 skin in assets.
        PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
        0.5f,
    ) {

    override fun getTextureLocation(entity: MailmanEntity): ResourceLocation = TEXTURE

    companion object {
        private val TEXTURE = RefurbishedEuBridge.id("textures/entity/mailman.png")
    }
}
