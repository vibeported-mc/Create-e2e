package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a redstone magnet pulls a second magnet towards it one way round, and pushes it away the other.
 *
 * The scene's claims: redstone magnets attract or repel other redstone magnets, opposite poles attract
 * and similar poles repel.
 *
 * One magnet stands in the world and the other rides a small sub-level, which is the only arrangement
 * that can show this. Two magnets both in the world cannot move; two magnets in two different
 * sub-levels are worse than useless, because each body's blocks live in its own plot and those plots
 * are twenty million blocks apart -- `MagnetMap` indexes magnets by the section they are in, so a pair
 * in separate plots is never even considered. Anchoring one of them in the world puts both in the same
 * frame and leaves the other free to be moved.
 *
 * The two arrangements are the textbook ones. Both magnets pointing the same way along the axis
 * between them puts one's north against the other's south -- opposite poles facing -- and they pull
 * together. Turning the loose one round puts like against like and it is pushed away.
 *
 * What is measured is how far the body ends up from where it started, not how fast it is going at some
 * particular moment. Velocity was the first attempt and it is not stable here: the body is pulled into
 * contact, bounces, and comes back, so the sign of its velocity at twenty ticks was the opposite of
 * its sign at sixty. Where it has actually got to integrates all of that away.
 *
 * **What this does not prove.** That attracting magnets turn to align with each other, which the scene
 * also shows and which wants a settled attitude rather than a body in the first moments of being
 * pulled.
 */
@DrivesMinecraft
class RedstoneMagnetTest {

    @Test
    @DisplayName("A magnet with its opposite pole facing is pulled towards the fixed one")
    fun `opposite poles attract`(cluster: ClusterScope) = cluster.stage {
        val drift = driftOfTheLooseMagnet(looseFacing = "east")

        assertTrue(
            drift < -MOVING,
            "Both magnets point east, so the fixed one's north faces the loose one's south and the " +
                "loose body should be pulled west towards it. It has moved $drift along x, " +
                "where west is negative",
        )
    }

    @Test
    @DisplayName("A magnet with its like pole facing is pushed away from the fixed one")
    fun `like poles repel`(cluster: ClusterScope) = cluster.stage {
        val drift = driftOfTheLooseMagnet(looseFacing = "west")

        assertTrue(
            drift > MOVING,
            "The loose magnet is turned round, so like poles face each other and it should be " +
                "pushed east away from the fixed one. It has moved $drift along x, where east " +
                "is positive",
        )
    }

    /**
     * How far the loose body has moved along the axis between the two magnets.
     *
     * Negative is towards the fixed magnet. The fixed one always points east, at the loose one; only
     * the loose one is turned, which is what changes opposite poles into like ones.
     */
    private suspend fun Stage.driftOfTheLooseMagnet(looseFacing: String): Double {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val fixed = at(-GAP, 1, 0)
        val loose = at(GAP, 1, 0)

        // The fixed magnet is left in the world, never assembled, so it has something to push against.
        magnetRig(fixed, "east")
        magnetRig(loose, looseFacing)
        serverTicks(SETTLE)

        val body = assembleArea(loose.offset(0, -1, 0), loose.offset(0, 1, 0))

        try {
            val from = poseOf(body).x
            serverTicks(PULL)

            return poseOf(body).x - from
        } finally {
            removeSubLevel(body)
        }
    }

    /**
     * A magnet with a stone block under it and a redstone block on top.
     *
     * The redstone block is what makes the magnet a magnet. Its strength is
     * `level.getBestNeighborSignal(pos)`, read fresh every time, so setting `powered=true` in the
     * blockstate achieves nothing -- the first version of this did exactly that and measured the two
     * bodies drifting at a hundred-thousandth of a metre a second, which is what no magnetic force
     * looks like. It goes above rather than beside so it is never between the two magnets.
     *
     * A column of three rather than a slab, so the loose one assembles into a body small enough to be
     * shifted by a magnet and narrow enough to stand two blocks from its neighbour.
     */
    private suspend fun Stage.magnetRig(origin: BlockPos, facing: String) {
        setBlock(origin.below(), "minecraft:stone")
        setBlock(origin, "simulated:redstone_magnet[facing=$facing,powered=false]")
        setBlock(origin.above(), "minecraft:redstone_block")
    }

    companion object {
        const val GROUND = 20
        const val SETTLE = 20

        /** How far each magnet stands from the middle, so they are [GAP] * 2 apart. */
        const val GAP = 1

        /** Long enough for the body to have gone somewhere, in whichever direction. */
        const val PULL = 80

        /**
         * Blocks. Below this the body has not really gone anywhere.
         *
         * A body with no magnetic force on it drifts by millionths of a block while it settles, so
         * this is four orders of magnitude clear of the noise and still a fraction of the two blocks
         * between the magnets.
         */
        const val MOVING = 0.05
    }
}
