package com.simibubi.create.e2e.gui

import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.ServerScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The redstone requester's screen, which is a standing order: what to ask for, and where from.
 *
 * The order itself is a row of ghost slots, so an item is carried into the first of them with the
 * cursor. The address it orders from is typed in, and the requester is told to take nothing rather
 * than take what it can get -- all three of which are sent together as the screen closes.
 *
 * A requester on its own is not on any network, which is fine: what is being tested is that the order
 * it was given is the order it is left holding, not whether anything ever answers it.
 *
 * Ported from Create's `RedstoneRequesterScreenTest`.
 */
@DrivesMinecraft
class RedstoneRequesterScreenTest {

    @Test
    @DisplayName("The order set on a redstone requester's screen is the order the block is left holding")
    fun `keeps the order it was given`(cluster: ClusterScope) = cluster.driving {
        clearGround(requester(), 4)
        setBlock(requester(), "create:redstone_requester")

        // An empty hand, since a requester answers a crouching or a holding click differently.
        holdItem("minecraft:air")
        runCommand("item replace entity $ALEX hotbar.1 with minecraft:cobblestone 1")
        serverTicks(SETTLE_TICKS)

        rightClickBlock(requester())
        waitForScreenNamed("RedstoneRequesterScreen")
        shot("redstone_requester_opened")

        // Two clicks, as a player makes them: the cobblestone up out of the inventory, then into the order.
        clickSlot(slotHolding("minecraft:cobblestone"))
        clickSlot(FIRST_ORDER_SLOT)

        clickWidget("addressBox")
        typeText(ADDRESS)
        serverTicks(SETTLE_TICKS)

        // All or nothing, rather than taking whatever happens to be there.
        clickWidget("dontAllowPartial")
        shot("redstone_requester_ordered")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertEquals(
            ADDRESS, address(),
            "The address typed onto the requester did not reach the block",
        )
        assertTrue(ordersCobblestone(), "The item put into the order did not reach the block")
        assertEquals(
            false, allowsPartial(),
            "Choosing to refuse partial requests did not reach the block",
        )
    }

    private suspend fun address(): String =
        server(requester()) { pos -> requesterAt(pos).encodedTargetAdress }

    private suspend fun allowsPartial(): Boolean =
        server(requester()) { pos -> requesterAt(pos).allowPartialRequests }

    private suspend fun ordersCobblestone(): Boolean = server(requester()) { pos ->
        requesterAt(pos).encodedRequest.stacks().any { it.stack.`is`(Items.COBBLESTONE) }
    }

    private fun requester() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.REDSTONE_REQUESTER

        const val SETTLE_TICKS = 10

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        /** The first slot of the order, which the menu lays out after the player's inventory. */
        const val FIRST_ORDER_SLOT = 36

        const val ADDRESS = "Warehouse"
    }
}

/** The requester at [pos], on the server. */
private fun ServerScope.requesterAt(pos: BlockPos): RedstoneRequesterBlockEntity =
    serverLevel.getBlockEntity(pos) as? RedstoneRequesterBlockEntity
        ?: throw AssertionError("There is no redstone requester at $pos")
