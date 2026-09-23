package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.MouseButton
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.lookAt
import dev.vibeported.mc.driver.mouseDown
import dev.vibeported.mc.driver.mouseUp
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Cracks drawn over a turning cogwheel, by actually mining it.
 *
 * The block-breaking overlay is a second draw of the same instances through a different shader, not
 * a material swap -- so it is the one part of the backend that nothing else exercises. Its failure
 * modes are all quiet: no cracks at all, cracks on the wrong instance, cracks drawn as an opaque
 * square over the model, or cracks that write depth and swallow the machine they are drawn on.
 *
 * ## Why a player has to do the mining
 *
 * Two shortcuts were tried before this and neither works. Setting the progress on the client level
 * writes into a map the renderer does not read -- the renderer takes its list from the server's
 * block-destruction packets. Setting it on the server level and broadcasting it does reach the
 * client, but Flywheel only draws crumbling for a block that *has* a visual, and the block a drill
 * or a command is breaking is ordinary terrain. The only thing that puts cracks on instanced
 * machinery is a player holding the button down on it, in survival, where breaking takes time.
 *
 * The cogwheel is also the right block to mine rather than a plain one: the overlay has to follow a
 * moving model, and a static block would look right whether or not the projection follows the
 * instance's own transform.
 *
 * ## The bug this found
 *
 * For a while this failed on every backend, and the cause was not the backend at all. Flywheel's
 * crumbling hook runs at the end of the frame and read the block-breaking states straight off the
 * level render state -- a list that `LevelExtractor` clears and refills on every extract, and 26.2
 * extracts the next frame while this one is still being drawn. Vanilla is unaffected because it
 * draws its own block-breaking early. By the time Flywheel looked, the list had been emptied for the
 * frame after. The context now copies it at the head of the level render instead.
 *
 * Establishing that took holding the button to the end: vanilla reports a stage under a tenth as
 * -1, which *removes* the entry rather than recording a faint crack, and a dig whose progress keeps
 * resetting still throws particles every tick -- so a short dig cannot tell a dead overlay from one
 * that never started. This test still holds it to the end for that reason.
 *
 * ```
 * ./gradlew test --tests '*CrumblingTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 */
@DrivesMinecraft
class CrumblingTest {

    @Test
    @DisplayName("A cogwheel being mined draws its cracks")
    fun `crumbling draws over instanced machinery`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = 16)

        setBlock(at(0, 2, 0), "create:creative_motor[facing=east]")
        setBlock(at(1, 2, 0), "create:shaft[axis=x]")
        setBlock(at(2, 2, 0), "create:large_cogwheel[axis=x]")
        serverTicks(60)

        // Face on to the cogwheel and close, which is the only angle the overlay reads from. The
        // cog turns about x, so its disc faces east and west and standing to the south of it shows
        // a thin bar with almost no surface for cracks to be drawn on.
        standAt(middleOf(at(5, 2, 0)), middleOf(at(2, 2, 0)))
        serverTicks(20)

        shot("crumbling_before")

        // Survival, and that is the whole trick. In creative the block goes in one frame and there
        // is no partly-broken state to draw at all.
        runCommand("gamemode survival $watcher")
        serverTicks(10)

        // Aimed again afterwards, because the switch takes flight away and the player drops the
        // half block it was hovering. The pitch does not drop with it, so the crosshair slides down
        // past the cogwheel and onto the floor -- which the first run of this spent twenty ticks
        // mining, with the cogwheel turning quietly in the corner of an empty screenshot.
        lookAt(watcher, at(2, 2, 0))
        serverTicks(10)

        holdAttack()
        try {
            // Long enough to be well along, which is the whole difficulty of this test. Vanilla
            // reports the breaking stage as `(progress * 10) - 1`, and a stage of -1 does not mean
            // "barely started", it *removes* the progress entry -- so a block under a tenth broken
            // is indistinguishable from one nobody is touching. Nor are particles evidence: a dig
            // whose progress keeps resetting throws them every tick and gets nowhere. An earlier
            // version of this held the button for twenty ticks and concluded the overlay was broken.
            serverTicks(60)

            shot("crumbling_cracked")

            val offered = breakingStates()
            val visuals = crumblingVisuals()
            val drawn = crumblingDraws()
            println("CRUMBLING offered=$offered visuals=$visuals draws=$drawn mining=${miningState()}")

            // Held to the end, so a zero above could not have been explained away as a dig that
            // never got going. The cogwheel does go, which means every one of the ten breaking
            // stages happened while the counts above were being taken.
            serverTicks(300)
            val after = blockAt(at(2, 2, 0))
            println("CRUMBLING finished block=$after")

            assertTrue(
                after == "Block{minecraft:air}",
                "The cogwheel survived a three hundred and sixty tick dig, so this scene never " +
                    "reached a late breaking stage and proves nothing either way about the overlay",
            )

            // The rest is asked in two steps, because "no cracks" has two unrelated causes and only
            // one of them is this backend's.
            //
            // The first is what the game offers Flywheel: the block-breaking render states the
            // extractor fills, which is the only place Flywheel looks. If that is empty through a
            // complete dig, nothing downstream can draw anything and the fault is upstream of every
            // backend -- the OpenGL one draws no cracks here either.
            assertTrue(
                offered > 0,
                "The game offered no block-breaking states while the player was demonstrably " +
                    "mining, so Flywheel was never asked to draw cracks and no backend could have " +
                    "drawn any. This is the race described on this class coming back: the states " +
                    "are read at the end of the frame from a list 26.2 has already cleared and " +
                    "refilled for the next one, and the fix is the copy taken at the head of the " +
                    "level render. It is upstream of every backend -- the OpenGL one draws no " +
                    "cracks here either -- so look at RenderContextImpl before looking here",
            )

            // Only the Blaze3D backend publishes this count; the OpenGL one leaves it at zero, and
            // asserting on it there would fail a run that is drawing cracks perfectly well.
            if (backendId() == "flywheel:indirect_blaze3d") {
                assertTrue(
                    drawn > 0,
                    "The game offered $offered block-breaking states and $visuals of them had a " +
                        "visual, and still the backend issued no crumbling draws -- so this one is " +
                        "ours, somewhere between collecting the instances and drawing them",
                )
            }
        } finally {
            releaseAttack()
            // Put the client back as it was found. Clients are shared between tests and the server
            // is one server, so a player left in survival is a player some later test breaks a
            // block with and gets nothing from.
            runCommand("gamemode creative $watcher")
        }
    }

    private suspend fun Stage.holdAttack() {
        client(watcher) {
            mouseDown(MouseButton.LEFT)
            true
        }
    }

    private suspend fun Stage.releaseAttack() {
        client(watcher) {
            mouseUp(MouseButton.LEFT)
            true
        }
    }

    /**
     * How many block-breaking states the game is offering this frame.
     *
     * Recorded inside the frame by the manager, not read from the render state out here. The render
     * state is reset between frames, so an honest zero and a mistimed read are the same answer --
     * which is how the first few runs of this concluded the game was offering nothing.
     */
    private suspend fun Stage.breakingStates(): Int = client(watcher) {
        dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.lastBlockBreakingStates
    }

    /** How many of those the manager found a visual for, which is the other half of the question. */
    private suspend fun Stage.crumblingVisuals(): Int = client(watcher) {
        dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.lastCrumblingVisuals
    }

    private suspend fun Stage.backendId(): String = client(watcher) {
        dev.engine_room.flywheel.api.backend.Backend.REGISTRY
            .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
            .toString()
    }

    /** Whether the block is still there, which is how a dig that never progresses gives itself away. */
    private suspend fun Stage.blockAt(where: BlockPos): String = server(where) { pos ->
        serverLevel.getBlockState(pos).block.toString()
    }

    /** What the client thinks it is mining, for when the cracks do not appear. */
    private suspend fun Stage.miningState(): String = client(watcher) {
        val hit = minecraft.hitResult
        val target = if (hit is net.minecraft.world.phys.BlockHitResult) {
            minecraft.level!!.getBlockState(hit.blockPos).block.toString() + " at " + hit.blockPos
        } else {
            "nothing (" + hit?.type + ")"
        }
        "gameMode=" + minecraft.gameMode!!.playerMode + " destroying=" +
            minecraft.gameMode!!.isDestroying + " target=" + target
    }

    /** How many crumbling draws the last crumbling frame issued, which no screenshot can tell apart. */
    private suspend fun Stage.crumblingDraws(): Int = client(watcher) {
        dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.crumblingCalls
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
