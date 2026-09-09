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
 * That an altitude sensor reads higher the higher it is, and reads its band the way it is told to.
 *
 * The scene's claims: the sensor outputs a redstone signal based on its elevation, travelling upwards
 * increases the signal by default, and the minimum and maximum altitudes can be changed in the UI.
 *
 * Those last two are tested together, because on the shipped band they cannot be separated. `getValue`
 * normalises the sensor's world height across the *whole* level, floor to build limit, and then maps
 * it between `lowSignal` and `highSignal`. Over a 384-block world one signal step is about 26 blocks,
 * so a sensor lifted a few blocks reads exactly what it did before and a test built on that would be
 * measuring nothing. The band is narrowed to [BAND] blocks first -- which is the UI's own knob -- and
 * then the lift is worth a signal or two.
 *
 * Two sensors are used rather than one that moves: a low one on the slab and a high one on a pillar,
 * both configured with the same band. That keeps the rig a single connected structure, which is what
 * assembly needs (see `bothWays`), and it means the two readings are taken in the same tick.
 *
 * The reading compared across the two worlds is the *difference* between the sensors, not either one
 * on its own -- height is the one thing a sub-level legitimately changes about a block, and the claim
 * being made here is about the gap rather than about the absolute number.
 *
 * **What this does not prove.** Inverting the sensor so that height decreases the signal, the goggles
 * and wrench readouts, or air pressure. All are in the scene; none is covered here.
 */
@DrivesMinecraft
class AltitudeSensorTest {

    @Test
    @DisplayName("A higher altitude sensor reads a stronger signal, the same in a sub-level")
    fun `higher reads stronger`(cluster: ClusterScope) = cluster.stage {
        val climbed = bothWays(
            name = "altitude_climbed",
            reach = REACH,
            build = { origin -> altitudeRig(origin) },
            stimulate = { origin -> narrowTheBand(origin) },
            read = { origin -> signalGainUpThePillarAt(origin) },
            expect = Parity.Same(tolerance = SIGNAL_TOLERANCE),
        )

        assertTrue(
            climbed.ground > 0.0,
            "The altitude sensor $LIFT blocks up reads no more than the one on the floor: the gap " +
                "is ${climbed.ground} on the ground and ${climbed.sub} in the sub-level, over a " +
                "$BAND-block band. Travelling upwards is supposed to increase the signal. " +
                "See ${climbed.pictures}",
        )
    }

    /** The high sensor's signal less the low one's. */
    private suspend fun Stage.signalGainUpThePillarAt(origin: BlockPos): Double {
        serverTicks(READ)

        return signalAt(origin.above(LIFT)) - signalAt(origin)
    }

    /**
     * Points both sensors at a [BAND]-block window starting at the lower one.
     *
     * `lowSignal` and `highSignal` are normalised heights -- the same 0-to-1 scale `getValue` works
     * in -- so they are computed from the sensor's own `toNormalHeight` rather than written as
     * numbers, which keeps this correct whatever the level's floor and build limit happen to be.
     */
    private suspend fun Stage.narrowTheBand(origin: BlockPos) {
        val bottom = origin
        val top = origin.above(LIFT)

        server(bottom, top) { low, high ->
            val reference = altitudeSensorAt(serverLevel, low)
            val from = reference.toNormalHeight(reference.worldHeight)
            val to = reference.toNormalHeight(reference.worldHeight + AltitudeSensorTest.BAND)

            for (pos in listOf(low, high)) {
                val be = altitudeSensorAt(serverLevel, pos)
                be.lowSignal = from
                be.highSignal = to
                be.notifyUpdate()
            }
        }
    }

    private suspend fun signalAt(pos: BlockPos): Double = server(pos) { at ->
        altitudeSensorAt(serverLevel, at).signal.toDouble()
    }

    /**
     * A sensor on the slab, a pillar, and a second sensor on top of it.
     *
     * One connected structure, so assembly takes the whole thing as a single body.
     */
    private suspend fun Stage.altitudeRig(origin: BlockPos) {
        setBlock(origin.below(), "minecraft:stone")
        setBlock(origin, SENSOR)

        for (dy in 1 until LIFT) {
            setBlock(origin.above(dy), "minecraft:stone")
        }

        setBlock(origin.above(LIFT), SENSOR)
    }

    companion object {
        const val SENSOR = "simulated:altitude_sensor[face=floor,facing=north,dial=linear]"

        /** How far apart the two sensors stand. */
        const val LIFT = 6

        /**
         * The window the sensors are set to read across, in blocks.
         *
         * Comfortably wider than [LIFT] so the high sensor is inside the band rather than clamped at
         * the top of it, and narrow enough that [LIFT] is worth several signal steps.
         */
        const val BAND = 10.0f

        /** Tall enough for the pillar. */
        const val REACH = 8

        /** Long enough for both sensors to have ticked since the band changed. */
        const val READ = 20

        /** One signal step, to absorb the two bodies sitting at very slightly different heights. */
        const val SIGNAL_TOLERANCE = 1.01
    }
}

/**
 * The altitude sensor at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun altitudeSensorAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity) {
        throw AssertionError(
            "There is no altitude sensor at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
