package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.setMotorSpeed
import com.simibubi.create.e2e.settleKinetics
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
 * That a torsion spring winds within its limit, unwinds when the input stops, and holds when powered.
 *
 * Its ponder scene states the contract: it relays rotation within a set range of angles; when the
 * input stops the spring returns to its starting angle; and when powered by redstone it will not
 * spring back. The angle it is wound to is `TorsionSpringBlockEntity.getAngle`, and the range is the
 * value panel's, which starts at 90 degrees.
 *
 * These are the three tests, and each runs its rig twice -- once on the stage floor, once assembled
 * into a sub-level beside it -- because the spring's whole job is holding an angle, and an angle is
 * exactly the kind of state a body with its own coordinates and its own tick can get wrong.
 *
 * A water wheel rides on the driven side so the pictures show whether the input is really turning.
 *
 * **What this does not prove.** The value panel, or the comparator that reads the spring's angle.
 * Both are worth their own tests.
 */
@DrivesMinecraft
class TorsionSpringTest {

    @Test
    @DisplayName("A driven torsion spring winds up, and no further than its angle limit")
    fun `it winds up to its limit`(cluster: ClusterScope) = cluster.stage {
        val wound = bothWays(
            name = "torsion_wound",
            reach = 3,
            build = { origin -> springRig(origin, powered = false) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.west())
                serverTicks(WIND)
            },
            read = { origin -> springAngleAt(origin) },
            expect = Parity.Same(tolerance = ANGLE_TOLERANCE),
        )

        assertTrue(
            Math.abs(wound.ground) > 1.0,
            "The torsion spring has not wound up at all: it reads ${wound.ground} degrees on the " +
                "ground and ${wound.sub} in the sub-level, with a $RPM rpm motor driving it. " +
                "Relaying rotation within a range of angles is the whole block. See ${wound.pictures}",
        )

        assertTrue(
            Math.abs(wound.ground) <= LIMIT + ANGLE_TOLERANCE,
            "The torsion spring wound past its limit: it reads ${wound.ground} degrees where the " +
                "value panel starts at $LIMIT. Relaying happens *within a set range*, so a spring " +
                "that keeps going is not limiting anything. See ${wound.pictures}",
        )
    }

    @Test
    @DisplayName("A torsion spring returns to its starting angle when the input stops")
    fun `it springs back when the input stops`(cluster: ClusterScope) = cluster.stage {
        val released = bothWays(
            name = "torsion_released",
            reach = 3,
            build = { origin -> springRig(origin, powered = false) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.west())
                serverTicks(WIND)
            },
            read = { origin -> angleAfterLettingGo(origin) },
            expect = Parity.Same(tolerance = ANGLE_TOLERANCE),
        )

        assertTrue(
            Math.abs(released.ground) < ANGLE_TOLERANCE,
            "The torsion spring did not return to its starting angle after the input stopped: it " +
                "is still at ${released.ground} degrees on the ground and ${released.sub} in the " +
                "sub-level. See ${released.pictures}",
        )
    }

    @Test
    @DisplayName("A powered torsion spring holds its angle instead of springing back")
    fun `it holds its angle while powered`(cluster: ClusterScope) = cluster.stage {
        val held = bothWays(
            name = "torsion_held",
            reach = 3,
            build = { origin -> springRig(origin, powered = true) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.west())
                serverTicks(WIND)
            },
            read = { origin -> angleAfterLettingGo(origin) },
            expect = Parity.Same(tolerance = ANGLE_TOLERANCE),
        )

        assertTrue(
            Math.abs(held.ground) > 1.0,
            "The torsion spring sprang back even though it is powered: it returned to " +
                "${held.ground} degrees on the ground and ${held.sub} in the sub-level, where a " +
                "powered spring is supposed to stay where the input left it. See ${held.pictures}",
        )
    }

    /**
     * Stops the motor, waits, and reports where the spring ended up.
     *
     * The motor is found by position rather than remembered from `stimulate`, because the two run as
     * separate bodies and neither may capture anything from the other.
     */
    private suspend fun Stage.angleAfterLettingGo(origin: BlockPos): Double {
        setMotorSpeed(origin.west(3), 0)
        serverTicks(UNWIND)

        return springAngleAt(origin)
    }

    /** How far the spring at [pos] is currently wound, in degrees. */
    private suspend fun springAngleAt(pos: BlockPos): Double = server(pos) { at ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.simulated_team.simulated.content.blocks.torsion_spring.TorsionSpringBlockEntity) {
            throw AssertionError("There is no torsion spring at $at but $be")
        }

        be.angle.toDouble()
    }

    /**
     * A driven shaft into the spring, and optionally a redstone block against it.
     *
     * The spring faces east so its axis is x, matching the shaft it is driven through. The redstone
     * block goes on its north face, which is off that axis and so cannot be mistaken for part of the
     * drive train.
     */
    private suspend fun Stage.springRig(origin: BlockPos, powered: Boolean) {
        setBlock(origin, "simulated:torsion_spring[facing=east,powered=false]")

        // The driven side. The motor goes on the far end of this, in `stimulate`.
        setBlock(origin.west(), "create:shaft[axis=x]")
        setBlock(origin.west(2), "create:water_wheel[facing=east]")

        setBlock(origin.north(), if (powered) "minecraft:redstone_block" else "minecraft:air")
        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        /** Fast enough to reach the limit inside [WIND] ticks. */
        const val RPM = 32

        /** The value panel's starting angle limit, set in `addBehaviours`. */
        const val LIMIT = 90.0

        /** Long enough for the spring to wind all the way to its limit and sit there. */
        const val WIND = 60

        /** Long enough for a released spring to return the whole way. */
        const val UNWIND = 80

        /** Degrees. The spring settles exactly, so this only absorbs float arithmetic. */
        const val ANGLE_TOLERANCE = 2.0
    }
}
