package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That Flywheel can run a compute shader, on whichever graphics backend the run started with.
 *
 * Blaze3D 26.2 has no compute of any kind -- `ShaderType` is `{VERTEX, FRAGMENT}`, `UniformType` is
 * `{UNIFORM_BUFFER, TEXEL_BUFFER}`, there is no storage usage bit, no dispatch on `CommandEncoder`
 * and no memory barrier. Flywheel's indirect backend needs all four, and supplies them itself for
 * both OpenGL and Vulkan. This is the test that says whether that layer is real.
 *
 * It is worth its own test rather than being folded into the renderer's, because it fails in a
 * place no rendering assertion can see. The shader is compiled, dispatched, waited on and read
 * back; a failure in any of that is a wrong number here, whereas the same failure reached through
 * the renderer is an empty screen with nothing in the log.
 *
 * Both backends run the same shader through the same call, which is the entire point of the layer
 * beneath it -- on OpenGL via `glDispatchCompute`, on Vulkan via a pipeline and command buffer of
 * Flywheel's own, because `VulkanRenderPipeline` hardcodes two shader stages and calls
 * `vkCreateGraphicsPipelines`.
 *
 * ```
 * ./gradlew test --tests '*ComputeSelfTestTest*' -Pgraphics=opengl --rerun
 * ./gradlew test --tests '*ComputeSelfTestTest*' -Pgraphics=vulkan --rerun
 * ```
 */
@DrivesMinecraft
class ComputeSelfTestTest {

    @Test
    @DisplayName("A compute shader compiles, dispatches, and reads back what it wrote")
    fun `compute works on this backend`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = selfTest()

        // Reported before the assertion so a pass says which backend it passed on. A green run that
        // silently fell back to OpenGL would otherwise be indistinguishable from a green Vulkan one.
        println("COMPUTE graphics=${result.graphics} compute=${result.compute} passed=${result.passed}")

        assertTrue(
            result.available,
            "Flywheel reports no compute backend at all on ${result.graphics}. Its indirect " +
                "renderer cannot run without one, so this is the thing to fix before anything " +
                "downstream of it: ${result.lines.joinToString(" | ")}",
        )
        assertTrue(
            result.passed,
            "Compute ran on ${result.graphics} but did not produce what it wrote. The shader " +
                "writes `i * 2 + 1` precisely so that zeros -- which is what a freshly allocated " +
                "buffer reads as, and so what a dispatch that never happened looks like -- cannot " +
                "pass: ${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("A storage buffer keeps its contents when it grows")
    fun `storage buffers survive a resize`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = bufferTest()

        println("BUFFER graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "A Flywheel storage buffer lost its contents across a resize on ${result.graphics}. " +
                "Growing is a GPU copy, and Blaze3D -- unlike glCopyNamedBufferSubData -- checks " +
                "that the source declares COPY_SRC, the destination COPY_DST, and that the two " +
                "slices are the same length. An arena that loses this drops every object in it " +
                "the first time a world grows past its initial capacity, and does so far from " +
                "here: ${result.lines.joinToString(" | ")}",
        )
    }

    /** Runs Flywheel's own buffer self-test on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.bufferTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.BufferSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own self-test on the client, and brings back what it said. */
    private suspend fun dev.vibeported.mc.driver.Stage.selfTest(): SelfTest = client(watcher) {
        val backend = dev.engine_room.flywheel.backend.compute.Compute.backend()
        val result = dev.engine_room.flywheel.backend.compute.ComputeSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = backend.available(),
            compute = backend.name(),
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    @Serializable
    private data class SelfTest(
        val passed: Boolean,
        val available: Boolean,
        val compute: String,
        val graphics: String,
        val lines: List<String>,
    )
}
