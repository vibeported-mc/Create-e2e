package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a throttle lever puts out exactly the analog power it is set to.
 *
 * The scene's claim: throttle levers are a compact and precise source of redstone power, set by
 * right-clicking and dragging.
 *
 * The dragging is the player's half. What is tested here is the other half -- that a lever told to
 * sit at a number emits that number, and emits nothing when it is told to sit at zero. Two settings
 * are used rather than one, because a block stuck at full strength would satisfy a single reading
 * taken at full strength.
 *
 * **What this does not prove.** The drag itself, or inverting the signal with a wrench. Both are in
 * the scene; both are player interactions the sub-level half cannot reach.
 */
@DrivesMinecraft
class ThrottleLeverTest {

    @Test
    @DisplayName("A throttle lever emits the power it is set to, the same in a sub-level")
    fun `it emits what it is set to`(cluster: ClusterScope) = cluster.stage {
        val swing = bothWays(
            name = "throttle_setting",
            reach = REACH,
            build = { origin -> leverRig(origin) },
            read = { origin -> outputAcrossTheSweepAt(origin) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            swing.ground == (HIGH - LOW).toDouble(),
            "Setting the throttle lever from $LOW to $HIGH moved its output by ${swing.ground} on " +
                "the ground and ${swing.sub} in the sub-level, where it should have moved by " +
                "${HIGH - LOW}. Being a precise source of redstone power is the whole block. " +
                "See ${swing.pictures}",
        )
    }

    @Test
    @DisplayName("A throttle lever set to nothing emits nothing")
    fun `at zero it emits nothing`(cluster: ClusterScope) = cluster.stage {
        val off = bothWays(
            name = "throttle_off",
            reach = REACH,
            build = { origin -> leverRig(origin) },
            stimulate = { origin -> setLever(origin, 0) },
            read = { origin -> outputOf(origin) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            off.ground == 0.0,
            "A throttle lever set to nothing is emitting ${off.ground} on the ground and " +
                "${off.sub} in the sub-level. Then the other test in this class is not measuring " +
                "the setting. See ${off.pictures}",
        )
    }

    /**
     * Sets the lever to two different powers and reports the gap between what came out.
     *
     * Each setting is checked as it is taken, so a failure says which one the lever got wrong rather
     * than only that the gap was off.
     */
    private suspend fun Stage.outputAcrossTheSweepAt(origin: BlockPos): Double {
        val low = outputAfterSetting(origin, LOW)

        assertTrue(
            low == LOW.toDouble(),
            "The throttle lever was set to $LOW and is emitting $low",
        )

        val high = outputAfterSetting(origin, HIGH)

        assertTrue(
            high == HIGH.toDouble(),
            "The throttle lever was set to $HIGH and is emitting $high",
        )

        return high - low
    }

    private suspend fun Stage.outputAfterSetting(origin: BlockPos, power: Int): Double {
        setLever(origin, power)
        serverTicks(SETTLE)

        return outputOf(origin)
    }

    private suspend fun setLever(pos: BlockPos, power: Int) = server(pos, power) { at, want ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity) {
            throw AssertionError("There is no throttle lever at $at but $be")
        }

        be.setSignal(want)
    }

    /** The strongest redstone any face of the lever at [pos] is putting out. */
    private suspend fun outputOf(pos: BlockPos): Double = server(pos) { at ->
        val state = serverLevel.getBlockState(at)

        net.minecraft.core.Direction.values().maxOf {
            state.getSignal(serverLevel, at, it)
        }.toDouble()
    }

    /** The lever standing on a slab, which is the whole rig. */
    private suspend fun Stage.leverRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:throttle_lever[face=floor,facing=north]")
    }

    companion object {
        const val REACH = 2
        const val SETTLE = 10

        /** Two settings well apart, so a lever stuck at either end fails one of them. */
        const val LOW = 4
        const val HIGH = 12
    }
}
