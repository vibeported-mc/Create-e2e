package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
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
 * That a wall hides the blocks behind it.
 *
 * This is a picture, not a measurement, and it exists because of a specific defect. 1.21.1 built a
 * render type from `CompositeState.builder()`, which arrived with `LEQUAL_DEPTH_TEST` and
 * `COLOR_DEPTH_WRITE` already set -- a type that said nothing about depth still tested and wrote it.
 * 26.2 has no such default: `RenderPipeline.Builder.build` ends with `depthStencilState.orElse(null)`
 * and a pipeline holding a null one is given no depth attachment at all. Every type ported across
 * without naming a depth state stopped being occluded by anything: the burner's flame, the
 * accumulator's diode, the laser, the rope and the spring all painted over the world from any
 * distance and through any wall.
 *
 * Nothing reports that. The draw is issued and it validates; it is only wrong on the screen. So the
 * two worst offenders are put behind a wall and photographed, and a person looks.
 *
 * Two rigs stand side by side and identical, one on the ground and one assembled into a sub-level,
 * because a body's blocks are drawn by Sable's own path rather than by the level renderer, and the
 * two could disagree. Two pictures of them: the wall between the camera and the blocks, and then the
 * same scene from above where nothing is in the way.
 *
 * **What a person is looking for.** Two pictures from one camera, differing only in whether the
 * stone is there. In `walls_between` -- two plain grey walls, with no flame, no diode and no glow
 * showing through either. In `walls_removed` -- a burner alight and a diode lit behind both, which
 * is what says the first picture is empty because they are hidden and not because nothing drew.
 *
 * **What this does not prove.** The laser, the rope, the spring and the levitite blocks, which are
 * the other types the same defect reached. Each wants a rig of its own, and this test is the shape
 * they should copy.
 */
@DrivesMinecraft
class RenderThroughWallsTest {

    @Test
    @DisplayName("A stone wall hides a burner's flame and an accumulator's diode, in a sub-level too")
    fun `a wall hides what is behind it`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND, height = SKY)

        val onTheGround = at(-APART, RIG, 0)
        val toAssemble = at(APART, RIG, 0)

        wallRig(onTheGround)
        wallRig(toAssemble)
        serverTicks(SETTLE)

        val body = assembleArea(
            toAssemble.offset(-WALL, -1, 0),
            toAssemble.offset(WALL, HIGH, BEHIND),
        )
        serverTicks(SETTLE)

        try {
            // The plot's centre block is the assembly anchor, which `assemble area` takes as the
            // lowest, most north-westerly corner of the box -- so the burner is a wall's half-width
            // in from it, a block up, and the depth of the rig back.
            val plot = plotOriginOf(body)
            val corner = BlockPos(plot.x, plot.y, plot.z)
            val burnerAloft = corner.offset(WALL - BURNER_IN, 1, BEHIND)

            assertTrue(
                burnerIsLit(onTheGround.offset(-BURNER_IN, 0, BEHIND)) && burnerIsLit(burnerAloft),
                "One of the burners is not lit, so an empty picture would say nothing about walls",
            )

            look(from = at(0, EYE, -BACK_OFF), at = at(0, RIG, BEHIND))
            shot("walls_between")

            // The same camera again with the walls gone, which is what makes the pair readable: two
            // pictures from one viewpoint, and the only difference between them is the stone.
            fill(
                onTheGround.offset(-WALL, 0, 0),
                onTheGround.offset(WALL, HIGH, 0),
                "minecraft:air",
            )
            fill(
                corner.offset(0, 1, 0),
                corner.offset(WALL * 2, HIGH + 1, 0),
                "minecraft:air",
            )
            serverTicks(SETTLE)

            shot("walls_removed")
        } finally {
            removeSubLevel(body)
        }
    }

    /** Whether the burner at [pos] is running, and so has a flame to draw. */
    private suspend fun burnerIsLit(pos: BlockPos): Boolean = server(pos) { at ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity) {
            throw AssertionError(
                "There is no hot air burner at $at. The block there is " +
                    serverLevel.getBlockState(at) + " and the block entity is " + be,
            )
        }

        be.signalStrength > 0
    }

    private suspend fun Stage.look(from: BlockPos, at: BlockPos) {
        spectateAt(
            Vec3(from.x + 0.5, from.y.toDouble(), from.z + 0.5),
            Vec3(at.x + 0.5, at.y.toDouble(), at.z + 0.5),
            settle = AIM,
        )
    }

    /**
     * A stone wall with a lit burner and an accumulator standing behind it.
     *
     * Both of them are on the far side and low down, so the wall covers them completely from the
     * front. They stand on a floor that reaches back from the foot of the wall, which is what makes
     * the whole rig one connected group -- `assemble area` makes a body per group, and a wall that
     * assembled separately from what it is meant to be hiding would prove nothing.
     */
    private suspend fun Stage.wallRig(origin: BlockPos) {
        fill(
            origin.offset(-WALL, -1, 0),
            origin.offset(WALL, -1, BEHIND),
            "minecraft:stone",
        )

        fill(
            origin.offset(-WALL, 0, 0),
            origin.offset(WALL, HIGH, 0),
            "minecraft:stone",
        )

        // A block of redstone under the burner rather than beside it: what a burner runs on is a
        // neighbouring signal, and underneath is the one face of it the wall is not hiding anyway.
        setBlock(origin.offset(-BURNER_IN, -1, BEHIND), "minecraft:redstone_block")
        setBlock(
            origin.offset(-BURNER_IN, 0, BEHIND),
            "aeronautics:adjustable_burner[powered=false,variant=fire]",
        )

        // Facing north, which is towards the camera: the diode is on the accumulator's front, and a
        // diode pointed away from the lens would be hidden by the block's own body rather than by
        // the wall, which is a different claim.
        setBlock(
            origin.offset(BURNER_IN, 0, BEHIND),
            "simulated:redstone_accumulator[facing=north]",
        )
    }

    companion object {
        /** How far each rig stands from the middle, so the two are [APART] * 2 apart. */
        const val APART = 8

        /**
         * The level the two blocks stand on.
         *
         * Two above the stage floor, not one: the rig lays its own floor a block under this, and a
         * floor laid at the stage's own level would be assembled out of the world and leave the body
         * standing in the hole it had just made.
         */
        const val RIG = 2

        /** How far the wall reaches either side of the rig's middle. */
        const val WALL = 3

        /** How tall the wall is above the blocks it hides. */
        const val HIGH = 3

        /** How far behind the wall the two blocks stand. */
        const val BEHIND = 2

        /** How far in from the wall's edge each of the two blocks stands. */
        const val BURNER_IN = 2

        const val GROUND = 32
        const val SKY = 24
        const val SETTLE = 20

        /** Eye level for the picture through the walls: above the blocks, below the top of the wall. */
        const val EYE = 4

        /** Far enough back for both rigs to be in one frame. */
        const val BACK_OFF = 26

        const val AIM = 10
    }
}
