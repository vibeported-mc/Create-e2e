package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.serverTicks
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
    @DisplayName("A depth pyramid can be built from the level's own depth buffer")
    fun `a depth pyramid builds`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // Machinery, and not for the look of it. The pyramid is built by the renderer only when it
        // has instances to cull -- there is no reason to reduce a depth buffer nobody is going to
        // test against -- so an empty stage never builds one and the test reads nothing.
        clearGround(at(0, 0, 0), radius = 12)
        setBlock(at(0, 2, 0), "create:creative_motor[facing=east]")
        setBlock(at(1, 2, 0), "create:shaft[axis=x]")
        setBlock(at(2, 2, 0), "create:large_cogwheel[axis=x]")

        spectateAt(middleOf(at(2, 4, 5)), middleOf(at(2, 2, 0)))
        serverTicks(40)

        val result = depthPyramidTest()

        println("PYRAMID graphics=${result.graphics} passed=${result.passed} " +
            "| ${result.lines.joinToString(" | ")}")

        assertTrue(
            result.passed,
            "A depth pyramid could not be built, so occlusion culling has nothing to read. This is " +
                "the question it exists to answer -- whether the level's depth texture can be " +
                "sampled at all, and whether a mip chain can be reduced by fragment passes when " +
                "there are no compute-writable images: ${result.lines.joinToString(" | ")}",
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

    @Test
    @DisplayName("A compute-written indirect draw puts the right picture on a texture")
    fun `indirect drawing works on this backend`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = indirectTest()

        println("INDIRECT graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "A compute-written indirect draw did not produce the expected picture on " +
                "${result.graphics}. This is the mechanism the whole GPU-driven backend rests on " +
                "-- the draw commands are written by a shader and consumed by the GPU, and the " +
                "only evidence either was right is the pixel that comes out: " +
                "${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("Two models, many instances, one indirect call, both halves right")
    fun `multi model indirect drawing works`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = multiDrawTest()

        println("MULTIDRAW graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "Two models drawn in one indirect call did not come out right on ${result.graphics}. " +
                "This is the shape the engine actually needs -- models sharing one vertex buffer, " +
                "one index buffer and one instance buffer, each finding its geometry by offset " +
                "and its instances by gl_DrawID -- and every part of it fails silently: " +
                "${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("The GPU chooses which instances to draw, and draws exactly those")
    fun `gpu culling works`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = cullTest()

        println("CULL graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "GPU culling did not produce the right picture on ${result.graphics}. Each model has " +
                "three instances in three colours and exactly one survives, a different one each " +
                "-- so keeping everything, compacting to the wrong index, or reading InstanceData " +
                "with gl_InstanceID directly all come out the wrong colour rather than merely the " +
                "wrong count: ${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("A pipeline can be built from shader source generated at runtime")
    fun `generated shaders reach the pipeline`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = generatedShaderTest()

        println("GENERATED graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "A pipeline built from runtime-generated shader source did not draw on " +
                "${result.graphics}. Flywheel's vertex shaders are assembled per instance type, " +
                "so none of them can be a file -- and unlike the OpenGL backend, a RenderPipeline " +
                "names its shaders by Identifier and Minecraft resolves them. If this fails the " +
                "backend has no way to express its shaders at all: ${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("An instance survives the round trip through generated unpacking")
    fun `instance layouts round trip`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = layoutTest()

        println("LAYOUT graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "An instance written by Flywheel's own writer did not come back out of the generated " +
                "unpacking intact on ${result.graphics}. Offsets, sign extension, normalisation " +
                "divisors and endianness all fail here -- and all of them fail invisibly anywhere " +
                "else, because wrong instance data does not crash or warn, it draws the right " +
                "number of things slightly wrong: ${result.lines.joinToString(" | ")}",
        )
    }

    @Test
    @DisplayName("The shader assembled for each real instance type compiles")
    fun `generated instance pipelines compile`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = pipelineTest()

        println("PIPELINE graphics=${result.graphics} passed=${result.passed}")

        assertTrue(
            result.passed,
            "A shader Flywheel assembled for a real instance type would not compile on " +
                "${result.graphics}. Every instance type gets its own, built from its layout and " +
                "a body a mod supplied, so this is a runtime question and the answer differs per " +
                "backend -- Vulkan runs the same GLSL through shaderc to SPIR-V and rebinds it " +
                "against the declared layout: ${result.lines.joinToString(" | ")}",
        )
    }

    /** Builds a depth pyramid on the client, which is what occlusion culling would read. */
    private suspend fun dev.vibeported.mc.driver.Stage.depthPyramidTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.DepthPyramidSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Asks the client to assemble and compile a pipeline per instance type. */
    private suspend fun dev.vibeported.mc.driver.Stage.pipelineTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.PipelineSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own instance layout round-trip on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.layoutTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.LayoutSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own generated-shader self-test on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.generatedShaderTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.GeneratedShaderSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own GPU culling self-test on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.cullTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.CullSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own multi-model indirect self-test on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.multiDrawTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.MultiDrawSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
        )
    }

    /** Runs Flywheel's own indirect-draw self-test on the client. */
    private suspend fun dev.vibeported.mc.driver.Stage.indirectTest(): SelfTest = client(watcher) {
        val result = dev.engine_room.flywheel.backend.engine.blaze.IndirectDrawSelfTest.run()

        SelfTest(
            passed = result.passed,
            available = true,
            compute = "n/a",
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            lines = result.lines,
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

private fun middleOf(pos: net.minecraft.core.BlockPos) =
    net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
