package com.simibubi.create.e2e.gui

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAt
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.scrollState
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.ServerScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The factory gauge's two screens, which are reached one after the other from the same click.
 *
 * A panel with nothing on it yet answers an empty-handed click by asking what it is for, which is the
 * first screen: a single ghost slot. Once it knows, the same click opens the second screen instead,
 * where the panel is given the address it orders from and how long a promise is waited on.
 *
 * So the test clicks the panel twice, and both screens have to appear in turn. The panel is asked what
 * it was left with after each -- the item after the first, the address and the waiting time after the
 * second.
 *
 * A gauge holds four panels in one block and which one is clicked depends on where the face was hit,
 * so rather than name one, the test reads back whichever of the four ended up with something on it.
 * Both clicks are aimed identically, so both reach the same one.
 *
 * Ported from Create's `FactoryPanelScreenTest`.
 */
@DrivesMinecraft
class FactoryPanelScreenTest {

    @Test
    @DisplayName("A factory panel is told what it makes on one screen and how to order it on the next")
    fun `sets its item and then its order`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        clearGround(wall(), 4)
        setBlock(wall(), "minecraft:stone")

        holdItem("create:factory_gauge")
        runCommand("item replace entity $watcher hotbar.1 with $MAKES 1")
        runCommand("item replace entity $watcher hotbar.2 with minecraft:air")
        serverTicks(SETTLE_TICKS)

        // A gauge refuses to be placed until it has been tuned to a logistics network, which in play
        // is done by clicking a stock link with it. Given one here rather than built, since what is
        // being tested is the panel's screens and not the network they order over.
        tuneToSomeNetwork()
        serverTicks(SETTLE_TICKS)

        // Hung on the wall the way a player hangs one. A gauge cannot be conjured into place: a block
        // with none of its four panels switched on takes itself back off the wall, and it is the
        // placing that switches one on.
        rightClickAt(quarter(), wall())
        serverTicks(SETTLE_TICKS)

        assertTrue(onTheWall(), "The gauge is not on the wall where the test expects it")

        // An empty hand from here on: anything held would be offered as the panel's item, or would
        // hang a second gauge, instead of opening the screen that asks what the panel is for.
        selectHotbar(2)
        serverTicks(SETTLE_TICKS)

        rightClickAt(quarter(), panel())
        waitForScreenNamed("FactoryPanelSetItemScreen")
        shot("factory_panel_set_item")

        // Two clicks, as a player makes them: the cobblestone up out of the inventory, then onto the panel.
        clickSlot(slotHolding(MAKES))
        clickSlot(FILTER_SLOT)

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)

        assertEquals(MAKES, itemOnThePanel(), "The item put onto the panel did not reach it")

        // Now that it knows what it is for, the same click opens the other screen.
        rightClickAt(quarter(), panel())
        waitForScreenNamed("FactoryPanelScreen")
        shot("factory_panel_opened")

        clickWidget("addressBox")
        typeText(ADDRESS)
        scrollWidget(widget("promiseExpiration"), 2)
        shot("factory_panel_configured")

        val shownExpiry = scrollState("promiseExpiration")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertEquals(ADDRESS, addressOnThePanel(), "The address typed onto the panel did not reach it")
        assertEquals(
            shownExpiry, expiryOnThePanel(),
            "The waiting time the screen showed did not reach the panel",
        )
    }

    private suspend fun Stage.selectHotbar(slot: Int) {
        client(watcher, slot) { which ->
            clientPlayer?.inventory?.setSelectedSlot(which)
            awaitTicks(1)
        }
    }

    /** Returns the frequency given, since a body that answers nothing has nothing to report. */
    private suspend fun Stage.tuneToSomeNetwork(): String = server(watcher) { name ->
        val player = playerNamed(name)
        val frequency = UUID.randomUUID()
        LogisticallyLinkedBlockItem.assignFrequency(player.mainHandItem, player, frequency)
        frequency.toString()
    }

    private suspend fun Stage.onTheWall(): Boolean = server(panel()) { pos ->
        serverLevel.getBlockEntity(pos) is FactoryPanelBlockEntity
    }

    private suspend fun Stage.itemOnThePanel(): String = server(panel()) { pos ->
        BuiltInRegistries.ITEM.getKey(configuredPanel(pos).filter.item).toString()
    }

    private suspend fun Stage.addressOnThePanel(): String = server(panel()) { pos ->
        readField(configuredPanel(pos), "recipeAddress") as String
    }

    private suspend fun Stage.expiryOnThePanel(): Int = server(panel()) { pos ->
        readField(configuredPanel(pos), "promiseClearingInterval") as Int
    }

    private fun Stage.wall() = at(0, 2, 0)

    /** The panel itself, hung on the south face of the wall. */
    private fun Stage.panel() = wall().south()

    /**
     * The spot on the wall's face that every click here is aimed at.
     *
     * Off the middle on purpose: a gauge holds four panels in one block and the middle of the face is
     * the corner where all four meet. Aiming at the same quarter each time is what puts the panel
     * there and then opens that same one.
     */
    private fun Stage.quarter() = Vec3(wall().x + 0.3, wall().y + 0.3, wall().z + 1.0)

    private companion object {


        const val SETTLE_TICKS = 10

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        /** The panel's own slot, which the menu lays out after the player's inventory. */
        const val FILTER_SLOT = 36

        const val MAKES = "minecraft:cobblestone"

        const val ADDRESS = "Quarry"
    }
}

/** Whichever of the block's four panels was the one clicked, found by it being the one set up. */
private fun ServerScope.configuredPanel(pos: BlockPos): FactoryPanelBehaviour {
    val be = serverLevel.getBlockEntity(pos)
    if (be !is FactoryPanelBlockEntity) throw AssertionError("There is no factory gauge at $pos but $be")

    return be.panels.values.firstOrNull { !it.filter.isEmpty }
        ?: throw AssertionError("None of the gauge's four panels was given an item")
}
