package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
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
 * That a gimbal sensor reads level when it is level, and reads a tilt when it is tilted.
 *
 * The scene's claim: the gimbal sensor outputs a redstone signal based on its orientation, emitted
 * downhill by default.
 *
 * Like the velocity sensor, half of this cannot happen on the ground. A block sitting on the floor of
 * the world is level and stays level; the only thing that tilts is a sub-level. So the level case is
 * a parity test -- both worlds should agree that nothing is happening -- and the tilted case
 * assembles a body, puts a spin on it, and reads the sensor while it is over.
 *
 * **What this does not prove.** Which face the signal comes out of, the angle-of-maximum-signal value
 * box, inverting the output, or the two axes being independent of one another. All are in the scene.
 * The first needs a settled, known attitude rather than a body mid-tumble, and the rest are value-box
 * interactions.
 */
@DrivesMinecraft
class GimbalSensorTest {

    @Test
    @DisplayName("A level gimbal sensor reads level, on the ground and in a sub-level")
    fun `level reads level`(cluster: ClusterScope) = cluster.stage {
        val flat = bothWays(
            name = "gimbal_level",
            reach = REACH,
            build = { origin -> gimbalRig(origin) },
            read = { origin -> tiltAt(origin) },
            expect = Parity.Same(tolerance = LEVEL),
        )

        assertTrue(
            flat.ground < LEVEL && flat.sub < LEVEL,
            "A gimbal sensor standing on flat ground reads a tilt of ${flat.ground} on the ground " +
                "and ${flat.sub} in the sub-level. Then a reading taken while it really is tilted " +
                "says nothing. See ${flat.pictures}",
        )
    }

    @Test
    @DisplayName("A tilted gimbal sensor reads the tilt and emits a signal")
    fun `a tilted sensor reads the tilt`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val builtAt = at(0, 1, 0)
        gimbalRig(builtAt)
        serverTicks(SETTLE)

        val corner = builtAt.offset(-REACH, -1, -REACH)
        val body = assembleArea(corner, builtAt.offset(REACH, REACH, REACH))
        serverTicks(SETTLE)

        try {
            val plot = plotOriginOf(body)
            val sensor = BlockPos(plot.x, plot.y, plot.z)
                .offset(builtAt.x - corner.x, builtAt.y - corner.y, builtAt.z - corner.z)

            val whileLevel = tiltAt(sensor)

            assertTrue(
                whileLevel < LEVEL,
                "The body is already tilted before it has been touched ($whileLevel), so nothing " +
                    "below is a claim about tilting it",
            )

            // A torque about the north-south axis, which rolls the body sideways. The gimbal's own
            // axes are what it reports on; this only has to be a rotation it can notice.
            angularImpulse(body, 0.0, 0.0, TORQUE)
            serverTicks(TUMBLE)

            val whileTilted = tiltAt(sensor)

            assertTrue(
                whileTilted > TILTED,
                "The body has been rolled and the gimbal sensor reads a tilt of $whileTilted, " +
                    "where it read $whileLevel standing still. Outputting a signal based on its " +
                    "orientation is the whole block",
            )

            assertTrue(
                strongestFaceAt(sensor) > 0,
                "The gimbal sensor knows it is tilted -- it reads $whileTilted -- and no face of it " +
                    "is emitting any redstone. The tilt is supposed to come out as a signal",
            )
        } finally {
            removeSubLevel(body)
        }
    }

    /**
     * How far from level the sensor at [pos] thinks it is.
     *
     * The larger of the two angles it reports, so the test does not depend on which way the body
     * happened to roll.
     */
    private suspend fun tiltAt(pos: BlockPos): Double = server(pos) { at ->
        val be = gimbalSensorAt(serverLevel, at)
        Math.max(Math.abs(be.xAngle), Math.abs(be.zAngle))
    }

    /** The strongest redstone any face of the sensor at [pos] is putting out. */
    private suspend fun strongestFaceAt(pos: BlockPos): Int = server(pos) { at ->
        val be = gimbalSensorAt(serverLevel, at)
        net.minecraft.core.Direction.values().maxOf { be.getPower(it) }
    }

    /** The sensor on a small slab, which is the whole rig. */
    private suspend fun Stage.gimbalRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:gimbal_sensor[axis=x]")
    }

    companion object {
        const val GROUND = 20
        const val REACH = 2
        const val SETTLE = 20

        /** Long enough for the roll to take hold and the sensor to have ticked on it. */
        const val TUMBLE = 20

        /** Enough to roll a body this small well off level. */
        const val TORQUE = 400.0

        /** Below this the sensor is level, allowing for the solver settling. */
        const val LEVEL = 0.05

        /** Above this it is unmistakably off level. */
        const val TILTED = 0.1
    }
}

/**
 * The gimbal sensor at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun gimbalSensorAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.gimbal_sensor.GimbalSensorBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.gimbal_sensor.GimbalSensorBlockEntity) {
        throw AssertionError(
            "There is no gimbal sensor at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
