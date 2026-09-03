package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.Registry
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class RouteState { TRAVELLING, RETURNING }

/**
 * One delivery in flight.
 *
 * The route - not the entity - is the durable thing. An entity cannot walk
 * through unloaded chunks because nothing ticks out there, so the mailman is
 * spawned as a view of this record only while somebody is close enough to see
 * it. The carried stack lives here, which is why a delivery survives chunk
 * unload and server restart instead of being dropped in an unloaded chunk.
 */
class MailRoute(
    val id: UUID,
    val stack: ItemStack,
    val originId: UUID,
    val targetId: UUID,
    val level: ResourceKey<Level>,
    var pos: Vec3,
    var state: RouteState,
    /**
     * In-memory only, never persisted: a materialised MailmanEntity has
     * shouldBeSaved() = false (Task 12 fix round 1), so it cannot survive a
     * restart by construction. A UUID written here would refer to nothing
     * once reloaded - dead weight that also misleads wasDriving on the first
     * tick after a restart.
     */
    var entity: UUID? = null,
    /** Horizontal distance at the last progress check; drives stall detection. */
    var lastDistance: Double = Double.MAX_VALUE,
    var stalledTicks: Int = 0,
) {

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.put("Stack", stack.save(CompoundTag()))
        tag.putUUID("Origin", originId)
        tag.putUUID("Target", targetId)
        tag.putString("Level", level.location().toString())
        tag.putDouble("X", pos.x)
        tag.putDouble("Y", pos.y)
        tag.putDouble("Z", pos.z)
        tag.putString("State", state.name)
        tag.putDouble("LastDistance", lastDistance)
        tag.putInt("StalledTicks", stalledTicks)
        // entity is deliberately not persisted - see the field doc comment above.
        return tag
    }

    companion object {
        /** A route we can't read is dropped, not fatal - one bad record must not lose the rest. */
        fun load(tag: CompoundTag): MailRoute? {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Origin") || !tag.hasUUID("Target")) return null
            val levelId = ResourceLocation.tryParse(tag.getString("Level")) ?: return null
            val state = runCatching { RouteState.valueOf(tag.getString("State")) }.getOrNull() ?: return null
            return MailRoute(
                id = tag.getUUID("Id"),
                stack = ItemStack.of(tag.getCompound("Stack")),
                originId = tag.getUUID("Origin"),
                targetId = tag.getUUID("Target"),
                level = ResourceKey.create(Registry.DIMENSION_REGISTRY, levelId),
                pos = Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                state = state,
                // entity is not read back - see the field doc comment above; it
                // starts null and MailRouteService.materialise() spawns fresh as needed.
                lastDistance = tag.getDouble("LastDistance"),
                stalledTicks = tag.getInt("StalledTicks"),
            )
        }
    }
}
