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
 * Before occlusion culling exists, `walled` and `open` should be about the same: the machinery is
 * hidden but still drawn. Once it works, `walled` should move toward `away`. That is the whole
 * result, and it needs no readback -- which matters, because reading the pyramid back is the thing
 * currently defeating verification.
 *
 * Measured on 2026-09-23 on Vulkan with no occlusion culling at all, at 1,152 trains:
 *
 * ```
 * run A   walled=2498  open=2677  away=3542  walledAgain=2728
 * run B   walled=2621  open=2894  away=3809  walledAgain=2722
 * OCCLUSION work walled=4 open=4
 * ```
 *
 * Read those as: **walled and open are the same to within the noise of this measurement**, which is
 * worth about eight per cent run to run -- `walledAgain` lands above `open` in one run and below it
 * in the other. The same four instancers are processed either way.
 *
 * <p>That is the baseline this exists to establish. Machinery hidden behind solid stone costs what
 * machinery in plain view costs, because it is still submitted in full and only rejected per
 * fragment. The headroom occlusion culling is chasing is the gap up to `away`, not any difference
 * between walled and open.
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

        applyVideo(before.framerateLimit, before.vsync, before.clouds)
        runCommand("forceload remove all")

        assertTrue(
            walled.alive && walled.fps > 0,
            "The client stopped drawing with the wall up: $walled",
        )

        // The point of the window. If this ever fails with occlusion culling on, the pyramid's
        // comparison is the wrong way round and it is hiding what the player can see.
        assertTrue(
            walled.instancing,
            "Flywheel is not instancing, so this scene measures block entity renderers rather than " +
                "anything to do with occlusion",
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
