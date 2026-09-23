package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A field of moving machinery, drawn until the frame rate settles, on whichever backend the run
 * asked for.
 *
 * This is the scene the Vulkan work is *for*. Everything else in this module asks whether Create is
 * correct; this one asks whether drawing it is cheap, which is the entire argument for instanced
 * and indirect rendering over falling back to block entity renderers. A handful of cogs proves a
 * pipeline works and says nothing about whether it is worth having -- the difference between
 * instancing and not only appears once there are thousands of moving parts in frame.
 *
 * Deliberately one client and one test. Frame rate measured while five other Minecraft clients are
 * competing for the same GPU is not a number anybody can compare against anything, so this runs on
 * its own and the suite's parallelism is turned off for it.
 *
 * ## Reading the result
 *
 * The assertion is only that the client is alive and drawing. The *number* is the point, and it is
 * printed rather than asserted, because a frame rate threshold baked into a test is a threshold
 * that fails on somebody else's machine. Compare two runs of this same test instead:
 *
 * ```
 * ./gradlew test --tests '*IndirectStressTest*' -Pclients=1 -Pgraphics=opengl --rerun
 * ./gradlew test --tests '*IndirectStressTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 *
 * `-Pclients=1` is not optional. The pool is six by default and every one of them is a Minecraft
 * client with its own window; measured against five siblings competing for the same GPU, the
 * number means nothing.
 *
 * The line to read is the one beginning `STRESS`. Measured on an RTX-class card at 18,432 shafts:
 *
 * ```
 * STRESS backend=flywheel:indirect fps=2171 parts=18432 instancing=true    (OpenGL)
 * STRESS backend=flywheel:off      fps=280  parts=18432 instancing=false   (Vulkan)
 * ```
 *
 * Those two are not a comparison of OpenGL against Vulkan, and reading them as one is the mistake
 * this paragraph exists to prevent. They are a comparison of *instancing against not*: Flywheel is
 * GL-only, so on Vulkan it reports `flywheel:off` and all 18,432 shafts fall back to being drawn as
 * ordinary block entities. The 7.8x is the price of having no backend, which is the entire case for
 * building one -- and it is why `backend` and `instancing` are printed beside the frame rate. A
 * Vulkan number read without them says nothing at all.
 *
 * When there is a Vulkan-capable backend, the same two runs become the comparison that says whether
 * it is any good, and the gate named in the plan: beat the OpenGL indirect backend on this scene
 * before preferring the new one over it.
 *
 * ## Sizing
 *
 * The scene has to be big enough that the frame rate answers to it. At 2,352 parts and again at
 * 4,704 this reported the same ~2,630 fps, because it was measuring an empty horizon -- the field
 * was being built at raw world coordinates while the camera looked at the stage's own plot. A
 * number that does not move when the part count doubles is not measuring the renderer, and that is
 * the check to make before trusting any figure here.
 */
@DrivesMinecraft
class IndirectStressTest {

    @Test
    @DisplayName("A field of turning machinery keeps drawing, and reports what it cost")
    fun `the stress scene draws`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        // The frame rate cap comes off first, and vsync with it. Left on, both backends report the
        // cap -- the first run of this measured 119 fps on OpenGL, which is the 120 limit and not
        // a fact about the renderer. A capped number makes the two backends look identical however
        // far apart they really are, which is the one outcome this test must not produce.
        unlockFramerate()

        buildField()

        // Long enough for the kinetic network to propagate from every motor. Nothing instanced is
        // drawn for machinery that is not moving, so a scene measured before it spins up is
        // measuring the cheap case.
        serverTicks(SPIN_UP_TICKS)

        // Asked of the server before anything is asked of the picture. Rotation is computed in the
        // shader from a clock uniform, so a field standing still uploads and draws exactly like one
        // that is turning -- and a benchmark of stationary geometry measures the cheap case while
        // claiming to measure the expensive one.
        val turning = speeds()

        assertTrue(
            turning.first != 0.0f && turning.last != 0.0f,
            "The field is not turning: the first shaft reads ${turning.first} and the last " +
                "${turning.last}. Both ends are checked because one motor failing to drive its row " +
                "leaves most of the scene moving and the measurement still wrong",
        )

        // Looking back across the whole field from above one corner, so as much of it as possible
        // is in frame and inside the culling frustum -- the thing being measured is drawing, and
        // machinery behind the camera is not drawn by any backend.
        spectateAt(middleOf(at(-20, 42, -20)), middleOf(at(LENGTH / 2, FLOOR + DECKS * DECK_GAP / 2, WIDTH / 2)))
        serverTicks(SETTLE_TICKS)

        // Sampled after the camera has been still for a while. The first frames after a teleport
        // are chunk meshing and instance upload rather than steady-state drawing, and averaging
        // them in flatters whichever backend happens to upload faster.
        val warm = drawing()
        serverTicks(MEASURE_TICKS)
        val measured = drawing()

        shot("indirect_stress")

        // A second shot, twenty ticks later. Rotation is computed in the shader from a clock
        // uniform rather than from instance data, so a field that has stopped turning uploads and
        // draws exactly as one that is turning -- the only difference is between two frames.
        // Seven ticks, not twenty. Twenty is exactly one second, and a shaft's angle is a
        // function of seconds -- so at any whole second the field is back where a full rotation
        // left it and two frames a second apart can look identical while everything is turning.
        serverTicks(7)
        shot("indirect_stress_later")

        // The same field with the camera turned away from it, which is the cheap case every
        // backend should be good at and the one the view above deliberately avoids.
        //
        // Read the two together and read them carefully: the gap between them is *not* a measure of
        // the GPU cull pass. Flywheel drops whole instancers on the CPU when their section leaves
        // the frustum, long before any of this runs, so most of the field never reaches the GPU at
        // all here -- `work` below says how many instancers were left. What this pair is good for is
        // the other direction: facing the field, the cull pass rejects nothing and still runs, so
        // that figure is the cost of having it, paid in full with none of the saving.
        spectateAt(middleOf(at(-20, 42, -20)), middleOf(at(-90, 42, -90)))
        serverTicks(SETTLE_TICKS)
        val away = drawing()
        val work = drawWork()
        shot("indirect_stress_away")

        assertTrue(
            measured.alive,
            "The client is no longer showing a level, so there is nothing to measure. A throw on " +
                "the render thread is not a slow frame, it is a dead client",
        )
        assertTrue(
            measured.fps > 0,
            "The client is up but drew nothing in the last second: $measured",
        )

        // The line this test exists to produce. Greppable on purpose: comparing two runs means
        // finding this in two logs.
        println(
            "STRESS backend=${measured.backend} fps=${measured.fps} (warm-up ${warm.fps}) " +
                "parts=$MOVING_PARTS instancing=${measured.instancing}"
        )
        println("STRESS facingAway fps=${away.fps} work=$work")
    }

    /**
     * The field: stacked decks of long shafts, every row driven by its own creative motor.
     *
     * Two `/fill` commands lay an entire deck, which is the only reason a scene this size is
     * practical: every block placed from the test side is a round trip, and the first version of
     * this built its field one `setblock` at a time.
     *
     * Shafts rather than a solid block of cogwheels, and that is a correctness point rather than a
     * stylistic one. Shafts connect end to end along their axis and not sideways, so each row in z
     * is its own kinetic network and a single fill cannot jam. Cogwheels packed side by side would
     * mesh with their neighbours, fight over rotation direction, and break every network in the
     * deck -- leaving a field that renders as thousands of *stationary* blocks, which is precisely
     * the cheap case this test exists to avoid measuring.
     *
     * Positions come from [Stage.at], so the field lands on the plot this stage was given. Building
     * it at raw world coordinates instead is what made the first run of this measure an empty
     * horizon at a flat 2,600 frames a second no matter how much machinery it claimed to place.
     */
    private suspend fun Stage.buildField() {
        for (deck in 0 until DECKS) {
            val y = FLOOR + deck * DECK_GAP

            fill(at(0, y, 0), at(LENGTH - 1, y, WIDTH - 1), "create:shaft[axis=x]")
            fill(at(-1, y, 0), at(-1, y, WIDTH - 1), "create:creative_motor[facing=east]")
        }
    }

    /** What the first and last rows of the field are actually doing, in rpm. */
    private suspend fun Stage.speeds(): Speeds =
        server(at(0, FLOOR, 0), at(LENGTH - 1, FLOOR + (DECKS - 1) * DECK_GAP, WIDTH - 1)) { first, last ->
            Speeds(speedOf(first), speedOf(last))
        }

    @Serializable
    private data class Speeds(val first: Float, val last: Float)

    /** Takes the frame rate cap and vsync off, so what is measured is the renderer. */
    private suspend fun Stage.unlockFramerate() {
        client(watcher) {
            // 260 is vanilla's "unlimited" sentinel, not a literal 260 frames.
            minecraft.options.framerateLimit().set(260)
            minecraft.options.enableVsync().set(false)
            minecraft.options.save()
            true
        }
    }

    /** What the client is drawing, and with what, in a shape that can cross a wire. */
    private suspend fun Stage.drawing(): Drawing = client(watcher) {
        val backend = dev.engine_room.flywheel.api.backend.Backend.REGISTRY
            .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
            .toString()

        Drawing(
            fps = minecraft.fps,
            alive = minecraft.level != null,
            backend = backend,
            instancing = dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn(),
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
        )
    }

    @Serializable
    private data class Drawing(
        val fps: Int,
        val alive: Boolean,
        val backend: String,
        val instancing: Boolean,
        val graphics: String,
    ) {
        override fun toString(): String =
            "$fps frames a second on $graphics, Flywheel `$backend`" +
                if (alive) "" else ", and no level at all"
    }

    private companion object {

        /** How far each deck runs along the shaft axis. */
        const val LENGTH = 48

        /** How many independent rows each deck holds, one per motor. */
        const val WIDTH = 48

        /** Decks stacked above one another, so the field has depth and not just area. */
        const val DECKS = 8

        /** The lowest deck, above the floor `clearGround` lays. */
        const val FLOOR = 2

        /** Vertical space between decks, enough to see between them. */
        const val DECK_GAP = 3

        /**
         * Every shaft is an instance the backend has to place and animate, which is the quantity
         * this whole test is about. Motors are not counted: they do not turn.
         */
        const val MOVING_PARTS = DECKS * LENGTH * WIDTH

        /** Wide enough to hold the field and the camera's corner. */
        const val GROUND = 64

        const val SPIN_UP_TICKS = 60
        const val SETTLE_TICKS = 40

        /** Long enough that the sample is steady state rather than the frames after a teleport. */
        const val MEASURE_TICKS = 100
    }
}

/**
 * A kinetic block's speed, or zero where there is none.
 *
 * A top-level function rather than a method, because it is called from a body that runs on the
 * server, and those may not reach back into the test object they were written in.
 */
private fun dev.vibeported.mc.driver.ServerScope.speedOf(where: BlockPos): Float =
    (serverLevel.getBlockEntity(where)
        as? com.simibubi.create.content.kinetics.base.KineticBlockEntity)
        ?.speed ?: 0.0f

private fun middleOf(pos: BlockPos) = net.minecraft.world.phys.Vec3(
    pos.x + 0.5,
    pos.y + 0.5,
    pos.z + 0.5,
)
