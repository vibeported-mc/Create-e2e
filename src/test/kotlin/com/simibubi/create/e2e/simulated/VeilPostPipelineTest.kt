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
 * That a Veil post-processing pipeline carries a picture from one framebuffer to another.
 *
 * ## Why this is the test that matters for the two features left
 *
 * Both things this family still wanted on Vulkan go through post-processing. The hot-air balloon
 * draws its overlay into a framebuffer and composites it with `aeronautics:soft_light`; the
 * contraption diagram renders a sub-level into a framebuffer and outlines it with
 * `simulated:diagram`. Neither is reachable from a test without building the scene it needs, and
 * both fail for the same handful of reasons if the post path is wrong.
 *
 * So this runs the path with the scene taken out: a pipeline of one blit stage, a shader that copies
 * its input, and two framebuffers this test owns. Everything underneath is the real thing -- the
 * framebuffer manager resolving names, `BlitPostStage` setting up the program, the input framebuffer
 * bound as `DiffuseSampler0`, and the draw opening a pass against the output.
 *
 * ## What a failure means
 *
 * The input is cleared to one colour and the output to another, so the three outcomes are
 * distinguishable rather than all being "wrong pixel": the output colour means it worked, the
 * output's own clear colour means the stage did not draw, and black means the sampler read nothing.
 */
@DrivesMinecraft
class VeilPostPipelineTest {

    @Test
    @DisplayName("A post pipeline copies one framebuffer into another")
    fun `it runs a blit stage`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val ran = runCopyPipeline()
        println("VEIL POST $ran")

        assertTrue(
            ran.found,
            "The post pipeline create_e2e:copy was not loaded, so nothing below it ran: " +
                ran.failure,
        )

        assertTrue(
            ran.ran,
            "Running the pipeline failed: " + ran.failure,
        )

        val wrong = SOURCE_BYTES.withIndex().filter { (i, want) -> abs(ran.pixel[i] - want) > SLACK }

        assertTrue(
            wrong.isEmpty(),
            "The output framebuffer does not hold what the input did. Got " +
                ran.pixel.joinToString() + ", wanted " + SOURCE_BYTES.joinToString() +
                ". " + TARGET_BYTES.joinToString() + " means the stage never drew; 0, 0, 0, 0 " +
                "means it drew but DiffuseSampler0 read nothing: " + ran.failure,
        )
    }

    private suspend fun Stage.runCopyPipeline(): Ran = client(watcher) {
        var found = false
        var ran = false
        val pixel = intArrayOf(0, 0, 0, 0)
        var failure = ""

        var input: foundry.veil.api.client.render.framebuffer.AdvancedFbo? = null
        var output: foundry.veil.api.client.render.framebuffer.AdvancedFbo? = null

        try {
            val manager = foundry.veil.api.client.render.VeilRenderSystem.renderer()
                .getPostProcessingManager()
            val pipeline = manager.getPipeline(net.minecraft.resources.Identifier.parse(PIPELINE))

            if (pipeline == null) {
                failure = "no such pipeline"
            } else {
                found = true

                input = framebuffer()
                output = framebuffer()

                input.clear(SOURCE[0], SOURCE[1], SOURCE[2], SOURCE[3], 0.0f, input.clearMask)
                output.clear(TARGET[0], TARGET[1], TARGET[2], TARGET[3], 0.0f, output.clearMask)

                val context = manager.postPipelineContext
                context.setFramebuffer(net.minecraft.resources.Identifier.parse(IN), input)
                context.setFramebuffer(net.minecraft.resources.Identifier.parse(OUT), output)

                // False, so the pipeline does not try to resolve into the game's post target
                // afterwards -- this is not running inside a frame and there is nothing to resolve to.
                manager.runPipeline(pipeline, false)
                ran = true

                readBack(output.getColorAttachment(0).gpuTextureView!!.texture(), pixel)
                    ?.let { failure = it }
            }
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        } finally {
            input?.free()
            output?.free()
        }

        Ran(found = found, ran = ran, pixel = pixel.toList(), failure = failure)
    }

    @Serializable
    data class Ran(
        val found: Boolean,
        val ran: Boolean,
        val pixel: List<Int>,
        val failure: String,
    ) {
        override fun toString(): String =
            "found=$found ran=$ran pixel=${pixel.joinToString()}" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        const val PIPELINE = "create_e2e:copy"
        const val IN = "create_e2e:post_in"
        const val OUT = "create_e2e:post_out"

        /** What the input holds, and so what the output should end up holding. */
        val SOURCE = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)
        val SOURCE_BYTES = listOf(64, 128, 191, 255)

        /** What the output holds beforehand. Nothing like the input, so the two cannot be confused. */
        val TARGET = floatArrayOf(1.0f, 0.0f, 1.0f, 1.0f)
        val TARGET_BYTES = listOf(255, 0, 255, 255)

        const val SLACK = 2
    }
}

private fun framebuffer(): foundry.veil.api.client.render.framebuffer.AdvancedFbo =
    foundry.veil.api.client.render.framebuffer.AdvancedFbo
        .withSize(16, 16)
        .addColorTextureBuffer()
        .build(true)
