package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * That a Veil shader program can actually draw, with a uniform set by name reaching the shader.
 *
 * ## The whole chain, in one test
 *
 * Everything the other Veil tests check separately has to work at once for this to pass: the sources
 * process, the loose uniform is gathered into a std140 block, the block's offsets are computed, the
 * program compiles into a pipeline, a framebuffer allocates real storage, a pass opens against it,
 * the block is uploaded and bound, and the draw lands. If the pixel is the colour that was asked
 * for, all of it happened.
 *
 * It is also the first thing here that proves a *value* set by name arrives. `VeilShaderProgramsTest`
 * proves the GLSL compiles; a block laid out at the wrong offsets compiles perfectly and reads
 * whatever its neighbour holds.
 *
 * ## Why a shader of this test's own
 *
 * The family's four programs cannot do this. `soft_light` and `hot_air_overlay` sample framebuffers
 * that do not exist outside the frame that fills them, and `end_sea` wants block geometry. A shader
 * that fills the screen from one uniform and reads no textures fails for one reason only, which is
 * the point of a test.
 *
 * Its vertex stage is Veil's own `blit_screen`, which builds an oversized triangle out of
 * `gl_VertexID` and needs no vertex buffer -- the same one every post-processing blit uses, so this
 * is the post-processing draw path with the framebuffer plumbing left out.
 *
 * ## The same call on both backends
 *
 * `VeilScreenQuad.draw` is one call because off OpenGL it has to be: the output is an argument to
 * the render pass and the program is a pipeline set on it, and both last exactly as long as the
 * pass. On OpenGL it is still the three pieces of global state it always was. Running the same
 * line on both is the point -- a colour that arrives on one backend and not the other is a
 * difference in this port, not in the test.
 */
@DrivesMinecraft
class VeilShaderDrawTest {

    @Test
    @DisplayName("A Veil shader program fills a framebuffer with a colour set by name")
    fun `it draws with a uniform`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val drawn = drawSolidColour()
        println("VEIL SHADER DRAW $drawn")

        assertTrue(
            drawn.compiled,
            "create_e2e:solid_colour did not compile into a usable program, so nothing below it " +
                "ran: " + drawn.failure,
        )

        assertTrue(
            drawn.hasUniform,
            "The program has no uniform called Colour. It declares one, so either the block " +
                "rewrite dropped it or its layout was not recorded: " + drawn.failure,
        )

        assertTrue(
            drawn.drew,
            "The draw did not complete: " + drawn.failure,
        )

        val wrong = EXPECTED.withIndex().filter { (i, want) -> abs(drawn.pixel[i] - want) > SLACK }

        assertTrue(
            wrong.isEmpty(),
            "The framebuffer does not hold the colour the uniform was set to. Got " +
                drawn.pixel.joinToString() + ", wanted " + EXPECTED.joinToString() +
                ". A pixel of 0,0,0,0 means the draw never reached the attachment; anything else " +
                "means the uniform block reached the shader at the wrong offset: " + drawn.failure,
        )
    }

    private suspend fun Stage.drawSolidColour(): Drawn = client(watcher) {
        var compiled = false
        var hasUniform = false
        var drew = false
        val pixel = intArrayOf(0, 0, 0, 0)
        var failure = ""

        try {
            val program = foundry.veil.api.client.render.VeilRenderSystem.renderer()
                .getShaderManager()
                .getShaders()[net.minecraft.resources.Identifier.parse(SHADER)]

            if (program == null) {
                failure = "the program is not registered at all"
            } else if (!program.isValid) {
                failure = "the program registered but did not compile"
            } else {
                compiled = true
                hasUniform = program.hasUniform("Colour")
                program.getUniform("Colour")?.setVector(COLOR[0], COLOR[1], COLOR[2], COLOR[3])

                val fbo = foundry.veil.api.client.render.framebuffer.AdvancedFbo
                    .withSize(SIZE, SIZE)
                    .addColorTextureBuffer()
                    .build(true)

                // Cleared to something the draw must overwrite, so "the draw did nothing" and "the
                // draw wrote the right colour" cannot be confused.
                fbo.clear(1.0f, 0.0f, 1.0f, 1.0f, 0.0f, fbo.clearMask)

                val texture = fbo.getColorAttachment(0).gpuTextureView!!.texture()

                foundry.veil.api.client.render.VeilDraw.screenQuad(fbo, program)
                drew = true

                readBack(texture, pixel)?.let { failure = it }

                fbo.free()
            }
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Drawn(
            compiled = compiled,
            hasUniform = hasUniform,
            drew = drew,
            pixel = pixel.toList(),
            failure = failure,
        )
    }

    @Serializable
    data class Drawn(
        val compiled: Boolean,
        val hasUniform: Boolean,
        val drew: Boolean,
        val pixel: List<Int>,
        val failure: String,
    ) {
        override fun toString(): String =
            "compiled=$compiled uniform=$hasUniform drew=$drew pixel=${pixel.joinToString()}" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        const val SHADER = "create_e2e:solid_colour"
        const val SIZE = 16

        /** Nothing here is 0 or 1, and none of it is the magenta the target is cleared to. */
        val COLOR = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)
        val EXPECTED = listOf(64, 128, 191, 255)
        const val SLACK = 2
    }
}


/**
 * Copies the first pixel back off the GPU, or says why it could not.
 *
 * Submits repeatedly rather than once: a fence records the submit index it was made in and clears
 * only once that index has moved on by two, which in a running game comes free with the next two
 * frames. This holds the render thread, so there are none.
 */
private fun readBack(texture: com.mojang.blaze3d.textures.GpuTexture, into: IntArray): String? {
    val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
    val encoder = device.createCommandEncoder()

    device.createBuffer(
        { "veil shader draw test readback" },
        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ or
            com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
        (16 * 16 * 4).toLong(),
    ).use { readback ->
        var done = false

        encoder.copyTextureToBuffer(texture, readback, 0L, {
            readback.map(true, false).use { mapped ->
                for (channel in 0 until 4) {
                    into[channel] = mapped.data().get(channel).toInt() and 0xFF
                }
            }
            done = true
        }, 0)

        var spins = 0
        while (!done && spins < 16) {
            encoder.submit()
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks()
            spins++
        }

        return if (done) null else "the readback never signalled"
    }
}
