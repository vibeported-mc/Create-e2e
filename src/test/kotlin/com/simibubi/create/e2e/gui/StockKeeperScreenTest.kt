package com.simibubi.create.e2e.gui

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.rightClickEntityAt
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The stock ticker's two screens, which are the shopkeeper's side of a counter and the customer's.
 *
 * Neither can be reached by a ticker standing on its own: a ticker needs somebody minding it.
 * Anything sitting on a seat beside it counts, so this shop is staffed by a pig on one -- which is
 * what both screens hang off.
 *
 * From there the two are told apart by what is clicked. Clicking the ticker itself is the owner
 * opening the categories they sort their stock into; clicking the keeper is a customer walking up to
 * the counter and asking for something.
 *
 * Ported from Create's `StockKeeperScreenTest`.
 */
@DrivesMinecraft
class StockKeeperScreenTest {

    @Test
    @DisplayName("A stock ticker with somebody minding it opens the categories its stock is sorted into")
    fun `opens the categories`(cluster: ClusterScope) = cluster.driving {
        build()

        rightClickBlock(ticker())
        waitForScreenNamed("StockKeeperCategoryScreen")
        shot("stock_keeper_categories")

        closeWithEscape()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    @Test
    @DisplayName("Walking up to the keeper of a stock ticker opens the counter to order from")
    fun `opens the counter`(cluster: ClusterScope) = cluster.driving {
        build()

        // The keeper rather than the block: the same shop, from the other side of the counter.
        rightClickEntityAt(seat())
        waitForScreenNamed("StockKeeperRequestScreen")
        shot("stock_keeper_counter")

        closeWithEscape()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    /** A ticker with a seat beside it and something sitting on the seat to mind the shop. */
    private suspend fun build() {
        clearGround(ticker(), 5)

        setBlock(ticker(), "create:stock_ticker[facing=south]")
        setBlock(seat(), "create:red_seat")

        // An empty hand, since a shopping list or a linked item is answered differently.
        holdItem("minecraft:air")

        // One is summoned only if the last test has not already left one standing about: clearing the
        // ground takes the seat away but leaves whoever was on it, so it can simply sit back down.
        if (!somebodyAlready()) {
            runCommand("summon minecraft:pig ${seat().x + 0.5} ${seat().y + 1} ${seat().z + 0.5} {NoAI:1b}")
            serverTicks(SETTLE_TICKS)
        }

        // Nothing else about the animal matters -- a ticker only asks whether the seat beside it is taken.
        sitEverybodyDown()
        serverTicks(SEATING_TICKS)

        assertTrue(seatTaken(), "Nothing sat down on the seat, so the shop has no keeper")
    }

    private suspend fun somebodyAlready(): Boolean = server(seat()) { pos ->
        serverLevel.getEntitiesOfClass(Mob::class.java, AABB(pos).inflate(4.0)).isNotEmpty()
    }

    /** Returns how many sat down, since a body that answers nothing has nothing to report. */
    private suspend fun sitEverybodyDown(): Int = server(seat()) { pos ->
        val nearby = serverLevel.getEntitiesOfClass(Mob::class.java, AABB(pos).inflate(4.0))
        for (mob in nearby) SeatBlock.sitDown(serverLevel, pos, mob)
        nearby.size
    }

    private suspend fun seatTaken(): Boolean = server(seat()) { pos ->
        serverLevel.getEntitiesOfClass(SeatEntity::class.java, AABB(pos).inflate(1.0))
            .any { it.isVehicle }
    }

    private fun ticker() = BlockPos(60, -58, ZONE)

    /** Beside the ticker, which is as near as a keeper has to be. */
    private fun seat() = ticker().east()

    private companion object {

        const val ZONE = Zones.STOCK_KEEPER

        const val SETTLE_TICKS = 10

        /** Long enough for the seat and its rider to have reached the client. */
        const val SEATING_TICKS = 20
    }
}
