package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.runCommand
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
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A field of machinery behind a wall, which is the only scene occlusion culling can help with.
 *
 * ## Why not the stress field
 *
 * [IndirectStressTest] looks like the obvious place to measure occlusion culling and is close to the
 * worst possible scene for it. The pyramid is reduced from the depth buffer as Flywheel renders,
 * which is after terrain and before any instanced machinery -- so the only things that can occlude
 * an instance are chunk blocks. An instance hidden behind another instance is never culled, because
 * that occluder has not been drawn yet when the pyramid is built. The stress field is machinery
 * floating in open air with the camera inside it: the occlusion it obviously has is exactly the kind
 * this design cannot use, and the measurement would come back flat whether the feature worked or
 * not.
 *
 * So this scene puts solid stone between the camera and the machinery, which is what a real base
 * does -- machines inside buildings, factories underground, a wall between you and the workshop.
 *
 * ## What it measures
 *
 * Three frame rates on one scene, which together bracket what occlusion culling can be worth:
 *
 * - **walled** -- the wall up, almost everything behind it. What the feature should speed up.
 * - **open** -- the same field with the wall taken away. The honest cost of drawing it all.
 * - **away** -- the camera turned around. The floor, where frustum culling alone has already won.
 *
 * Before occlusion culling existed, `walled` and `open` came out the same: the machinery was hidden
 * but still drawn. Measured on 2026-09-23 on Vulkan at 1,152 trains, with the feature absent:
 *
 * ```
 * run A   walled=2498  open=2677  away=3542  walledAgain=2728
 * run B   walled=2621  open=2894  away=3809  walledAgain=2722
 * ```
 *
 * Read those as: **walled and open are the same to within the noise of this measurement**, which is
 * worth about eight per cent run to run -- `walledAgain` lands above `open` in one run and below it
 * in the other. Machinery behind solid stone cost what machinery in plain view cost, because it was
 * still submitted in full and only rejected per fragment.
 *
 * With the feature working, on the same scene:
 *
 * ```
 * Vulkan   walled=2921  open=2690  away=3176   2754 of 3332 culled as hidden
 * OpenGL   walled=1509  open=1538  away=1350   2574 of 3332 culled as hidden
 * ```
 *
 * The count is the result; the frame rates are corroboration, and weak corroboration at that. Four
 * instancers of a few thousand instances are not where a frame goes, so removing three quarters of
 * them moves the total by less than this measurement's own noise -- on OpenGL `walled` and `open`
 * are still level, and `away` came in below both. What the scene proves is that the right things
 * are being culled, and it takes the counts to say so.
 *
 * <p>The two backends agreeing is worth more than either number. They cull within a couple of
 * hundred instances of each other and their deepest pyramid sample agrees to four digits --
 * 0.004373 against 0.004361 -- which is two entirely separate implementations of the reduction
 * arriving at the same depth.
 *
 * <p>Two traps this scene has already fallen into, both worth keeping in mind before reading any
 * figure off it. The first reading of a run is the slow one -- teleport, chunk rebuild, pipelines
 * compiling -- and taking it as the walled figure made the wall look four hundred frames a second
 * expensive; hence the discarded warm-up read. And a single pair of numbers from one run is not
 * enough to tell a real six per cent from noise, which is why `walledAgain` exists at all.
 *
 * ## The window
 *
 * There is a hole in the wall, and it is not decoration. The failure this feature invites is a
 * reduction with its comparison backwards, which culls what you can plainly see; a solid wall cannot
 * tell that apart from working perfectly, because both give an empty picture. Machinery must still
 * be visible through the window.
 */
@DrivesMinecraft
class OcclusionTest {

    @Test
    @DisplayName("Machinery behind a wall, with a window to prove it is not over-culled")
    fun `occluded machinery still draws what can be seen`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val before = videoSettings()
        applyVideo(UNLIMITED_FRAMERATE, vsync = false, clouds = false)

        // Named rather than left to priority. This is the only backend that does occlusion culling,
        // and on OpenGL it loses the pick to flywheel:indirect -- so without this the test would
        // measure the old backend, find no cull counts at all, and fail for a reason that has
        // nothing to do with what it is testing.
        useBackend("flywheel:indirect_blaze3d")

        buildField()
        forceLoadField()
        serverTicks(SPIN_UP_TICKS)

        val speeds = trainSpeeds(0, FLOOR, 0)
        println("OCCLUSION train=$speeds")

        assertTrue(
            speeds.allTurning,
            "The field is not turning: $speeds. A field of stationary blocks is the cheap case and " +
                "measuring it says nothing about anything",
        )

