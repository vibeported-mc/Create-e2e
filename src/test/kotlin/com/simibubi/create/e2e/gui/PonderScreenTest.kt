package com.simibubi.create.e2e.gui

import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.hoverWidget
import com.simibubi.create.e2e.openScreenName
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotBounds
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.PlayerMode
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.awaitScreen
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.runOnServer
import dev.vibeported.mc.driver.setPlayerMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ponder, opened the way a player opens it: hover an item in the inventory and hold the key.
 *
 * Worth a test of its own because nothing else in this suite goes near it. Ponder is a framework
 * rather than a feature -- what it does is play Create's own animated help for a block, in a level of
 * its own with real block entities ticking and real renderers drawing them -- so opening one scene
 * exercises a slab of client-side Create that no server-side assertion can reach. When something in
 * there breaks the mod still runs and only the help is wrong, which is exactly the sort of thing that
 * goes unnoticed.
 *
 * The water wheel, because it is a block whose whole point is that it turns: a scene that has laid one
 * down and set it going has got a long way through Create's own rendering.
 *
 * Two things about the key make this test what it is. It is *held* rather than pressed -- Ponder fills
 * a little bar over about a quarter of a second and opens when it is full -- and it is read off the
 * hardware keyboard rather than off the key mapping: `isKeyPressed` goes through
 * `InputConstants.isKeyDown(window, code)`, so nothing but a genuinely held key will do. That is what
 * the driver's own held keys exist for, and this is the second thing in the suite to need them.
 */
@DrivesMinecraft
class PonderScreenTest {

    @Test
    @DisplayName("Holding the ponder key over a water wheel opens its scene, and the scene plays out")
    fun `ponder opens on the water wheel`(cluster: ClusterScope) = cluster.stage {
        theClient()
        giveTheWaterWheel()
        openTheInventory()

        // Found by what it holds rather than by number: a hotbar slot is one number to the player and
        // another to the screen, and only the screen's own is any use for putting a cursor on it.
        hoverWidget(slotBounds(slotHolding(WATER_WHEEL)))
        shot("ponder_hovering")

        holdThePonderKey()

        assertEquals(
            PONDER_SCREEN, openScreenName(),
            "Holding the ponder key over a water wheel did not open its scene",
        )
        shot("ponder_opened")

        // And then watched all the way through, rather than for an arbitrary moment. A scene that
        // opens and stands still is a scene whose blocks were laid out and never ticked; one that
        // stops halfway is one that threw somewhere nobody was looking. Neither is visible in a
        // single picture, and both are visible in the count of where it got to.
        val opened = sceneProgress()
        assertTrue(opened.total > 0, "The scene says it has no length at all: $opened")

        var waited = 0
        var playing = opened

        while (waited < PATIENCE_TICKS && !playing.finished) {
            serverTicks(POLL_TICKS)
            waited += POLL_TICKS
            playing = sceneProgress()
        }

        shot("ponder_finished")

        assertTrue(
            playing.finished,
            "The ponder scene did not play to its end within ${PATIENCE_TICKS / 20} seconds: " +
                "it reached $playing, having started at $opened",
        )
        assertTrue(
            playing.current > opened.current,
            "The ponder scene reported itself finished without ever moving: $playing",
        )

        closeAnyScreen()
        restoreHud()

        assertNull(openScreenName(), "The ponder screen would not close")
    }

    /**
     * A water wheel in the player's hand.
     *
     * Survival, because the inventory key opens a different screen in each mode and only the survival
     * one has the slots this suite knows how to find. A borrowed client arrives in creative; nothing
     * puts it back, because the next test to borrow it is handed it cleaned.
     */
    private suspend fun Stage.giveTheWaterWheel() {
        setPlayerMode(watcher, PlayerMode.SURVIVAL)
        runOnServer("item replace entity $watcher hotbar.0 with $WATER_WHEEL 1")
        serverTicks(SETTLE_TICKS)
    }

    private suspend fun Stage.openTheInventory() {
        closeAnyScreen()
        client(watcher) {
            press(Key.E)
            awaitScreen(INVENTORY_SCREEN)
        }
    }

    /**
     * The key held until the scene opens, and let go of whatever happens.
     *
     * Released in a `finally` on purpose: a ponder key left down is a key the next test finds still
     * down, and what that looks like over there is a player walking forwards for no reason.
     */
    private suspend fun Stage.holdThePonderKey() {
        try {
            client(watcher) { keyDown(PONDER_KEY) }
            waitForScreenNamed(PONDER_SCREEN)
        } finally {
            client(watcher) { keyUp(PONDER_KEY) }
        }
    }

    /**
     * Where the scene has got to, asked of the scene rather than guessed from a picture.
     *
     * Two screenshots of something moving differ in ways nothing can assert on. A scene knows how
     * long it is, how far through it is, and whether it has finished, and all three come back
     * together so a failure can say which of them was wrong.
     */
    private suspend fun Stage.sceneProgress(): Progress = client(watcher) {
        val screen = minecraft.gui.screen() as? net.createmod.ponder.impl.client.gui.PonderUI
            ?: throw AssertionError(
                "The ponder screen closed while it was being watched; what is up now is " +
                    minecraft.gui.screen()?.javaClass?.simpleName
            )

        val scene = screen.activeScene
        Progress(scene.currentTime, scene.totalTime, scene.isFinished)
    }

    /** How far through a ponder scene is, in a shape that can cross a wire. */
    @kotlinx.serialization.Serializable
    private data class Progress(val current: Int, val total: Int, val finished: Boolean) {
        override fun toString(): String = "tick $current of $total" + if (finished) " (finished)" else ""
    }

    private companion object {

        const val WATER_WHEEL = "create:water_wheel"

        /** Ponder's own screen, which is what the key opens onto. */
        const val PONDER_SCREEN = "PonderUI"

        const val INVENTORY_SCREEN = "InventoryScreen"

        /** W by default, and read off the hardware keyboard rather than the key mapping. */
        val PONDER_KEY: Key = Key.W

        const val SETTLE_TICKS = 5

        /** How long a scene is given to play itself out. Create's are seconds rather than minutes. */
        const val PATIENCE_TICKS = 60 * 20

        /** How often it is asked how far it has got. */
        const val POLL_TICKS = 10
    }
}
