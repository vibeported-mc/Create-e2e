package com.simibubi.create.e2e.gui

import com.simibubi.create.AllDataComponents
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.screenFieldClass
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.sneakRightClick
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * The Wand of Symmetry's screen, which picks the kind of mirror the wand works with and how it is
 * turned.
 *
 * Unlike the other held-item screens, this one does not write to the stack in the player's hand and
 * leave it at that: the copy the screen was handed is a client one, so it also sends what was chosen
 * to the server. That makes the server-side item the only thing worth asserting on -- a screen that
 * set itself up correctly but stopped sending would still leave the wand doing nothing.
 *
 * Which mirror the screen ends up on is read off the screen and held against the item, rather than
 * named here, so this stays true if the list of mirrors is ever reordered or added to.
 *
 * Ported from Create's `SymmetryWandScreenTest`. The original compared two `SymmetryMirror` objects
 * directly; here one is on a client and one is on a server, so what is compared is the class name
 * and the orientation -- the facts about them, since the objects themselves cannot cross.
 *
 * Ordered first on purpose: this one brings the dedicated server down, so whatever runs after it is
 * a standing check that the driver noticed, said so, and started the games again.
 */
@Order(1)
@DrivesMinecraft
class SymmetryWandScreenTest {

    @Test
    @DisplayName("The mirror the symmetry wand's screen was set to reaches the wand on the server")
    fun `configures the mirror`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        holdItem("create:wand_of_symmetry")
        serverTicks(SETTLE_TICKS)

        sneakRightClick()
        waitForScreenNamed("SymmetryWandScreen")
        shot("symmetry_wand_opened")

        // One mirror further down the list, which turns the single plane into the crossed pair, and
        // then one alignment further down -- the crossed pair is the mirror that has more than one.
        scrollWidget(widget("areaType"), -1)
        scrollWidget(widget("areaAlign"), -1)
        shot("symmetry_wand_configured")

        // Picking a mirror builds a new one, so this is read after the scrolling rather than before.
        val shownKind = screenFieldClass("currentElement")
        val shownOrientation = client(watcher) {
            val mirror = com.simibubi.create.e2e.readField(
                minecraft.gui.screen(), "currentElement",
            ) as com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror
            mirror.orientationIndex
        }

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        val onTheWand = heldKind()

        assertNotNull(onTheWand, "Closing the screen did not leave a mirror on the wand at all")
        assertEquals(
            shownKind, onTheWand,
            "The mirror the screen showed is not the kind the wand was left with",
        )
        assertEquals(
            shownOrientation, heldOrientation(),
            "The alignment the screen showed did not reach the wand",
        )
    }

    /** The kind of mirror on the wand in the player's hand, on the server, where the wand really lives. */
    private suspend fun Stage.heldKind(): String? = server(watcher) { name ->
        playerNamed(name).mainHandItem.get(AllDataComponents.SYMMETRY_WAND)?.javaClass?.name
    }

    private suspend fun Stage.heldOrientation(): Int = server(watcher) { name ->
        playerNamed(name).mainHandItem.get(AllDataComponents.SYMMETRY_WAND)?.orientationIndex ?: -1
    }

    private companion object {
        const val SETTLE_TICKS = 5
    }
}