        buildWall()
        serverTicks(20)

        // Behind the wall, looking through it at the field.
        spectateAt(middleOf(at(CENTRE_XZ, EYE, -WALL_STANDOFF)), middleOf(at(CENTRE_XZ, EYE, SPAN)))
        serverTicks(SETTLE_TICKS)

        // Thrown away. The first reading of a run is always the slow one -- teleport, chunk rebuild,
        // pipelines compiling -- and taking it as the walled figure made the wall look like it cost
        // four hundred frames a second. Measured again at the end, the same scene read higher than
        // the wall-less one; the difference was entirely warm-up.
        drawing()
        serverTicks(SETTLE_TICKS)

        val walled = drawing()
        shot("occlusion_walled")

        // The wall away, same camera. Everything the wall was hiding is now drawn for real.
        clearWall()
        serverTicks(SETTLE_TICKS)

        val open = drawing()
        shot("occlusion_open")

        // And the floor: the camera turned around, where frustum culling has already done the work.
        spectateAt(middleOf(at(CENTRE_XZ, EYE, -WALL_STANDOFF)), middleOf(at(CENTRE_XZ, EYE, -SPAN)))
        serverTicks(SETTLE_TICKS)

        val away = drawing()

        // The wall back, and measured again. Order is a confound worth ruling out rather than
        // explaining away: the walled figure is the first one taken after a teleport and a chunk
        // rebuild, and the open one comes later with everything warm. If the two walled readings
        // agree, the difference is the wall; if the second matches the open one, it was warm-up.
        buildWall()
        spectateAt(middleOf(at(CENTRE_XZ, EYE, -WALL_STANDOFF)), middleOf(at(CENTRE_XZ, EYE, SPAN)))
        serverTicks(SETTLE_TICKS)

        val walledAgain = drawing()

        println(
            "OCCLUSION backend=${walled.backend} trains=$TRAINS parts=${TRAINS * 4} " +
                "walled=${walled.fps} open=${open.fps} away=${away.fps} " +
                "walledAgain=${walledAgain.fps}",
        )
        println("OCCLUSION work walled=${walled.work} open=${open.work}")
        val cull = cullCounts()
        println("OCCLUSION cull $cull")

        applyVideo(before.framerateLimit, before.vsync, before.clouds)
        runCommand("forceload remove all")

        assertTrue(
            walled.alive && walled.fps > 0,
            "The client stopped drawing with the wall up: $walled",
        )

        assertTrue(
            walled.instancing,
            "Flywheel is not instancing, so this scene measures block entity renderers rather than " +
                "anything to do with occlusion",
        )

        // Something behind the wall was culled. Without this the test passes on a backend that has
        // occlusion culling switched off, which is how it read for its whole first day.
        assertTrue(
            cull.occluded > 0,
            "Nothing was culled as hidden, although the field is behind a wall: $cull",
        )

