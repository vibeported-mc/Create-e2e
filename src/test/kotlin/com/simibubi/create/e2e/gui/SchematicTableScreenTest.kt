package com.simibubi.create.e2e.gui

import com.simibubi.create.AllItems
import com.simibubi.create.content.schematics.table.SchematicTableBlockEntity
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.neoforged.neoforge.transfer.item.ItemUtil
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The schematic table, which is a menu over a block rather than over an item in hand.
 *
 * Nothing is uploaded here -- that needs a file on disk, and what the upload does is not the table's
 * own doing. What is worth checking is the way in: the table opens on an empty-handed click, and the
 * slot it offers takes a blank schematic out of the player's inventory and holds onto it.
 *
 * So the blank is carried into the slot with the cursor, the way a player puts one there, and the
 * block on the server is then asked whether it is really holding it.
 *
 * Ported from Create's `SchematicTableScreenTest`.
 */
@DrivesMinecraft
class SchematicTableScreenTest {

    @Test
    @DisplayName("A blank schematic put into the table's slot is the one the table ends up holding")
    fun `takes a blank schematic`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        clearGround(table(), 4)
        setBlock(table(), "create:schematic_table[facing=south]")
        holdItem(BLANK)
        serverTicks(SETTLE_TICKS)

        rightClickBlock(table())
        waitForScreenNamed("SchematicTableScreen")
        shot("schematic_table_opened")

        // Two clicks, as a player makes them: the blank up out of the inventory, then into the slot.
        clickSlot(slotHolding(BLANK))
        clickSlot(INPUT_SLOT)
        serverTicks(SETTLE_TICKS)
        shot("schematic_table_loaded")

        assertTrue(
            holdsTheBlank(),
            "The blank schematic put into the table's slot did not reach the table",
        )

        closeWithEscape()
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    private suspend fun Stage.holdsTheBlank(): Boolean = server(table()) { pos ->
        val be = serverLevel.getBlockEntity(pos)
        if (be !is SchematicTableBlockEntity) throw AssertionError("There is no schematic table at $pos but $be")

        AllItems.EMPTY_SCHEMATIC.isIn(ItemUtil.getStack(be.inventory, INPUT_SLOT))
    }

    private fun Stage.table() = at(0, 1, 0)

    private companion object {


        const val SETTLE_TICKS = 10

        /** The table's own first slot, before the player's inventory is laid out after it. */
        const val INPUT_SLOT = 0

        const val BLANK = "create:empty_schematic"
    }
}
