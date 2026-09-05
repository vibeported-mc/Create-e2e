package com.simibubi.create.e2e.gui

import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen
import com.simibubi.create.e2e.holdRightClickAt
import com.simibubi.create.e2e.hoverGui
import com.simibubi.create.e2e.releaseRightClick
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.Point
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A block's value board: the little dial that appears when the button is held on the box drawn on
 * its face.
 *
 * Not a screen anything clicks. The crosshair rests on the small box drawn on the block, and after a
 * few ticks the board appears; the cursor is then dragged along it and what is set is whatever it
 * was resting on when the button was let go. So this holds, drags and releases rather than clicking.
 *
 * A speed controller is a fair stand-in for the rest: its box sits on whichever side is not along
 * its axis, and what the board sets is a plain number that can be read straight back off the block.
 *
 * Ported from Create's `ValueSettingsScreenTest`.
 */
@DrivesMinecraft
class ValueSettingsScreenTest {

    @Test
    @DisplayName("The value dragged out on a block's board is the value the block is left set to")
    fun `drags out a value`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()


        clearGround(controller(), 4)
        // Laid along x, which is what puts its little box on the side facing the player.
        setBlock(controller(), "create:rotation_speed_controller[axis=x]")
        serverTicks(SETTLE_TICKS)

        val before = speed()

        // Held rather than clicked, and held on the box itself: anywhere else on the block and the
        // button is an ordinary click that never brings the board up.
        holdRightClickAt(valueBox(), controller())

        waitForScreenNamed("ValueSettingsScreen")
        serverTicks(WARMUP_TICKS)
        shot("value_settings_opened")

        // The board says where each of its steps is drawn, so the cursor goes to one it named rather
        // than to a place worked out here. Asked of the screen directly inside a client body: the
        // screen cannot cross a wire but a body runs where it lives, so it can hold the real thing
        // and call a real method on it. Only the two numbers come back.
        val target = client(watcher, COLUMN) { column ->
            val board = minecraft.gui.screen() as ValueSettingsScreen
            val coordinate = board.getCoordinateOfValue(0, column)
            Point(coordinate.x.toDouble(), coordinate.y.toDouble())
        }
        hoverGui(target.x, target.y)
        shot("value_settings_dragged")

        // Letting go is what sets it.
        releaseRightClick()
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertNotEquals(
            before, speed(),
            "Dragging the board out and letting go did not change what the block is set to",
        )
    }

    private suspend fun Stage.speed(): Int = server(controller()) { pos ->
        val be = serverLevel.getBlockEntity(pos) as? SpeedControllerBlockEntity
            ?: throw AssertionError("There is no speed controller at $pos")
        be.targetSpeed.value
    }

    private fun Stage.controller() = at(0, 1, 0)

    /**
     * Where the little box is drawn on the block, which is the only place the board can be summoned
     * from.
     *
     * Near the top of the face and just outside it, so the crosshair lands on the box rather than
     * passing through into the block behind.
     */
    private fun Stage.valueBox() = Vec3(
        controller().x + 0.5,
        controller().y + 11 / 16.0,
        controller().z + 0.97,
    )

    private companion object {


        const val SETTLE_TICKS = 10

        /** Long enough for the board to have appeared, which takes six ticks of holding. */
        const val WARMUP_TICKS = 15

        /** Long enough for the board's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        /** How far along the bar to drag, in the board's own steps. */
        const val COLUMN = 12
    }
}
