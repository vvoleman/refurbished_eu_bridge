package dev.vvoleman.refurbishedeu

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * The advancements that can only be granted from code, and the rules for who
 * receives them.
 *
 * Each one declares a single `minecraft:impossible` criterion in its JSON, which
 * is the cheaper of the two options: registering real CriteriaTriggers would
 * make them part of a data API, and nobody has asked for one. Pack makers
 * writing their own advancements against transformer state is a fine thing to
 * want, but it is a decision to take when someone wants it, not a shape to
 * commit to for eight lines of flavour content.
 *
 * The progression line (root, wired_in, stepping_up) needs nothing from here -
 * it runs on vanilla triggers.
 */
object ModAdvancements {

    /** The criterion name every advancement below declares in its JSON. */
    private const val CRITERION = "awarded"

    /**
     * Who counts as present when something happens to a transformer.
     *
     * 32 blocks is the entity tracking range, so it is roughly "close enough to
     * have heard the motor change" - which is the thing the award is meant to
     * acknowledge noticing.
     */
    private const val AWARD_RADIUS = 32.0

    private const val AWARD_RADIUS_SQR = AWARD_RADIUS * AWARD_RADIUS

    /** Named a transformer. Awarded wherever a ServerPlayer sets the name. */
    val LABEL_MAKER: ResourceLocation = RefurbishedEuBridge.id("label_maker")

    /** Switched a transformer to redstone control, from the GUI. */
    val HANDS_OFF: ResourceLocation = RefurbishedEuBridge.id("hands_off")

    /** Eight devices working at once on one circuit. */
    val FULLY_WIRED: ResourceLocation = RefurbishedEuBridge.id("fully_wired")

    /** Overloaded a transformer. */
    val BREAKER_TRIPPED: ResourceLocation = RefurbishedEuBridge.id("breaker_tripped")

    /** Thirty-two devices on one transformer. */
    val SUBSTATION: ResourceLocation = RefurbishedEuBridge.id("substation")

    /**
     * The three that are properties of the *block*, not of a player action, so
     * they can come true with nobody in range and have to be held on the block
     * entity until someone turns up. See TransformerBlockEntity.grant().
     */
    val RETROACTIVE: List<ResourceLocation> = listOf(FULLY_WIRED, BREAKER_TRIPPED, SUBSTATION)

    /** Resolves a path saved in a block entity's pending list back to an id. */
    fun retroactiveByPath(path: String): ResourceLocation? =
        RETROACTIVE.firstOrNull { it.path == path }

    /**
     * Vanilla ignores a criterion that is already satisfied, so this is safe to
     * call repeatedly and needs no bookkeeping of its own. A missing advancement
     * is not an error either: a data pack is allowed to remove ours.
     */
    fun award(player: ServerPlayer, id: ResourceLocation) {
        val advancement = player.server.advancements.getAdvancement(id) ?: return
        player.advancements.award(advancement, CRITERION)
    }

    /**
     * Award to everyone close enough to the block to have witnessed it.
     *
     * @return false if nobody was in range, which is the caller's cue to
     *   remember the condition instead of dropping it.
     */
    fun awardNearby(level: ServerLevel, pos: BlockPos, id: ResourceLocation): Boolean {
        val centre = Vec3.atCenterOf(pos)
        var witnessed = false
        for (player in level.players()) {
            if (player.distanceToSqr(centre) > AWARD_RADIUS_SQR) continue
            award(player, id)
            witnessed = true
        }
        return witnessed
    }
}
