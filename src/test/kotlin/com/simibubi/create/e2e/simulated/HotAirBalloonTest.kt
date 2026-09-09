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
 * A hot air balloon that leaves the ground.
 *
 * This is the whole aeronautics chain in one test, and none of its links can be shown on their own.
 * An envelope is a shell of airtight blocks with nothing inside it; a burner is a block that makes
 * hot air and has nowhere to put it; lift is a force with nothing to push. Only when a burner is
 * firing into an envelope, and the two of them are one physics body, does anything happen at all.
 *
 * What the scene says the parts do: envelope blocks enclose a volume that generates lift when filled
 * with hot air, the burner fills the balloon above it when redstone-powered, hot air fills from the
 * top downwards, and lift is limited by the balloon's size.
 *
 * The balloon is built cold, assembled, and left alone -- and only then does the redstone arrive.
 * That order is what makes this a test of the burner rather than of gravity: a body already rising
 * before anything was switched on would prove nothing, so the cold reading is asserted too.
 *
 * Both `getTotalLift` and the body's own motion are asserted. The first says the balloon worked out
 * that it should be lifting; the second says that reached the physics. They fail separately, and
 * which one fails says where to look -- `ServerBalloon.updateGasAmounts` for the first,
 * `ServerBalloon.applyForces` and the force group it pushes into for the second.
 *
 * **What this does not prove.** That lift is limited by balloon size, that a punctured envelope
 * leaks, that the burner's output scales with redstone strength, or that a balloon comes back down
 * when the burner goes out. All are in the scene and each wants a second balloon to compare against.
 */
@DrivesMinecraft
class HotAirBalloonTest {

