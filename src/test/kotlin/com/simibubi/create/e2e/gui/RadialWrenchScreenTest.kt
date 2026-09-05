package com.simibubi.create.e2e.gui

import com.simibubi.create.AllKeys
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.standLookingAt
import com.simibubi.create.e2e.waitForScreenNamed
import com.mojang.blaze3d.platform.InputConstants
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The wrench's rotation menu, which offers the ways the block under the crosshair can be turned and
 * applies them one click at a time.
 *
 * It is the one screen in the mod that no key opens out of the box: the binding ships unassigned, so
 * a player has to give it a key in the options before it can be used at all. This does the same
 * before pressing it, which is the only way to reach the screen and is worth knowing still works.
 *
 * What it offers depends on the block being looked at, so the block here is a staircase -- it has a
 * facing, a half and a shape, which is three of the things the menu knows how to turn.
 *
 * Ported from Create's `RadialWrenchScreenTest`.
 */
@DrivesMinecraft
class RadialWrenchScreenTest {

    @Test
    @DisplayName("Once its key is bound, the wrench's rotation menu opens on the block being looked at")
    fun `opens on the block being looked at`(cluster: ClusterScope) = cluster.driving {
        clearGround(stairs(), 4)
        setBlock(stairs(), "minecraft:oak_stairs[facing=south]")
        holdItem("create:wrench")
        serverTicks(SETTLE_TICKS)

        // What a player does in the options screen before any of this works.
        client(ALEX, BOUND_TO) { code ->
            AllKeys.ROTATE_MENU.keybind.key = InputConstants.Type.KEYSYM.getOrCreate(code)
            KeyMapping.resetMapping()
            awaitTicks(2)
        }
        serverTicks(SETTLE_TICKS)

        // The menu is about whatever is under the crosshair, so the player is put in front of it first.
        standLookingAt(stairs())

        // Held down rather than tapped, which is how this menu is worked: `keyReleased` submits the
        // sector the cursor is over and closes it. A press and a release in one gesture therefore
        // opens the screen and shuts it again in between two round trips, and what the test then
        // sees is a screen that never opened.
        client(ALEX, BOUND_TO) { code -> keyDown(Key(code)) }

        waitForScreenNamed("RadialWrenchMenu")
        shot("radial_wrench")

        // Escape rather than the release, so nothing is submitted; the key is then let go against no
        // screen at all, which leaves the client holding nothing down for whatever runs next.
        closeWithEscape()
        client(ALEX, BOUND_TO) { code -> keyUp(Key(code)) }
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    private fun stairs() = BlockPos(60, -58, ZONE)

    private companion object {

        const val ZONE = Zones.RADIAL_WRENCH

        const val SETTLE_TICKS = 10

        /** Some key to give the binding, since it ships without one. */
        const val BOUND_TO = GLFW.GLFW_KEY_R
    }
}
