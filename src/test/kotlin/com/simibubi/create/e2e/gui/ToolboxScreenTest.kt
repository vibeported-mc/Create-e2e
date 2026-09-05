package com.simibubi.create.e2e.gui

import com.simibubi.create.content.equipment.toolbox.ToolboxBlockEntity
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.standLookingAt
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.neoforged.neoforge.transfer.ResourceHandler
import net.neoforged.neoforge.transfer.item.ItemResource
import net.neoforged.neoforge.transfer.item.ItemUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The toolbox, which is eight compartments a player drops tools into.
 *
 * A toolbox opens on a click that is not crouching -- crouching at one picks it up instead -- and the
 * compartments are its own slots, laid out before the player's inventory is added after them. So the
 * first compartment is the first slot on the screen, and a pickaxe carried into it should be the
 * pickaxe the block is left holding.
 *
 * The other way in is not a click at all: a key brings up a ring of whichever toolboxes are near
 * enough to reach, which is how a player takes a tool out without walking over to the box.
 *
 * Ported from Create's `ToolboxScreenTest`.
 */
@DrivesMinecraft
class ToolboxScreenTest {

    @Test
    @DisplayName("A tool put into a toolbox compartment is the one the toolbox is left holding")
    fun `takes a tool into a compartment`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        build()
        holdItem(TOOL)
        serverTicks(SETTLE_TICKS)

        rightClickBlock(toolbox())
        waitForScreenNamed("ToolboxScreen")
        shot("toolbox_opened")

        // Two clicks, as a player makes them: the pickaxe up out of the inventory, then into the toolbox.
        clickSlot(slotHolding(TOOL))
        clickSlot(FIRST_COMPARTMENT)
        serverTicks(SETTLE_TICKS)
        shot("toolbox_filled")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        assertEquals(
            TOOL, inTheToolbox(),
            "The tool put into the first compartment did not reach the block",
        )
    }

    @Test
    @DisplayName("The toolbox key brings up the ring of nearby toolboxes")
    fun `opens the ring of nearby toolboxes`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        build()
        serverTicks(SETTLE_TICKS)

        // Nothing is clicked here. The ring is a key away, and what it offers is whichever toolboxes
        // are near enough to reach -- so the player is stood in front of this one first.
        standLookingAt(toolbox())

        // Held down, not tapped: like the wrench's rotation menu this is a ring worked while the key
        // is down, and its `keyReleased` closes it. Pressing and releasing in one gesture would open
        // and shut it again before anything here could look.
        client(watcher) { keyDown(Key(RING_KEY)) }

        waitForScreenNamed("RadialToolboxMenu")
        shot("toolbox_ring")

        // Let go of the key, which is how this ring is meant to be dismissed -- and then close
        // whatever is left. Create's toolbox handler acts on the release as well as the press,
        // unlike the wrench's, so the release that shuts the ring can open it straight back up; and
        // `closeAnyScreen` is what makes that stop mattering here, since an escape sent at a screen
        // that already went would open the game menu instead of closing anything.
        client(watcher) { keyUp(Key(RING_KEY)) }
        serverTicks(SETTLE_TICKS)
        closeAnyScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    private suspend fun Stage.build() {
        clearGround(toolbox(), 4)
        setBlock(toolbox(), "create:red_toolbox[facing=south]")
    }

    /** What the first compartment is holding, as its registry name. */
    private suspend fun Stage.inTheToolbox(): String = server(toolbox()) { pos ->
        val be = serverLevel.getBlockEntity(pos)
        if (be !is ToolboxBlockEntity) throw AssertionError("There is no toolbox at $pos but $be")

        @Suppress("UNCHECKED_CAST")
        val compartments = readField(be, "inventory") as ResourceHandler<ItemResource>

        BuiltInRegistries.ITEM.getKey(ItemUtil.getStack(compartments, FIRST_COMPARTMENT).item).toString()
    }

    private fun Stage.toolbox() = at(0, 1, 0)

    private companion object {


        const val SETTLE_TICKS = 10

        /** The first compartment, which the menu lays out before the player's own inventory. */
        const val FIRST_COMPARTMENT = 0

        const val TOOL = "minecraft:diamond_pickaxe"

        /** What the toolbelt binding ships on. */
        const val RING_KEY = GLFW.GLFW_KEY_LEFT_ALT
    }
}
