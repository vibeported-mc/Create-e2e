package com.simibubi.create.e2e.gui

import com.simibubi.create.AllDataComponents
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.itemInSlot
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.sneakRightClick
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The linked controller's screen: twelve ghost slots, two to each of the six keys it can send on.
 *
 * The frequencies are put in the way a player puts them in -- an item picked up out of the player's
 * own inventory with the cursor and dropped onto a ghost slot -- and the controller is then closed on
 * its own confirm button, which is what makes the menu write the slots back onto the item.
 *
 * The trash button is worked as well, since clearing has its own path: it empties the slots on the
 * client and sends a packet, rather than going through the same save the confirm button does.
 *
 * Ported from Create's `LinkedControllerScreenTest`.
 */
@DrivesMinecraft
class LinkedControllerScreenTest {

    @Test
    @DisplayName("A frequency dropped into the linked controller is on the item once it is closed")
    fun `keeps the frequency it was given`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        open()
        shot("linked_controller_opened")

        putIntoFirstSlot()
        shot("linked_controller_bound")

        assertEquals(
            FREQUENCY, itemInSlot(FIRST_FREQUENCY_SLOT),
            "The frequency slot did not take what was dropped onto it",
        )

        close()

        assertEquals(
            listOf(FREQUENCY), frequencies().values,
            "The frequency put into the controller is not the one it was left holding",
        )
    }

    @Test
    @DisplayName("The linked controller's trash button clears the frequencies it was holding")
    fun `clears the frequencies`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        open()
        putIntoFirstSlot()

        // Not the same path as confirming: clearing empties the slots and sends a packet of its own.
        clickWidget("resetButton")
        serverTicks(SETTLE_TICKS)
        shot("linked_controller_cleared")

        close()

        val left = frequencies().values
        assertTrue(
            left.isEmpty(),
            "The trash button did not clear the controller, which still holds $left",
        )
    }

    /** The gesture that opens it: sneaking and right-clicking, with the controller in the main hand. */
    private suspend fun Stage.open() {
        // Ground of its own and a look at open sky, so the crouching click reaches the item rather
        // than whatever the last scene left standing in front of the player.
        clearGround(here(), 4)
        standAt(
            Vec3(here().x + 0.5, here().y.toDouble(), here().z + 0.5),
            Vec3(here().x + 0.5, here().y + 30.0, here().z + 0.5),
        )

        holdItem("create:linked_controller")
        runCommand("item replace entity $watcher hotbar.1 with $FREQUENCY 1")
        serverTicks(SETTLE_TICKS)

        sneakRightClick()

        waitForScreenNamed("LinkedControllerScreen")
    }

    /**
     * Two clicks, as a player makes them: the cobblestone up out of the inventory and down onto the
     * first frequency slot. A ghost slot only takes a likeness, so the cobblestone itself stays.
     */
    private suspend fun Stage.putIntoFirstSlot() {
        clickSlot(slotHolding(FREQUENCY))
        clickSlot(FIRST_FREQUENCY_SLOT)
    }

    private suspend fun Stage.close() {
        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    /** What the controller in the player's hand is bound to, on the server. */
    private suspend fun Stage.frequencies(): Frequencies = server(watcher) { name ->
        val bound = playerNamed(name).mainHandItem.get(AllDataComponents.LINKED_CONTROLLER_ITEMS)
            ?: return@server Frequencies(emptyList())

        Frequencies(
            bound.nonEmptyItems().map { BuiltInRegistries.ITEM.getKey(it.typeHolder().value()).toString() },
        )
    }

    /**
     * What a controller is bound to, wrapped.
     *
     * A `List<String>` cannot cross on its own -- a generic type erases to its class, so the
     * serializer looked up for it would encode the wrong thing and the compiler refuses it.
     */
    @Serializable
    private data class Frequencies(val values: List<String>)

    private fun Stage.here() = at(0, 1, 0)

    private companion object {


        const val SETTLE_TICKS = 10

        /**
         * Where the controller's own slots start.
         *
         * The menu lays the player's own inventory out first -- twenty seven and a hotbar of nine --
         * and only then its twelve, so the first slot of the screen is one of the player's.
         */
        const val FIRST_FREQUENCY_SLOT = 36

        const val FREQUENCY = "minecraft:cobblestone"
    }
}
