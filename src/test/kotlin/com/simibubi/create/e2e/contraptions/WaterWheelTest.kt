package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Water wheels driven by a creative motor, photographed.
 *
 * These cover rather more than the blocks they place: Create's registration, a kinetic network
 * forming across a block boundary, and -- since the camera is a real client -- Flywheel drawing the
 * result.
 *
 * Ported from Create's `WaterWheelTests` client gametest.
 */
@DrivesMinecraft
class WaterWheelTest {

    @Test
    @DisplayName("A creative motor turns a small water wheel")
    fun `small water wheel`(cluster: ClusterScope) = cluster.driving {
        val wheel = BlockPos(3, GROUND, ZONE)
        // The motor sits on the wheel's north face, where the wheel's shaft comes out. facing is the
        // axis the shaft runs along, so both blocks face along z and meet.
        val motor = wheel.north()

        clearGround(wheel, 6)
        watch(wheel)

        setBlock(wheel, "create:water_wheel[facing=north]")
        setBlock(motor, "create:creative_motor[facing=south]")

        // Long enough for the network to form and the wheel to spin up to the motor's speed.
        serverTicks(40)
        shot("small_water_wheel")
        restoreHud()

        assertBlock(wheel, "create:water_wheel")
        assertBlock(motor, "create:creative_motor")
        assertDriven(wheel, motor)
    }

    @Test
    @DisplayName("A creative motor turns a large water wheel")
    fun `large water wheel`(cluster: ClusterScope) = cluster.driving {
        // The large wheel is 3x3 around its centre, so it has to stand a block clear of the ground.
        val wheel = BlockPos(3, GROUND + 1, ZONE + 16)
        val motor = wheel.north()

        // Cleared about the ground rather than about the wheel. `clearGround` lays its stone floor a
        // block under whatever it is given, and a floor directly beneath this wheel is something in
        // the way of the ring it builds -- so it puts itself up and takes itself straight down again,
        // leaving air where the wheel was. The originals never met this: their world was fresh, and
        // nothing had to be cleared at all.
        clearGround(BlockPos(wheel.x, GROUND, wheel.z), 6)
        watch(wheel)

        setBlock(wheel, "create:large_water_wheel[axis=z]")
        setBlock(motor, "create:creative_motor[facing=south]")

        serverTicks(40)
        shot("large_water_wheel")
        restoreHud()

        assertBlock(wheel, "create:large_water_wheel")
        assertBlock(motor, "create:creative_motor")

        // The large wheel builds its own ring of structural blocks on the tick after placement, and
        // tears itself down again if anything is in the way, so their presence is the real evidence
        // that it stood up.
        assertBlock(wheel.above(), "create:water_wheel_structure")
        assertBlock(wheel.below(), "create:water_wheel_structure")

        assertDriven(wheel, motor)
    }

    /** Stands off the south-east corner so the shot catches the wheel's face and the motor beside it. */
    private suspend fun watch(wheel: BlockPos) {
        spectateAt(
            Vec3(wheel.x + 4.5, wheel.y + 2.0, wheel.z + 4.5),
            Vec3.atCenterOf(wheel),
        )
    }

    /**
     * Compared by registry id rather than by the mod's own BlockEntry.
     *
     * A BlockEntry cannot cross a wire, and the description id will not do either: "water_wheel" is
     * a substring of both "large_water_wheel" and "water_wheel_structure", so a loose comparison
     * would pass for the wrong block.
     */
    private suspend fun assertBlock(pos: BlockPos, expected: String) {
        val found = server(pos) { where ->
            BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(where).block).toString()
        }
        assertEquals(expected, found, "Expected $expected at $pos")
    }

    /**
     * That the motor is what is turning the wheel.
     *
     * Two readings in one call rather than two: a kinetic network is live state, and asking about it
     * twice from another process is asking about two different moments.
     */
    private suspend fun assertDriven(wheelPos: BlockPos, motorPos: BlockPos) {
        val report = server(wheelPos, motorPos) { wheel, motor ->
            val a = kineticAt(serverLevel, wheel)
            val b = kineticAt(serverLevel, motor)
            Driven(a.speed, b.speed, a.network == b.network)
        }

        // A water wheel generates nothing without water, so any speed it has comes from the motor.
        assertTrue(report.wheelSpeed != 0f, "The water wheel is not turning, so the motor did not reach it")
        assertTrue(report.sameNetwork, "The wheel and the motor are on separate kinetic networks")

        // The two are shaft to shaft with no gearing between them to change the rate. Which way round
        // the sign comes out is the propagator's business, so only the rate is checked.
        assertEquals(
            Math.abs(report.motorSpeed), Math.abs(report.wheelSpeed),
            "The wheel is not turning at the motor's speed",
        )
    }

    /**
     * What one look at the kinetic network saw.
     *
     * A `@Serializable` record because the reading has to cross a process boundary, and because the
     * three parts of it have to be one moment rather than three.
     */
    @kotlinx.serialization.Serializable
    private data class Driven(val wheelSpeed: Float, val motorSpeed: Float, val sameNetwork: Boolean)

    private companion object {

        const val ZONE = Zones.WATER_WHEEL

        /**
         * The superflat preset the driver seeds puts grass at y=-61, so the first free block above
         * the ground is y=-60.
         */
        const val GROUND = -60

        fun kineticAt(level: ServerLevel, pos: BlockPos): KineticBlockEntity =
            level.getBlockEntity(pos) as? KineticBlockEntity
                ?: throw AssertionError("Expected a kinetic block entity at $pos")
    }
}
