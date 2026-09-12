package com.simibubi.create.e2e

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Building a tooltip out of an item's name must not rename the item.
 *
 * Create and its family write lines like `builder().add(stack.getHoverName()).text(" x33")`. Catnip's
 * builder took the first component it was handed and appended to that very object, which was
 * harmless on 1.21.1 because an item's name was built fresh for each call. 26.2 hands back the
 * `ITEM_NAME` data component itself, and every stack of an item shares it.
 *
 * So a goggle tooltip, rebuilt every frame, wrote " x33" into the name of the item it was describing,
 * once per frame, for good - and styling the builder recoloured it too. What a player saw was logs
 * whose name ran off both edges of the screen in green, in the goggle overlay, in Jade's tooltip and
 * in EMI's, because all three show the item's name.
 *
 * Both halves are checked here: that the name really is shared, which is what makes the mistake
 * expensive, and that building with it leaves it alone.
 */
@DrivesMinecraft
class ItemNamesTest {

    @Test
    @DisplayName("Building a tooltip from an item's name leaves the name alone")
    fun `a lang builder does not rename what it describes`(cluster: ClusterScope) = cluster.stage {
        val shared = nameIsSharedBetweenStacks()
        assertEquals(
            true, shared,
            "26.2 is expected to hand every stack of an item the same name component; " +
                "if that has changed, this test no longer guards anything",
        )

        val (before, after) = buildTooltipsFromTheNameOf("minecraft:oak_log").split(" -> ")
        assertEquals(before, after, "Building tooltips out of an item's name changed the item's name")
    }

    /** Do two stacks of one item hand out the very same name object? */
    private suspend fun Stage.nameIsSharedBetweenStacks(): Boolean = server {
        val one = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG)
        val two = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 33)
        one.hoverName === two.hoverName
    }

    /**
     * Does to [item] what a goggle tooltip does to the stack it is describing, several frames' worth,
     * and reports its name before and after.
     */
    private suspend fun Stage.buildTooltipsFromTheNameOf(item: String): String = server(item) { id ->
        val stack = net.minecraft.world.item.ItemStack(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)),
            33,
        )
        val before = stack.hoverName.string

        repeat(5) {
            net.createmod.catnip.api.lang.Lang.builder("create")
                .add(stack.hoverName)
                .text(" x" + stack.count)
                .style(net.minecraft.ChatFormatting.GREEN)
                .string()
        }

        "$before -> ${stack.hoverName.string}"
    }
}
