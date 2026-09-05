package com.simibubi.create.e2e.gui

import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.scrollState
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * A sequenced gearshift's screen, and the instruction the block is left holding.
 *
 * What the row ends up saying is read off the screen rather than named here, since which instruction
 * is one place down the list, and what values that one allows, are the screen's business. The block
 * is then asked what it was left with, and the two have to agree.
 *
 * Ported from Create's `SequencedGearshiftScreenTest` client gametest. The screen is on the client
 * and the block is on the server, which the original could take for granted and this cannot: the
 * screen's controls are read by reflection over there, and only the numbers come back.
 *
 * Ordered after `SymmetryWandScreenTest`, which crashes the server. Passing here means the driver
 * rebuilt the cluster underneath it.
 */
@Order(2)
@DrivesMinecraft
class SequencedGearshiftScreenTest {

    @Test
    @DisplayName("The instruction set on a sequenced gearshift's screen is the one the block is left with")
    fun `configures the first instruction`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        // Before anything is built, so the chunk is loaded and ticking. `/setblock` will place a
        // block into a chunk nobody is near, but it does not stay resident, and a moment later the
        // block entity the test wants to ask about is not there to ask -- which reads as "there is
        // no sequenced gearshift" rather than as anything to do with chunks.
        standAt(
            Vec3(gearshift().x + 0.5, gearshift().y + 1.0, gearshift().z + 3.0),
            Vec3.atCenterOf(gearshift()),
        )

        clearGround(gearshift(), 4)
        setBlock(gearshift(), "create:sequenced_gearshift")
        serverTicks(SETTLE_TICKS)

        val instructionBefore = onTheBlock("instruction")

        rightClickBlock(gearshift())
        waitForScreenNamed("SequencedGearshiftScreen")
        shot("sequenced_gearshift_opened")

        // One instruction down the list, which also changes what the rest of the row means, and then
        // a couple of notches on the value the new instruction takes.
        scrollWidget(widget("inputs", FIRST_ROW, INSTRUCTION), -1)
        scrollWidget(widget("inputs", FIRST_ROW, VALUE), 2)
        shot("sequenced_gearshift_configured")

        val shownInstruction = scrollState("inputs", FIRST_ROW, INSTRUCTION)
        val shownValue = scrollState("inputs", FIRST_ROW, VALUE)

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertNotEquals(
            instructionBefore, shownInstruction,
            "Scrolling the instruction did not move it off the one the gearshift started on",
        )
        assertEquals(
            shownInstruction, onTheBlock("instruction"),
            "The instruction the screen showed is not the one the gearshift was left with",
        )
        assertEquals(
            shownValue, onTheBlock("value"),
            "The value the screen showed did not reach the gearshift",
        )
    }

    /**
     * What the gearshift's first instruction says, as a number.
     *
     * An instruction keeps its parts to itself, so they are read rather than asked for -- and which
     * kind of instruction it is comes back as its place in the list, which is what the screen's
     * control holds. The same reflection helper serves here as on the client; it is compiled into
     * this module, which both games load.
     */
    private suspend fun Stage.onTheBlock(part: String): Int = server(gearshift(), part, FIRST_ROW) { pos, named, row ->
        val be = serverLevel.getBlockEntity(pos) as? SequencedGearshiftBlockEntity
            ?: throw AssertionError("There is no sequenced gearshift at $pos")

        when (val value = readField(be.instructions[row], named)) {
            is Enum<*> -> value.ordinal
            else -> value as Int
        }
    }

    private fun Stage.gearshift() = at(0, 1, 0)

    private companion object {


        const val SETTLE_TICKS = 5

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        /** The row a fresh gearshift already has an instruction on. */
        const val FIRST_ROW = 0

        const val INSTRUCTION = 0
        const val VALUE = 1
    }
}
