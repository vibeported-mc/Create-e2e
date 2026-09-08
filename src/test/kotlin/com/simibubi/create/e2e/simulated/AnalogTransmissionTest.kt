package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.kineticSpeedAt
import com.simibubi.create.e2e.setAnalogLever
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.settleKinetics
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That an analog transmission gears rotation by its redstone signal, on the ground and in a sub-level.
 *
 * The block is one block that is two things: a shaft along its `axis`, and a cogwheel meshing
 * perpendicular to it, kept as a second kinetic block entity at the same position. What it does is
 * change the ratio between those two halves according to the analog signal reaching it.
 *
 * Its ponder scene states the contract, and the code agrees with it exactly.
 * `AnalogTransmissionBlockEntity.getRotationModifier` is `1 - (signal + 1) / 16`, so:
 *
 * | signal | ratio | the scene's figure against a 16 RPM input |
 * |---|---|---|
 * | 0 | 1 (special-cased) | "behave exactly like Encased Cogwheels" |
 * | 5 | 0.625 | 10 RPM |
 * | 11 | 0.25 | 4 RPM |
 * | 15 | disconnected | "the shaft and cogwheel disconnect" |
 *
 * So the numbers asserted here are derived, not observed -- a test written by running it and writing
 * down what came out would agree with a broken block just as readily as with a working one.
 *
 * Each half of the rig carries a water wheel, so the pictures show which side is turning and roughly
 * how fast. They are evidence for a person; nothing is measured from them.
 *
 * **What this does not prove.** The cogwheel-driven direction, where the shaft speeds up instead of
 * slowing down, and the value panel. Both are worth their own tests.
 */
@DrivesMinecraft
class AnalogTransmissionTest {

    @Test
    @DisplayName("An unpowered analog transmission relays one to one, the same in a sub-level")
    fun `it relays one to one when unpowered`(cluster: ClusterScope) = cluster.stage {
        val plain = bothWays(
            name = "transmission_unpowered",
            reach = 3,
            build = { origin -> transmissionRig(origin) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.north())
            },
            read = { origin -> ratioAcross(origin) },
            expect = Parity.Same(tolerance = TOLERANCE),
        )

