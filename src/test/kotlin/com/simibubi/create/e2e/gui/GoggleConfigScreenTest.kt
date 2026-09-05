package com.simibubi.create.e2e.gui

import com.simibubi.create.e2e.clickGui
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.infrastructure.config.AllConfigs
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The screen that says where the goggle overlay sits.
 *
 * It is the only screen here opened by a command rather than by holding, clicking or pressing
 * anything -- `/create overlay`. Clicking anywhere on it moves the overlay to that spot, and closing
 * the screen is what writes where it ended up into the client's own settings.
 *
 * So the test clicks off to one side and reads the offset back out of those settings, which is where
 * the overlay itself looks for it.
 *
 * Ported from Create's `GoggleConfigScreenTest`.
 */
@DrivesMinecraft
class GoggleConfigScreenTest {

    @Test
    @DisplayName("Clicking the goggle overlay to a new place is where it is remembered")
    fun `moves the overlay`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        // Back to where it started, so that the move has somewhere to move it from.
        playerCommand("create overlay reset")
        serverTicks(SETTLE_TICKS)

        val before = overlayOffsetX()

        playerCommand("create overlay")
        waitForScreenNamed("GoggleConfigScreen")
        shot("goggle_overlay_opened")

        // Put down off to one side of the middle, which is the whole of what this screen does.
        val middle = client(watcher) {
            Middle(minecraft.window.guiScaledWidth / 2, minecraft.window.guiScaledHeight / 2)
        }
        clickGui((middle.x + MOVED_BY).toDouble(), middle.y.toDouble())
        shot("goggle_overlay_moved")

        closeWithEscape()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        assertNotEquals(
            before, overlayOffsetX(),
            "Moving the overlay across the screen did not move where it is remembered",
        )
    }

    /** Typed by the player rather than run by the server, since it is the player's own screen it opens. */
    private suspend fun Stage.playerCommand(command: String) {
        client(watcher, command) { typed ->
            clientPlayer?.connection?.sendCommand(typed)
            awaitTicks(2)
        }
    }

    /** Where the client currently believes the overlay belongs. */
    private suspend fun Stage.overlayOffsetX(): Int = client(watcher) {
        AllConfigs.client().overlayOffsetX.get()
    }

    /**
     * The middle of the window.
     *
     * Two ints, wrapped, so one round trip answers both -- and because a pair is generic and a
     * generic type cannot be serialized for the wire.
     */
    @kotlinx.serialization.Serializable
    private data class Middle(val x: Int, val y: Int)

    private companion object {

        const val SETTLE_TICKS = 10

        /** How far off centre to put it, in the screen's own units. */
        const val MOVED_BY = 40
    }
}
