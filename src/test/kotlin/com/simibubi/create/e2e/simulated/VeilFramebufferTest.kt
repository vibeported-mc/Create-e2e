package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * That a Veil `AdvancedFbo` allocates real storage and can be cleared and read back.
 *
 * ## What an AdvancedFbo was, and what is left of it
 *
 * An OpenGL framebuffer object is two things at once: somewhere to keep attachments, and a piece of
 * global state saying where draws currently go. 26.2 keeps the first and deletes the second -- a
 * target is named when a render pass is opened, and there is no "currently bound framebuffer" for a
 * later draw to find.
 *
 * So off OpenGL the first half exists and the second throws. This asserts the first half: build one,
 * clear it to a colour nothing else would produce, and read the pixel back off the GPU.
 *
 * ## Why read a pixel rather than check it was built
 *
 * Because "built" is the easy half and it is not where this goes wrong. An attachment allocated with
 * the wrong format is built fine and fails later, somewhere else, with a message about a render pass.
 * The specific trap here is depth: the builder reaches the attachment with `GL_DEPTH_COMPONENT`, an
 * *unsized* format that OpenGL is allowed to interpret and `toGpuFormat` has no mapping for -- so the
 * obvious implementation allocates a depth attachment as `RGBA8_UNORM` and nothing says so until a
 * pass is opened against it.
 *
 * Clearing both attachments and reading the colour back exercises the whole chain: allocation,
 * format, and the clear path itself.
 *
 * ## Depth is cleared to zero on purpose
 *
 * 26.2 reverses the depth buffer -- near is 1, far is 0 -- so an empty depth buffer is 0 and the
 * test runs the other way round. `AdvancedFbo.clear()` with no arguments still passes 1.0F, because
 * that is what it meant on every version before this one. Clearing to 1 gives a buffer that is
 * entirely "nearest", and everything drawn into it afterwards fails the depth test and is invisible.
 */
@DrivesMinecraft
class VeilFramebufferTest {

    @Test
    @DisplayName("A Veil framebuffer clears to the colour it was asked for")
    fun `it allocates and clears`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val cleared = clearAndReadBack()
        println("VEIL FRAMEBUFFER $cleared")

        assertTrue(
            cleared.built,
            "The framebuffer was not built, so nothing below it ran: " + cleared.failure,
        )

        assertEquals(
            SIZE,
            cleared.width,
            "The framebuffer did not come back at the size it was asked for: " + cleared.failure,
        )

        assertTrue(
            cleared.hasDepth,
            "The framebuffer reports no depth attachment although one was asked for, so anything " +
                "depth-tested drawn into it would be wrong: " + cleared.failure,
        )

        assertTrue(
            cleared.readBack,
            "The colour attachment could not be read back, so there is no evidence it holds " +
                "anything at all: " + cleared.failure,
        )

        // Named channel by channel rather than as one packed integer, so a channel-order mistake
        // reads as "red is where blue should be" instead of as an unrecognisable number.
        val wrong = EXPECTED.withIndex().filter { (i, want) -> abs(cleared.pixel[i] - want) > SLACK }

        assertTrue(
            wrong.isEmpty(),
            "The cleared pixel is not the colour it was cleared to, so either the clear did not " +
                "reach this attachment or it was allocated in the wrong format. Got " +
                cleared.pixel.joinToString() + ", wanted " + EXPECTED.joinToString() +
                " (+/- " + SLACK + ")",
        )
    }

    /**
     * Builds a framebuffer, clears it, and copies one pixel back, in the game.
     *
     * The readback goes through `copyTextureToBuffer`, whose callback is queued behind a fence and
     * run from `RenderSystem.executePendingTasks`. Nothing here is inside a frame, so that pump has
     * to be turned by hand -- bounded, because a GPU that never signals should fail this test rather
     * than hang the client, which is the one failure mode this harness cannot recover from.
     */
    private suspend fun Stage.clearAndReadBack(): Cleared = client(watcher) {
        var built = false
        var width = 0
        var hasDepth = false
        var readBack = false
        val pixel = intArrayOf(0, 0, 0, 0)
        var failure = ""

        try {
            val fbo = foundry.veil.api.client.render.framebuffer.AdvancedFbo
                .withSize(SIZE, SIZE)
                .addColorTextureBuffer()
                .setDepthTextureBuffer()
                .build(true)

            built = true
            width = fbo.width
            hasDepth = fbo.hasDepthAttachment()

            // Depth to 0, which is "far" under a reversed depth buffer. The no-argument clear()
            // would pass 1.
            fbo.clear(
                COLOR[0], COLOR[1], COLOR[2], COLOR[3], 0.0f,
                fbo.clearMask,
            )

            val texture = fbo.getColorAttachment(0).gpuTextureView!!.texture()
            val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
            val encoder = device.createCommandEncoder()

            device.createBuffer(
                { "veil framebuffer test readback" },
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ or
                    com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
                (SIZE * SIZE * 4).toLong(),
            ).use { readback ->
                var done = false

                encoder.copyTextureToBuffer(texture, readback, 0L, {
                    readback.map(true, false).use { mapped ->
                        for (channel in 0 until 4) {
                            pixel[channel] = mapped.data().get(channel).toInt() and 0xFF
                        }
                    }
                    done = true
                }, 0)

                // Submitted repeatedly, not once. A fence records the submit index it was made
                // in, and `GlCommandEncoder.awaitSubmit` only reports it complete once the index
                // has moved on by two -- the encoder keeps two fence slots and indexes them by
                // submit. In a running game those two come free with the next two frames. Here
                // there are no frames, because this is holding the render thread, so they have
                // to be asked for.
                var spins = 0
                while (!done && spins < MAX_SUBMITS) {
                    encoder.submit()
                    com.mojang.blaze3d.systems.RenderSystem.executePendingTasks()
                    spins++
                }

                readBack = done
                if (!done) {
                    failure = "the readback never signalled after $MAX_SUBMITS submits"
                }
            }

            fbo.free()
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Cleared(
            built = built,
            width = width,
            hasDepth = hasDepth,
            readBack = readBack,
            pixel = pixel.toList(),
            failure = failure,
        )
    }

    @Serializable
    data class Cleared(
        val built: Boolean,
        val width: Int,
        val hasDepth: Boolean,
        val readBack: Boolean,
        val pixel: List<Int>,
        val failure: String,
    ) {
        override fun toString(): String =
            "built=$built ${width}px depth=$hasDepth read=$readBack pixel=${pixel.joinToString()}" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        /** Small on purpose: this is about the format and the clear, not about area. */
        const val SIZE = 16

        /**
         * Nothing here is 0 or 1, so a buffer that was never cleared and a buffer cleared to white
         * both fail rather than one of them passing by accident.
         */
        val COLOR = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)

        /** The same colour as bytes: 0.25 is 63.75, which rounds either way. */
        val EXPECTED = listOf(64, 128, 191, 255)

        /** One step of rounding in each direction, and no more. */
        const val SLACK = 2

        /**
         * Three would do -- the fence clears two submits after the one it was made in. Bounded
         * well above that and not open: a fence that never signals is a driver problem, and a
         * test that reports it beats a client that has to be killed by hand.
         */
        const val MAX_SUBMITS = 16
    }
}
