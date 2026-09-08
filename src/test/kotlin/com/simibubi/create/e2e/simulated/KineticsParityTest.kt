package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.kineticSpeedAt
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
 * That Simulated's kinetic blocks do their job, and do it the same inside a sub-level.
 *
 * Each test builds its rig twice -- once on the stage floor and once beside it, assembled into a
 * sub-level -- drives both with a creative motor, reads the same block on each, and leaves two
 * pictures a few ticks apart with both rigs in frame. See `Parity.kt`.
 *
 * The rigs are hand-built with `setBlock`, so nothing here can pass on a number that was already
 * sitting in a block entity: every block starts at rest and the only rotation in the scene is the
 * motor the test placed.
 *
 * Each rig carries water wheels on the shafts it is meant to be turning. They do nothing for the
 * assertions -- they are there so the pictures show rotation to a person reading them, which two bare
 * shafts never will.
 *
 * The behaviour comes from the mod's ponder scenes, read as documentation and nothing more: those
 * scenes fake their effects, writing the downstream speed by hand rather than letting the block
 * produce it, so a number taken out of one is not evidence of anything.
 *
 * **What this does not prove.** How any of it looks. The pictures are evidence for a person, not
 * something measured.
 */
@DrivesMinecraft
class KineticsParityTest {

    @Test
    @DisplayName("A powered directional gearshift relays rotation through itself, the same in a sub-level")
    fun `the gearshift relays rotation when powered`(cluster: ClusterScope) = cluster.stage {
        val relayed = bothWays(
            name = "gearshift_relay",
            reach = 3,
            build = { origin -> gearshiftRig(origin, powerAbove = true) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.east())
            },
            // The water wheel on the *far* side of the gearshift. Reading the wheel rather than the
            // shaft beside it means the parity check covers the wheel in both worlds, and the wheel
            // is the thing whose motion the pictures show -- so what is asserted and what is looked
            // at are the same object. The gearshift's own speed would say only that a motor next to
            // it turns it, which is a fact about the motor.
            read = { origin -> kineticSpeedAt(origin.east(2)).toDouble() },
            expect = Parity.Same(),
        )

        assertTrue(
            relayed.ground != 0.0 && relayed.sub != 0.0,
            "The gearshift is powered on its left side and is relaying nothing. The far water wheel " +
                "reads ${relayed.ground} on the ground and ${relayed.sub} in the sub-level, with a " +
                "$RPM rpm motor driving the other side. Left powered means pass rotation straight " +
                "through, and that is the whole block. See ${relayed.pictures}",
        )

        // And the driven wheel, so that a rig which relays nothing because it was never driven in the
        // first place fails saying so, rather than failing as if the gearshift were at fault.
        val driving = kineticSpeedAt(at(-SIDE, 1, 0).west(2)).toDouble()

