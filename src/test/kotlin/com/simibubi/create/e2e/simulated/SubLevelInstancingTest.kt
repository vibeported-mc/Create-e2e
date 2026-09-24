package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.render.cullCounts
import com.simibubi.create.e2e.render.drawWork
import com.simibubi.create.e2e.render.restoreBackend
import com.simibubi.create.e2e.render.useBackend
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Turning machinery inside a sub-level, watched to see that Flywheel draws it.
 *
 * ## Why this is its own test
 *
 * Every other sub-level test here reads the server: a sensor's value, a shaft's speed, an item in a
 * chest. They pass whether or not a single pixel of the body reaches the screen, and Sable's own
 * notes say as much -- machines floating with no platform under them is the failure they describe,
 * and the tests were green throughout it.
 *
 * `SubLevelPhysicsTest` covers the *terrain*, which Sable meshes itself. This covers the
 * *machinery*, which it does not: a water wheel is a Flywheel visual, drawn by whichever backend is
 * live, and a sub-level puts it somewhere nothing else does -- a plot parked around x/z = 2e7,
 * drawn back at the body's pose.
 *
 * ## Only in the sub-level
 *
 * Deliberately nothing equivalent on the ground. The counters this reads are per-frame totals for
 * the whole backend, so an identical rig standing in the world would keep them healthy while the
 * sub-level's copy drew nothing at all -- which is the bug, and what a parity scene would hide.
 */
@DrivesMinecraft
class SubLevelInstancingTest {

    @Test
    @DisplayName("Machinery inside a sub-level is drawn, not culled away")
    fun `a sub-level's kinetics reach the screen`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        // Named: these counters belong to this backend and no other fills them.
        useBackend("flywheel:indirect_blaze3d")

        val platform = theBodyAt(at(0, SPAWN_HEIGHT, 0)) {
            runCommand(
                "execute positioned ${at(0, SPAWN_HEIGHT, 0).x} ${at(0, SPAWN_HEIGHT, 0).y} " +
                    "${at(0, SPAWN_HEIGHT, 0).z} run sable spawn platform $PLATFORM $MATERIAL"
            )
        }
        serverTicks(SETTLE_TICKS)

        val origin = plotOriginOf(platform)
        buildRig(origin)
        serverTicks(SPIN_UP_TICKS)

        val at = poseOf(platform)
        spectateAt(
            Vec3(at.x + VIEW_BACK, at.y + VIEW_UP, at.z + VIEW_BACK),
            Vec3(at.x, at.y, at.z),
            settle = SETTLE_TICKS,
        )

        shot("sublevel_kinetics")

        val work = drawWork()
        val cull = cullCounts()
        println("SUBLEVEL body=$platform work=$work")
        println("SUBLEVEL cull $cull")

        restoreBackend()
        runCommand("forceload remove all")

        assertTrue(
            work.indirectInstancers > 0,
            "Flywheel prepared nothing at all, so the machinery inside the sub-level never reached " +
                "the backend. Nothing equivalent stands in the world, so this is the sub-level's " +
                "copy and only the sub-level's copy: $work",
        )

        assertTrue(
            cull.visible > 0,
            "Every instance was culled. A body's blocks live in a plot far from the world origin " +
                "and are drawn back at its pose, so a bounding sphere tested in the space it was " +
                "stored in rather than the one it is drawn in lands nowhere near the camera: $cull",
        )

        // The one that matters, and the one the two above cannot stand in for.
        //
        // Instancers can be prepared and instances survive culling and still nothing be issued --
        // that is exactly what this scene did when it was written. The counters said three
        // instancers and zero calls, and the picture showed the platform with the machinery's
        // static blocks on it and no moving parts at all.
        //
        // The cause was that Sable declares three extra values on an embedded environment in its
        // own copies of Flywheel's shader files, which this backend does not include because it
        // generates its own -- so the declarations were missing while the uses were not, and the
        // pipeline failed to compile on an undeclared identifier.
        assertTrue(
            work.indirectCalls > 0,
            "The instances survived culling and then no draw was issued for any of them, so the " +
                "machinery is absent from the picture while every count above looks healthy: $work",
        )
    }

    /**
     * Water wheels on a shaft, placed into the sub-level's plot.
     *
     * By world coordinates, because that is where a sub-level's blocks actually are -- the plot is
     * an ordinary region and Sable offsets into it. The same approach `SubLevelPhysicsTest` uses to
     * furnish its platform.
     */
    private suspend fun Stage.buildRig(origin: Origin) {
        val x = origin.x
        val y = origin.y + 1
        val z = origin.z

        runCommand("setblock $x $y $z create:shaft[axis=x]")
        runCommand("setblock ${x - 1} $y $z create:shaft[axis=x]")
        runCommand("setblock ${x - 2} $y $z create:water_wheel[facing=east]")
        runCommand("setblock ${x + 1} $y $z create:shaft[axis=x]")
        runCommand("setblock ${x + 2} $y $z create:water_wheel[facing=east]")

        // Turning, so this is the expensive case rather than a still one -- and so a reader can see
        // at a glance from the picture whether it is moving.
        runCommand("setblock ${x - 3} $y $z create:creative_motor[facing=east]")
    }

    private companion object {
        const val GROUND = 24

        /** As SubLevelPhysicsTest spawns its platform. */
        const val SPAWN_HEIGHT = 8
        const val PLATFORM = 4
        const val MATERIAL = "minecraft:oak_planks"

        /** Off to one side and above, so the body's space and the world's do not line up. */
        const val VIEW_BACK = 9.0
        const val VIEW_UP = 5.0

        const val SPIN_UP_TICKS = 60
        const val SETTLE_TICKS = 20
    }
}
