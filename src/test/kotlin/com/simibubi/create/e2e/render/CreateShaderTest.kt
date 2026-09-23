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
 * That the shader Flywheel assembles for each of **Create's** instance types compiles.
 *
 * Flywheel's own test covers Flywheel's four types, which is not the same question. Create declares
 * its own, and their shader bodies are written against an ABI that the assembler has to supply in
 * full -- a single identifier a body names and the generated preamble does not declare is a shader
 * that will not compile, and an instance type that then silently never draws.
 *
 * That is not hypothetical. `flw_renderTicks` was missing while `flw_renderSeconds` was present, so
 * rotating machines drew perfectly and every belt in the world was invisible, with nothing in the
 * game to say why. This test exists so the next one of those is a named compile error here rather
 * than a missing machine somebody notices in a screenshot.
 *
 * It is a compile check, not a picture: it asks the device to build each pipeline and reports which
 * would not. Whether the picture is then right is [BackendParityTest]'s question.
 */
@DrivesMinecraft
class CreateShaderTest {

    @Test
    @DisplayName("Every Create instance type assembles into a pipeline that compiles")
    fun `create instance shaders compile`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = compileAll()

        println("CREATESHADERS graphics=${result.graphics} ok=${result.ok} failed=${result.failed}")

        assertTrue(
            result.failed.isEmpty(),
            "These Create instance types produced a shader that will not compile on " +
                "${result.graphics}, so nothing of those types draws at all: ${result.failed}. " +
                "The driver's complaint and the generated source are in the client log",
        )
        assertTrue(
            result.ok > 0,
            "No Create instance types were checked, so this asserted nothing. They are reached " +
                "reflectively, so the likely cause is a renamed class or field",
        )
    }

    /**
     * Reflectively, because Create's instance types are public static fields on a class this
     * module can name but whose set changes as Create grows -- and a hand-written list is one that
     * stops covering the type somebody added last week.
     */
    private suspend fun dev.vibeported.mc.driver.Stage.compileAll(): Compiled = client(watcher) {
        val failed = mutableListOf<String>()
        var ok = 0

        val holder = Class.forName("com.simibubi.create.foundation.render.AllInstanceTypes")

        for (field in holder.declaredFields) {
            if (!dev.engine_room.flywheel.api.instance.InstanceType::class.java
                    .isAssignableFrom(field.type)
            ) {
                continue
            }

            field.isAccessible = true
            val type = field.get(null) as dev.engine_room.flywheel.api.instance.InstanceType<*>

            val stride = dev.engine_room.flywheel.lib.math.MoreMath.align16(type.layout().byteSize())

            try {
                // Every fog and cutout the backend can compile in, not just one pair. They are
                // compiled *into* the fragment shader, because on 26.2 a pipeline carries its
                // shaders -- so "the rotating shader compiles" is a statement about one combination
                // out of twelve, and the one that breaks is the one nobody built.
                val fogs = dev.engine_room.flywheel.lib.material.FogShaders::class.java
                    .declaredFields
                    .filter { dev.engine_room.flywheel.api.material.FogShader::class.java.isAssignableFrom(it.type) }
                    .map { it.isAccessible = true; (it.get(null) as dev.engine_room.flywheel.api.material.FogShader).source() }
                val cutouts = dev.engine_room.flywheel.lib.material.CutoutShaders::class.java
                    .declaredFields
                    .filter { dev.engine_room.flywheel.api.material.CutoutShader::class.java.isAssignableFrom(it.type) }
                    .map { it.isAccessible = true; (it.get(null) as dev.engine_room.flywheel.api.material.CutoutShader).source() }

                var shaders = dev.engine_room.flywheel.backend.engine.blaze.BlazeShaders
                    .generate(type, stride, fogs.first(), cutouts.first())
                var valid = true

                for (fog in fogs) {
                    for (cutout in cutouts) {
                        shaders = dev.engine_room.flywheel.backend.engine.blaze.BlazeShaders
                            .generate(type, stride, fog, cutout)
                        val pipeline = dev.engine_room.flywheel.backend.engine.blaze.PipelineSelfTest
                            .pipelineFor(shaders)

                        if (!com.mojang.blaze3d.systems.RenderSystem.getDevice()
                                .precompilePipeline(pipeline).isValid
                        ) {
                            failed.add("${field.name} (fog $fog, cutout $cutout)")
                            valid = false
                            break
                        }
                    }
                    if (!valid) break
                }

                if (valid) {
                    // The cull shader too: it is generated from the same layout plus a second
                    // body the mod supplies, and a type whose culler will not build is a type
                    // that draws nothing once culling is on.
                    val cull = dev.engine_room.flywheel.backend.compute.Compute.backend()
                        .createPipeline(
                            dev.engine_room.flywheel.backend.compute.ComputePipeline.Description.of(
                                "cull " + field.name,
                                dev.engine_room.flywheel.backend.engine.blaze.CullShaders
                                    .generate(type, stride),
                            ),
                        )

                    if (cull == null) {
                        failed.add(field.name + " (cull shader)")
                    } else {
                        cull.close()
                        ok++
                    }
                } else {
                    failed.add(field.name)
                    dev.engine_room.flywheel.backend.FlwBackend.LOGGER.error(
                        "FLWSOURCE-BEGIN {}",
                        dev.engine_room.flywheel.backend.engine.blaze.GeneratedShaders
                            .get(shaders, com.mojang.blaze3d.shaders.ShaderType.VERTEX),
                    )
                }
            } catch (e: Exception) {
                failed.add(field.name + " (" + e + ")")
            }
        }

        Compiled(
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            ok = ok,
            failed = failed,
        )
    }

    @Serializable
    private data class Compiled(val graphics: String, val ok: Int, val failed: List<String>)
}
