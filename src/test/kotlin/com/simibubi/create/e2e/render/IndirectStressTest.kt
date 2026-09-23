package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A field of gear trains, drawn until the frame rate settles, on whichever backend the run asked
 * for.
 *
 * Each train is a motor, a small cog it drives, a large cog meshed with that, and two more large
 * cogs turning off it at right angles -- five wheels, four of them moving. Ten thousand of them
 * stacked in decks.
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
 * The line to read is the one beginning `STRESS`. Measured on an RTX 5080 at 10,000 gear trains --
 * 40,000 turning cogs -- with the camera standing in among them:
 *
 * ```
 * STRESS backend=flywheel:indirect         fps=852 trains=10000 parts=40000   (OpenGL)
 * STRESS backend=flywheel:indirect_blaze3d fps=799 trains=10000 parts=40000   (Vulkan)
 * ```
 *
 * Those two are from separate runs and should not be subtracted from one another. The comparison
 * worth making is the `STRESS rival` line, which measures both backends in one client on one field
 * with nothing rebuilt in between, and then returns to the first to show what order was worth:
 *
 * ```
 * STRESS rival backend=flywheel:indirect_blaze3d fps=1345
 *              against flywheel:indirect fps=854 then 907
 * ```
 *
 * The older backend's two readings bracket the rival instead of closing on it, so the gap is the
 * backend rather than warm-up -- which is the failure mode this scene invites, and which cost a
 * wrong conclusion once already in [OcclusionTest]. That measurement is why
 * `flywheel:indirect_blaze3d` was promoted past `flywheel:indirect`.
 *
 * Part of the margin is occlusion culling, which the older backend does not do at all. This is not
 * evidence that indirect drawing through Blaze3D is half again as fast -- it is evidence that the
 * whole path is, on this scene.
 *
 * Read that beside the older shaft field, where the same two backends were 2135 and 4323. The
 * difference is the shape of the work, not a regression: a field of shafts seen from outside is
 * thousands of small instances and little else, so it measures instancing. This scene puts the
 * camera inside a dense mass of geometry, where both backends spend most of the frame on fill and
 * chunk rendering and converge. Both are worth having; neither on its own says how fast a backend
 * is.
 *
 * `backend` and `instancing` are printed beside every figure because a frame rate read without them
 * says nothing at all -- Flywheel reports `flywheel:off` when it has no usable backend, and then the
 * number measures ordinary block entity renderers rather than anything here.
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
        val before = videoSettings()
        applyVideo(UNLIMITED_FRAMERATE, vsync = false, renderDistance = RENDER_DISTANCE, clouds = false)

        val builtAt = System.nanoTime()
        buildField()
        println("STRESS build took ${(System.nanoTime() - builtAt) / 1_000_000} ms")

        // Force-loaded, or most of the field never ticks. A dedicated server only simulates chunks
        // near a player, and a field two hundred blocks across reaches well past that however the
        // camera is placed -- the corner train read zero rpm while the near one turned, which is a
        // field of stationary blocks measured as if it were turning.
        forceLoadField()

        // Long enough for the kinetic network to propagate from every motor. Nothing instanced is
        // drawn for machinery that is not moving, so a scene measured before it spins up is
        // measuring the cheap case.
        serverTicks(SPIN_UP_TICKS)

        // Asked of the server before anything is asked of the picture. Rotation is computed in the
        // shader from a clock uniform, so a field standing still uploads and draws exactly like one
        // that is turning -- and a benchmark of stationary geometry measures the cheap case while
        // claiming to measure the expensive one.
        val first = trainSpeeds(0, FLOOR, 0)
        val last = trainSpeeds(
            (GRID - 1) * SPACING,
            FLOOR + (DECKS - 1) * DECK_GAP,
            (GRID - 1) * SPACING,
        )
        println("STRESS train first=$first last=$last")

        assertTrue(
            first.allTurning && last.allTurning,
            "A gear train is not turning all the way through: the first reads $first and the last " +
                "$last. Every wheel is checked because a cog placed a block out of mesh still looks " +
                "like a gear train, turns at zero, and measures the cheap case",
        )

        // Looking back across the whole field from above one corner, so as much of it as possible
        // is in frame and inside the culling frustum -- the thing being measured is drawing, and
        // machinery behind the camera is not drawn by any backend.
        spectateAt(cameraFrom(), fieldCentre())

        // The HUD back and F3 on, in that order: spectating hides the whole GUI layer and the debug
        // screen is part of it, so pressing F3 under a hidden HUD toggles something nobody can see.
        restoreHud()
        pressF3()
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
        spectateAt(cameraFrom(), middleOf(at(-CENTRE_XZ, CENTRE_Y, -CENTRE_XZ)))
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
                "trains=$TRAINS parts=$MOVING_PARTS instancing=${measured.instancing}"
        )
        println("STRESS facingAway fps=${away.fps} work=$work")

        // And the same scene on the other backend, without rebuilding it.
        //
        // This is the comparison the priority question turns on -- flywheel:indirect_blaze3d sits
        // below flywheel:indirect and should not be promoted until it wins here -- and it is only
        // worth making back to back. Run to run this scene moves by several per cent, which is
        // wider than the gap being looked for, so two numbers from two logs cannot settle it. One
        // client, one field, one camera, the backend swapped underneath.
        //
        // Only on OpenGL: flywheel:indirect reports itself unsupported without a GL context, so on
        // Vulkan there is no second backend to compare against.
        val rival = if (backendId() == "flywheel:indirect_blaze3d") "flywheel:indirect" else
            "flywheel:indirect_blaze3d"

        if (canUse(rival)) {
            spectateAt(cameraFrom(), fieldCentre())
            useBackend(rival)
            serverTicks(SETTLE_TICKS)

            // Discarded the same way the first reading is: switching backends throws away every
            // instancer and rebuilds it, and the frames during that are not steady state.
            drawing()
            serverTicks(MEASURE_TICKS)
            val other = drawing()

            // Back to the first backend and measured again, because order is a confound here and
            // not a small one. The rival is read later, with chunks meshed and pipelines built,
            // and this scene moves several per cent run to run anyway. If the two readings of the
            // first backend agree, the gap between them and the rival is the backend; if the
            // second one has caught up, the gap was warm-up.
            useBackend(measured.backend)
            serverTicks(SETTLE_TICKS)
            drawing()
            serverTicks(MEASURE_TICKS)
            val again = drawing()

            println(
                "STRESS rival backend=${other.backend} fps=${other.fps} " +
                    "against ${measured.backend} fps=${measured.fps} then ${again.fps}"
            )
        } else {
            println("STRESS rival $rival is unsupported here, so there is nothing to compare")
        }

        // F3 off again, the backend choice handed back, and the video options put back as they were
        // found. Clients are shared between tests and these settings outlive the test, so one that
        // unlocks the frame rate or pins a backend and walks away has changed what every later test
        // measures. Switching back to the backend this started on is not enough on its own: that
        // pins it by name, where it arrived here as whatever Flywheel would pick.
        restoreBackend()
        pressF3()
        applyVideo(before.framerateLimit, before.vsync, before.renderDistance, before.clouds)
        runCommand("forceload remove all")
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
        // The whole field in one call, and that is not a tidiness point. Every setBlock from the
        // test side is a round trip, and a field this size is a quarter of a million blocks -- one
        // at a time it would still be building tomorrow. Inside this block the loops run on the
        // server thread, next to the level they are writing to.
        val placed = server(at(0, FLOOR, 0)) { origin ->
            val states = listOf(
                "create:creative_motor[facing=east]",
                "create:cogwheel[axis=x]",
                "create:large_cogwheel[axis=x]",
                "create:large_cogwheel[axis=y]",
                "create:large_cogwheel[axis=y]",
            ).map {
                net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(serverLevel.holderLookup(net.minecraft.core.registries.Registries.BLOCK), it, false)
                    .blockState()
            }

            // Offsets of one unit, in the order the states are listed above.
            val offsets = listOf(
                Triple(0, 0, 0),
                Triple(1, 0, 0),
                Triple(1, 1, 1),
                Triple(2, 2, 1),
                Triple(0, 2, 1),
            )

            var count = 0
            for (deck in 0 until DECKS) {
                for (i in 0 until GRID) {
                    for (j in 0 until GRID) {
                        for (k in states.indices) {
                            val (dx, dy, dz) = offsets[k]
                            serverLevel.setBlock(
                                origin.offset(
                                    i * SPACING + dx,
                                    deck * DECK_GAP + dy,
                                    j * SPACING + dz,
                                ),
                                states[k],
                                net.minecraft.world.level.block.Block.UPDATE_CLIENTS,
                            )
                            count++
                        }
                    }
                }
            }
            count
        }

        println("STRESS placed=$placed blocks")
    }

    /**
     * One unit: a motor, a small cog it drives, a large cog that small one meshes with, and two more
     * large cogs turning off that at right angles.
     *
     * The offsets are not arbitrary and they are not guesses -- they are Create's own meshing rules,
     * read out of `RotationPropagator`:
     *
     * - A **large cog meshes with a small cog** when they share the *same* rotation axis and sit
     *   diagonally adjacent in the plane perpendicular to it: zero displacement along the shared
     *   axis, one block along each of the other two.
     * - Two **large cogs mesh with each other** when their axes are *perpendicular* and they sit
     *   diagonally adjacent in the plane those two axes span: non-zero along both of their axes,
     *   zero along the third.
     *
     * So the first large cog stands on the small cog's axis with its disc upright, and the two it
     * drives turn about the vertical axis with their discs flat -- which is the right angle the
     * large-to-large rule is there for.
     *
     * Every one of the five is read back afterwards. A cog placed a block out still looks like a
     * gear train and turns at zero, and a field of stationary blocks is exactly the cheap case this
     * test exists not to measure.
     */
    /** What every wheel of one unit is turning at, so a mis-meshed train cannot pass as a built one. */
    private suspend fun Stage.trainSpeeds(x: Int, y: Int, z: Int): Speeds = server(
        at(x + 1, y, z),
        at(x + 1, y + 1, z + 1),
        at(x + 2, y + 2, z + 1),
        at(x, y + 2, z + 1),
    ) { small, large, flatA, flatB ->
        Speeds(speedOf(small), speedOf(large), speedOf(flatA), speedOf(flatB))
    }

    @Serializable
    private data class Speeds(val small: Float, val large: Float, val flatA: Float, val flatB: Float) {
        val allTurning: Boolean
            get() = small != 0.0f && large != 0.0f && flatA != 0.0f && flatB != 0.0f
    }

    /**
     * Where the camera stands: back from one corner and above, far enough to hold the field.
     *
     * Scales with the grid rather than being a fixed point, so raising [GRID] does not quietly leave
     * the camera inside the machinery or so far back that the field is a smudge on the horizon.
     */
    private fun Stage.cameraFrom() = middleOf(
        at(CENTRE_XZ - BACK_OFF, CENTRE_Y + DECKS * DECK_GAP / 2, CENTRE_XZ - BACK_OFF),
    )

    /** The middle of the field, which is what the camera is aimed at. */
    private fun Stage.fieldCentre() = middleOf(at(CENTRE_XZ, CENTRE_Y, CENTRE_XZ))

    /**
     * Keeps every chunk the field stands on loaded and ticking.
     *
     * One command covers it: forceload takes a block range and vanilla's limit is 256 chunks, while
     * a field of this size is about eleven chunks square.
     */
    private suspend fun Stage.forceLoadField() {
        val low = at(-SPACING, FLOOR, -SPACING)
        val high = at(GRID * SPACING + SPACING, FLOOR, GRID * SPACING + SPACING)

        runCommand("forceload add ${low.x} ${low.z} ${high.x} ${high.z}")
    }

    /**
     * The video settings as found, so the test can put them back.
     *
     * Every one of these changes what is measured, and all three are written to the options file --
     * so leaving them set is not a tidiness point, it is a later test silently measuring something
     * else. The frame rate cap is the worst of them: left on, both backends report the cap and look
     * identical however far apart they are.
     */
    private suspend fun Stage.videoSettings(): Video = client(watcher) {
        Video(
            minecraft.options.framerateLimit().get(),
            minecraft.options.enableVsync().get(),
            minecraft.options.renderDistance().get(),
            minecraft.options.cloudStatus().get() != net.minecraft.client.CloudStatus.OFF,
        )
    }

    private suspend fun Stage.applyVideo(
        framerateLimit: Int,
        vsync: Boolean,
        renderDistance: Int,
        clouds: Boolean,
    ) {
        client(watcher, framerateLimit, vsync, renderDistance, clouds) { limit, sync, distance, sky ->
            minecraft.options.framerateLimit().set(limit)
            minecraft.options.enableVsync().set(sync)
            minecraft.options.renderDistance().set(distance)
            minecraft.options.cloudStatus().set(
                if (sky) net.minecraft.client.CloudStatus.FANCY else net.minecraft.client.CloudStatus.OFF,
            )
            minecraft.options.save()
            true
        }
    }

    @Serializable
    private data class Video(
        val framerateLimit: Int,
        val vsync: Boolean,
        val renderDistance: Int,
        /** Off while measuring: a field this tall puts the camera at cloud height, and then the
         * picture is a cloud deck with the machinery somewhere behind it. */
        val clouds: Boolean,
    )

    /**
     * The ordinary debug screen, toggled the ordinary way.
     *
     * Pressed rather than set, because 26.2 moved the debug screen behind `Minecraft.debugEntries`
     * and what F3 shows is whatever that list decides -- reaching in to flip a flag would be
     * reproducing the keybind's logic rather than using it. Pressed again at the end of the test to
     * turn it off.
     */
    private suspend fun Stage.pressF3() {
        client(watcher) {
            press(dev.vibeported.mc.driver.Key(org.lwjgl.glfw.GLFW.GLFW_KEY_F3))
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

        /**
         * How many gear trains along each side. One while the shape of a unit is being agreed;
         * raise it to make the field.
         */
        const val GRID = 25

        /** Stacked decks, so a field of this many units is a block rather than a carpet. */
        const val DECKS = 16

        /** Three blocks tall a unit, so decks cannot reach into each other. */
        const val DECK_GAP = 3

        /** Far enough to see the whole field; put back afterwards. */
        const val RENDER_DISTANCE = 32

        /** Vanilla's "unlimited" sentinel, not a literal 260 frames. */
        const val UNLIMITED_FRAMERATE = 260

        /** Enough room that neighbouring trains do not mesh with each other by accident. */
        const val SPACING = 6

        /** How far the field reaches, for aiming the camera. */
        const val SPAN = (GRID - 1) * SPACING + 4

        /**
         * How far back and above the camera stands, scaled to the field rather than fixed.
         *
         * A field this size is a couple of hundred blocks across, so the camera has to stand back
         * far enough to hold it and the render distance has to reach that far -- which is why this
         * test raises it and puts it back.
         */
        const val BACK_OFF = GRID * SPACING / 4

        /** Gear trains in the field: a motor, a small cog and three large ones each. */
        const val TRAINS = GRID * GRID * DECKS

        /** Five wheels a unit, of which four turn -- the motor is not a moving part. */
        const val MOVING_PARTS = TRAINS * 4

        const val FLOOR = 2

        /** The middle of the field in the horizontal plane, and in height. */
        const val CENTRE_XZ = (GRID - 1) * SPACING / 2

        const val CENTRE_Y = FLOOR + (DECKS - 1) * DECK_GAP / 2

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
