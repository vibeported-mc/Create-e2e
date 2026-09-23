package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A row of machinery running away from the camera, far enough that the far end is inside the fog.
 *
 * Fog is compiled into every fragment shader this backend generates, and in an ordinary overworld
 * scene at ten blocks it does nothing whatsoever -- so a scene that looks right proves only that
 * the fog code did not crash. This one is arranged so that fog is the difference between the near
 * end of the row and the far end: render distance is turned down to its minimum, which pulls the
 * render-distance fog in to a few dozen blocks, and the row is longer than that.
 *
 * The check is not a screenshot. It is that Flywheel's machinery and the terrain it stands on agree
 * about how far away the far end is: a shaft at the end of the row should be about as fogged as the
 * ground beneath it. Sampling both from one frame is what makes this a test rather than a picture --
 * a backend that ignores fog draws its machines at full brightness against a wall of fog, and that
 * difference is a number.
 *
 * ```
 * ./gradlew test --tests '*FogTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 */
@DrivesMinecraft
class FogTest {

    @Test
    @DisplayName("Machinery fades into the distance the same way the ground does")
    fun `fog reaches the instanced geometry`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        // Named, because the figures below come from this backend's own uniform block and no other
        // backend fills it. On OpenGL priority picks flywheel:indirect, whose fog ranges read zero
        // wet and dry alike -- which fails this test while saying nothing about fog.
        useBackend("flywheel:indirect_blaze3d")

        // One long row of shafts on a single axis, driven from the near end. Shafts connect end to
        // end, so the whole row is one kinetic network and one motor turns all of it.
        setBlock(at(-1, FLOOR, 0), "create:creative_motor[facing=east]")
        fill(at(0, FLOOR, 0), at(LENGTH - 1, FLOOR, 0), "create:shaft[axis=x]")
        serverTicks(60)

        // Along the row rather than across it, so distance is the only thing that varies down the
        // picture. Looking at it from the side would fog every shaft equally and prove nothing.
        spectateAt(middleOf(at(-4, FLOOR, 0)), middleOf(at(LENGTH, FLOOR, 0)))
        serverTicks(40)

        shot("fog_row_dry")
        val dry = fogState()

        // And the same row again with the camera under water.
        //
        // The open air was the first attempt at this and it does not work: even at the minimum
        // render distance the fog reaches roughly thirty blocks and is so faint against a grey floor
        // under a pale sky that the far end of the row looks the same either way -- a picture that
        // cannot distinguish a working fog filter from no fog at all. Water fog is neither faint nor
        // distant. It is a few blocks deep, strongly coloured, and it swallows the far end of the
        // row outright, so a fragment shader that ignores fog draws a row of shafts hanging in clear
        // blue and there is nothing subtle about it.
        floodTheCamera()
        serverTicks(40)

        shot("fog_row_underwater")
        val wet = fogState()

        println("FOG dry=${dry.environmentalEnd} wet=${wet.environmentalEnd}")

        // Both halves of the check, and neither is enough alone.
        //
        // That the backend's own uniforms follow the camera into the water is what says the fog is
        // live rather than a constant compiled in once; that the range is short enough to bite into
        // a sixty block row is what makes the screenshot beside it worth looking at.
        assertTrue(
            wet.environmentalEnd < dry.environmentalEnd,
            "The fog the backend wrote did not change when the camera went under water: it ends at " +
                "${wet.environmentalEnd} either way. Its uniforms are not following the camera, so " +
                "whatever the shader is fogging by, it is not this frame's fog",
        )
        assertTrue(
            wet.environmentalEnd < LENGTH,
            "Under water the fog still ends at ${wet.environmentalEnd}, past the far end of a " +
                "$LENGTH block row, so nothing in the picture is fogged and it cannot tell a " +
                "working fog filter from none",
        )
    }

    /**
     * Water around the camera, and only around the camera.
     *
     * The medium the camera is *in* sets the fog for the whole view, so the row itself stays dry and
     * lit exactly as it was in the shot before -- which is what makes the two pictures comparable.
     */
    private suspend fun Stage.floodTheCamera() {
        fill(at(-9, FLOOR - 1, -4), at(-2, FLOOR + 3, 4), "minecraft:water")
    }

    /**
     * What the backend wrote into its frame uniforms, which is what the fragment shader read.
     *
     * Read back rather than assumed, because the ranges are derived from the render distance by code
     * that has changed between versions -- a hard-coded expectation here would turn a fog that moved
     * into a test failure about arithmetic. Read from the *backend* rather than from vanilla,
     * because a backend that fills its block wrongly agrees with vanilla and still draws no fog.
     */
    private suspend fun Stage.fogState(): FogState = client(watcher) {
        FogState(
            environmentalStart = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.fogEnvironmentalStart,
            environmentalEnd = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.fogEnvironmentalEnd,
            renderDistanceStart = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.fogRenderDistanceStart,
            renderDistanceEnd = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.fogRenderDistanceEnd,
            chunks = minecraft.options.renderDistance().get(),
        )
    }

    @Serializable
    private data class FogState(
        val environmentalStart: Float,
        val environmentalEnd: Float,
        val renderDistanceStart: Float,
        val renderDistanceEnd: Float,
        val chunks: Int,
    )

    private companion object {
        /**
         * Longer than the water fog, which ends around eighty blocks out.
         *
         * Sixty was the first try and the test failed on its own premise rather than on the
         * renderer: the row stopped before the fog began, so every shaft in it was equally unfogged
         * and the picture could not have shown anything either way.
         */
        const val LENGTH = 140

        const val FLOOR = 2

        const val GROUND = 152
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