        assertTrue(
            driving != 0.0,
            "The water wheel on the driven side is not turning either ($driving), so the motor never " +
                "reached the gearshift and nothing downstream of it means anything. " +
                "See ${relayed.pictures}",
        )
    }

    @Test
    @DisplayName("A directional gearshift powered on its other side reverses rotation, the same in a sub-level")
    fun `the gearshift reverses rotation`(cluster: ClusterScope) = cluster.stage {
        val reversed = bothWays(
            name = "gearshift_reverse",
            reach = 3,
            build = { origin -> gearshiftRig(origin, powerAbove = false) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.east())
            },
            read = { origin -> reversalAcross(origin) },
            expect = Parity.Same(),
        )

        assertTrue(
            reversed.ground < 0.0 && reversed.sub < 0.0,
            "The gearshift is powered on its right side and is not reversing. The two water wheels " +
                "either side of it turn the same way, so their ratio came out ${reversed.ground} on " +
                "the ground and ${reversed.sub} in the sub-level, where a reversal is negative. " +
                "Right powered means invert the direction. See ${reversed.pictures}",
        )
    }

    @Test
    @DisplayName("A directional gearshift powered on both sides stops relaying, the same in a sub-level")
    fun `the gearshift stops relaying when both sides are powered`(cluster: ClusterScope) =
        cluster.stage {
            val stopped = bothWays(
                name = "gearshift_stopped",
                reach = 3,
                build = { origin -> gearshiftRig(origin, powerAbove = true, powerBelow = true) },
                stimulate = { origin ->
                    driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                    settleKinetics(origin.west(2))
                },
                read = { origin -> kineticSpeedAt(origin.east(2)).toDouble() },
                expect = Parity.Same(),
            )

            assertTrue(
                stopped.ground == 0.0 && stopped.sub == 0.0,
                "The gearshift is powered on both sides and is still relaying: the far water wheel " +
                    "reads ${stopped.ground} on the ground and ${stopped.sub} in the sub-level, " +
                    "where both should be still. See ${stopped.pictures}",
            )

            // The driven side must still be turning, or "nothing came out" is just "nothing went in".
            val driving = kineticSpeedAt(at(-SIDE, 1, 0).west(2)).toDouble()

            assertTrue(
                driving != 0.0,
                "Nothing is coming out of the gearshift, but nothing is going in either ($driving), " +
                    "so this says nothing about whether it stopped relaying. See ${stopped.pictures}",
            )
        }

    /**
     * The relayed speed over the driving speed: negative when the gearshift has reversed it.
     *
     * A ratio rather than the far-side speed on its own, because a gearshift is a split shaft and
     * what comes out is not required to match what went in. The sign is the claim; the magnitude is
     * not asserted.
     */
    private suspend fun reversalAcross(origin: BlockPos): Double {
        val driving = kineticSpeedAt(origin.west(2)).toDouble()
        val relayed = kineticSpeedAt(origin.east(2)).toDouble()

        return if (driving == 0.0) 0.0 else relayed / driving
    }

    /**
     * A motor, a water wheel, the gearshift, and a second shaft and wheel on the far side.
     *
     * The gearshift faces up, which puts its two control faces on top and underneath:
     * `DirectionalGearshiftBlock.getLeftDirection` is the block's `FACING` and `getRightDirection`
     * its opposite, and `DirectionalGearshiftBlockEntity.getRotationSpeedModifier` returns +1 when
     * the left is powered, -1 when the right is, and 0 for both or neither. So a redstone block above
     * relays, one below reverses, and both together stop it. With neither, nothing passes at all --
     * which is the block working, and would make a test that forgot to power it pass for the wrong
     * reason.
     *
     * Everything else sits on the x axis, which is what the facing and `axis_along_first` combine to
     * give as the rotation axis.
     */
    private suspend fun Stage.gearshiftRig(
        origin: BlockPos,
        powerAbove: Boolean,
        powerBelow: Boolean = !powerAbove,
    ) {
        setBlock(origin, GEARSHIFT)

        // The driven side. The motor goes on the far end of this, in `stimulate`.
        setBlock(origin.west(), "create:shaft[axis=x]")
        setBlock(origin.west(2), "create:water_wheel[facing=east]")

        // The relayed side, which is what the assertions read, and what only turns when the gearshift
        // is told to pass rotation on.
        setBlock(origin.east(), "create:shaft[axis=x]")
        setBlock(origin.east(2), "create:water_wheel[facing=east]")

        setBlock(origin.above(), if (powerAbove) "minecraft:redstone_block" else "minecraft:air")
        setBlock(origin.below(), if (powerBelow) "minecraft:redstone_block" else "minecraft:stone")
    }

    companion object {
        /**
         * A gearshift whose rotation axis is x, so its shafts run east-west.
         *
         * It is a directional block rather than an axis one, and the axis is derived:
         * `DirectionalAxisKineticBlock.getRotationAxis` reads a vertical `facing` plus
         * `axis_along_first` as x. `[axis=x]` is not a state this block has, and the server rejects
         * the command outright.
         */
        const val GEARSHIFT = "simulated:directional_gearshift[facing=up,axis_along_first=true]"

        /** Fast enough to be unmistakable, slow enough to be an ordinary network. */
        const val RPM = 32

        /** How far off the stage origin `bothWays` builds the ground rig. Kept in step with it. */
        const val SIDE = 8
    }
}
