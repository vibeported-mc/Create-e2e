package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A turning contraption, watched to see that its blocks keep being drawn.
 *
 * ## The gap this fills
 *
 * Everything mounted on a contraption is drawn through Flywheel's *embedded* path: the parts are
 * positioned in the contraption's own space, and the contraption carries a matrix saying where that
 * space currently is. Nothing else in Create uses it, and until this test nothing exercised it --
 * every other render test here stands machinery still in the world, where that matrix is the
 * identity and a mistake in it cannot show.
 *
 * The entities riding a contraption are drawn by ordinary entity renderers and are *not* evidence:
 * they keep appearing when every block around them has gone, which is exactly how this was first
 * noticed by eye -- chests floating above a train with no train under them.
 *
 * ## What it measures
 *
 * The cull pass publishes where it dropped instances, and this reads those counts while the
 * contraption turns, asserting that no frame drew nothing. A screenshot would not serve for the
 * flickering this guards against: a picture that happens to catch a good frame proves nothing.
 *
 * ## What it does not catch, which is worth knowing before trusting it
 *
 * It does **not** reproduce the fault that prompted it: the cull shader testing bounding spheres in
 * the space a contraption was *assembled* in rather than the space it is *drawn* in. That was
 * measured -- this test passes with the fix reverted, twice, to identical counts.
 *
 * A bearing only turns. Its parts stay within a few blocks of the axle, the conservative sphere
 * still covers them, and the frustum test comes out the same either way. The fault needed a
 * contraption that *travels*: trains were the only thing in Create seen to break, and they run the
 * length of a circuit, hundreds of blocks from where they were assembled. Every other moving
 * contraption -- bearings, pistons, rollers, elevators -- was fine throughout.
 *
 * So this covers the embedded path being drawn at all, which nothing else did. Catching that fault
 * again wants a train under way, and the screenshot on a corner in `TrainCircuitTest` is what a
 * person has to look at for now.
 */
@DrivesMinecraft
class ContraptionRenderTest {

    @Test
    @DisplayName("A turning contraption keeps its blocks on screen")
    fun `an embedded contraption is not culled away`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        buildBearing()
        serverTicks(SPIN_UP_TICKS)

        // Before anything is measured. A bearing that never assembled leaves its blocks standing in
        // the world, where the environment pose is the identity and the mistake this test exists to
        // catch cannot happen -- so the scene would read perfectly whether the bug was there or
        // not. The first version of this test did exactly that, and passed with the fix reverted.
        assertTrue(
            contraptionAssembled(),
            "The bearing did not assemble anything, so nothing here is riding a contraption and " +
                "the embedded path is not being exercised at all",
        )

        // Off to one side and above, so the contraption's own space and the world's disagree in
        // every axis. Looking at it head on from its own origin would hide the very mistake this
        // exists to catch, because there the two spaces nearly agree.
        spectateAt(middleOf(at(VIEW_X, EYE, VIEW_Z)), middleOf(at(0, AXLE_Y + 2, 0)))
        serverTicks(SETTLE_TICKS)

        shot("contraption_turning")

        // Sampled across a spread of poses rather than once. The contraption turns a full circle in
        // a few seconds, so this walks it through most of one -- and the fault this was written for
        // appears at some angles and not others.
        val readings = mutableListOf<CullCounts>()
        repeat(SAMPLES) {
            readings.add(cullCounts())
            serverTicks(SAMPLE_GAP_TICKS)
        }

        val work = drawWork()
        println("CONTRAPTION work=$work")
        readings.forEachIndexed { i, counts -> println("CONTRAPTION sample $i: $counts") }

        shot("contraption_turned")

        runCommand("forceload remove all")

        assertTrue(
            work.indirectInstancers > 0,
            "Nothing was drawn indirectly, so this scene says nothing about the backend under " +
                "test: $work",
        )

        // The real assertion. Every frame has to draw something; a single empty one is the fault,
        // and averaging across the samples would hide exactly the flicker being looked for.
        val empty = readings.withIndex()
            .filter { (_, counts) -> counts.tested > 0 && counts.visible == 0 }

        assertTrue(
            empty.isEmpty(),
            "The cull pass kept nothing at all on ${empty.size} of $SAMPLES samples, while the " +
                "contraption was turning in plain view. Its bounding spheres are being tested in " +
                "the space it was assembled in rather than the space it is drawn in, so the parts " +
                "are culled as off-screen. Samples: ${empty.map { it.value }}",
        )
    }

    /**
     * A bearing with a creative motor under it and a slab of blocks on top.
     *
     * Andesite casing rather than anything decorative because it is what Create's own contraption
     * tests carry: plain blocks, no block entities, so what is on screen is the embedded path and
     * nothing else.
     */
    private suspend fun Stage.buildBearing() {
        setBlock(at(0, AXLE_Y - 1, 0), "create:creative_motor[facing=up]")
        setBlock(at(0, AXLE_Y, 0), "create:mechanical_bearing[facing=up]")

        // The platform the bearing turns. Wide enough that its far corners swing well away from the
        // axle, which is where a sphere left in the wrong space goes astray first.
        fill(
            at(-ARM, AXLE_Y + 1, -ARM),
            at(ARM, AXLE_Y + 1, ARM),
            "create:andesite_casing",
        )

        // And cogwheels along it, which are the actual subject.
        //
        // The casing is ordinary blocks: a contraption draws those as one baked mesh, not as
        // Flywheel instances, so a platform of nothing but casing exercises none of the embedded
        // path. The first version of this test carried only casing and reported two instances --
        // the bearing and the motor, both of them standing in the world rather than riding
        // anything. It passed, and would have passed with the bug still in.
        for (x in -ARM..ARM) {
            setBlock(at(x, AXLE_Y + 2, -ARM), "create:cogwheel[axis=x]")
            setBlock(at(x, AXLE_Y + 2, ARM), "create:cogwheel[axis=x]")
        }

        runCommand("forceload add ${-GROUND} ${-GROUND} $GROUND $GROUND")
    }

    private suspend fun Stage.contraptionAssembled(): Boolean = server(at(0, AXLE_Y, 0)) { pos ->
        serverLevel.getEntitiesOfClass(
            com.simibubi.create.content.contraptions.AbstractContraptionEntity::class.java,
            net.minecraft.world.phys.AABB(pos).inflate(ARM + 4.0),
        ).isNotEmpty()
    }

    private companion object {
        const val GROUND = 24
        const val AXLE_Y = 64
        const val ARM = 3

        /** Off-axis in x and z both, and above, so no two spaces line up. */
        const val VIEW_X = 14
        const val VIEW_Z = 11
        const val EYE = AXLE_Y + 6

        const val SPIN_UP_TICKS = 60
        const val SETTLE_TICKS = 20

        const val SAMPLES = 12
        const val SAMPLE_GAP_TICKS = 5
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
