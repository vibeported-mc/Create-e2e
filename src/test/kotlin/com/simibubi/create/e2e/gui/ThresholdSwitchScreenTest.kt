package com.simibubi.create.e2e.gui

import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.readField
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A threshold switch's screen, which sets the levels it turns on and off at.
 *
 * The switch is stood on a chest so that it has something to watch: what the screen offers depends on
 * what is being watched, and a switch watching nothing has nothing to offer.
 *
 * Both thresholds are read off the screen rather than named here -- the two are tied together, and
 * moving one past the other pushes the other along, so what a given number of notches leaves them at
 * is the screen's business. What is checked is that whatever the screen showed is what the block was
 * left with.
 *
 * Ported from Create's `ThresholdSwitchScreenTest`.
 */
@DrivesMinecraft
class ThresholdSwitchScreenTest {

    @Test
    @DisplayName("The levels set on a threshold switch's screen are the ones the block is left with")
    fun `configures the thresholds`(cluster: ClusterScope) = cluster.driving {
        build()

        // Something for it to measure, so the screen has a range worth scrolling through.
        give(chest(), 0, "minecraft:cobblestone", 64)
        serverTicks(SETTLE_TICKS)

        rightClickBlock(switchPos())
        waitForScreenNamed("ThresholdSwitchScreen")
        shot("threshold_switch_opened")

        scrollWidget(widgetOnAbove(), 2)
        scrollWidget(widgetOffBelow(), 1)
        shot("threshold_switch_configured")

        val shownOnAbove = scrollState("onAbove")
        val shownOffBelow = scrollState("offBelow")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertTrue(
            shownOnAbove > shownOffBelow,
            "The screen left the upper threshold at or below the lower one, so nothing useful was set",
        )
        assertEquals(
            shownOnAbove, number("onWhenAbove"),
            "The upper threshold the screen showed did not reach the block",
        )
        assertEquals(
            shownOffBelow, number("offWhenBelow"),
            "The lower threshold the screen showed did not reach the block",
        )
    }

    @Test
    @DisplayName("A threshold switch counts in stacks when its screen is set to, and can be inverted")
    fun `counts in stacks and inverts`(cluster: ClusterScope) = cluster.driving {
        build()
        serverTicks(SETTLE_TICKS)

        val invertedBefore = inverted()

        rightClickBlock(switchPos())
        waitForScreenNamed("ThresholdSwitchScreen")

        // Items to stacks, one option down the list.
        scrollWidget("inStacks", -1)

        // The flip button sends on its own rather than waiting for the screen to be closed.
        clickWidget("flipSignals")
        serverTicks(SEND_TICKS)
        shot("threshold_switch_flipped")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertNotEquals(
            invertedBefore, inverted(),
            "Flipping the signal on the screen did not reach the block",
        )
        assertTrue(flag("inStacks"), "Choosing to count in stacks did not reach the block")
    }

    private suspend fun build() {
        clearGround(chest(), 4)
        setBlock(chest(), "minecraft:chest")
        setBlock(switchPos(), "create:stockpile_switch[target=floor,facing=north]")
    }

    private suspend fun widgetOnAbove() = com.simibubi.create.e2e.widget("onAbove")

    private suspend fun widgetOffBelow() = com.simibubi.create.e2e.widget("offBelow")

    private suspend fun number(field: String): Int = server(switchPos(), field) { pos, named ->
        readField(switchAt(serverLevel, pos), named) as Int
    }

    private suspend fun flag(field: String): Boolean = server(switchPos(), field) { pos, named ->
        readField(switchAt(serverLevel, pos), named) as Boolean
    }

    /** Inverted is asked rather than read: the block works it out rather than keeping it in a field. */
    private suspend fun inverted(): Boolean = server(switchPos()) { pos ->
        switchAt(serverLevel, pos).isInverted
    }

    /**
     * Where the switch is: on top of the chest it watches.
     *
     * Which way it looks is not a facing but the face it is attached by -- stood on the floor it
     * looks down, which from up here means down into the chest.
     */
    private fun switchPos() = chest().above()

    private fun chest() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.THRESHOLD_SWITCH

        const val SETTLE_TICKS = 5

        /** Long enough for the screen's own packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        fun switchAt(
            level: net.minecraft.server.level.ServerLevel,
            pos: BlockPos,
        ): ThresholdSwitchBlockEntity =
            level.getBlockEntity(pos) as? ThresholdSwitchBlockEntity
                ?: throw AssertionError("There is no threshold switch at $pos")
    }
}