    @Test
    @DisplayName("A hot air balloon rises once its burner is lit")
    fun `it rises once the burner is lit`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND, height = SKY)

        val basket = at(0, 1, 0)
        balloonRig(basket)
        serverTicks(SETTLE)

        val body = assembleArea(basket.offset(-SHELL, DECK, -SHELL), basket.offset(SHELL, CAP, SHELL))
        serverTicks(SETTLE)

        try {
            // Where the rig's blocks ended up. A body's blocks live in its plot out at x/z = 2e7,
            // and the plot stays put even though the body does not -- so every reading and every
            // change below is addressed through the plot rather than through where the rig was
            // built, and goes on working after the balloon has flown away from that spot.
            //
            // The plot's centre block is the assembly's *anchor*, and `assemble area` anchors on
            // `blocks.getFirst()`: the lowest, most north-westerly corner of the box, which here is
            // a corner of the deck rather than the middle of it. So the burner is a shell's width
            // in from that corner in both directions as well as a block up from it.
            val plot = plotOriginOf(body)
            val corner = BlockPos(plot.x, plot.y, plot.z)
            val burner = corner.offset(SHELL, BURNER - DECK, SHELL)

            watchTheBalloon(body)
            shot("balloon_cold")

            val restingY = poseOf(body).y

            assertTrue(
                liftOf(burner) == 0.0,
                "The balloon is generating lift with nothing powering its burner, so nothing below " +
                    "would be a claim about having lit it",
            )

            // The deck plank the burner stands on becomes a block of redstone. A neighbouring signal
            // is what a burner runs on -- `getBestNeighborSignal`, read fresh every time -- and
            // underneath is the one face of it that is not on show.
            setBlock(burner.below(), "minecraft:redstone_block")
            serverTicks(FILL)

            val lift = liftOf(burner)

            assertTrue(
                lift > 0.0,
                "The burner has been powered for $FILL ticks and its balloon reports $lift lift. " +
                    "An envelope filling with hot air is supposed to generate some",
            )

            watchTheBalloon(body)
            shot("balloon_lit")

            val climb = velocityOf(body).ly

            assertTrue(
                climb > RISING,
                "The balloon is generating $lift lift and moving upwards at $climb. The lift is " +
                    "being worked out and is not reaching the body",
            )

            val climbedTo = poseOf(body).y

            assertTrue(
                climbedTo > restingY + ROSE_BY,
                "The balloon has lift and upward velocity but has only got from $restingY to " +
                    "$climbedTo. It is not actually going anywhere",
            )

            watchTheBalloon(body)
            shot("balloon_aloft")
        } finally {
            removeSubLevel(body)
        }
    }

    /** Points the camera at the balloon, wherever it has got to by now. */
    private suspend fun Stage.watchTheBalloon(body: String) {
        val at = poseOf(body)

        spectateAt(
            Vec3(at.x + VIEW_OUT, at.y + VIEW_UP, at.z + VIEW_OUT),
            Vec3(at.x, at.y + LOOK_UP, at.z),
            settle = AIM,
        )
    }

    /** How much lift the balloon this burner belongs to is generating. */
    private suspend fun liftOf(burner: BlockPos): Double = server(burner) { at ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity) {
            throw AssertionError(
                "There is no hot air burner at $at. The block there is " +
                    serverLevel.getBlockState(at) + " and the block entity is " + be,
            )
        }

        val balloon = be.balloon

        // No balloon at all is a real reading rather than an error: an unlit burner has not gone
        // looking for one yet, which is exactly what the cold assertion is checking.
        if (balloon !is dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon) {
            0.0
        } else {
            balloon.totalLift
        }
    }

    /**
     * A balloon shaped like one: a basket, four posts, and an envelope hanging open above them.
     *
     * The proportions come from the scene, and they are not decoration. A burner finds its balloon
     * by casting a ray straight up and taking the airtight block it hits -- so it needs a clear
     * column from its own top face to the envelope's cap, and the volume it fills is the space just
     * under that cap. Seal the envelope underneath as well and the ray hits that floor instead, and
     * the position it comes back with is on the far side of it, outside the balloon rather than
     * inside: a sealed box cannot be fed by a burner at all.
     *
     * So the envelope hangs open over a gap, which is also what makes the thing legible. The burner
     * stands on the deck with three blocks of clear air over it, firing up into the mouth of the
     * envelope, and its flame is in plain view from the side rather than buried inside a bag.
     *
     * The posts are structural in both senses. `assemble area` makes one body per connected group,
     * not one per box, so an envelope floating over an unattached basket would assemble as two
     * sub-levels -- a balloon with nothing slung under it, and a basket with nothing to lift it.
     */
    private suspend fun Stage.balloonRig(basket: BlockPos) {
        // The deck.
        fill(
            basket.offset(-SHELL, DECK, -SHELL),
            basket.offset(SHELL, DECK, SHELL),
            "minecraft:dark_oak_planks",
        )

        // Its railing: the ring around the edge of the deck, one block up.
        for (step in -SHELL..SHELL) {
            for (side in listOf(
                basket.offset(step, 0, -SHELL),
                basket.offset(step, 0, SHELL),
                basket.offset(-SHELL, 0, step),
                basket.offset(SHELL, 0, step),
            )) {
                setBlock(side, "minecraft:dark_oak_fence")
            }
        }

        // The four posts, carrying the envelope's corners down to the corners of the basket.
        for (dx in listOf(-SHELL, SHELL)) {
            for (dz in listOf(-SHELL, SHELL)) {
                fill(
                    basket.offset(dx, 1, dz),
                    basket.offset(dx, WALL - 1, dz),
                    "minecraft:dark_oak_fence",
                )
            }
        }

        // The envelope, laid solid and then hollowed out. Filling the shell and cutting the inside
        // away is the only way to be sure no face has a hole in it: the volume counts as a balloon
        // only while its sides and top are airtight, and one missing block turns the whole thing
        // into scenery that reports no lift and gives no clue why.
        fill(
            basket.offset(-SHELL, WALL, -SHELL),
            basket.offset(SHELL, CAP, SHELL),
            "aeronautics:white_envelope",
        )

        fill(
            basket.offset(-SHELL + 1, WALL, -SHELL + 1),
            basket.offset(SHELL - 1, CAP - 1, SHELL - 1),
            "minecraft:air",
        )

        // Cold. What lights it arrives after the balloon is a body, and until then this is a burner
        // standing in a basket.
        setBlock(basket.above(BURNER), "aeronautics:adjustable_burner[powered=false,variant=fire]")
    }

    companion object {
        /** How far the envelope and the basket reach from the middle, so both are 7 across. */
        const val SHELL = 3

        /** The deck, a block below the rig's own level, so the burner stands on the origin. */
        const val DECK = -1

        /** The burner's level: on the deck, inside the railing. */
        const val BURNER = 0

        /** Where the envelope starts, leaving the burner three blocks of clear air to fire into. */
        const val WALL = 4

        /** The envelope's cap, and the block the burner's ray is meant to find. */
        const val CAP = 13

        const val GROUND = 24

        /** Head-room over the stage floor: the balloon is fifteen blocks tall before it takes off. */
        const val SKY = 48

        const val SETTLE = 20

        /**
         * How long the burner is left running.
         *
         * The gas fills over about 180 ticks, and the basket and shell together weigh well under
         * half of what a full envelope lifts -- so it turns buoyant early in this and spends the
         * rest of it climbing.
         */
        const val FILL = 200

        /** Metres per second upwards. Below this the body is only settling. */
        const val RISING = 0.05

        /** Blocks. Enough that it has clearly left where it was standing. */
        const val ROSE_BY = 0.5

        const val VIEW_OUT = 18.0
        const val VIEW_UP = 6.0

        /** The pose sits at the basket; the balloon over it is what the picture should be about. */
        const val LOOK_UP = 6.0
        const val AIM = 10
    }
}
