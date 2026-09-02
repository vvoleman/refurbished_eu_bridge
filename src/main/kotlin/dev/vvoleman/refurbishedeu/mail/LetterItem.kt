package dev.vvoleman.refurbishedeu.mail

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * Text mail.
 *
 * Addressed by naming the stack - an anvil rename sets the target mailbox. That
 * avoids a bespoke screen and a packet for what is one string, and it matches
 * how players already address things in vanilla.
 */
class LetterItem(properties: Properties) : Item(properties) {

    /**
     * Nullable parameters throughout: Kotlin would otherwise emit intrinsic null
     * checks on these Java overrides and crash when the game passes null, which
     * has already happened once in this mod.
     */
    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val target = MailAddress.target(stack) ?: targetFromName(stack)
        if (target != null) {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.addressed_to", target))
        } else {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.unaddressed"))
        }
    }

    companion object {
        /** A renamed stack is an addressed stack; the sweep in MailRouteService reads this. */
        fun targetFromName(stack: ItemStack): String? =
            if (stack.hasCustomHoverName()) stack.hoverName.string.trim().ifBlank { null } else null
    }
}
