package com.simibubi.create.e2e.gui

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.scrollState
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A display link's screen, which chooses what it reads and which line of the sign it writes to.
 *
 * The aiming at the sign is done directly rather than by clicking the sign with the link first, since
 * what is being tested is the screen rather than the way a link is bound.
 *
 * Ported from Create's `DisplayLinkScreenTest`.
 */
@DrivesMinecraft
class DisplayLinkScreenTest {

    @Test
    @DisplayName("The line chosen on a display link's screen is the line the link is left writing to")
    fun `configures the target line`(cluster: ClusterScope) = cluster.driving {
        clearGround(chest(), 5)

        // Something to read from -- a switch watching a chest reports how full it is -- something to
        // write on, and the link between them.
        setBlock(chest(), "minecraft:chest")
        setBlock(switchPos(), "create:stockpile_switch[target=floor,facing=north]")
        setBlock(link(), "create:display_link[facing=up]")
        setBlock(sign(), "minecraft:oak_sign")
        serverTicks(SETTLE_TICKS)

        server(link(), sign()) { linkPos, signPos ->
            val linkBe = linkAt(serverLevel, linkPos)
            linkBe.target(signPos)

            // The client decides whether the screen may open at all, so it has to be told as well.
            linkBe.notifyUpdate()
        }
        serverTicks(SETTLE_TICKS)

        val lineBefore = targetLine()

        rightClickBlock(link())
        waitForScreenNamed("DisplayLinkScreen")
        shot("display_link_opened")

        // A list of lines runs the other way round to a number, so down the list is a downward notch.
        scrollWidget("targetLineSelector", -1)
        shot("display_link_configured")

        val shownLine = scrollState("targetLineSelector")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertNotEquals(lineBefore, shownLine, "Scrolling the line did not move it off the one it started on")
        assertEquals(
            shownLine, targetLine(),
            "The line the screen showed is not the line the link was left writing to",
        )
        assertTrue(hasSource(), "Closing the screen did not leave the link reading anything")
    }

    private suspend fun targetLine(): Int = server(link()) { pos -> linkAt(serverLevel, pos).targetLine }

    private suspend fun hasSource(): Boolean =
        server(link()) { pos -> linkAt(serverLevel, pos).activeSource != null }

    private fun chest() = BlockPos(60, -58, ZONE)

    /** What is read: a switch watching the chest below it. */
    private fun switchPos() = chest().above()

    /**
     * The link itself, stood on the switch.
     *
     * A link's facing is the face of the block it was stuck to, and it reads whatever is on the other
     * side of that -- so one stood on top of something faces up and reads downwards.
     */
    private fun link() = switchPos().above()

    /** What is written on, a few blocks clear so neither is in the other's way. */
    private fun sign() = BlockPos(60, -58, ZONE - 3)

    private companion object {

        const val ZONE = Zones.DISPLAY_LINK

        const val SETTLE_TICKS = 10

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        fun linkAt(level: ServerLevel, pos: BlockPos): DisplayLinkBlockEntity =
            level.getBlockEntity(pos) as? DisplayLinkBlockEntity
                ?: throw AssertionError("There is no display link at $pos")
    }
}
