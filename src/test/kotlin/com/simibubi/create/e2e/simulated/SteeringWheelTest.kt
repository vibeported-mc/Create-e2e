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
 * That a steering wheel turns to the angle it is given, stops at its limit, and can be read.
 *
 * Its ponder scene says steering wheels provide precise rotational output, that the maximum turning
 * angle is configurable on the value panel, and that redstone comparators can read the current angle.
 * Those are the three claims here.
 *
 * The wheel is turned through `updateTargetAngle` rather than by holding right click and moving the
 * mouse. The grabbing is the player's half of the interaction; what this is about is whether the
 * block then goes where it was told, and whether it still does inside a sub-level -- an angle held
 * across a body with its own coordinates and its own tick is exactly the sort of thing a port breaks.
 *
 * **What this does not prove.** The mouse interaction itself, the 45-degree snapping while sneaking,
 * or repainting the wheel with planks. All three are in the scene and none is covered here.
 */
@DrivesMinecraft
class SteeringWheelTest {

    @Test
    @DisplayName("A steering wheel turns to the angle it is given, the same in a sub-level")
    fun `it turns to the angle it is given`(cluster: ClusterScope) = cluster.stage {
        val turned = bothWays(
            name = "wheel_turned",
            reach = 2,
            build = { origin -> wheelRig(origin) },
            read = { origin -> angleAfterTurningTo(origin, TARGET) },
            expect = Parity.Same(tolerance = ANGLE_TOLERANCE),
        )

        assertTrue(
            Math.abs(turned.ground - TARGET) < ANGLE_TOLERANCE,
            "The steering wheel was told to go to $TARGET degrees and settled at ${turned.ground} " +
                "on the ground and ${turned.sub} in the sub-level. Precise rotational output is the " +
                "whole block. See ${turned.pictures}",
        )
    }

    @Test
    @DisplayName("A steering wheel will not turn past its configured maximum angle")
    fun `it stops at its configured maximum`(cluster: ClusterScope) = cluster.stage {
        val clamped = bothWays(
            name = "wheel_clamped",
            reach = 2,
            build = { origin -> wheelRig(origin) },
            stimulate = { origin -> setMaximumAngle(origin, NARROW) },
            // Asked for far more than it is allowed, so what comes back is the limit rather than the
            // request. The default maximum is 180, and the request is under that -- if the panel were
            // ignored the wheel would happily go to BEYOND and the test would say so.
            read = { origin -> angleAfterTurningTo(origin, BEYOND) },
            expect = Parity.Same(tolerance = ANGLE_TOLERANCE),
        )

        assertTrue(
            Math.abs(clamped.ground - NARROW) < ANGLE_TOLERANCE,
            "The steering wheel has its maximum angle set to $NARROW and was asked for $BEYOND. It " +
                "settled at ${clamped.ground} on the ground and ${clamped.sub} in the sub-level, " +
                "where it should have stopped at $NARROW. See ${clamped.pictures}",
        )
    }

    @Test
    @DisplayName("A comparator beside a steering wheel reads how far it is turned")
    fun `a comparator reads its angle`(cluster: ClusterScope) = cluster.stage {
        val read = bothWays(
            name = "wheel_comparator",
            reach = 2,
            build = { origin -> wheelRig(origin) },
            read = { origin -> comparatorAfterTurningTo(origin, TARGET) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            read.ground > 0.0,
            "A comparator beside the turned steering wheel reads ${read.ground} on the ground and " +
                "${read.sub} in the sub-level, where a wheel held at $TARGET degrees should give it " +
                "something to read. See ${read.pictures}",
        )
    }

    /** Turns the wheel to [target] and reports where it actually settled. */
    private suspend fun Stage.angleAfterTurningTo(origin: BlockPos, target: Double): Double {
        turnTo(origin, target)
        serverTicks(TURN)

        return angleAt(origin)
    }

    /** Turns the wheel to [target] and reports what a comparator on its side would see. */
    private suspend fun Stage.comparatorAfterTurningTo(origin: BlockPos, target: Double): Double {
        turnTo(origin, target)
        serverTicks(TURN)

        return comparatorAt(origin, Direction.EAST)
    }

    /**
     * Points the wheel at [target], the way a player dragging it does.
     *
     * By writing `targetAngleToUpdate` rather than calling `updateTargetAngle`. The two are not the
     * same thing: `tick` calls `updateTargetAngle(targetAngleToUpdate)` on every tick the wheel is
     * not being held, so a single call is overwritten on the next tick and the wheel walks straight
     * back to zero. `targetAngleToUpdate` is the field the interaction writes and the one the
     * comparator reads.
     */
    private suspend fun turnTo(pos: BlockPos, target: Double) = server(pos, target) { at, angle ->
        wheelAt(serverLevel, at).targetAngleToUpdate = angle.toFloat()
    }

    private suspend fun setMaximumAngle(pos: BlockPos, degrees: Int) = server(pos, degrees) { at, most ->
        wheelAt(serverLevel, at).angleInput.setValue(most)
    }

    private suspend fun angleAt(pos: BlockPos): Double = server(pos) { at ->
        wheelAt(serverLevel, at).angle.toDouble()
    }

    /**
     * What a comparator against the wheel's [side] face would read.
     *
     * Asked of the block directly rather than by placing a comparator and reading redstone off it.
     * `getAnalogOutputSignalFrom` is the method a comparator calls, and calling it avoids having to
     * orient a second block correctly in two worlds -- while still failing if the wheel stops
     * answering.
     */
    private suspend fun comparatorAt(pos: BlockPos, side: Direction): Double =
        server(pos, side) { at, dir ->
            val state = serverLevel.getBlockState(at)
            val block = state.block

            if (block !is dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock) {
                throw AssertionError("There is no steering wheel at $at but $block")
            }

            block.getAnalogOutputSignalFrom(state, serverLevel, at, dir).toDouble()
        }

    /** The wheel, on the floor, with something under it to stand on. */
    private suspend fun Stage.wheelRig(origin: BlockPos) {
        setBlock(origin, "simulated:steering_wheel[facing=north,on_floor=true,waterlogged=false]")
        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        /** Comfortably inside the default maximum of 180, so nothing is clamped by accident. */
        const val TARGET = 45.0

        /** A maximum set well below [BEYOND], so the clamp is what decides where the wheel stops. */
        const val NARROW = 30

        /** More than [NARROW] and less than the default 180. */
        const val BEYOND = 120.0

        /** Long enough for the wheel to travel the whole way and stop. */
        const val TURN = 100

        const val ANGLE_TOLERANCE = 2.0
    }
}

/**
 * The steering wheel at [pos], or a failure naming what is there instead.
 *
 * Top-level rather than a method on the test class: an RPC body may not capture a receiver, so a
 * helper it calls has to be resolvable by name on the far node.
 */
internal fun wheelAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity) {
        throw AssertionError("There is no steering wheel at $pos but $be")
    }

    return be
}
