package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.kineticSpeedAt
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.settleKinetics
import com.simibubi.create.e2e.gametest.blockProperty
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
 * That a swivel bearing passes rotation through, and locks or unlocks as its panel says.
 *
 * Two claims from its ponder scene. Rotational power via the shaft passes directly through the
 * bearing and stays uninterrupted while it turns; and when provided with redstone power the bearing
 * unlocks and spins freely, which is behaviour the value panel configures.
 *
 * The locking one is worth more than it looks. What the panel holds is
 * `SwivelBearingBlockEntity.LockingSetting`, an enum whose constants used to name an `AllIcons` --
 * and on 26.2 `AllIcons` cannot be loaded on a dedicated server at all, so touching that enum threw
 * `NoClassDefFoundError` and a swivel bearing could not be placed on one. That is fixed, and this is
 * the test that would notice it coming back: reaching `shouldLock` means the enum initialised.
 *
 * The locked state is read off the block's `powered` property, which `isLocking` is defined as and
 * which the tick keeps in step with the setting. The panel's own default is `LOCKED_DEFAULT`
 * (`lockedDefaultOption.value = 1`), so the scene's description is what an untouched bearing does.
 *
 * **What this does not prove.** Assembling a contraption and turning it, which is the bearing's other
 * half and wants a test of its own.
 */
@DrivesMinecraft
class SwivelBearingTest {

    @Test
    @DisplayName("Rotation passes straight through a swivel bearing, the same in a sub-level")
    fun `rotation passes through it`(cluster: ClusterScope) = cluster.stage {
        val through = bothWays(
            name = "swivel_passthrough",
            reach = 3,
            build = { origin -> bearingRig(origin, powered = false) },
            stimulate = { origin ->
                driveWith(origin.west(2), Direction.WEST, rpm = RPM)
                settleKinetics(origin.east())
            },
            // The far side of the bearing over the near side. A ratio rather than a raw speed,
            // because "passes directly through" is a claim about the two being the same and not
            // about either of them being any particular number.
            read = { origin -> passthroughRatioAt(origin) },
            expect = Parity.Same(tolerance = RATIO_TOLERANCE),
        )

        assertTrue(
            Math.abs(through.ground - 1.0) < RATIO_TOLERANCE,
            "Rotation is supposed to pass directly through the swivel bearing, so the shafts on " +
                "either side should turn together. Their ratio came out ${through.ground} on the " +
                "ground and ${through.sub} in the sub-level. See ${through.pictures}",
        )
    }

    @Test
    @DisplayName("A swivel bearing is locked until redstone unlocks it, the same in a sub-level")
    fun `redstone unlocks it`(cluster: ClusterScope) = cluster.stage {
        val unlocked = bothWays(
            name = "swivel_unlocked",
            reach = 3,
            build = { origin -> bearingRig(origin, powered = false) },
            read = { origin -> lockedThenUnlockedAt(origin) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            unlocked.ground == BOTH_RIGHT,
            "A bearing on the panel's default setting should be locked with no redstone on it and " +
                "unlocked with some. The two readings together came out ${unlocked.ground} on the " +
                "ground and ${unlocked.sub} in the sub-level, where $BOTH_RIGHT means both were as " +
                "expected. See ${unlocked.pictures}",
        )
    }

    /**
     * Whether the bearing locks with no redstone and unlocks with some, as one number.
     *
     * Both states are exercised inside the reading so the same sequence runs on the ground and in the
     * sub-level, and each is asserted as it is taken so a failure names which half was wrong.
     */
    private suspend fun Stage.lockedThenUnlockedAt(origin: BlockPos): Double {
        serverTicks(SETTLE)
        val lockedWhenQuiet = isLockedAt(origin)

        assertTrue(
            lockedWhenQuiet,
            "The bearing is unlocked with no redstone anywhere near it. On the panel's default " +
                "setting it should be locked until something powers it",
        )

        setBlock(origin.north(), "minecraft:redstone_block")
        serverTicks(SETTLE)
        val lockedWhenPowered = isLockedAt(origin)

        assertTrue(
            !lockedWhenPowered,
            "The bearing is still locked with a redstone block against it, where redstone power is " +
                "supposed to unlock it and let it spin freely",
        )

        return (if (lockedWhenQuiet) 1.0 else 0.0) + (if (lockedWhenPowered) 0.0 else 1.0)
    }

    /**
     * Whether the bearing at [pos] is holding itself still.
     *
     * `isLocking` is private, and is defined as exactly this: the block's `powered` property, which
     * the tick writes from `LockingSetting.shouldLock`. Reading the property rather than the method
     * costs nothing and reads the same thing.
     */
    private suspend fun isLockedAt(pos: BlockPos): Boolean = blockProperty(pos, "powered") == "true"

    /** The far shaft's speed over the near one's: one when rotation passes straight through. */
    private suspend fun passthroughRatioAt(origin: BlockPos): Double {
        val near = kineticSpeedAt(origin.west()).toDouble()
        val far = kineticSpeedAt(origin.east()).toDouble()

        return if (near == 0.0) 0.0 else far / near
    }

    /**
     * A shaft into the bearing and a shaft out of it, with a wheel on each.
     *
     * The bearing faces east, so its rotation axis is x and -- while it is not assembled -- it takes
     * a shaft on either of those faces. The redstone, when there is any, goes north: off that axis,
     * so it cannot be mistaken for part of the drive train.
     */
    private suspend fun Stage.bearingRig(origin: BlockPos, powered: Boolean) {
        setBlock(origin, "simulated:swivel_bearing[facing=east,assembled=false,powered=false]")

        // The driven side. The motor goes on the far end of this, in `stimulate`.
        setBlock(origin.west(), "create:shaft[axis=x]")
        setBlock(origin.west(2), "create:water_wheel[facing=east]")

        // The far side, which should be turning with it.
        setBlock(origin.east(), "create:shaft[axis=x]")
        setBlock(origin.east(2), "create:water_wheel[facing=east]")

        setBlock(origin.north(), if (powered) "minecraft:redstone_block" else "minecraft:air")
        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        /** Fast enough to be unmistakable, slow enough to be an ordinary network. */
        const val RPM = 32

        /** Long enough for the tick to write the locked state through to the block. */
        const val SETTLE = 20

        /** One point for being locked when quiet, one for being unlocked when powered. */
        const val BOTH_RIGHT = 2.0

        const val RATIO_TOLERANCE = 0.02
    }
}