        // And the point of the window. Everything culled and nothing drawn is not success -- it is
        // the comparison being the wrong way round, which is the failure this feature invites and
        // which a solid wall cannot distinguish from working perfectly.
        assertTrue(
            cull.visible > 0,
            "Every instance was culled, including whatever is visible through the window. The "
                + "depth comparison is inverted and this is hiding what the player can see: $cull",
        )
    }

    /**
     * A wall of stone across the near side of the field, with a window in the middle.
     *
     * Tall enough and wide enough to cover the whole field from where the camera stands, or the
     * parts that peek round the edge are drawn anyway and the measurement is a blend of the two
     * cases it is trying to separate.
     */
    private suspend fun Stage.buildWall() {
        fill(at(-MARGIN, FLOOR - 1, WALL_Z), at(SPAN + MARGIN, WALL_TOP, WALL_Z), "minecraft:stone")

        // The window, which is what stops a working result and an over-culling one looking alike.
        fill(
            at(CENTRE_XZ - WINDOW / 2, EYE - WINDOW / 2, WALL_Z),
            at(CENTRE_XZ + WINDOW / 2, EYE + WINDOW / 2, WALL_Z),
            "air",
        )
    }

    private suspend fun Stage.clearWall() {
        fill(at(-MARGIN, FLOOR - 1, WALL_Z), at(SPAN + MARGIN, WALL_TOP, WALL_Z), "air")
    }

    /** The field, built server side in one call -- see IndirectStressTest for why. */
    private suspend fun Stage.buildField() {
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

        println("OCCLUSION placed=$placed blocks")
    }

    private suspend fun Stage.forceLoadField() {
        val low = at(-MARGIN - SPACING, FLOOR, -WALL_STANDOFF - SPACING)
        val high = at(SPAN + MARGIN + SPACING, FLOOR, SPAN + SPACING)

        runCommand("forceload add ${low.x} ${low.z} ${high.x} ${high.z}")
    }

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
     * Where the cull pass dropped instances, which is the only way to see why it culled nothing.
     *
     * A pass that removes nothing and a pass that is never reached both leave every instance drawn,
     * and no frame rate distinguishes them.
     */
    private suspend fun Stage.cullCounts(): CullCounts = client(watcher) {
        val counts = dev.engine_room.flywheel.backend.engine.blaze.BlazeEngine.lastDrawManager()
            ?.cullCounts()

        if (counts == null) {
            CullCounts(0, 0, 0, 0, 0, 0, 0, 0)
        } else {
            CullCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                counts[6], counts[7])
        }
    }

    @Serializable
    private data class CullCounts(
        val visible: Int,
        val tested: Int,
        val outOfFrustum: Int,
        val tooClose: Int,
        val offScreen: Int,
        val occluded: Int,
        val maxFurthest: Int,
        val maxHiZ: Int,
    ) {
        override fun toString(): String =
            "of $tested tested: $outOfFrustum outside the frustum, $occluded hidden, $visible drawn " +
                "($tooClose too close to test, $offScreen partly off screen); " +
                "deepest pyramid sample ${maxFurthest / 1e6}, nearest sphere corner ${maxHiZ / 1e6}"
    }

    private suspend fun Stage.drawing(): Drawing = client(watcher) {
        Drawing(
            fps = minecraft.fps,
            alive = minecraft.level != null,
            backend = dev.engine_room.flywheel.api.backend.Backend.REGISTRY
                .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
                .toString(),
            instancing = dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn(),
            work = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.indirectInstancers,
        )
    }

    @Serializable
    private data class Drawing(
        val fps: Int,
        val alive: Boolean,
        val backend: String,
        val instancing: Boolean,
        /** Instancers the backend drew indirectly, which is what occlusion culling should reduce. */
        val work: Int,
    )

    private suspend fun Stage.videoSettings(): Video = client(watcher) {
        Video(
            minecraft.options.framerateLimit().get(),
            minecraft.options.enableVsync().get(),
            minecraft.options.cloudStatus().get() != net.minecraft.client.CloudStatus.OFF,
        )
    }

    private suspend fun Stage.applyVideo(framerateLimit: Int, vsync: Boolean, clouds: Boolean) {
        client(watcher, framerateLimit, vsync, clouds) { limit, sync, sky ->
            minecraft.options.framerateLimit().set(limit)
            minecraft.options.enableVsync().set(sync)
            minecraft.options.cloudStatus().set(
                if (sky) net.minecraft.client.CloudStatus.FANCY else net.minecraft.client.CloudStatus.OFF,
            )
            minecraft.options.save()
            true
        }
    }

    @Serializable
    private data class Video(val framerateLimit: Int, val vsync: Boolean, val clouds: Boolean)

    private companion object {
        /** Smaller than the stress field: this measures a ratio, not a peak. */
        const val GRID = 12

        const val DECKS = 8

        const val SPACING = 6

        const val DECK_GAP = 3

        const val TRAINS = GRID * GRID * DECKS

        const val FLOOR = 2

        const val SPAN = (GRID - 1) * SPACING + 4

        const val CENTRE_XZ = (GRID - 1) * SPACING / 2

        /** Where the wall stands, and how far behind it the camera does. */
        const val WALL_Z = -4

        const val WALL_STANDOFF = 16

        /** Above the tallest deck, so nothing shows over the top. */
        const val WALL_TOP = FLOOR + DECKS * DECK_GAP + 6

        /** Past the edges of the field, so nothing shows round the side. */
        const val MARGIN = 12

        const val WINDOW = 4

        const val EYE = FLOOR + 6

        const val GROUND = 64

        const val SPIN_UP_TICKS = 60

        const val SETTLE_TICKS = 60

        const val UNLIMITED_FRAMERATE = 260
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

/**
 * What a kinetic block is turning at.
 *
 * A top-level function rather than a method, because it is called from a body that runs on the
 * server, and those may not reach back into the test object they were written in.
 */
private fun dev.vibeported.mc.driver.ServerScope.speedOf(where: BlockPos): Float =
    (serverLevel.getBlockEntity(where)
        as? com.simibubi.create.content.kinetics.base.KineticBlockEntity)
        ?.speed ?: 0.0f
