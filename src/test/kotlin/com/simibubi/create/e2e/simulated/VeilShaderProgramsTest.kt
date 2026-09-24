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
 * Veil's own shader programs, which are a different thing from its render types.
 *
 * ## Two shader systems
 *
 * A Veil *render type* names its shaders and is drawn through Minecraft's own `RenderPipeline`.
 * Those work on both backends -- `VeilRenderTypesTest` holds them to it.
 *
 * A Veil *shader program* is Veil compiling a program itself, from a JSON under
 * `pinwheel/shaders/program`, and handing it back for a mod to set uniforms on by name. That is raw
 * OpenGL: `glCreateProgram`, `glCompileShader`, uniform locations queried after linking, and a
 * default uniform block that Vulkan does not have at all.
 *
 * ## What this asserts, and what it deliberately does not
 *
 * An earlier version of this test demanded that four named programs be compiled *and drawn with* on
 * both backends, and was red on Vulkan by design. The drawing half rested on a false premise.
 * Each of the four, checked one at a time:
 *
 * - `simulated:end_sea` was fetched by `EndSeaRenderer` and then not drawn with. The sea is drawn
 *   through `SimRenderTypes.endSea()`, whose shader is `shaders/core/end_sea`, and the two uniforms
 *   being set -- `ShadowVolumeSize` and `StartY` -- are declared only by the *program's* fragment
 *   shader, part of the parked shadow-map path. The sets were dead on both backends. The fetch was
 *   worse than dead on Vulkan: the null check under it removed the sea from the picture. Both are
 *   now gone, and `VeilVertexArrayTest` covers the path that actually draws it.
 * - `aeronautics:hot_air_overlay` and `aeronautics:soft_light` are reached only from
 *   `ClientBalloonEffectRenderer`, which returns early off OpenGL several statements before it asks
 *   for a program -- it draws into an `AdvancedFbo`, a raw framebuffer object with no counterpart
 *   here. Compiling the programs would not move that by one pixel; rebuilding Veil's framebuffers
 *   on `GpuTexture` would.
 * - `aeronautics:levitite` is reached only from `SodiumWorldRendererMixin`, which is registered in
 *   no mixin config on 26.2.
 *
 * So the family has no *direct* live consumer of a Veil shader program on Vulkan. It has an
 * indirect one: `BlitPostStage` runs a program, so every post pipeline is one, and both blocked
 * paths above go through post-processing. What they are blocked on is the framebuffer, not this.
 *
 * What this asserts is therefore the two halves that exist. That the sources process -- the
 * version, the includes, the `#veil:buffer` blocks, the loose uniforms gathered into one std140
 * block -- and that what comes out compiles into a usable program. Off OpenGL that second half is
 * a `RenderPipeline` built from those sources, which is the only check anywhere that the GLSL Veil
 * emits is legal SPIR-V: blocks, samplers, bindings, vertex format and all.
 *
 * It does not assert that anything can be *drawn* through one. `BlazeShaderProgram.bind()` throws,
 * because binding a program outside a render pass is not something 26.2 has and every consumer
 * here is written as bind-then-draw. That comes with the first consumer that needs it.
 */
@DrivesMinecraft
class VeilShaderProgramsTest {

    @Test
    @DisplayName("Veil builds the family's shader programs on either backend")
    fun `veil processes its shader program sources`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val loaded = shaderPrograms()
        println("VEIL SHADERS $loaded")

        assertTrue(
            loaded.total > 0,
            "Veil registered no shader programs at all. Registration happens once a program's " +
                "sources are parsed and processed, which is backend-independent, so none at all " +
                "means the reload threw or was skipped: $loaded",
        )

        // Named rather than counted, because a count can be met by the wrong programs.
        assertTrue(
            loaded.missing.isEmpty(),
            "These were not registered, so their sources did not survive processing: " +
                loaded.missing.joinToString(),
        )

        assertTrue(
            loaded.broken.isEmpty(),
            "These registered but did not compile into a usable program, so the GLSL Veil emits " +
                "for them is not legal on this backend: " + loaded.broken.joinToString(),
        )
    }

    /**
     * Which of the wanted programs Veil knows about.
     *
     * Read off the registry rather than through `getShader`, and the difference is the point.
     * `getShader` still answers null off OpenGL, deliberately: it is the switch that decides
     * whether every `if (shader == null) return` in Veil and the mods above it starts proceeding,
     * and it should be thrown when there is something for them to proceed into. The registry is
     * the same map without that decision attached.
     */
    private suspend fun Stage.shaderPrograms(): Loaded = client(watcher, Wanted(WANTED)) { wanted ->
        val registered = foundry.veil.api.client.render.VeilRenderSystem.renderer()
            .getShaderManager()
            .getShaders()

        val wantedIds = wanted.names.associateWith { net.minecraft.resources.Identifier.parse(it) }

        val missing = wanted.names.filterNot { registered.containsKey(wantedIds[it]) }

        // isValid rather than a null check. Off OpenGL a program is a RenderPipeline, and a
        // pipeline whose shaders did not compile is not absent -- it is present and invalid, and
        // drawing through one is skipped rather than refused.
        val broken = wanted.names.filter { name ->
            val program = registered[wantedIds[name]]
            program != null && !program.isValid
        }

        Loaded(total = registered.size, missing = missing, broken = broken)
    }

    /**
     * The list, wrapped.
     *
     * Both this and [Loaded] cross between processes, and the driver's compiler plugin will not
     * carry a bare generic: only the class survives to the lookup, so a `List<String>` would be
     * encoded as the wrong thing. It says so at compile time, which is the point of it.
     */
    @Serializable
    private data class Wanted(val names: List<String>)

    @Serializable
    private data class Loaded(
        val total: Int,
        val missing: List<String>,
        val broken: List<String>,
    ) {
        override fun toString(): String =
            "$total programs registered" +
                (if (missing.isEmpty()) "" else ", missing ${missing.size}: ${missing.joinToString()}") +
                (if (broken.isEmpty()) "" else ", broken ${broken.size}: ${broken.joinToString()}")
    }

    private companion object {
        /**
         * The programs this family ships, by the identifier they are registered under.
         *
         * A deliberately short list. Veil ships twenty-one of its own -- deferred lights, bloom,
         * the debug viewers, the necromancer, quasar's particles -- and nothing in Sable,
         * Simulated or Aeronautics asks for any of them, so they are out of scope for this port
         * and out of scope for this test.
         */
        val WANTED = listOf(
            "simulated:end_sea",
            "simulated:spread_end_sea",
            "aeronautics:hot_air_overlay",
            "aeronautics:soft_light",
        )
    }
}
