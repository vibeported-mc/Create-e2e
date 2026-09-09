package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.settleKinetics
import com.simibubi.create.e2e.gametest.totalItemsIn
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a driven auger shaft pulls items out of a container and carries them along.
 *
 * Its ponder scene's first claim, and the one the block exists for: powered auger shafts transport
 * items and extract them from actors. `AugerShaftBlockEntity.extract` looks at the block behind it in
 * the anti-flow direction, and takes from whatever inventory is there.
 *
 * An undriven auger is checked too, in the same shape. A test that only watched a driven auger empty
 * a chest would pass just as happily if augers emptied chests regardless of whether they were
 * turning, which is not what the scene claims and not what the block should do.
 *
 * **What this does not prove.** The scene's own arrangement, which puts an andesite funnel on the
 * auger and drops items into it -- this takes the other route the block offers, pulling straight out
 * of the container behind it, because a funnel needs a free face and both ends of a short run are
 * already spoken for by the source and the motor. Nor does it cover cycling the block to an auger cog
 * with a wrench, encasing it in industrial iron, or the negative case where items cannot be inserted
 * at the cog or at either end. Those are player interactions on the block, and the sub-level half of
 * these tests keeps its blocks twenty million blocks from any player, so they want a different shape
 * of test.
 */
@DrivesMinecraft
class AugerShaftTest {

    @Test
    @DisplayName("A driven auger shaft empties the container behind it, the same in a sub-level")
    fun `a driven auger takes items from a chest`(cluster: ClusterScope) = cluster.stage {
        val pulled = bothWays(
            name = "auger_driven",
            reach = 3,
            build = { origin -> augerRig(origin) },
            stimulate = { origin ->
                // Against the auger at `origin`, not against thin air. `driveWith` places the motor
                // adjacent to the block it is given, so naming an empty position leaves a one-block
                // gap and a motor connected to nothing.
                driveWith(origin, Direction.EAST, rpm = RPM)
                settleKinetics(origin)
                serverTicks(CARRY)
            },
            // Whether the chest lost anything, not how much of it.
            //
            // The two rigs do not get equal running time: `stimulate` is called for the ground rig
            // and then for the sub-level one, so by the time both are read the ground auger has been
            // turning for twice as long. Comparing counts made that timing difference look like a
            // sub-level defect -- the first run of this read 0 against 32, which is one chest emptied
            // and one half emptied, both of them working perfectly.
            read = { origin -> if (totalItemsIn(origin.west(2)) < LOADED) TOOK_SOME else TOOK_NONE },
            expect = Parity.Same(tolerance = 0.0),
        )

        // Turning first, so that "the chest is still full" cannot be read as a claim about augers
        // when it is really a claim about a motor that never reached one.
        val turning = com.simibubi.create.e2e.kineticSpeedAt(at(-8, 1, 0)).toDouble()

        assertTrue(
            turning != 0.0,
            "The auger shaft is not turning at all ($turning), so nothing below says anything about " +
                "whether a *driven* auger moves items. See ${pulled.pictures}",
        )

        assertTrue(
            pulled.ground == TOOK_SOME,
            "A driven auger shaft is supposed to pull items out of the container behind it, and the " +
                "chest still holds every one of the $LOADED it started with. See ${pulled.pictures}",
        )
    }

    @Test
    @DisplayName("An undriven auger shaft leaves the container behind it alone")
    fun `an undriven auger takes nothing`(cluster: ClusterScope) = cluster.stage {
        val untouched = bothWays(
            name = "auger_idle",
            reach = 3,
            build = { origin -> augerRig(origin) },
            stimulate = { origin ->
                // No motor. The chest is loaded and the augers are in place; the only thing missing
                // is the rotation, which is what the other test says does the work.
                serverTicks(CARRY)
            },
            read = { origin -> if (totalItemsIn(origin.west(2)) < LOADED) TOOK_SOME else TOOK_NONE },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            untouched.ground == TOOK_NONE,
            "An auger shaft with nothing turning it has taken items out of the chest anyway, on " +
                "the ground and in the sub-level alike. Then the other test in this class proves " +
                "nothing about rotation. See ${untouched.pictures}",
        )
    }

    /**
     * A chest, two auger shafts, and room for a motor on the far end.
     *
     * The chest sits west of the run and the motor goes east of it, so the augers carry away from the
     * chest -- `extract` looks at the block behind it in the anti-flow direction, so the container has
     * to be at the upstream end rather than anywhere convenient.
     */
    private suspend fun Stage.augerRig(origin: BlockPos) {
        setBlock(origin.west(2), "minecraft:chest[facing=north]")
        give(origin.west(2), 0, CARGO, LOADED)

        setBlock(origin.west(), "simulated:auger_shaft[axis=x,encased=false]")
        setBlock(origin, "simulated:auger_shaft[axis=x,encased=false]")

        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        const val CARGO = "minecraft:diamond"

        /** A full stack, so a single extraction of sixteen is a clear dent rather than a rounding. */
        const val LOADED = 64

        /**
         * Negative, because the flow has to run away from the chest.
         *
         * `extract` looks at the block behind the auger in the anti-flow direction, so the container
         * has to be upstream. Driven the other way the chest is downstream, the auger looks at a
         * motor instead, and it sits there turning and moving nothing -- which is what the first run
         * of this measured.
         */
        const val RPM = -32

        /** Long enough for the auger to make several extractions. */
        const val CARRY = 100

        const val TOOK_SOME = 1.0
        const val TOOK_NONE = 0.0
    }
}
