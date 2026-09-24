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
 * An earlier version of this test demanded that four named programs be *compiled* on both backends,
 * and was red on Vulkan by design. That demand turned out to rest on a false premise, so it is gone.
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
 * So after the End Sea fix the family has **no live consumer of a Veil shader program on Vulkan**,
 * and a Vulkan implementation of `ShaderProgram` would today have nothing to serve. What is left to
 * guard is the half that was built and does run on both backends: the *sources*. Processing them --
 * the version, the includes, the `#veil:buffer` blocks, and the loose uniforms gathered into one
 * std140 block by `ShaderUniformBlockProcessor` -- is parsing and text, and it runs anywhere. It is
 * also the half a Vulkan `ShaderProgram` will be built on, so a regression in it is worth catching
 * now rather than when something finally needs it.
 */
@DrivesMinecraft
class VeilShaderProgramsTest {

    @Test
    @DisplayName("Veil processes the family's shader program sources on either backend")
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
    }

    /**
     * Which of the wanted programs Veil knows about.
     *
     * Counted off the registry rather than through `getShader`, and the difference is the point.
     * `getShader` answers "is there a usable program", which is an OpenGL question and is null on
     * Vulkan by design. The registry answers "did the sources process", which is the thing this
     * test is about and is true on both.
     */
    private suspend fun Stage.shaderPrograms(): Loaded = client(watcher, Wanted(WANTED)) { wanted ->
        val registered = foundry.veil.api.client.render.VeilRenderSystem.renderer()
            .getShaderManager()
            .getShaders()

        val missing = wanted.names.filterNot { name ->
            registered.containsKey(net.minecraft.resources.Identifier.parse(name))
        }

        Loaded(total = registered.size, missing = missing)
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
    private data class Loaded(val total: Int, val missing: List<String>) {
        override fun toString(): String =
            "$total programs registered" +
                if (missing.isEmpty()) "" else ", missing ${missing.size}: ${missing.joinToString()}"
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