        assertTrue(
            Math.abs(plain.ground - 1.0) < TOLERANCE,
            "An unpowered analog transmission is supposed to behave exactly like an Encased " +
                "Cogwheel, so its cogwheel and its shaft should turn at the same speed. The ratio " +
                "came out ${plain.ground} on the ground and ${plain.sub} in the sub-level. " +
                "See ${plain.pictures}",
        )
    }

    @Test
    @DisplayName("Analog signal gears an analog transmission down, further at higher strengths")
    fun `it gears down with signal strength`(cluster: ClusterScope) = cluster.stage {
        val geared = bothWays(
            name = "transmission_geared",
            reach = 3,
            build = { origin -> transmissionRig(origin) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.north())
            },
            read = { origin -> gearedRatiosOf(origin) },
            expect = Parity.Same(tolerance = TOLERANCE),
        )

        // Both halves went through both signal levels inside the reading, which checked each ratio
        // against its derived figure as it went. What is left is the claim that ties them together.
        assertTrue(
            geared.ground > 1.0,
            "A stronger analog signal is supposed to give a larger gear ratio, so the cogwheel " +
                "should be slower at a signal of 11 than at 5. The ratio between the two came out " +
                "${geared.ground} on the ground and ${geared.sub} in the sub-level, where anything " +
                "above one means it did slow down. See ${geared.pictures}",
        )
    }

    @Test
    @DisplayName("At full signal an analog transmission's shaft and cogwheel come apart")
    fun `it disconnects at full signal`(cluster: ClusterScope) = cluster.stage {
        val apart = bothWays(
            name = "transmission_disconnected",
            reach = 3,
            build = { origin -> transmissionRig(origin) },
            stimulate = { origin ->
                setAnalogLever(origin.above(), FULL)
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.west())
            },
            read = { origin -> kineticSpeedAt(origin.north()).toDouble() },
            expect = Parity.Same(),
        )

        assertTrue(
            apart.ground == 0.0 && apart.sub == 0.0,
            "At full signal the shaft and the cogwheel are supposed to come apart and turn " +
                "independently, so with only the shaft driven the cogwheel should be standing " +
                "still. It reads ${apart.ground} on the ground and ${apart.sub} in the sub-level. " +
                "See ${apart.pictures}",
        )

        val shaft = kineticSpeedAt(at(-SIDE, 1, 0).west()).toDouble()

        assertTrue(
            shaft != 0.0,
            "The cogwheel is still, but so is the shaft ($shaft), so nothing here shows the two " +
                "came apart -- the motor never reached the transmission at all. See ${apart.pictures}",
        )
    }

    /**
     * Both geared ratios, checked as they are read, reported as the one number the test asserts on.
     *
     * The sweep happens inside the reading so that it runs identically on the ground and inside the
     * sub-level, and so the parity check compares like with like.
     */
    private suspend fun Stage.gearedRatiosOf(origin: BlockPos): Double {
        setAnalogLever(origin.above(), FIVE)
        settleKinetics(origin.north())
        val atFive = ratioAcross(origin)

        assertTrue(
            Math.abs(atFive - AT_FIVE) < TOLERANCE,
            "At an analog signal of $FIVE the cogwheel should turn at $AT_FIVE of the shaft's " +
                "speed, and it came out $atFive",
        )

        setAnalogLever(origin.above(), ELEVEN)
        settleKinetics(origin.north())
        val atEleven = ratioAcross(origin)

        assertTrue(
            Math.abs(atEleven - AT_ELEVEN) < TOLERANCE,
            "At an analog signal of $ELEVEN the cogwheel should turn at $AT_ELEVEN of the shaft's " +
                "speed, and it came out $atEleven",
        )

        return if (atEleven == 0.0) 0.0 else Math.abs(atFive / atEleven)
    }

    /** The cogwheel's speed over the shaft's, unsigned: the gear ratio through the transmission. */
    private suspend fun ratioAcross(origin: BlockPos): Double {
        val shaft = kineticSpeedAt(origin.west()).toDouble()
        val cog = kineticSpeedAt(origin.north()).toDouble()

        return if (shaft == 0.0) 0.0 else Math.abs(cog / shaft)
    }

    /**
     * A driven shaft into the transmission, a cogwheel out of its side, and an analog lever on top.
     *
     * The cogwheel goes north because the transmission's own cogwheel meshes perpendicular to its
     * `axis`, and the lever goes on top because the block reads `getBestNeighborSignal`, which takes
     * the strongest of all six sides. Each side carries a water wheel, which is the only thing in
     * these pictures big enough to show motion.
     */
    private suspend fun Stage.transmissionRig(origin: BlockPos) {
        setBlock(origin, "simulated:analog_transmission[axis=x]")

        // The driven side. The motor goes on the far end of this, in `stimulate`.
        setBlock(origin.west(), "create:shaft[axis=x]")
        setBlock(origin.west(2), "create:water_wheel[facing=east]")

        // The geared side: a cogwheel meshing with the transmission's own, and a wheel on its shaft.
        setBlock(origin.north(), "create:cogwheel[axis=x]")
        setBlock(origin.north().west(), "create:water_wheel[facing=east]")

        setBlock(origin.above(), "create:analog_lever[face=floor,facing=north]")
        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        /** Fast enough to be unmistakable, slow enough to be an ordinary network. */
        const val RPM = 32

        /** How far off the stage origin `bothWays` builds the ground rig. Kept in step with it. */
        const val SIDE = 8

        const val FIVE = 5
        const val ELEVEN = 11
        const val FULL = 15

        /** `1 - (5 + 1) / 16`, which is the 10 RPM the ponder scene shows against a 16 RPM input. */
        const val AT_FIVE = 0.625

        /** `1 - (11 + 1) / 16`, which is its 4 RPM. */
        const val AT_ELEVEN = 0.25

        /** The speeds are exact multiples, so this only has to absorb float arithmetic. */
        const val TOLERANCE = 0.02
    }
}
