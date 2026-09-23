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
 * ## This currently fails, and not because of the backend
 *
 * The crack overlay does not render anywhere in this 26.2 port. Not on instanced machinery and not
 * on plain stone: a player mining ordinary terrain throws break particles and the block stays
 * uncracked, on Vulkan and on OpenGL alike. Measured from inside the frame, Flywheel's crumbling
 * hook is handed an empty list of block-breaking states for every frame of a two hundred tick dig,
 * so nothing downstream of it can draw anything. The same scene in 1.21.1 cracks normally.
 *
 * So the fault is upstream of every renderer -- the client level's destruction progress is not
 * reaching the level extractor that fills those states. This test is written against the behaviour
 * that should exist, and its first assertion says plainly which of the two failures it is looking
 * at, so that when the feed is fixed this becomes a real test of the backend rather than a rewrite.
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
            // Long enough to actually get somewhere, which is the whole difficulty of this test.
            // Vanilla reports the breaking stage as `(progress * 10) - 1`, and a stage of -1 does
            // not mean "barely started", it *removes* the progress entry -- so a block that is
            // under a tenth broken is indistinguishable from one nobody is touching. A cogwheel
            // mined bare-handed takes a couple of hundred ticks to get that far, and the first
            // version of this held the button for twenty and concluded the overlay was broken.
            serverTicks(120)

            shot("crumbling_cracked")

            val offered = breakingStates()
            val visuals = crumblingVisuals()
            val drawn = crumblingDraws()
            println("CRUMBLING offered=$offered visuals=$visuals draws=$drawn mining=${miningState()}")

            // Asked in two steps, because "no cracks" has two completely different causes and only
            // one of them is this backend's.
            //
            // The first is what the game offers Flywheel: the list of block-breaking render states
            // on the level's own render state, which is where the extractor puts them and the only
            // place Flywheel looks. If that is empty while a player is demonstrably mining, nothing
            // downstream can draw anything, and the fault is upstream of every backend -- the
            // OpenGL one draws no cracks here either.
            assertTrue(
                offered > 0,
                "The game offered no block-breaking states while the player was demonstrably " +
                    "mining, so Flywheel was never asked to draw cracks and no backend could have " +
                    "drawn any. This is the known upstream gap described on this class, not a " +
                    "fault in the backend -- when it is fixed, this assertion starts passing and " +
                    "the one below becomes the real test",
            )

            assertTrue(
                drawn > 0,
                "The game offered $offered block-breaking states and the backend issued no " +
                    "crumbling draws, so this one is ours: either the block's visual was not " +
                    "found, or it declined to be drawn crumbling",
            )
        } finally {
            releaseAttack()
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
