package com.simibubi.create.e2e.gui

import com.simibubi.create.AllDataComponents
import com.simibubi.create.content.equipment.zapper.terrainzapper.PlacementOptions
import com.simibubi.create.content.equipment.zapper.terrainzapper.TerrainBrushes
import com.simibubi.create.content.equipment.zapper.terrainzapper.TerrainTools
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.openScreen
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
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
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Creative Worldshaper's screen, opened and worked the way a player opens and works it.
 *
 * Nothing here reaches into the screen to make it do things: the item goes into the hand, the sneak
 * key goes down, the right mouse button is pressed, and the controls are clicked and scrolled at
 * where they are on screen. So a screen that stopped opening, or a button that stopped taking clicks,
 * fails this test rather than quietly passing it.
 *
 * What the screen is for is writing settings onto the item it was opened from, and that is what is
 * checked at the end -- on the server, where the item really lives.
 *
 * Ported from Create's `WorldshaperScreenTest`.
 */
@DrivesMinecraft
class WorldshaperScreenTest {

    @Test
    @DisplayName("Sneaking and right-clicking the Worldshaper opens its screen, and the settings stick")
    fun `configures the held item`(cluster: ClusterScope) = cluster.driving {
        // Ground of its own and a look at open sky: the zapper fires at whatever the crosshair is on,
        // and the screen is the only thing this test wants out of the click.
        clearGround(here(), 4)
        standAt(
            Vec3(here().x + 0.5, here().y.toDouble(), here().z + 0.5),
            Vec3(here().x + 0.5, here().y + 30.0, here().z + 0.5),
        )

        holdItem("create:handheld_worldshaper")
        serverTicks(SETTLE_TICKS)

        sneakRightClick()

        waitForScreenNamed("WorldshaperScreen")
        shot("worldshaper_opened")

        // A cuboid to start with, and a sphere one place further down the list -- so the wheel turns
        // down, the way a player reaching for the next option turns it. A list of options is wired the
        // other way round to a number, which is why this is negative and the parameter below is not.
        scrollWidget(widget("brushInput"), -1)

        // The parameters belong to the brush, so they are only worth touching once it has been picked.
        scrollWidget(widget("brushParams", 0), 2)

        clickWidget(widget("toolButtons", 1))
        clickWidget(widget("placementButtons", 1))
        serverTicks(SETTLE_TICKS)
        shot("worldshaper_configured")

        // What the screen believes it is set to, to be held against what the item ends up with.
        val shownBrush = shownBrush()
        val shownTool = shownTool()
        val shownPlacement = shownPlacement()

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        assertEquals(TerrainBrushes.Sphere, shownBrush, "Scrolling the brush input did not change the brush")
        assertNotEquals(TerrainTools.Fill, shownTool, "Clicking the second tool button did not change the tool")
        assertNotEquals(
            PlacementOptions.Merged, shownPlacement,
            "Clicking the second placement button did not change the placement",
        )

        // The screen writes what it was set to onto the item as it closes, so the item is the proof.
        assertEquals(
            shownBrush, heldBrush(),
            "The brush the screen showed is not the brush the item was left with",
        )
        assertEquals(
            shownTool, heldTool(),
            "The tool the screen showed is not the tool the item was left with",
        )
        assertEquals(
            shownPlacement, heldPlacement(),
            "The placement the screen showed is not the placement the item was left with",
        )
    }

    /*
     * What the open screen is set to. Read on the client, where the screen is, and returned as the
     * enum constant itself -- an enum names its own value and travels, where the screen it came off
     * could not.
     */

    private suspend fun shownBrush(): TerrainBrushes =
        client(ALEX) { readField(openScreen(), "currentBrush") as TerrainBrushes }

    private suspend fun shownTool(): TerrainTools =
        client(ALEX) { readField(openScreen(), "currentTool") as TerrainTools }

    private suspend fun shownPlacement(): PlacementOptions =
        client(ALEX) { readField(openScreen(), "currentPlacement") as PlacementOptions }

    /* And what the item in the player's hand carries, on the server, where the item really lives. */

    private suspend fun heldBrush(): TerrainBrushes = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SHAPER_BRUSH, TerrainBrushes.Cuboid)
    }

    private suspend fun heldTool(): TerrainTools = server(ALEX) { name ->
        playerNamed(name).mainHandItem.getOrDefault(AllDataComponents.SHAPER_TOOL, TerrainTools.Fill)
    }

    private suspend fun heldPlacement(): PlacementOptions = server(ALEX) { name ->
        playerNamed(name).mainHandItem
            .getOrDefault(AllDataComponents.SHAPER_PLACEMENT_OPTIONS, PlacementOptions.Merged)
    }

    private fun here() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.WORLDSHAPER

        /** How long a menu or a deferred screen has to appear before something is wrong. */
        const val SETTLE_TICKS = 5
    }
}
