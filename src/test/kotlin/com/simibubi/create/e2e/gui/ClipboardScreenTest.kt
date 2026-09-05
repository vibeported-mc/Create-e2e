package com.simibubi.create.e2e.gui

import com.simibubi.create.content.equipment.clipboard.ClipboardEntry
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A clipboard, which is written on in a screen and keeps what was written on the item itself.
 *
 * So this writes a line, closes, and asks the server what the item says -- and then opens the same
 * clipboard again, which fills the screen from the item rather than from anything the first screen
 * left behind, and is what proves the whole round trip rather than only the sending half of it.
 *
 * Ported from Create's `ClipboardScreenTest`.
 */
@DrivesMinecraft
class ClipboardScreenTest {

    @Test
    @DisplayName("What is typed onto a clipboard is on the item, and is there again when it is reopened")
    fun `keeps what was typed`(cluster: ClusterScope) = cluster.driving {
        // Ground of its own and a look at open sky. A clipboard opens on a plain right-click at
        // whatever the crosshair is on, so left standing where the last test finished it opens that
        // test's screen instead -- which is what a blueprint still hanging on a wall did here.
        clearGround(here(), 4)
        standAt(
            Vec3(here().x + 0.5, here().y.toDouble(), here().z + 0.5),
            Vec3(here().x + 0.5, here().y + 30.0, here().z + 0.5),
        )

        holdItem("create:clipboard")
        serverTicks(SETTLE_TICKS)

        // A clipboard opens on a plain right-click; sneaking at one places it as a block instead.
        rightClickAhead()

        waitForScreenNamed("ClipboardScreen")
        shot("clipboard_opened")

        typeText(WRITTEN)
        serverTicks(SETTLE_TICKS)
        shot("clipboard_typed")

        close()

        val lines = linesOnTheItem()
        assertTrue(
            WRITTEN in lines.values,
            "What was typed onto the clipboard did not reach the item, which reads ${lines.values}",
        )

        rightClickAhead()
        waitForScreenNamed("ClipboardScreen")
        shot("clipboard_reopened")

        // Read off the reopened screen, in a body that can hold the real screen and call the real
        // reader -- only the strings come back.
        val shown = client(ALEX) {
            val screen = minecraft.gui.screen() as ClipboardScreen
            Lines(
                ClipboardEntry.readAll(screen.content)
                    .flatten()
                    .map { it.text.string },
            )
        }

        close()
        restoreHud()

        assertEquals(
            listOf(WRITTEN), shown.values,
            "The clipboard did not open again on what was written on it",
        )
    }

    /** The screen's own close button, rather than escape, since that is the one a player reaches for. */
    private suspend fun close() {
        clickWidget("closeBtn")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
    }

    /** Every line of every page of the clipboard in the player's hand, read on the server. */
    private suspend fun linesOnTheItem(): Lines = server(ALEX) { name ->
        Lines(
            ClipboardEntry.readAll(playerNamed(name).mainHandItem)
                .flatten()
                .map { it.text.string },
        )
    }

    /**
     * Lines of a clipboard, wrapped.
     *
     * A `List<String>` cannot cross on its own -- a generic type erases to its class, so the
     * serializer looked up for it would encode the wrong thing and the compiler refuses it. A record
     * holding one is not generic, and travels.
     */
    @Serializable
    private data class Lines(val values: List<String>)

    private fun here() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.CLIPBOARD

        const val SETTLE_TICKS = 5

        /** Long enough for the edit to have been sent and taken. */
        const val SEND_TICKS = 20

        const val WRITTEN = "sixteen andesite"
    }
}
