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
 * That a laser pointer lights a laser sensor across a gap, and that a block in the way stops it.
 *
 * The scene's claims, in its own words: laser pointers emit a laser when powered, the laser can be
 * obstructed by blocks, laser sensors can detect the laser when unobstructed, and they emit a signal
 * dependent on the laser power.
 *
 * So the sensor has to actually sense a laser. There is no shortcut here where the sensor is poked
 * directly -- a real pointer is placed three blocks away, powered with a redstone block, and the
 * sensor is read to see whether the beam arrived. The obstructed and unpowered cases are the same rig
 * with one block changed, which is what makes the detecting case mean anything.
 *
 * All three run on the ground and inside a sub-level. A laser is cast by a raycast through the level,
 * and a raycast in a body that has its own coordinates is exactly the kind of thing that survives a
 * port in one world and not the other.
 *
 * **What this does not prove.** Colour filters, inverting the output with a wrench on the front or
 * back face, or the maximum casting distance on the back panel. All three are in the scene and all
 * three are player interactions on the block, which the sub-level half of this cannot reach.
 */
@DrivesMinecraft
class LaserTest {

    @Test
    @DisplayName("A powered laser pointer lights a sensor across a gap, the same in a sub-level")
    fun `a powered pointer lights the sensor`(cluster: ClusterScope) = cluster.stage {
        val lit = bothWays(
            name = "laser_lit",
            reach = REACH,
            build = { origin -> laserRig(origin, powered = true, obstructed = false) },
            read = { origin -> sensorPowerAt(origin.east(GAP)) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            lit.ground > 0.0,
            "The laser sensor is reading ${lit.ground} on the ground and ${lit.sub} in the " +
                "sub-level, with a powered pointer $GAP blocks away and nothing in between. " +
                "Detecting an unobstructed laser is the whole pair of blocks. See ${lit.pictures}",
        )
    }

    @Test
    @DisplayName("A block in the way stops the laser reaching the sensor")
    fun `a block obstructs the laser`(cluster: ClusterScope) = cluster.stage {
        val blocked = bothWays(
            name = "laser_blocked",
            reach = REACH,
            build = { origin -> laserRig(origin, powered = true, obstructed = true) },
            read = { origin -> sensorPowerAt(origin.east(GAP)) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            blocked.ground == 0.0,
            "There is a solid block between the pointer and the sensor, and the sensor still reads " +
                "${blocked.ground} on the ground and ${blocked.sub} in the sub-level. The laser is " +
                "supposed to be obstructed by blocks. See ${blocked.pictures}",
        )
    }

    @Test
    @DisplayName("An unpowered laser pointer lights nothing")
    fun `an unpowered pointer lights nothing`(cluster: ClusterScope) = cluster.stage {
        val dark = bothWays(
            name = "laser_dark",
            reach = REACH,
            build = { origin -> laserRig(origin, powered = false, obstructed = false) },
            read = { origin -> sensorPowerAt(origin.east(GAP)) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            dark.ground == 0.0,
            "The pointer has no redstone on it and the sensor is reading ${dark.ground} on the " +
                "ground and ${dark.sub} in the sub-level. Pointers emit a laser *when powered*, so " +
                "the other tests in this class prove nothing if this one does not hold. " +
                "See ${dark.pictures}",
        )
    }

    /** What the laser sensor at [pos] is currently reading. */
    private suspend fun sensorPowerAt(pos: BlockPos): Double = server(pos) { at ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.simulated_team.simulated.content.blocks.lasers.laser_sensor.LaserSensorBlockEntity) {
            throw AssertionError(
                "There is no laser sensor at $at. The block there is " +
                    serverLevel.getBlockState(at) + " and the block entity is " + be +
                    ". Its neighbours west to east are " +
                    (-4..4).joinToString(" | ") { serverLevel.getBlockState(at.east(it)).block.toString() },
            )
        }

        be.currentPower.toDouble()
    }

    /**
     * A pointer, a gap, and a sensor facing back down it, all standing on one slab.
     *
     * The pointer faces east and the sensor west, so they are aimed at each other along the x axis.
     * The redstone block that powers the pointer sits behind it, off the beam, so it cannot be the
     * thing the sensor is reacting to.
     *
     * The slab runs the whole length and is the reason this works. The first version put stone under
     * the pointer and stone under the sensor with the gap between them left as air -- two islands
     * that happen to be in one box. Assembly makes bodies out of *connected* blocks, so the pointer's
     * island became the sub-level and the sensor's was left behind: the sub-level half then read air
     * where it expected a sensor, and the abandoned half was visible in the world falling on its own.
     */
    private suspend fun Stage.laserRig(origin: BlockPos, powered: Boolean, obstructed: Boolean) {
        for (dx in -BASE_BEHIND..(GAP + 1)) {
            setBlock(origin.east(dx).below(), "minecraft:stone")
        }

        setBlock(origin, "simulated:laser_pointer[facing=east,inverted=false,powered=false]")
        setBlock(origin.east(GAP), "simulated:laser_sensor[facing=west]")

        setBlock(origin.west(), if (powered) "minecraft:redstone_block" else "minecraft:air")
        setBlock(origin.east(1), if (obstructed) "minecraft:stone" else "minecraft:air")
    }

    companion object {
        /** How far apart the pointer and the sensor stand. */
        const val GAP = 3

        /** How far the slab runs behind the pointer, so the redstone block is on it too. */
        const val BASE_BEHIND = 2

        /** Wide enough to take the whole slab, from [BASE_BEHIND] behind to one past the sensor. */
        const val REACH = 4
    }
}
