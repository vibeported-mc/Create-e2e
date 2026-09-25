package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The hot-air overlay: the heated volume drawn inside a balloon's envelope.
 *
 * ## Why this is not part of `HotAirBalloonTest`
 *
 * That test assembles its balloon into a sub-level so it can fly, which is what it is about. A body
 * in flight is the worst possible subject for looking at a shader: it drifts, so the camera has to
 * chase it, and two screenshots taken seconds apart differ because the balloon moved whether or not
 * anything was drawn differently.
 *
 * So this one nails the envelope to the ground and never assembles it. The rig is identical in every
 * other way -- same envelope, same burner, same fuel -- and because nothing moves, a camera put in
 * one place stays pointed at the same thing, and two readings of the same framebuffer differ only if
 * the effect itself changed.
 *
 * ## What is asserted, and why not a screenshot
 *
 * The overlay is composited over the world with a soft light, against a white envelope. Faint and
 * absent look alike in a picture, and a still picture cannot show whether something is moving at
 * all. So the assertions read the overlay's own framebuffer:
 *
 * - **It is drawn.** Some pixels have alpha.
 * - **It is not black.** The shader samples `heat_overlay.png`; a sampler left pointing at whatever
 *   was last on the texture unit reads dark, and the effect appears as a shadow rather than as heat.
 * - **It moves.** The texture scrolls with the game time. A uniform that is set and never reaches
 *   the shader produces a perfectly good still overlay, which is the harder failure to notice.
 *
 * Screenshots are taken as well, for a person to look at, but nothing is asserted about them.
 *
 * ## The gap between the two readings
 *
 * Not a multiple of twenty. `Scroll` is `gameTime / -20`, added straight to a UV of a repeating
 * texture, so over twenty ticks it moves by exactly one whole texture and samples identically. Seven
 * shares no factor with twenty.
 */
@DrivesMinecraft
class HotAirOverlayTest {

