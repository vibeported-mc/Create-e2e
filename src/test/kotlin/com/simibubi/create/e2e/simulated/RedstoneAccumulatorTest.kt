package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a redstone accumulator fills up from behind and drains from the side.
 *
 * The scene's claims: accumulators store an analog signal that can be modified over time, signals at
 * the back increase the output strength, and signals from the sides decrease it.
 *
 * "Over time" is the part that decides how this is written. The block moves its stored value by one
 * step every `inputDelay` ticks, which starts at ten, so nothing here can be read on the tick after
 * the redstone arrives -- both tests hold the input on for [CHARGE] ticks and read what the value has
 * climbed or fallen to.
 *
 * Where the inputs go is taken from `getUpdatedBlockstate` rather than guessed: the back is
 * `pos.relative(FACING)` and the two sides are the blocks either side of that axis. The output is
 * whatever `getSignal` gives towards `FACING`.
 *
 * **What this does not prove.** The value panel that sets the rate, or inverting the block. Both are
 * in the scene and both are player interactions the sub-level half cannot reach.
 */
@DrivesMinecraft
class RedstoneAccumulatorTest {

    @Test
    @DisplayName("A redstone accumulator fills up from a signal behind it, the same in a sub-level")
    fun `it fills from behind`(cluster: ClusterScope) = cluster.stage {
        val filled = bothWays(
            name = "accumulator_filling",
            reach = REACH,
            build = { origin -> accumulatorRig(origin) },
            read = { origin -> outputAfterCharging(origin) },
            expect = Parity.Same(tolerance = STEP),
        )

        assertTrue(
            filled.ground > 0.0,
            "The accumulator has had a redstone block behind it for $CHARGE ticks and is putting " +
                "out ${filled.ground} on the ground and ${filled.sub} in the sub-level. Signals at " +
                "the back are supposed to increase the output. See ${filled.pictures}",
        )
    }

    @Test
    @DisplayName("A signal from the side drains a redstone accumulator back down")
    fun `it drains from the side`(cluster: ClusterScope) = cluster.stage {
        val drained = bothWays(
            name = "accumulator_draining",
            reach = REACH,
            build = { origin -> accumulatorRig(origin) },
            read = { origin -> fallAfterDrainingAt(origin) },
            expect = Parity.Same(tolerance = STEP),
        )

        assertTrue(
            drained.ground > 0.0,
            "The accumulator was charged and then given a signal from the side for $CHARGE ticks, " +
                "and its output fell by ${drained.ground} on the ground and ${drained.sub} in the " +
                "sub-level. Signals from the sides are supposed to decrease it. See ${drained.pictures}",
        )
    }

    /** Holds a signal behind it for a while, and reports what it climbed to. */
    private suspend fun Stage.outputAfterCharging(origin: BlockPos): Double {
        setBlock(origin.relative(FACES), "minecraft:redstone_block")
        serverTicks(CHARGE)

        return outputOf(origin)
    }

    /** Charges it, then drains it from the side, and reports how far it fell. */
    private suspend fun Stage.fallAfterDrainingAt(origin: BlockPos): Double {
        val charged = outputAfterCharging(origin)

        assertTrue(
            charged > 0.0,
            "The accumulator did not charge in the first place ($charged), so there is nothing for " +
                "a signal from the side to drain",
        )

        // The back input goes away and a side input takes its place, which is the arrangement the
        // scene draws: one or the other, not both at once.
        setBlock(origin.relative(FACES), "minecraft:air")
        setBlock(origin.relative(SIDE), "minecraft:redstone_block")
        serverTicks(CHARGE)

        return charged - outputOf(origin)
    }

    /** What the accumulator at [pos] is putting out of its front. */
    private suspend fun outputOf(pos: BlockPos): Double = server(pos) { at ->
        serverLevel.getBlockState(at).getSignal(serverLevel, at, FACES).toDouble()
    }

    /**
     * The accumulator on a slab, with nothing touching it yet.
     *
     * The slab reaches under every position the tests put a redstone block on, so that whichever
     * input is in use the rig is still one connected structure for assembly.
     */
    private suspend fun Stage.accumulatorRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:redstone_accumulator[facing=east]")
        setBlock(origin.relative(FACES), "minecraft:air")
        setBlock(origin.relative(SIDE), "minecraft:air")
    }

    companion object {
        /** The block's `FACING`, which is where `getUpdatedBlockstate` reads the back input from. */
        val FACES: Direction = Direction.EAST

        /** One of the two sides, which are the blocks either side of the facing axis. */
        val SIDE: Direction = Direction.NORTH

        const val REACH = 2

        /**
         * Long enough for several steps.
         *
         * `inputDelay` starts at ten ticks per step, so this is worth about eight of them -- far
         * enough from zero to be unmistakable, and short of the fifteen that would clamp.
         */
        const val CHARGE = 80

        /** One step of the stored value, to absorb the two rigs landing on adjacent ticks. */
        const val STEP = 1.01
    }
}
