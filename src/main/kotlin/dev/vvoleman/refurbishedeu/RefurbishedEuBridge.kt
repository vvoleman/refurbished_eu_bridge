package dev.vvoleman.refurbishedeu

import com.mojang.datafixers.types.Type
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Material
import net.minecraft.world.inventory.MenuType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.extensions.IForgeMenuType
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(RefurbishedEuBridge.ID)
object RefurbishedEuBridge {
    const val ID = "refurbished_eu"

    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID)
    private val MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ID)
    private val SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID)

    val EU_TRANSFORMER: RegistryObject<Block> = BLOCKS.register("eu_transformer") {
        TransformerBlock(
            BlockBehaviour.Properties.of(Material.METAL)
                .strength(3.0f)
                .requiresCorrectToolForDrops()
        )
    }

    val EU_TRANSFORMER_ITEM: RegistryObject<Item> = ITEMS.register("eu_transformer") {
        BlockItem(EU_TRANSFORMER.get(), Item.Properties().tab(CreativeModeTab.TAB_REDSTONE))
    }

    val EU_TRANSFORMER_BE: RegistryObject<BlockEntityType<TransformerBlockEntity>> =
        BLOCK_ENTITIES.register("eu_transformer") {
            BlockEntityType.Builder.of(
                { pos: BlockPos, state: BlockState -> TransformerBlockEntity(pos, state) },
                EU_TRANSFORMER.get()
            ).build(null as Type<*>?)
        }

    val EU_TRANSFORMER_MENU: RegistryObject<MenuType<TransformerMenu>> =
        MENUS.register("eu_transformer") {
            IForgeMenuType.create { containerId, inventory, _ ->
                TransformerMenu(containerId, inventory)
            }
        }

    /**
     * Resolved through our own sounds.json, which points at Refurbished's
     * generator engine loop rather than shipping a copy of it.
     */
    val EU_TRANSFORMER_HUM: RegistryObject<SoundEvent> = SOUNDS.register("eu_transformer_hum") {
        SoundEvent(id("eu_transformer_hum"))
    }

    init {
        BLOCKS.register(MOD_BUS)
        ITEMS.register(MOD_BUS)
        BLOCK_ENTITIES.register(MOD_BUS)
        MENUS.register(MOD_BUS)
        SOUNDS.register(MOD_BUS)

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
            Runnable { ClientSetup.register(MOD_BUS) }
        }
    }

    fun id(path: String) = ResourceLocation(ID, path)
}
