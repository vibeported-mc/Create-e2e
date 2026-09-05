package com.simibubi.create.e2e.gui

import com.simibubi.create.AllDataComponents
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.openScreen
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.sneakRightClick
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The screen that says where a schematic is to be laid down, and which way round.
 *
 * The schematic here names a file that was never uploaded, which is deliberate: the screen and
 * everything behind it is reached without needing a real structure on disk, since nothing is loaded
 * until the schematic is actually deployed and a schematic that reports no size is dropped before
 * that happens. What is being tested is the placement, not the printing.
 *
 * The other screen here belongs to the Schematic and Quill: marking out two corners with it and
 * clicking once more asks what to call the box between them, which is the step before anything is
 * saved at all.
 *
 * Closing the placement screen does not write to the item directly. It hands the placement to the
 * client's schematic handler, which sends it up a short while later, and the server writes it onto
 * the stack in the hotbar slot the schematic was held in -- so the assertions wait for that.
 *
 * Ported from Create's `SchematicEditScreenTest`.
 */
@DrivesMinecraft
class SchematicEditScreenTest {

    @Test
    @DisplayName("The placement set on a schematic's screen reaches the schematic on the server")
    fun `configures the placement`(cluster: ClusterScope) = cluster.driving {
        // Somewhere settled to stand: the anchor the screen offers is the player's own position, so a
        // player still falling would be asked about a place they are about to leave.
        clearGround(firstCorner(), 6)
        standAt(
            Vec3(firstCorner().x + 0.5, firstCorner().y.toDouble(), firstCorner().z + 0.5),
            Vec3(firstCorner().x + 0.5, firstCorner().y + 30.0, firstCorner().z + 0.5),
        )

        // A named file and a size, which is all the screen needs: without a size the handler has no
        // bounds to place anything within, and without a name it does not take the schematic as the
        // active one.
        runCommand(
            "item replace entity $ALEX hotbar.0 with " +
                "create:schematic[create:schematic_file=\"gametest.nbt\",create:schematic_bounds=[I;3,3,3]]"
        )
        client(ALEX) {
            clientPlayer?.inventory?.setSelectedSlot(0)
            awaitTicks(1)
        }
        serverTicks(SETTLE_TICKS)

        sneakRightClick()

        waitForScreenNamed("SchematicEditScreen")
        shot("schematic_edit_opened")

        // One turn and one mirror down each list, which is as far as a player scrolls to get off "none".
        scrollWidget(widget("rotationArea"), -1)
        scrollWidget(widget("mirrorArea"), -1)
        shot("schematic_edit_configured")

        // Where the screen says it will go. Undeployed, it offers the player's own position, so this
        // is read off the screen rather than worked out again here.
        val shownAnchor = anchorShownOnScreen()

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SYNC_TICKS)
        restoreHud()

        assertEquals(
            Rotation.CLOCKWISE_90, heldRotation(),
            "The rotation the screen was scrolled to did not reach the schematic",
        )
        assertEquals(
            Mirror.LEFT_RIGHT, heldMirror(),
            "The mirror the screen was scrolled to did not reach the schematic",
        )
        assertEquals(
            shownAnchor, heldAnchor(),
            "The position the screen showed is not where the schematic was left anchored",
        )
        assertEquals(
            true, heldDeployed(),
            "Confirming the placement did not leave the schematic deployed",
        )
    }

    @Test
    @DisplayName("Marking out two corners with the quill asks for a name to save them under")
    fun `prompts for a name once both corners are set`(cluster: ClusterScope) = cluster.driving {
        clearGround(firstCorner(), 6)

        // Two blocks stood up off the ground, so that each corner can be aimed at squarely. Aimed at
        // along the floor instead, the crosshair lands on whichever square happens to be nearest.
        setBlock(firstCorner(), "minecraft:stone")
        setBlock(secondCorner(), "minecraft:stone")

        holdItem("create:schematic_and_quill")
        serverTicks(SETTLE_TICKS)

        // Three clicks, as a player makes them: one corner, the other corner, and then a third that
        // asks what to call what is now boxed in between them.
        rightClickBlock(firstCorner())
        serverTicks(SETTLE_TICKS)

        rightClickBlock(secondCorner())
        serverTicks(SETTLE_TICKS)

        rightClickAhead()

        waitForScreenNamed("SchematicPromptScreen")
        shot("schematic_prompt")

        closeWithEscape()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    /** The three coordinate boxes, read as the position they spell out. */
    private suspend fun anchorShownOnScreen(): BlockPos = client(ALEX) {
        val screen = openScreen()

        fun coordinate(field: String) = (readField(screen, field) as EditBox).value.toInt()

        BlockPos(coordinate("xInput"), coordinate("yInput"), coordinate("zInput"))
    }

    /* What the schematic in the player's hand carries, on the server. */

    private suspend fun heldRotation(): Rotation = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SCHEMATIC_ROTATION, Rotation.NONE)
    }

    private suspend fun heldMirror(): Mirror = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SCHEMATIC_MIRROR, Mirror.NONE)
    }

    private suspend fun heldAnchor(): BlockPos = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SCHEMATIC_ANCHOR, BlockPos.ZERO)
    }

    private suspend fun heldDeployed(): Boolean = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SCHEMATIC_DEPLOYED, false)
    }

    /** Two blocks a few apart, which is what the quill boxes in between. */
    private fun firstCorner() = BlockPos(58, -58, ZONE)

    private fun secondCorner() = firstCorner().east(4)

    private companion object {

        const val ZONE = Zones.SCHEMATIC_EDIT

        const val SETTLE_TICKS = 10

        /** Longer than the handler's own delay before it sends what it was given. */
        const val SYNC_TICKS = 30
    }
}
