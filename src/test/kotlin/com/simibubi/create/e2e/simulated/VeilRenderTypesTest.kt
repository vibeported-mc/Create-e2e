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

/**
 * That every render type Veil builds can actually be drawn through.
 *
 * These exist because of a bug that produced no error of any kind. On 26.2 a `RenderPipeline` binds a
 * uniform block or a sampler only if it declared one as a `BindGroupLayout`: `GlProgram` resolves the
 * declared names in the linked program and records the bindings, and `GlCommandEncoder` binds nothing
 * it has no record of. Veil's builder declared none, so every render type it built drew with
 * `ModelViewMat` reading as zero -- every vertex collapsing to the origin -- and with its textures
 * unbound. Issued, validated, and invisible.
 *
 * Four names survive that anyway, because `GlProgram.BUILT_IN_UNIFORMS` binds `Projection`,
 * `Lighting`, `Fog` and `Globals` whether or not a pipeline asks for them. `DynamicTransforms` is not
 * among them, and it is the block holding `ModelViewMat`. Samplers have no such list at all.
 *
 * So the assertions are on what the pipeline declares rather than on a picture. A screenshot test is
 * the obvious way to catch "nothing was drawn" and a poor one here: these are markers and overlays a
 * few pixels across, drawn over a world that moves. What actually separates the broken state from the
 * working one is one list on the pipeline, and reading it is exact.
 *
 * The types this covers were invisible together and fixed together, and none of them had been seen
 * working when it was written.
 */
@DrivesMinecraft
class VeilRenderTypesTest {

    @Test
    @DisplayName("Every Veil-built render type declares the transform block it draws with")
    fun `they declare dynamic transforms`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val types = declarationsOf(SIMULATED_RENDER_TYPES)

        assertTrue(
            types.isNotEmpty(),
            "No render types were reported at all, so this is asserting nothing. They are reached " +
                "reflectively, so the likely cause is a renamed class or accessor",
        )

        val undeclared = types.filterNot { it.uniforms.contains(DYNAMIC_TRANSFORMS) }

        assertTrue(
            undeclared.isEmpty(),
            "These declare no " + DYNAMIC_TRANSFORMS + ", so ModelViewMat reads as zero and they " +
                "draw nothing at all, silently: " +
                undeclared.joinToString { it.name + " has " + it.uniforms },
        )
    }

    @Test
    @DisplayName("Every Veil-built render type compiles into a usable pipeline")
    fun `their pipelines compile`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // The other half of the same failure mode. Declarations being right does not help if the
        // shader did not survive linking, and that surfaces as an invalid compiled pipeline rather
        // than as a throw -- the draw is skipped and the frame carries on without it.
        val broken = declarationsOf(SIMULATED_RENDER_TYPES).filterNot { it.compiles }

        assertTrue(
            broken.isEmpty(),
            "These have no usable shader program, so nothing drawn through them appears: " +
                broken.joinToString { it.name },
        )
    }

    @Test
    @DisplayName("Each render type declares exactly the samplers its shader reads")
    fun `they declare their samplers`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // Named per type rather than asserted as a blanket rule, because a blanket rule is wrong
        // here: the laser is a pure vertex-colour effect whose shader samples nothing, and demanding
        // a sampler of it fails on correct code. What matters is that a type reading a texture
        // declares it -- samplers have no built-in fallback, so an undeclared one binds to nothing
        // and the shader reads whatever is on texture unit 0.
        val byName = declarationsOf(SIMULATED_RENDER_TYPES).associateBy { it.name }
        val wrong = EXPECTED_SAMPLERS.filter { (name, expected) ->
            byName[name]?.samplers?.toSet() != expected.toSet()
        }

        assertTrue(
            wrong.isEmpty(),
            "These do not declare the samplers their shaders read, so those textures never reach " +
                "them: " + wrong.keys.joinToString {
                    it + " declares " + byName[it]?.samplers + ", expected " + EXPECTED_SAMPLERS[it]
                },
        )
    }

    /**
     * What each named render type's pipeline declares, and whether it compiles.
     *
     * Resolved in the game rather than here, because this process holds none of these mods. Each
     * name is a class and a static accessor returning a `RenderType`.
     */
    private suspend fun Stage.declarationsOf(names: List<String>): List<Declared> =
        client(watcher, Names(names)) { asked ->
            Declarations(asked.names.mapNotNull { name ->
                val split = name.lastIndexOf('#')
                val holder = Class.forName(name.substring(0, split))
                val method = runCatching { holder.getDeclaredMethod(name.substring(split + 1)) }
                    .getOrNull() ?: return@mapNotNull null

                method.isAccessible = true
                val type = runCatching { method.invoke(null) }.getOrNull()
                    as? net.minecraft.client.renderer.rendertype.RenderType ?: return@mapNotNull null

                val layouts = type.pipeline().getBindGroupLayouts()

                Declared(
                    name = name.substringAfterLast('#'),
                    uniforms = com.mojang.blaze3d.pipeline.BindGroupLayout.flattenUniforms(layouts)
                        .map { it.name() },
                    samplers = com.mojang.blaze3d.pipeline.BindGroupLayout.flattenSamplers(layouts),
                    compiles = com.mojang.blaze3d.systems.RenderSystem.getDevice()
                        .precompilePipeline(type.pipeline()).isValid,
                )
            })
        }.declared

    /**
     * The names, and the answers, each wrapped in a class of their own.
     *
     * Both cross between processes, and the driver's compiler plugin will not carry a bare generic:
     * only the class survives to the lookup, so a `List<Declared>` would be encoded as the wrong
     * thing. It says so at compile time rather than at run time, which is the point of it.
     */
    @Serializable
    data class Names(val names: List<String>)

    @Serializable
    data class Declarations(val declared: List<Declared>)

    @Serializable
    data class Declared(
        val name: String,
        val uniforms: List<String>,
        val samplers: List<String>,
        val compiles: Boolean,
    )

    companion object {
        /**
         * The block holding `ModelViewMat`, and the one name `GlProgram.BUILT_IN_UNIFORMS` does not
         * cover.
         */
        const val DYNAMIC_TRANSFORMS = "DynamicTransforms"

        private const val SIM_TYPES = "dev.simulated_team.simulated.index.SimRenderTypes"

        /**
         * Named one by one rather than swept reflectively, so a type quietly disappearing is a
         * failure rather than a shorter list nobody notices.
         */
        /**
         * What each type's shader actually reads.
         *
         * `Sampler0` is its texture and `Sampler2` the lightmap, which a type asks for with
         * `useLightmap()`. The laser has neither: it is drawn from vertex colour alone.
         */
        val EXPECTED_SAMPLERS = mapOf(
            "lock" to listOf("Sampler2", "Sampler0"),
            "laser" to emptyList(),
            "lens" to listOf("Sampler2", "Sampler0"),
            "rope" to listOf("Sampler2", "Sampler0"),
        )

        val SIMULATED_RENDER_TYPES = listOf(
            SIM_TYPES + "#lock",
            SIM_TYPES + "#laser",
            SIM_TYPES + "#lens",
            SIM_TYPES + "#rope",
        )
    }
}
