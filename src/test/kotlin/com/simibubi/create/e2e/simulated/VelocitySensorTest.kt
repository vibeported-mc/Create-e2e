package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a velocity sensor reads the speed of the body it is riding, along its own axis only.
 *
 * The scene's claims: the sensor outputs a redstone signal based on its speed, and velocity is only
 * measured through the facing axis.
 *
 * This is the first block in the package whose contract cannot be tested on the ground at all. A
 * velocity sensor sitting on the floor of the world has no velocity and never will; the only thing
 * that moves in Minecraft-with-Sable is a sub-level. So the shape here is different from the parity
 * tests next door: one test keeps the standing-still case honest through `bothWays`, and the two that
 * matter assemble a body, shove it, and read the sensor while it is travelling.
 *
 * The direction of the shove comes from the sensor itself. `getCurrentNormal` is the axis it measures
 * along, so one test pushes the body straight down that axis and the other pushes it square across --
 * which means neither depends on my working out what `facing` and `axis_along_first` combine to, and
 * both would still be pushing the right way if that derivation changed.
 *
 * **What this does not prove.** The maximum-speed value box, or flipping the output to the side
 * towards the direction of motion. Both are in the scene; neither is covered here.
 */
@DrivesMinecraft
class VelocitySensorTest {

    @Test
    @DisplayName("A velocity sensor standing still reads nothing, on the ground and in a sub-level")
    fun `at rest it reads nothing`(cluster: ClusterScope) = cluster.stage {
        val still = bothWays(
            name = "velocity_still",
            reach = REACH,
            build = { origin -> sensorRig(origin) },
            read = { origin -> Math.abs(adjustedVelocityAt(origin)) },
            expect = Parity.Same(tolerance = AT_REST),
        )

        assertTrue(
            still.ground < AT_REST && still.sub < AT_REST,
            "A velocity sensor that is not going anywhere reads ${still.ground} on the ground and " +
                "${still.sub} in the sub-level. Then a reading taken while it *is* moving says " +
                "nothing. See ${still.pictures}",
        )
    }

    @Test
    @DisplayName("A velocity sensor reads the speed of a body pushed along its axis")
    fun `it reads motion along its axis`(cluster: ClusterScope) = cluster.stage {
        val pushed = readingAfterPush(alongItsAxis = true)

        assertTrue(
            pushed.bodySpeed > MOVING,
            "The body is not moving (${pushed.bodySpeed}), so the sensor reading ${pushed.reading} " +
                "says nothing about whether it measures speed",
        )

        assertTrue(
            Math.abs(pushed.reading) > READS_SOMETHING,
            "The body is travelling at ${pushed.bodySpeed} straight down the sensor's own axis and " +
                "the sensor reads ${pushed.reading}. Outputting a signal based on its speed is the " +
                "whole block",
        )
    }

    @Test
    @DisplayName("A velocity sensor ignores motion across its axis")
    fun `it ignores motion across its axis`(cluster: ClusterScope) = cluster.stage {
        val across = readingAfterPush(alongItsAxis = false)

        assertTrue(
            across.bodySpeed > MOVING,
            "The body is not moving (${across.bodySpeed}), so a sensor reading of nothing is not " +
                "evidence that it ignores this direction",
        )

        assertTrue(
            Math.abs(across.reading) < AT_REST,
            "The body is travelling at ${across.bodySpeed} square across the sensor's axis and the " +
                "sensor still reads ${across.reading}. Velocity is only supposed to be measured " +
                "through its own axis",
        )
    }

    /**
     * Builds a body, shoves it, and reports what the sensor made of it.
     *
     * The shove is aimed from `getCurrentNormal`, either along it or across it, so the test never has
     * to know which way the block thinks it is pointing.
     */
    private suspend fun Stage.readingAfterPush(alongItsAxis: Boolean): Reading {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val builtAt = at(0, 1, 0)
        sensorRig(builtAt)
        serverTicks(SETTLE)

        val corner = builtAt.offset(-REACH, -1, -REACH)
        val body = assembleArea(corner, builtAt.offset(REACH, REACH, REACH))
        serverTicks(SETTLE)

        try {
            val plot = plotOriginOf(body)
            val sensor = BlockPos(plot.x, plot.y, plot.z)
                .offset(builtAt.x - corner.x, builtAt.y - corner.y, builtAt.z - corner.z)

            val normal = normalAt(sensor)
            val push = if (alongItsAxis) normal else normal.across()

            linearImpulse(body, push.x * IMPULSE, push.y * IMPULSE, push.z * IMPULSE)
            serverTicks(TRAVEL)

            return Reading(adjustedVelocityAt(sensor), velocityOf(body).linearSpeed)
        } finally {
            removeSubLevel(body)
        }
    }

    /** What the sensor at [pos] says it is travelling at, in metres per second. */
    private suspend fun adjustedVelocityAt(pos: BlockPos): Double = server(pos) { at ->
        velocitySensorAt(serverLevel, at).adjustedVelocity.toDouble()
    }

    /** The axis the sensor at [pos] measures along. */
    private suspend fun normalAt(pos: BlockPos): Normal = server(pos) { at ->
        val normal = velocitySensorAt(serverLevel, at).currentNormal
        Normal(normal.x(), normal.y(), normal.z())
    }

    /** The sensor on a slab, which is the whole rig. */
    private suspend fun Stage.sensorRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:velocity_sensor[facing=up,axis_along_first=true]")
    }

    /** The axis a velocity sensor measures along. */
    @Serializable
    data class Normal(val x: Double, val y: Double, val z: Double) {
        /**
         * A horizontal direction square across this one.
         *
         * Swapping x and z and negating one turns a horizontal axis a quarter turn, which is all this
         * needs: the sensors here lie flat, so the perpendicular that matters is the other horizontal
         * one rather than straight up.
         */
        fun across(): Normal = Normal(-z, y, x)
    }

    /** What the sensor read, and how fast the body was actually going when it read it. */
    data class Reading(val reading: Double, val bodySpeed: Double)

    companion object {
        const val GROUND = 20
        const val REACH = 2
        const val SETTLE = 20

        /** Long enough for the body to be up to speed and the sensor to have ticked. */
        const val TRAVEL = 20

        /** Hard enough to be unmistakable on a body this small. */
        const val IMPULSE = 300.0

        /** Below this the body has not really been shoved and nothing else here means anything. */
        const val MOVING = 0.05

        /** Metres per second. Anything under this is the solver settling rather than travel. */
        const val AT_REST = 0.05

        /** Metres per second the sensor has to notice before it counts as having read the motion. */
        const val READS_SOMETHING = 0.1
    }
}

/**
 * The velocity sensor at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun velocitySensorAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity) {
        throw AssertionError(
            "There is no velocity sensor at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
