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
 * ## Two shader systems, and only one of them works
 *
 * A Veil *render type* names its shaders and is drawn through Minecraft's own `RenderPipeline`.
 * Those compile on both backends -- `VeilRenderTypesTest` holds them to it.
 *
 * A Veil *shader program* is Veil compiling a program itself, from a JSON under
 * `pinwheel/shaders/program`, and handing it back for a mod to set uniforms on by name. That is raw
 * OpenGL: `glCreateProgram`, `glCompileShader`, uniform locations queried after linking. None of it
 * exists on Vulkan, so the whole step is short-circuited there and `getShader` returns null.
 *
 * Measured, on one scene, same Veil, both backends: **33 programs on OpenGL and none on Vulkan**.
 *
 * ## What is actually lost
 *
 * The mods are written defensively -- `if (shader == null) return;` -- so nothing crashes and
 * nothing in the suite goes red. Features are simply absent: Simulated's End Sea, its contraption
 * diagram outline, Aeronautics' hot-air overlay and soft light, and Sable's fancy sub-level
 * dispatcher, which is the path taken when Sodium is not installed.
 *
 * That silence is why this test exists. Every other test here would pass with all of it missing.
 */
@DrivesMinecraft
class VeilShaderProgramsTest {

    @Test
    @DisplayName("Veil compiles the shader programs the family draws with")
    fun `veil loads its shader programs`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val loaded = shaderPrograms()
        println("VEIL SHADERS $loaded")

        assertTrue(
            loaded.total > 0,
            "Veil compiled no shader programs at all, so every feature drawn through one is absent " +
                "-- the End Sea, the contraption diagram's outline, the hot-air overlay, and " +
                "Sable's fancy sub-level renderer. Nothing fails loudly when this happens, because " +
                "each of those checks for null and returns: $loaded",
        )

        // Named rather than counted, because a count can be met by the wrong programs. These are the
        // ones the family actually draws with.
        assertTrue(
            loaded.missing.isEmpty(),
            "Veil did not compile these, so what each draws is missing from the picture: " +
                loaded.missing.joinToString(),
        )
    }

    private suspend fun Stage.shaderPrograms(): Loaded = client(watcher, Wanted(WANTED)) { wanted ->
        val manager = foundry.veil.api.client.render.VeilRenderSystem.renderer()
            .getShaderManager()

        val missing = wanted.names.filter { name ->
            manager.getShader(net.minecraft.resources.Identifier.parse(name)) == null
        }

        // Counted through getShader rather than off the registry. A program is registered once
        // its sources are processed, which happens on either backend now, and compiled only on
        // OpenGL -- so the registry's size says how many were *seen*, and answers 31 on a backend
        // where none of them works. Asking the way a mod asks is the only honest count.
        val usable = wanted.names.size - missing.size

        Loaded(total = usable, missing = missing)
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
            "$total of the family's programs usable" +
                if (missing.isEmpty()) "" else ", missing ${missing.size}: ${missing.joinToString()}"
    }

    private companion object {
        /**
         * The programs this family draws with, by the identifier the mod asks for.
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