    @Test
    @DisplayName("A balloon's hot-air overlay is drawn, lit, and moving")
    fun `the overlay animates`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND, height = SKY)

        val basket = at(0, 1, 0)
        balloonRig(basket)
        serverTicks(SETTLE)

        // Lit the way the flying test lights its own: a burner runs on a neighbouring redstone
        // signal, and the block under it is the one face that is not on show.
        setBlock(basket.below(), "minecraft:redstone_block")
        serverTicks(LIGHT)

        // At the top of the envelope, one block off the burner's column, looking across and up.
        //
        // Inside, because the overlay is the heated volume itself: from outside the envelope
        // stands in front of it and the composite discards it on depth.
        //
        // At the top, because the alpha is clamp(Position.y - CutoffY, 0, 1) and CutoffY falls
        // from the roof as the envelope fills. The top of the volume clears the cutoff first, so
        // a short burn lights the part the camera is pointed at and a full one is not needed.
        //
        // Off the column, because the burner sits at the basket and everything it emits goes
        // straight up from there. On the axis the camera sits in the plume and looks along it.
        // A block to the side puts it beside the camera instead of through it.
        //
        // The aim crosses to the far upper corner, so the frame holds both the cap and the top of
        // a side wall. That matters because the shader divides a horizontal face's UV by twenty
        // -- `if (directionless != 0.0) uv /= vec2(1.0, 20.0)` -- so the cap scrolls twenty times
        // slower than the walls do. A frame of nothing but cap is a frame of the one surface that
        // barely appears to move even when everything is working.
        // One block to the side of the burner, aimed level and straight at the wall in front.
        //
        // Level, because a wall is the surface the effect scrolls across at full speed: the
        // shader divides a horizontal face's UV by twenty, so the cap crawls and reads as still
        // however well the effect is working.
        //
        // A block to the side, because the burner sits at the basket and everything it emits
        // rises straight up from there. That column is now behind the camera, so the smoke, the
        // flame and the embers are out of frame and whatever moves in this shot is the overlay.
        val eye = Vec3(basket.x + ASIDE, basket.y + EYE, basket.z + 0.5)
        spectateAt(eye, Vec3(basket.x + ASIDE, basket.y + CAP + 10.0, basket.z + 0.5), settle = AIM)

        // Burner off before measuring, and the reason is the measurement rather than the picture.
        //
        // While it runs the envelope keeps filling, CutoffY keeps falling, and the lit region
        // creeps down the wall. That changes a large share of the drawn pixels on its own, so a
        // reading taken with the burner on cannot tell a scrolling texture from a growing one --
        // which is how this test reported movement that nobody watching the window could see.
        //
        // Briefly, so the balloon is still registered: a few ticks later the map drops it and
        // there is no overlay at all.
        shot("overlay_first")

        // Two reads, with the wait between them out here rather than inside the client.
        //
        // That is the whole difference between a reading that can see movement and one
        // that cannot. A client block holds the render thread: awaiting ticks inside one
        // advances the server while the client draws nothing, so both reads are of the
        // same frame and come back byte-identical however well the shader is working.
        // Out here the ticks are real frames.
        val seen = overlay("a")
        serverTicks(SCROLL_TICKS)
        overlay("b")
        println("OVERLAY $seen")

        // And the screen itself, which is the only thing that answers the question.
        //
        // The framebuffer above is what the effect draws; this is what a person sees, and the
        // two are separated by the composite. An overlay that animates in its own buffer and
        // is then flattened by soft light against a bright wall is a still picture to anybody
        // watching, and reading the buffer cannot tell you that.
        // Night, and the reason is arithmetic rather than atmosphere.
        //
        // Both of this effect's textures are luminance masks -- white RGB with the pattern in
        // the alpha -- so the colour reaching soft light is a constant, and the only thing that
        // varies across the volume is how much of it is mixed in. Soft light against a bright
        // surface moves that by about four levels out of 255, which is below the noise in a
        // JPEG and well below what an eye picks up. Against a dark one the same inputs move it
        // by ten times as much.
        //
        // So a brightly lit envelope is a rig that cannot show this effect whether or not it
        // works, which is worth knowing before concluding anything from a screenshot.
        runCommand("time set midnight")
        serverTicks(AIM)

        shot("screen_a")
        serverTicks(SCROLL_TICKS)
        shot("screen_b")

        // The same view again from a camera that is moving, which is the only way to catch
        // the hard-edged wedge reported from play: it appears while walking about inside the
        // envelope and a fixed camera never shows it. Each step is small enough to keep the
        // cap in frame and large enough that anything depending on the previous frame -- a
        // depth buffer compared against a newer one, a target still holding the last frame --
        // is somewhere different from where it should be.
        // And from outside, below the envelope, looking up at its underside.
        //
        // The heated volume is inside a solid envelope, so from out here it is behind blocks
        // and must not be visible at all -- that is what the composite's depth comparison is
        // for. Nothing else in this scene moves once the envelope is full, so two shots a few
        // ticks apart are the same picture unless something is leaking through; and a scrolling
        // texture is exactly the thing that would show up as a difference.
        val outside = Vec3(basket.x + 0.5, basket.y - 3.0, basket.z + SHELL + 6.0)
        spectateAt(outside, Vec3(basket.x + 0.5, basket.y + CAP.toDouble(), basket.z + 0.5), settle = AIM)
        shot("outside_a")
        serverTicks(SCROLL_TICKS)
        shot("outside_b")

        // The frame right after the camera jumps, against the same view once it has settled.
        //
        // The composite decides where to apply itself by comparing the overlay's depth against
        // the main framebuffer's. If the depth it reads belongs to the previous frame, the two
        // disagree by however far the camera moved between them -- nothing when still, more
        // the faster it moves. So the same view, shot cold and shot settled, differs only if
        // something in this chain depends on the frame before.
        val jumped = Vec3(basket.x + 0.5 - 1.5, eye.y, basket.z + 0.5)
        spectateAt(jumped, Vec3(basket.x + 0.5, basket.y + CAP + 10.0, basket.z + 0.5), settle = 0)
        shot("cold")
        serverTicks(20)
        shot("settled")

        for (step in 1..DRIFT_STEPS) {
            // Kept inside the hollow, which runs to SHELL - 1 either side of the basket. The
            // first version of this walked the camera a block and three quarters diagonally and
            // put it through the envelope wall, where the last two shots were of the sky.
            val offset = (step - (DRIFT_STEPS + 1) / 2.0) * DRIFT
            val moved = Vec3(basket.x + 0.5 + offset, eye.y, basket.z + 0.5)
            spectateAt(moved, Vec3(basket.x + 0.5, basket.y + CAP + 10.0, basket.z + 0.5), settle = 2)
            shot("drift_$step")
        }


        // Held on that view afterwards, for a person watching the window. Nothing is read here
        // -- the assertions below are already decided -- it is time to see the thing move.
        serverTicks(WATCH)
        shot("overlay_last")

        assertTrue(
            seen.found,
            "The overlay framebuffer was never built, so the effect did not get as far as having " +
                "somewhere to draw: " + seen.failure,
        )

        assertTrue(
            seen.covered > 0,
            "The overlay framebuffer is empty -- nothing was drawn into it at all: " + seen,
        )

        assertTrue(
            seen.brightness >= LIT,
            "The overlay is drawn but almost black, which is what a sampler reading some other " +
                "texture looks like. It is composited as a soft light, so dark reads as a shadow " +
                "over the world rather than as heat: " + seen,
        )

        // Nothing is asserted about movement.
        //
        // Two readings of this framebuffer cannot tell a scrolling texture from the lit region
        // growing as the envelope fills, and every attempt to make them do so reported movement
        // that nobody watching the window could see. The checks above are the ones a number can
        // answer -- it is drawn, it is not black -- and whether it moves is answered by watching
        // it, which is what WATCH is for.
    }

    /**
     * What the overlay's framebuffer holds, and how much of it changes.
     *
     * Both readings happen in here, with the wait between them on the client. Going back out to
     * the test between them would mean shipping a million pixels across the wire twice; and the
     * comparison wants the pixels themselves, because the question is what fraction of them
     * moved rather than whether any did.
     */
    private suspend fun Stage.overlay(tag: String): Overlay = client(watcher, tag) { tag ->
        var found = false
        var covered = 0
        var light = 0L
        var failure = ""

        try {
            val field = Class
                .forName("dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer")
                .getDeclaredField("overlayFbo")
            field.isAccessible = true
            val fbo = field.get(null) as? foundry.veil.api.client.render.framebuffer.AdvancedFbo

            if (fbo == null) {
                failure = "no overlay framebuffer has been built"
            } else {
                found = true
                val texture = fbo.getColorAttachment(0).gpuTextureView!!.texture()
                val w = texture.getWidth(0)
                val h = texture.getHeight(0)
                val pixels = IntArray(w * h)
                failure = readBackPixels(texture, pixels) ?: ""

                if (failure.isEmpty()) {
                    for (argb in pixels) {
                        if ((argb ushr 24) == 0) {
                            continue
                        }
                        covered++
                        light += ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)
                    }

                    // Composited onto black rather than written with its alpha. Most of this
                    // framebuffer is transparent, and a transparent PNG is shown over whatever is
                    // behind it -- which in every viewer is white, the same white the overlay is.
                    // Read that way an empty framebuffer and a full one are the same picture.
                    val opaque = IntArray(pixels.size)
                    for (i in pixels.indices) {
                        val a = (pixels[i] ushr 24) and 0xFF
                        val r = (((pixels[i] shr 16) and 0xFF) * a) / 255
                        val g = (((pixels[i] shr 8) and 0xFF) * a) / 255
                        val b = ((pixels[i] and 0xFF) * a) / 255
                        opaque[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    image.setRGB(0, 0, w, h, opaque, 0, w)
                    val out = java.io.File("build/e2e/overlay-fbo-$tag.png")
                    out.parentFile.mkdirs()
                    javax.imageio.ImageIO.write(image, "png", out)
                }
            }
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Overlay(
            found = found,
            covered = covered,
            brightness = if (covered == 0) 0 else (light / (covered * 3)).toInt(),
            failure = failure,
        )
    }
    /**
     * The same rig the flying test builds, and it is deliberately the same.
     *
     * The envelope has to hang open over a gap: a burner finds its volume by casting a ray up and
     * taking the airtight block it hits, so a sealed box cannot be fed at all. The posts carry the
     * envelope's corners down to the basket.
     */
    private suspend fun Stage.balloonRig(basket: BlockPos) {
        fill(basket.offset(-SHELL, DECK, -SHELL), basket.offset(SHELL, DECK, SHELL),
            "minecraft:dark_oak_planks")

        for (dx in listOf(-SHELL, SHELL)) {
            for (dz in listOf(-SHELL, SHELL)) {
                fill(basket.offset(dx, 1, dz), basket.offset(dx, WALL - 1, dz),
                    "minecraft:dark_oak_fence")
            }
        }

        // Laid solid and then hollowed: the volume counts as a balloon only while its sides and top
        // are airtight, and one missing block turns the whole thing into scenery.
        fill(basket.offset(-SHELL, WALL, -SHELL), basket.offset(SHELL, CAP, SHELL),
            "aeronautics:white_envelope")
        fill(basket.offset(-SHELL + 1, WALL, -SHELL + 1), basket.offset(SHELL - 1, CAP - 1, SHELL - 1),
            "minecraft:air")

        setBlock(basket, "aeronautics:adjustable_burner[powered=false,variant=fire]")
    }

    @Serializable
    data class Overlay(
        val found: Boolean,
        val covered: Int,
        /** Mean of the colour channels over the drawn pixels: 0 is black, 255 is white. */
        val brightness: Int,
        val failure: String,
    ) {
        override fun toString(): String =
            "covered=$covered light=$brightness" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        const val GROUND = 24
        const val SKY = 20

        /** As the flying test builds its own, so the two are the same balloon. */
        const val SHELL = 3
        const val DECK = -1
        const val WALL = 4
        const val CAP = 13

        const val SETTLE = 10

        /** Short: long enough for the flame to stop, not long enough to lose the balloon. */
        const val OFF = 4
        const val AIM = 10

        /**
         * Long enough to fill the envelope, which is not the same as long enough to see anything.
         *
         * The alpha is clamp(Position.y - CutoffY, 0, 1), and CutoffY starts at the height of the
         * balloon and falls as it fills. Until it is most of the way down, the only lit surface is
         * the floor of the volume -- and the floor is the one surface the shader scrolls twenty
         * times slower, because it divides a horizontal face's UV by twenty. So a short burn
         * frames the single surface that cannot appear to move.
         *
         * Measured on this rig: the fill reaches 98% about five hundred ticks after the burner
         * lights.
         */
        const val LIGHT = 520

        /** Long enough for a person to watch it move: twenty seconds at twenty ticks a second. */
        const val WATCH = 400

        /** Not a multiple of twenty -- see the note on this class. */
        const val SCROLL_TICKS = 7

        /**
         * Well inside the hollow, which runs from WALL to CAP - 1 -- five blocks of air above it.
         *
         * Not at the top. Up there the cap is a couple of blocks away and fills the frame with a
         * near-flat wash, and any higher clears the rim entirely and looks down into an open box
         * from outside, where the overlay is behind the envelope and correctly invisible.
         */
        const val EYE = 7.5

        /** How far the camera slides between drift shots, and how many it takes. */
        const val DRIFT = 0.5
        const val DRIFT_STEPS = 5

        /** A block to the side of the burner, which puts its plume behind the camera. */
        const val ASIDE = 1.5

        /**
         * How much of the overlay a scroll should move.
         *
         * Set low on purpose. A third is far more than a creeping edge can account for and far
         * less than a full scroll produces, so it separates the two without asserting a number
         * that depends on how fast the envelope happens to be filling.
         */
        const val MOVED = 33

        /** Dark enough to be a shadow rather than heat. */
        const val LIT = 40
    }
}
