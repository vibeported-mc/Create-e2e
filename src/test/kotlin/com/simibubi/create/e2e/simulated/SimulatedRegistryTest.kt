package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage

import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That every block the Simulated family registers is still there, and still places.
 *
 * This is the cheapest test in the package and the widest. The family registers 141 blocks, and 98 of
 * them are dye variants -- sixteen envelopes, sixteen sails, sixteen nameplates, eighteen handles,
 * sixteen portable engines, sixteen encased shafts. Nothing else in this package will ever touch
 * those, because a test per colour would only prove that Registrate's `colored()` loop still loops.
 * This one covers them all in a single round trip.
 *
 * What it is really watching for is a registration quietly disappearing in the 26.2 port. A block
 * whose entry was dropped does not fail loudly -- it fails as an item missing from the creative tab,
 * a recipe that no longer resolves, and a world that loads with air where something used to be.
 *
 * **What this does not prove.** That any of them work. It asserts existence, item form, placement and
 * one tick -- nothing about behaviour. Behaviour is the parity tests next door, which put the same
 * rig on the ground and inside a sub-level and compare the two.
 */
@DrivesMinecraft
class SimulatedRegistryTest {

    @Test
    @DisplayName("Every block the family registers exists, and has an item unless something else places it")
    fun `every block is registered`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val census = censusOfTheFamily()

        assertTrue(
            census.total >= EXPECTED_AT_LEAST,
            "Only ${census.total} blocks are registered across ${NAMESPACES.joinToString()}, where " +
                "there should be at least $EXPECTED_AT_LEAST. A whole mod's block registry is " +
                "missing, or one of them failed to construct",
        )

