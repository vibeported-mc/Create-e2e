package com.simibubi.create.e2e.gui

import com.simibubi.create.content.equipment.blueprint.BlueprintEntity
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.invokeOn
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.rightClickEntityAt
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler
import net.neoforged.neoforge.transfer.item.ItemUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The crafting blueprint, which is the only screen here that belongs to an entity rather than a block.
 *
 * A blueprint is hung on a wall the way a picture is, so the test hangs one itself: the item goes into
 * the hand and the wall is clicked, which is what puts the entity there. Clicking the blueprint after
 * that reaches the entity rather than the wall behind it, since it stands flush against the face.
 *
 * Its screen is a crafting grid of ghost slots. An item carried into the first of them should be the
 * item the blueprint is left remembering -- and since the slots are the same kind the filters and the
 * linked controller use, this is the third place that behaviour is checked from.
 *
 * Ported from Create's `BlueprintScreenTest`.
 */
@DrivesMinecraft
class BlueprintScreenTest {

    @Test
    @DisplayName("An item put into a crafting blueprint's grid is the one the blueprint is left holding")
    fun `takes an ingredient`(cluster: ClusterScope) = cluster.driving {
        clearGround(wall(), 4)
        setBlock(wall(), "minecraft:stone")
        holdItem("create:crafting_blueprint")
        runCommand("item replace entity $ALEX hotbar.1 with $INGREDIENT 1")
        serverTicks(SETTLE_TICKS)

        // Hung on the wall the way a player hangs one: the blueprint in hand, and the wall clicked.
        rightClickBlock(wall())
        serverTicks(SETTLE_TICKS)

        // Off the blueprints before clicking the one now hanging there, so a click that missed would
        // put nothing else on the wall.
        selectHotbar(1)
        serverTicks(SETTLE_TICKS)

        rightClickEntityAt(blueprint())
        waitForScreenNamed("BlueprintScreen")
        shot("blueprint_opened")

        // Two clicks, as a player makes them: the cobblestone up out of the inventory, then into the grid.
        clickSlot(slotHolding(INGREDIENT))
        clickSlot(FIRST_GRID_SLOT)
        shot("blueprint_filled")

        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()

        assertEquals(
            INGREDIENT, inTheBlueprint(),
            "The item put into the blueprint's grid did not reach the blueprint",
        )
    }

    private suspend fun selectHotbar(slot: Int) {
        client(ALEX, slot) { which ->
            clientPlayer?.inventory?.setSelectedSlot(which)
            awaitTicks(1)
        }
    }

    /** What the blueprint's first square is remembering, as a registry name. */
    private suspend fun inTheBlueprint(): String = server(wall()) { pos ->
        val hanging = serverLevel.getEntitiesOfClass(BlueprintEntity::class.java, AABB(pos).inflate(3.0))
        if (hanging.isEmpty()) throw AssertionError("No blueprint was hung on the wall at $pos")

        // A blueprint keeps its sections to itself, so the one at the front is asked through its own
        // class rather than by name.
        val section = invokeOn(hanging[0], "getSectionAt", Vec3.ZERO)
        val items = invokeOn(section, "getItems") as ItemStacksResourceHandler

        BuiltInRegistries.ITEM.getKey(ItemUtil.getStack(items, 0).item).toString()
    }

    private fun wall() = BlockPos(60, -57, ZONE)

    /** The space in front of the wall, where a blueprint clicked onto its south face ends up. */
    private fun blueprint() = wall().south()

    private companion object {

        const val ZONE = Zones.BLUEPRINT

        const val SETTLE_TICKS = 10

        /**
         * The first square of the crafting grid.
         *
         * The menu lays the player's own inventory out first -- twenty seven and a hotbar of nine --
         * and only then its own nine squares, the result and the icon.
         */
        const val FIRST_GRID_SLOT = 36

        const val INGREDIENT = "minecraft:cobblestone"
    }
}
