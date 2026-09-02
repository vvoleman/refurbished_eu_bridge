package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/** Box mail: nine slots, addressed by naming the stack, exactly like a Letter. */
class ParcelItem(properties: Properties) : Item(properties) {

    override fun use(
        level: Level,
        player: Player,
        hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (!level.isClientSide && player is net.minecraft.server.level.ServerPlayer) {
            net.minecraftforge.network.NetworkHooks.openScreen(player, ParcelMenu.provider(stack, hand))
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val target = MailAddress.target(stack) ?: LetterItem.targetFromName(stack)
        if (target != null) {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.addressed_to", target))
        } else {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.unaddressed"))
        }
        val filled = contents(stack).count { !it.isEmpty }
        tooltip.add(Component.translatable("tooltip.refurbished_eu.parcel_contents", filled, SIZE))
    }

    companion object {
        const val SIZE = 9

        fun contents(stack: ItemStack): NonNullList<ItemStack> {
            val items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
            val tag = stack.tag ?: return items
            ContainerHelper.loadAllItems(tag.getCompound("Parcel"), items)
            return items
        }

        fun store(stack: ItemStack, container: Container) {
            val items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
            for (i in 0 until SIZE) items[i] = container.getItem(i)
            val tag = CompoundTag()
            ContainerHelper.saveAllItems(tag, items)
            stack.getOrCreateTag().put("Parcel", tag)
        }

        fun asContainer(stack: ItemStack): SimpleContainer {
            val container = SimpleContainer(SIZE)
            contents(stack).forEachIndexed { i, s -> container.setItem(i, s) }
            return container
        }
    }
}