        // Named exactly rather than counted, because the interesting failure is a block that gained
        // or lost its item form -- which changes how it is obtained, not whether it exists.
        assertEquals(
            EXPECTED_WITHOUT_ITEM.sorted(), census.withoutItem.sorted(),
            "The set of blocks with no item form has changed. Four of them are deliberate -- they are " +
                "placed by a parent block or by an interaction rather than from the hotbar -- and the " +
                "fifteen dyed sails drop their white counterpart. Anything else in this list is a " +
                "block that can no longer be obtained",
        )
    }

    @Test
    @DisplayName("Every block the family registers can be placed and ticked")
    fun `every block places and ticks`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val placed = placeEveryBlock(at(0, 0, 0))

        assertTrue(
            placed.threw.isEmpty(),
            "Placing these threw: ${placed.threw}",
        )

        assertTrue(
            placed.missingBlockEntity.isEmpty(),
            "These declare a block entity and did not get one when placed, so their block entity " +
                "type is not registered or its factory refused: ${placed.missingBlockEntity}",
        )

        // A tick is where a block entity that lost a behaviour registration in the port actually
        // falls over -- construction is lenient, `tick` is not.
        serverTicks(TICKS)

        val after = survivorsOf(at(0, 0, 0))

        assertEquals(
            NEEDS_SUPPORT.sorted(), after.vanished.sorted(),
            "The set of blocks that remove themselves on a bare floor has changed. The expected ones " +
                "check for something to hold on to and find nothing, which is correct. Anything else " +
                "here deletes itself on its first tick, which is a support or neighbour check reading " +
                "the wrong thing on 26.2",
        )

        assertTrue(
            after.brokenBlockEntities.isEmpty(),
            "These lost their block entity over $TICKS ticks: ${after.brokenBlockEntities}",
        )
    }

    /** Every block id in the three namespaces, and which of them have no item form. */
    private suspend fun Stage.censusOfTheFamily(): Census = server {
        val withoutItem = mutableListOf<String>()
        var total = 0

        for (entry in net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            val id = entry.key.identifier()

            if (id.namespace !in NAMESPACES) {
                continue
            }

            total++

            if (entry.value.asItem() === net.minecraft.world.item.Items.AIR) {
                withoutItem += id.toString()
            }
        }

        Census(total, withoutItem)
    }

    /**
     * Lays one of every block on a grid and says what happened.
     *
     * Spaced two apart so nothing forms a multiblock with its neighbour by accident -- a row of
     * nameplates would chain into one controller, and a pair of docking connectors would try to pair.
     *
     * Laid on the floor rather than a block above it. `clearGround` puts stone at `centre.y - 1`, so
     * `centre.y` is the first solid footing; anything that checks for support finds it there and
     * floats without it, which would fail this test for the wrong reason.
     */
    private suspend fun Stage.placeEveryBlock(centre: BlockPos): Placed = server(centre) { origin ->
        val level = serverLevel
        val missingBlockEntity = mutableListOf<String>()
        val threw = mutableListOf<String>()
        var count = 0

        // A slab of stone for them to sit in, laid first.
        //
        // Half of these blocks mount on something -- a nameplate looks for a wall behind it, a handle
        // for something to be bolted to -- and on an open floor they are right to break, which is a
        // failure about the test rather than about the port. Embedding each one in stone gives every
        // support check something to find, in every direction, so a block that still removes itself
        // is one that is really deleting itself.
        for (dx in -SPAN..SPAN) {
            for (dz in -SPAN..SPAN) {
                level.setBlock(
                    BlockPos(origin.x + dx, origin.y, origin.z + dz),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                    2,
                )
            }
        }

        for (entry in net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            val id = entry.key.identifier()

            if (id.namespace !in NAMESPACES || id.path in PLACED_BY_A_PARENT) {
                continue
            }

            val pos = BlockPos(
                origin.x - SPAN + (count % ROW) * 2,
                origin.y,
                origin.z - SPAN + (count / ROW) * 2,
            )

            try {
                val state = entry.value.defaultBlockState()
                level.setBlockAndUpdate(pos, state)

                if (state.hasBlockEntity() && level.getBlockEntity(pos) == null) {
                    missingBlockEntity += id.toString()
                }
            } catch (failure: Throwable) {
                // With the frames, because the interesting part of a NoClassDefFoundError is never
                // the message -- it is which of this block's own classes reached for the missing one.
                threw += id.toString() + ": " + failure + "\n    " +
                    failure.stackTraceToString().lines().drop(1).take(6).joinToString("\n    ") { it.trim() }
            }

            count++
        }

        Placed(count, missingBlockEntity, threw)
    }

    /**
     * Which of the grid are gone, and which lost their block entity.
     *
     * Walks the registry in the same order `placeEveryBlock` did, so a missing block can be named
     * rather than counted -- "three vanished" says nothing about which three.
     */
    private suspend fun Stage.survivorsOf(centre: BlockPos): Survivors = server(centre) { origin ->
        val level = serverLevel
        val vanished = mutableListOf<String>()
        val broken = mutableListOf<String>()
        var count = 0

        for (entry in net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
            val id = entry.key.identifier()

            if (id.namespace !in NAMESPACES || id.path in PLACED_BY_A_PARENT) {
                continue
            }

            val pos = BlockPos(
                origin.x - SPAN + (count % ROW) * 2,
                origin.y,
                origin.z - SPAN + (count / ROW) * 2,
            )
            count++

            val state = level.getBlockState(pos)

            if (state.isAir) {
                vanished += id.toString()
            } else if (state.hasBlockEntity() && level.getBlockEntity(pos) == null) {
                broken += id.toString()
            }
        }

        Survivors(vanished, broken)
    }

    @Serializable
    data class Census(val total: Int, val withoutItem: List<String>)

    @Serializable
    data class Placed(val count: Int, val missingBlockEntity: List<String>, val threw: List<String>)

    @Serializable
    data class Survivors(val vanished: List<String>, val brokenBlockEntities: List<String>)

    companion object {
        val NAMESPACES = setOf("simulated", "aeronautics", "offroad")

        /**
         * The floor, not the figure.
         *
         * 141 today: 95 simulated, 43 aeronautics, 3 offroad. Asserted as "at least" so that adding a
         * block does not fail this test, while losing a whole registry does.
         */
        const val EXPECTED_AT_LEAST = 140

        /**
         * Blocks with no item, and the reason each has none.
         *
         * Four are placed by something other than a hotbar slot: `merging_glue` by right-clicking a
         * slime ball, `spring` by the two-point spring interaction, `paired_docking_connector` by the
         * docking connector in front of it, and `swivel_bearing_link_block` by the swivel bearing.
         * `levitite_blend` is a fluid, so its block is the fluid's and you carry a bucket. The
         * remaining fifteen are dyed sails, which drop the white one rather than themselves.
         */
        val EXPECTED_WITHOUT_ITEM: List<String> = listOf(
            "aeronautics:levitite_blend",
            "simulated:merging_glue",
            "simulated:paired_docking_connector",
            "simulated:spring",
            "simulated:swivel_bearing_link_block",
        ) + listOf(
            "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
        ).map { "simulated:" + it + "_symmetric_sail" }

        /**
         * Left out of the placement grid, because placing them bare is not a thing that happens.
         *
         * Each is created by a parent block that also sets up the state it needs -- a partner
         * position, a controller, a desired length. Dropped on a floor on their own they have every
         * right to misbehave, and a failure there would say nothing about the port.
         */
        val PLACED_BY_A_PARENT = setOf(
            "merging_glue", "spring", "paired_docking_connector", "swivel_bearing_link_block",
        )

        /**
         * Blocks that correctly refuse to stay on a bare floor.
         *
         * Filled in from what the first run actually reported rather than guessed at, and asserted
         * exactly so that a block joining or leaving the list is a failure worth reading.
         */
        val NEEDS_SUPPORT = setOf<String>()

        const val GROUND = 20
        const val ROW = 14
        const val SPAN = 13
        const val TICKS = 40
    }
}
