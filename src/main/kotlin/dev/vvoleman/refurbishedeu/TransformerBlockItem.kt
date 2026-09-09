package dev.vvoleman.refurbishedeu

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * The placed block spells its capacity out in the GUI, but until now the three
 * tiers were indistinguishable in hand - you had to place one to find out what
 * it could carry. The numbers come from the same config the block reads, so a
 * server that raises a cap advertises the raised one.
 */
class TransformerBlockItem(
    block: Block,
    properties: Item.Properties,
    private val tier: TransformerTier
) : BlockItem(block, properties) {

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag
    ) {
        super.appendHoverText(stack, level, tooltip, flag)

        tooltip.add(
            Component.translatable(
                "tooltip.refurbished_eu.devices", TransformerConfig.maxDevices(tier)
            ).withStyle(ChatFormatting.GRAY)
        )
        tooltip.add(
            Component.translatable(
                "tooltip.refurbished_eu.buffer",
                EuFormat.grouped(TransformerConfig.buffer(tier))
            ).withStyle(ChatFormatting.GRAY)
        )
        tooltip.add(
            Component.translatable(
                "gui.refurbished_eu.tier", TransformerConfig.sinkTier(tier)
            ).withStyle(ChatFormatting.DARK_GRAY)
        )
    }
}
