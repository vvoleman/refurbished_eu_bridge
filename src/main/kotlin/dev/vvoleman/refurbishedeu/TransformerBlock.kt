package dev.vvoleman.refurbishedeu

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.network.NetworkHooks

class TransformerBlock(
    properties: BlockBehaviour.Properties,
    val tier: TransformerTier
) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(BlockStateProperties.POWERED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.POWERED)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        TransformerBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        return if (level.isClientSide) {
            // Client side exists purely to feed Refurbished's AudioManager.
            BlockEntityTicker { _, _, _, be ->
                if (be is TransformerBlockEntity) be.clientTick()
            }
        } else {
            BlockEntityTicker { _, _, _, be ->
                if (be is TransformerBlockEntity) be.serverTick()
            }
        }
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos)
            if (be is TransformerBlockEntity && player is ServerPlayer) {
                NetworkHooks.openScreen(player, be, pos)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
