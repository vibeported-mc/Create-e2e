package com.simibubi.create.e2e.gui

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The postbox's screen, which is where a package port is given the address it answers to.
 *
 * A postbox opens on an empty-handed click; a clipboard in hand copies the address instead of opening
 * anything, which is why the hand is left empty here. The address is typed into the box and is sent
 * when the screen closes, so it is read back off the block: an address that never left the screen is
 * one no package would ever be sorted by.
 *
 * The send-only and send-and-receive buttons are not touched. A port only shows them once it has a
 * target to deliver into -- a chain conveyor or a train station -- and a postbox stood on the ground
 * has none, so on this one they are not on screen to be clicked.
 *
 * Ported from Create's `PackagePortScreenTest`.
 */
@DrivesMinecraft
class PackagePortScreenTest {

    @Test
    @DisplayName("The address typed onto a postbox is the one the block is left answering to")
    fun `keeps its address`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        clearGround(postbox(), 4)
        setBlock(postbox(), "create:blue_postbox[facing=south]")

        // An empty hand, since a clipboard would copy the address rather than open the screen.
        holdItem("minecraft:air")
        serverTicks(SETTLE_TICKS)

        rightClickBlock(postbox())
        waitForScreenNamed("PackagePortScreen")
        shot("package_port_opened")

        clickWidget("addressBox")
        typeText(ADDRESS)
        serverTicks(SETTLE_TICKS)
        shot("package_port_addressed")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertEquals(
            ADDRESS, address(),
            "The address typed onto the postbox did not reach the block",
        )
    }

    private suspend fun Stage.address(): String = server(postbox()) { pos ->
        val be = serverLevel.getBlockEntity(pos)
        if (be !is PackagePortBlockEntity) throw AssertionError("There is no postbox at $pos but $be")

        be.addressFilter
    }

    private fun Stage.postbox() = at(0, 1, 0)

    private companion object {


        const val SETTLE_TICKS = 10

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        const val ADDRESS = "Smeltery"
    }
}
