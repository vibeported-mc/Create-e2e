package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
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

            // From inside the envelope, which is the only place the hot-air overlay can be seen.
            // It is the heated volume itself, drawn with the near surface culled away, so from
            // outside the envelope stands in front of it and the composite discards it on depth.
            // Filled much further before looking. The overlay's alpha is
            // clamp(Position.y - CutoffY, 0, 1) and CutoffY is (1 - filled) * (height + 1), so an
            // envelope that is a few percent full has a cutoff above its own roof and the whole
            // volume is discarded. That is the effect working, not failing -- there is no hot air
            // to show -- and it is why looking at a freshly lit balloon shows nothing at all.
            // Flown, not waited out.
            //
            // This used to burn a flat two minutes on the theory that the envelope needed that
            // long. What the test is actually waiting for is the balloon to leave the ground, and
            // that happens the moment lift passes weight -- which is sooner, and varies with how
            // fast the burner happens to be running. So it watches for the thing it cares about
            // and stops there.
            //
            // It also turns a hang into a diagnosis. A balloon that never rises used to look like
            // a slow test and then fail on an assertion two minutes later; now it says how high it
            // got and how long it was given.
            val liftedOff = riseTo(body, restingY + ROSE_BY)

            assertTrue(
                liftedOff,
                "The balloon never left the ground. It was given " + BRIM + " ticks to get from " +
                    restingY + " to " + (restingY + ROSE_BY) + " and reached " + poseOf(body).y +
                    ", with " + lift + " lift under it",
            )

            insideTheBalloon(body)
            shot("balloon_inside")

            // Read twice, a few ticks apart. A screenshot cannot answer either of the two
            // questions that matter about this effect -- the envelope is white and the
            // composite is a soft light, so "faint" and "absent" look the same -- and it cannot
            // tell a still picture from a moving one at all.
            val first = overlayCoverage()
            serverTicks(SCROLL_TICKS)
            val second = overlayCoverage()
            println("OVERLAY $first then $second")

            assertTrue(
                first.found,
                "The overlay framebuffer does not exist, so the effect never got as far as " +
                    "having somewhere to draw: " + first.failure,
            )

            assertTrue(
                first.covered > 0,
                "The overlay framebuffer is empty, so the heated volume was not drawn into it. " +
                    "Everything downstream of this -- the composite, the depth comparison -- " +
                    "has nothing to work with: " + first.failure,
            )

            // The texture scrolls with the game time, a sixteenth of a texel at a step, so two
            // reads a second apart are of different pictures. Equal is the shape of a uniform
            // that is set and never reaches the shader, which is a still overlay rather than a
            // missing one and is the harder of the two to notice.
            assertTrue(
                second.covered > 0,
                "The overlay was drawn once and then not again: " + first + " then " + second,
            )

            assertTrue(
                first.checksum != second.checksum,
                "The overlay drew the same pixels " + SCROLL_TICKS + " ticks apart, so it is " +
                    "not animating. Scroll is set every frame from the game time; a value that " +
                    "never reaches the shader looks exactly like this: " + first + " vs " + second,
            )

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

    /**
     * Puts the camera in the middle of the envelope, looking sideways.
     *
     * The envelope reaches from [WALL] to [CAP] above the basket and the body's pose sits lower
     * than that, so this climbs into it rather than aiming at it from outside.
     */
    private suspend fun Stage.insideTheBalloon(body: String) {
        val at = poseOf(body)

        spectateAt(
            Vec3(at.x, at.y + INSIDE_UP, at.z),
            Vec3(at.x + INSIDE_LOOK, at.y + INSIDE_UP, at.z),
            settle = AIM,
        )
    }

    /**
     * What the overlay's own framebuffer holds.
     *
     * Reached by reflection because the renderer keeps it private and static, which is right --
     * it is one buffer for the whole screen and nothing else has any business with it.
     *
     * The checksum is over the colour bytes rather than the count, because the volume's outline
     * barely moves as it scrolls: what changes is the texture across it, so a count of covered
     * pixels would be the same number twice and prove nothing.
     */
    private suspend fun Stage.overlayCoverage(): Overlay = client(watcher) {
        var found = false
        var covered = 0
        var checksum = 0
        var light = 0L
        var failure = ""

        try {
            val field = Class
                .forName("dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer")
                .getDeclaredField("overlayFbo")
            field.isAccessible = true
            val fbo = field.get(null)
                as? foundry.veil.api.client.render.framebuffer.AdvancedFbo

            if (fbo == null) {
                failure = "no overlay framebuffer has been built"
            } else {
                found = true
                val texture = fbo.getColorAttachment(0).gpuTextureView!!.texture()
                var index = 0
                failure = readBackAll(texture) { r, g, b, a ->
                    if (a != 0) {
                        covered++
                        // Colour, not alpha. The volume's shape is the same from one frame to the
                        // next and its alpha with it; what scrolling moves is the texture across
                        // that shape, which only the colour channels show.
                        checksum = checksum * 31 + r + g * 7 + b * 13 + a + index
                        light += r + g + b
                    }
                    index++
                } ?: ""
            }
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Overlay(
            found = found,
            covered = covered,
            checksum = checksum,
            brightness = if (covered == 0) 0 else (light / (covered * 3)).toInt(),
            failure = failure,
        )
    }

    @kotlinx.serialization.Serializable
    data class Overlay(
        val found: Boolean,
        val covered: Int,
        val checksum: Int,
        /** Mean of the colour channels over the drawn pixels: 0 is black, 255 is white. */
        val brightness: Int,
        val failure: String,
    ) {
        override fun toString(): String =
            "found=$found covered=$covered sum=$checksum light=$brightness" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    /**
     * Ticks until the balloon has climbed past [target], or gives up.
     *
     * Polled in chunks rather than tick by tick: each round trip to the server costs more than
     * the ticks it asks for, so asking one at a time would make the waiting slower than the
     * thing being waited for.
     *
     * @return whether it got there
     */
    private suspend fun Stage.riseTo(body: String, target: Double): Boolean {
        var waited = 0
        while (waited < BRIM) {
            serverTicks(STEP)
            waited += STEP
            if (poseOf(body).y > target) {
                return true
            }
        }
        return false
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

        /** Above the body's pose, into the envelope's hollow. */
        const val INSIDE_UP = 3.0

        /** Far enough sideways that the camera is looking across the volume, not at a wall. */
        const val INSIDE_LOOK = 4.0

        /**
         * Not twenty, and not any multiple of it.
         *
         * Scroll is {@code gameTime / -20}, so over twenty ticks it moves by exactly 1.0 -- and the
         * shader adds it straight to a UV of a repeating texture. A whole-number shift samples the
         * same texels, so twenty ticks apart the overlay is pixel-for-pixel identical however well
         * it is animating. Seven is a third of a period and shares no factor with twenty.
         */
        const val SCROLL_TICKS = 7

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

        /** Long enough for the envelope to be full enough that the overlay is not all cut off. */
        /** The cap on waiting for lift-off, not the time it is expected to take. */
        const val BRIM = 2400

        /** How many ticks to ask for between checks: a round trip costs more than the ticks do. */
        const val STEP = 40

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
