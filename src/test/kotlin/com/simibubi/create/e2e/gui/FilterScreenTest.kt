package com.simibubi.create.e2e.gui

import com.simibubi.create.AllDataComponents
import com.simibubi.create.e2e.clickSlot
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.slotHolding
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The three filter screens, opened and worked the way a player opens and works them.
 *
 * All three are a menu over the item in hand, and all three exist to write what was set onto that
 * item as they close, so each test ends by reading the item on the server. What the screen showed
 * has to be what the item was left with.
 *
 * The plain filter is driven furthest, since it is the one with something to carry: an item is
 * picked up out of the player's own inventory and put into a filter slot with the cursor, the same
 * two clicks a player makes.
 *
 * Ported from Create's `FilterScreenTest`.
 */
@DrivesMinecraft
class FilterScreenTest {

    @Test
    @DisplayName("A filter keeps the item it was given and the list mode it was set to")
    fun `keeps its item and mode`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        openWith("create:filter", "FilterScreen")
        shot("filter_opened")

        // Two clicks, as a player makes them: pick the cobblestone up out of the inventory, then put
        // it into the first filter slot. A filter slot only takes a likeness, so the cobblestone stays.
        clickSlot(slotHolding("minecraft:cobblestone"))
        clickSlot(FIRST_FILTER_SLOT)

        clickWidget("blacklist")
        shot("filter_set")

        close()

        assertTrue(
            hasFilterList(),
            "Closing the screen did not write the filter list onto the item",
        )
        assertEquals(
            "minecraft:cobblestone", firstFiltered(),
            "The item put into the filter slot is not the one the filter was left holding",
        )
        assertEquals(
            true, blacklisted(),
            "Choosing the deny list did not reach the item",
        )
    }

    @Test
    @DisplayName("A filter set to ignore data says so on the item")
    fun `keeps whether it respects data`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        openWith("create:filter", "FilterScreen")

        // Something has to be in the list, since a filter with nothing in it is taken back off the item.
        clickSlot(slotHolding("minecraft:cobblestone"))
        clickSlot(FIRST_FILTER_SLOT)

        clickWidget("respectNBT")
        close()

        assertEquals(
            true, respectsData(),
            "Choosing to respect data did not reach the item",
        )
    }

    @Test
    @DisplayName("The attribute filter opens on its own item and closes onto it")
    fun `the attribute filter opens`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        openWith("create:attribute_filter", "AttributeFilterScreen")
        shot("attribute_filter_opened")

        // Its own toggle, between matching any attribute and all of them.
        clickWidget("blacklist")
        close()

        assertTrue(
            hasAttributeMode(),
            "Closing the attribute filter did not write its mode onto the item",
        )
    }

    @Test
    @DisplayName("The package filter takes an address and keeps it")
    fun `the package filter keeps its address`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        openWith("create:package_filter", "PackageFilterScreen")

        typeText(ADDRESS)
        serverTicks(2)
        shot("package_filter_typed")

        close()

        assertEquals(
            ADDRESS, address(),
            "The address typed into the package filter did not reach the item",
        )
    }

    /**
     * The item in hand, right-clicked, which asks the server for the menu behind the screen.
     *
     * The player is stood on cleared ground looking at open sky rather than left wherever whatever
     * ran last put it. A filter opens on a plain right-click whatever the crosshair is on, but a
     * player who has fallen into unloaded terrain has no click to give -- and that failure reads as
     * a screen that never opened, which says nothing.
     */
    private suspend fun Stage.openWith(item: String, screen: String) {
        clearGround(here(), 4)
        standAt(
            Vec3(here().x + 0.5, here().y.toDouble(), here().z + 0.5),
            Vec3(here().x + 0.5, here().y + 30.0, here().z + 0.5),
        )

        holdItem(item)
        runCommand("item replace entity $watcher hotbar.1 with minecraft:cobblestone 1")
        serverTicks(SETTLE_TICKS)

        // A filter opens on a plain right-click, and does nothing at all on a sneaking one.
        rightClickAhead()

        waitForScreenNamed(screen)
    }

    /** Accepts the screen, which is what closes the menu and sends what was set to the server. */
    private suspend fun Stage.close() {
        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
    }

    /*
     * What the item in the player's hand was left carrying, read on the server.
     *
     * One method per component rather than one that takes the component: a `DataComponentType` is a
     * registry object and cannot cross a wire, and the answers -- a flag, a name -- can.
     */

    private suspend fun Stage.hasFilterList(): Boolean = server(watcher) { name ->
        playerNamed(name).mainHandItem.has(AllDataComponents.FILTER_ITEMS)
    }

    private suspend fun Stage.blacklisted(): Boolean? = server(watcher) { name ->
        playerNamed(name).mainHandItem.get(AllDataComponents.FILTER_ITEMS_BLACKLIST)
    }

    private suspend fun Stage.respectsData(): Boolean? = server(watcher) { name ->
        playerNamed(name).mainHandItem.get(AllDataComponents.FILTER_ITEMS_RESPECT_NBT)
    }

    private suspend fun Stage.hasAttributeMode(): Boolean = server(watcher) { name ->
        playerNamed(name).mainHandItem.has(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE)
    }

    private suspend fun Stage.address(): String? = server(watcher) { name ->
        playerNamed(name).mainHandItem.get(AllDataComponents.PACKAGE_ADDRESS)
    }

    /** The first item the filter was left holding, as its registry name. */
    private suspend fun Stage.firstFiltered(): String = server(watcher) { name ->
        val contents = playerNamed(name).mainHandItem.get(AllDataComponents.FILTER_ITEMS)
            ?: return@server "minecraft:air"

        contents.nonEmptyItems()
            .firstOrNull()
            ?.let { BuiltInRegistries.ITEM.getKey(it.typeHolder().value()).toString() }
            ?: "minecraft:air"
    }

    private fun Stage.here() = at(0, 1, 0)

    private companion object {


        /** Long enough for the server to be asked for a menu and to answer. */
        const val SETTLE_TICKS = 10

        /**
         * Where the filter's own slots start.
         *
         * A filter menu lays the player's own inventory out first -- twenty seven and a hotbar of
         * nine -- and only then its own, so the first slot of the screen is one of the player's.
         */
        const val FIRST_FILTER_SLOT = 36

        const val ADDRESS = "depot"
    }
}
