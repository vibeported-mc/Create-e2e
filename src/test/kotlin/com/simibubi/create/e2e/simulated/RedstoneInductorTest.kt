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
 * That a redstone inductor walks its output up to its input instead of jumping to it.
 *
 * The scene's claim: inductors output an analog signal that changes over time to match its input.
 *
 * Both halves of that matter and the test asserts both. A block that ignored its input entirely would
 * fail the second reading; a block that simply passed the input straight through -- a repeater --
 * would satisfy "matches its input" and fail the first, because it would already be at full strength
 * a moment after the redstone arrived.
 *
 * The rate is `inputDelay`, ten ticks a step to begin with, so a full swing from nothing to fifteen
 * takes about 150 ticks. The early reading is taken well inside that.
 *
 * **What this does not prove.** The value panel that sets the rate, or inverting the block. Both are
 * in the scene; both are player interactions the sub-level half cannot reach.
 */
@DrivesMinecraft
class RedstoneInductorTest {

    @Test
    @DisplayName("A redstone inductor climbs towards its input over time, the same in a sub-level")
    fun `it climbs towards its input`(cluster: ClusterScope) = cluster.stage {
        val climb = bothWays(
            name = "inductor_climbing",
            reach = REACH,
            build = { origin -> inductorRig(origin) },
            read = { origin -> climbAcrossTheSweepAt(origin) },
            expect = Parity.Same(tolerance = STEP),
        )

        assertTrue(
            climb.ground > 0.0,
            "The inductor has had full redstone on its input throughout and its output did not " +
                "climb between the two readings: it moved by ${climb.ground} on the ground and " +
                "${climb.sub} in the sub-level. Changing over time to match its input is the whole " +
                "block. See ${climb.pictures}",
        )
    }

    /**
     * How much further along the output is after another spell of the same input.
     *
     * The early reading also has to be short of full, which is what separates this block from a
     * repeater, and that is asserted here where both numbers are in hand.
     */
    private suspend fun Stage.climbAcrossTheSweepAt(origin: BlockPos): Double {
        setBlock(origin.relative(FACES), "minecraft:redstone_block")

        serverTicks(EARLY)
        val early = outputOf(origin)

        assertTrue(
            early < FULL,
            "The inductor is already at $early after only $EARLY ticks, which is its input's full " +
                "strength. It is supposed to change over time rather than pass the signal straight " +
                "through",
        )

        serverTicks(LATER)

        return outputOf(origin) - early
    }

    /** What the inductor at [pos] is putting out. */
    private suspend fun outputOf(pos: BlockPos): Double = server(pos) { at ->
        serverLevel.getBlockState(at).getSignal(serverLevel, at, FACES).toDouble()
    }

    /** The inductor on a slab, with nothing on its input yet. */
    private suspend fun Stage.inductorRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:redstone_inductor[facing=east,inverted=false,powered=false]")
        setBlock(origin.relative(FACES), "minecraft:air")
    }

    companion object {
        /** The block's `FACING`: where it reads its input and where it puts its output. */
        val FACES: Direction = Direction.EAST

        const val REACH = 2

        /** Full redstone, which is what the input is held at throughout. */
        const val FULL = 15.0

        /** A few steps in, well short of the whole swing. */
        const val EARLY = 40

        /** Enough more for the output to have moved on again. */
        const val LATER = 60

        /** One step of the output, to absorb the two rigs landing on adjacent ticks. */
        const val STEP = 1.01
    }
}
