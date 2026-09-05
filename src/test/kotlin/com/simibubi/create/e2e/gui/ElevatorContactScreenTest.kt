package com.simibubi.create.e2e.gui

import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The elevator contact's screen, which is what names a floor.
 *
 * The short name is what the call buttons show and the long one is the description beside it. The
 * screen opens with the short name already being edited and all of it selected, so typing goes
 * straight onto it and replaces what was there, which is the gesture a player makes to rename a
 * floor.
 *
 * The contact is placed on its own rather than as part of a working elevator: what is being tested
 * is the naming, and a contact answers that whether or not there is a pulley above it.
 *
 * Ported from Create's `ElevatorContactScreenTest`.
 */
@DrivesMinecraft
class ElevatorContactScreenTest {

    @Test
    @DisplayName("The names typed onto an elevator contact are the ones the floor is left with")
    fun `names the floor`(cluster: ClusterScope) = cluster.driving {
        clearGround(contact(), 4)
        setBlock(contact(), "create:elevator_contact[facing=south]")
        serverTicks(SETTLE_TICKS)

        rightClickBlock(contact())
        waitForScreenNamed("ElevatorContactScreen")
        shot("elevator_contact_opened")

        // The short name is already being edited, with what was there selected, so this replaces it.
        typeText(SHORT_NAME)
        serverTicks(SETTLE_TICKS)

        clickWidget("longNameInput")
        typeText(LONG_NAME)
        serverTicks(SETTLE_TICKS)
        shot("elevator_contact_named")

        clickWidget("confirm")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertEquals(
            SHORT_NAME, onTheBlock("shortName"),
            "The short name typed on the screen did not reach the contact",
        )
        assertEquals(
            LONG_NAME, onTheBlock("longName"),
            "The description typed on the screen did not reach the contact",
        )
    }

    /**
     * One of the contact's names, by the name the block entity calls it.
     *
     * The original passed a getter across; a body cannot capture one, so the field is named instead
     * and read on the server where it lives.
     */
    private suspend fun onTheBlock(field: String): String = server(contact(), field) { pos, named ->
        val be = serverLevel.getBlockEntity(pos) as? ElevatorContactBlockEntity
            ?: throw AssertionError("There is no elevator contact at $pos")

        com.simibubi.create.e2e.readField(be, named).toString()
    }

    private fun contact() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.ELEVATOR_CONTACT

        const val SETTLE_TICKS = 5

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        const val SHORT_NAME = "3"
        const val LONG_NAME = "Smeltery"
    }
}
