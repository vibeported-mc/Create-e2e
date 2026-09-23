package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That the clients are running on the graphics backend the run asked for.
 *
 * This exists because asking is not getting. `PreferredGraphicsApi.getBackendsToTry` returns the
 * requested backend *and* the other one, and `Minecraft`'s startup loop takes the first that builds
 * a device -- so a client told `--graphicsBackend vulkan` that cannot start Vulkan comes up on
 * OpenGL, plays perfectly, and says so only in a log line nobody reads. Every other test in a
 * Vulkan run would then pass while testing OpenGL, which is worse than failing.
 *
 * The usual cause is not the flag. FML's early loading screen creates a GL context on the window
 * before Minecraft chooses a backend, and Vulkan needs `GLFW_NO_API`; `earlyWindowControl` lives in
 * `config/fml.toml` and nowhere else -- no system property, no launch argument -- so the driver
 * writes it when it seeds a client directory. A run that regressed to OpenGL is usually a client
 * directory that got its `fml.toml` from somewhere else.
 *
 * Run the suite either way with `-Pgraphics=vulkan`; unset, this only reports what it found.
 */
@DrivesMinecraft
class GraphicsBackendTest {

    @Test
    @DisplayName("The client runs on the backend the build asked for")
    fun `the requested backend is the live one`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val live = client(watcher) {
            com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName
        }

        assertTrue(live.isNotBlank(), "The device reported no backend name at all")

        // Set by the build from -Pgraphics. Absent means the run expressed no preference, and then
        // there is nothing to hold it to -- whatever Minecraft chose is correct by definition.
        val asked = System.getProperty("e2e.graphics")
        if (asked.isNullOrBlank()) {
            println("Graphics backend: $live (no -Pgraphics given)")
            return@stage
        }

        assertEquals(
            asked.lowercase(),
            live.lowercase(),
            "The build asked for '$asked' and the client came up on '$live'. Minecraft falls back " +
                "rather than failing, so this is a backend that would not start -- check that " +
                "earlyWindowControl is false in the client's config/fml.toml, and read the game " +
                "log for BackendCreationException and its Reason",
        )
    }
}
