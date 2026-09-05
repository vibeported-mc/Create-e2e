package com.simibubi.create.e2e.gui

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The schematicannon's placement settings, which are the part of its screen that works without a
 * schematic to print.
 *
 * They are hidden until asked for, so the test opens them first, then picks a replacement rule and
 * turns the two other options over. Each of those buttons sends on its own rather than waiting for
 * the screen to close, so the block can be asked about each one as it goes.
 *
 * Nothing is fired: what is being tested is the screen, and the cannon answers these settings whether
 * or not it has anything to build.
 *
 * Ported from Create's `SchematicannonScreenTest`.
 */
@DrivesMinecraft
class SchematicannonScreenTest {

    @Test
    @DisplayName("The placement settings chosen on the schematicannon's screen reach the cannon")
    fun `configures the placement settings`(cluster: ClusterScope) = cluster.driving {
        clearGround(cannon(), 4)
        setBlock(cannon(), "create:schematicannon")
        serverTicks(SETTLE_TICKS)

        val skippedMissingBefore = skipsMissing()
        val replacedBlockEntitiesBefore = replacesBlockEntities()

        rightClickBlock(cannon())
        waitForScreenNamed("SchematicannonScreen")
        shot("schematicannon_opened")

        // The placement settings are not on screen until asked for, and the buttons do not exist
        // until then.
        clickWidget("showSettingsButton")
        serverTicks(SETTLE_TICKS)
        shot("schematicannon_settings_shown")

        clickWidget(widget("replaceLevelButtons", REPLACE_ANY))
        clickWidget("skipMissingButton")
        clickWidget("skipBlockEntitiesButton")
        serverTicks(SETTLE_TICKS)
        shot("schematicannon_configured")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        assertEquals(
            REPLACE_ANY, replaceMode(),
            "The replacement rule chosen on the screen did not reach the cannon",
        )
        assertNotEquals(
            skippedMissingBefore, skipsMissing(),
            "Turning over whether to skip missing blocks did not reach the cannon",
        )
        assertNotEquals(
            replacedBlockEntitiesBefore, replacesBlockEntities(),
            "Turning over whether to replace block entities did not reach the cannon",
        )
    }

    private suspend fun replaceMode(): Int = server(cannon()) { pos -> cannonAt(pos).replaceMode }

    private suspend fun skipsMissing(): Boolean = server(cannon()) { pos -> cannonAt(pos).skipMissing }

    private suspend fun replacesBlockEntities(): Boolean =
        server(cannon()) { pos -> cannonAt(pos).replaceBlockEntities }

    private fun cannon() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.SCHEMATICANNON

        const val SETTLE_TICKS = 10

        /**
         * Replace with any, the third of the four rules.
         *
         * Not the one a cannon starts on, so clicking it is a change rather than a click that does
         * nothing -- the buttons only send when the rule would really move.
         */
        const val REPLACE_ANY = 2
    }
}

/**
 * The cannon at [pos], on the server.
 *
 * A top-level function rather than a member, so that the server-side bodies above can call it
 * without carrying the test object across with them.
 */
private fun dev.vibeported.mc.driver.ServerScope.cannonAt(pos: BlockPos): SchematicannonBlockEntity =
    serverLevel.getBlockEntity(pos) as? SchematicannonBlockEntity
        ?: throw AssertionError("There is no schematicannon at $pos")
