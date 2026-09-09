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
 * That an optical sensor sees a block in front of it, and reports how far away it is.
 *
 * The scene's claims: optical sensors detect blocks which obstruct their laser, a redstone signal is
 * emitted when one is detected, and the signal strength is relative to the detected block's distance.
 *
 * The distance is the interesting half. "It saw something" is one bit and would be satisfied by a
 * sensor that fired at anything; what the block actually promises is a reading that tracks where the
 * obstruction is, so the sweep here puts a block at two distances and checks the sensor reports both.
 *
 * All of it runs on the ground and inside a sub-level, because the sensor works by casting a ray
 * through the level and a ray inside a body with its own coordinates is exactly the sort of thing
 * that survives a port in one world and not the other.
 *
 * **What this does not prove.** The item filter, or the maximum distance on the value panel. Both are
 * in the scene, and both are player interactions the sub-level half cannot reach.
 */
@DrivesMinecraft
class OpticalSensorTest {

    @Test
    @DisplayName("An optical sensor sees a block in front of it and says how far, the same in a sub-level")
    fun `it sees a block and reports its distance`(cluster: ClusterScope) = cluster.stage {
        val seen = bothWays(
            name = "optical_seen",
            reach = REACH,
            build = { origin -> sensorRig(origin, obstacleAt = NEAR) },
            read = { origin -> distanceAcrossTheSweepAt(origin) },
            expect = Parity.Same(tolerance = DISTANCE_TOLERANCE),
        )

        assertTrue(
            Math.abs(seen.ground - (FAR - NEAR)) < DISTANCE_TOLERANCE,
            "Moving the obstruction from $NEAR blocks away to $FAR should move the sensor's reading " +
                "by ${FAR - NEAR}. It moved by ${seen.ground} on the ground and ${seen.sub} in the " +
                "sub-level. A reading that does not track the block is not relative to its distance " +
                "at all. See ${seen.pictures}",
        )
    }

    @Test
    @DisplayName("An optical sensor with nothing in front of it stays quiet")
    fun `it sees nothing when nothing is there`(cluster: ClusterScope) = cluster.stage {
        val quiet = bothWays(
            name = "optical_clear",
            reach = REACH,
            build = { origin -> sensorRig(origin, obstacleAt = null) },
            read = { origin -> if (hasHitAt(origin)) hitDistanceAt(origin) else SAW_NOTHING },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            quiet.ground == SAW_NOTHING,
            "The optical sensor has clear air within its $RANGE-block range and reports a hit " +
                "anyway, at ${quiet.ground} blocks on the ground and ${quiet.sub} in the sub-level. " +
                "Then the other test in this class is not measuring detection. See ${quiet.pictures}",
        )
    }

    /**
     * How much the reported distance moves when the obstruction does.
     *
     * Both placements happen inside the reading, so the same sweep runs on the ground and in the
     * sub-level, and each reading is checked against where the block actually is as it goes.
     */
    private suspend fun Stage.distanceAcrossTheSweepAt(origin: BlockPos): Double {
        val near = distanceSeenFrom(origin, NEAR)

        assertTrue(
            Math.abs(near - NEAR) < DISTANCE_TOLERANCE,
            "The obstruction is $NEAR blocks in front of the optical sensor and it reports $near",
        )

        val far = distanceSeenFrom(origin, FAR)

        assertTrue(
            Math.abs(far - FAR) < DISTANCE_TOLERANCE,
            "The obstruction is $FAR blocks in front of the optical sensor and it reports $far",
        )

        return far - near
    }

    /** Puts the obstruction [at] blocks in front, and reports what the sensor makes of it. */
    private suspend fun Stage.distanceSeenFrom(origin: BlockPos, at: Int): Double {
        for (dx in 1..FAR) {
            setBlock(origin.east(dx), if (dx == at) OBSTACLE else "minecraft:air")
        }

        serverTicks(LOOK)

        assertTrue(
            hasHitAt(origin),
            "The optical sensor sees nothing with a block $at in front of it",
        )

        return hitDistanceAt(origin)
    }

    private suspend fun hasHitAt(pos: BlockPos): Boolean = server(pos) { at ->
        opticalSensorAt(serverLevel, at).hasHit()
    }

    private suspend fun hitDistanceAt(pos: BlockPos): Double = server(pos) { at ->
        opticalSensorAt(serverLevel, at).hitBlockDistance.toDouble()
    }

    /**
     * The sensor on a slab, with clear air in front of it.
     *
     * The slab runs the whole length so that everything here is one connected structure and assembly
     * takes it as a single body -- see `bothWays`. It sits a block below the beam, so it is never
     * itself the thing the sensor sees.
     */
    private suspend fun Stage.sensorRig(origin: BlockPos, obstacleAt: Int?) {
        for (dx in 0..(FAR + 1)) {
            setBlock(origin.east(dx).below(), "minecraft:stone")
        }

        setBlock(origin, "simulated:optical_sensor[facing=east,powered=false]")

        for (dx in 1..FAR) {
            setBlock(origin.east(dx), if (dx == obstacleAt) OBSTACLE else "minecraft:air")
        }

        // The range is set rather than left at whatever the block ships with, so that "nothing in
        // front of it" is a statement about a known distance. On its default range the ground sensor
        // reported a hit with the near air clear -- it was seeing something further out that the
        // sub-level copy, sitting in an empty plot, had nothing to see.
        setRange(origin, RANGE)
    }

    /** Sets how far the sensor at [pos] will look. */
    private suspend fun setRange(pos: BlockPos, blocks: Int) = server(pos, blocks) { at, range ->
        opticalSensorAt(serverLevel, at).setRange(range)
    }

    companion object {
        const val OBSTACLE = "minecraft:iron_block"

        /** Two distances far enough apart that a reading which ignores the block cannot fake both. */
        const val NEAR = 2
        const val FAR = 5

        /** Wide enough for the slab, which runs one past the furthest obstruction. */
        const val REACH = 7

        /** Just past the furthest obstruction, so nothing beyond the rig is ever in view. */
        const val RANGE = 6

        /** Long enough for the sensor to cast again after the obstruction moves. */
        const val LOOK = 20

        const val SAW_NOTHING = 0.0

        /** The reading is in blocks and lands on the block face, so this absorbs the face offset. */
        const val DISTANCE_TOLERANCE = 1.01
    }
}

/**
 * The optical sensor at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so has to reach its helpers by name.
 */
internal fun opticalSensorAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.lasers.optical_sensor.OpticalSensorBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.lasers.optical_sensor.OpticalSensorBlockEntity) {
        throw AssertionError(
            "There is no optical sensor at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
