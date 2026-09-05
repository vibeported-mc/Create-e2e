package com.simibubi.create.e2e.trains

import com.simibubi.create.AllDataComponents
import com.simibubi.create.content.trains.station.StationBlock
import com.simibubi.create.content.trains.station.StationBlockEntity
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAt
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.ServerScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The train station's two screens, which are the same block answering in two different states.
 *
 * A station is the one thing here that cannot simply be put down and clicked: it only has a screen
 * once it belongs to a stretch of track, and it is given that as it is placed. So both tests lay a
 * run of rails, click them with the station in hand to choose which ones it watches, and only then
 * put the station down beside them -- the two clicks a player makes.
 *
 * From there one test names the station, and the other asks it for a new train, which turns it over
 * to assembly mode and so opens the other screen the next time it is clicked.
 *
 * The originals each got a world to themselves, and here there is one -- so rather than share a
 * stretch of rails the two tests are laid far apart. That is not the usual tidiness: rails and the
 * stations on them are remembered by the railway rather than only by the blocks, so track that has
 * been claimed once stays claimed even after the blocks are cleared away, and a second station over
 * the same rails is refused by something that is no longer in the world at all.
 *
 * Ported from Create's `StationScreenTest`.
 */
@DrivesMinecraft
class StationScreenTest {

    @Test
    @DisplayName("The name typed onto a train station is the name the station is left with")
    fun `names the station`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        // Each test has ground of its own now, so both build in the same spot on it.
        val platform = at(0, 1, 0)
        build(platform)

        rightClickBlock(station(platform))
        waitForScreenNamed("StationScreen")
        shot("station_opened")

        clickWidget("nameBox")
        typeText(NAME)
        serverTicks(SETTLE_TICKS)
        shot("station_named")

        // A station has no button to accept it; what was typed is sent as the screen is taken away.
        closeWithEscape()
        serverTicks(SEND_TICKS)
        restoreHud()

        assertEquals(
            NAME, nameOf(platform),
            "The name typed onto the station did not reach it",
        )
    }

    @Test
    @DisplayName("Asking a station for a new train puts it into assembly mode and opens that screen")
    fun `switches to assembly`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        val platform = at(0, 1, 0)
        build(platform)

        rightClickBlock(station(platform))
        waitForScreenNamed("StationScreen")

        // Asking for a new train hands straight over to the other screen rather than closing this
        // one, and tells the block to turn over to assembly as it goes.
        clickWidget("newTrainButton")
        waitForScreenNamed("AssemblyScreen")
        serverTicks(SEND_TICKS)
        shot("station_assembly")

        assertTrue(assembling(platform), "The station did not turn over to assembly mode")

        closeWithEscape()
        serverTicks(SEND_TICKS)

        // And which of the two screens it opens from now on is decided by that same state.
        rightClickBlock(station(platform))
        waitForScreenNamed("AssemblyScreen")
        closeWithEscape()
        serverTicks(SEND_TICKS)
        restoreHud()
    }

    /** A run of rails with a station beside it, put down the way a player puts one down. */
    private suspend fun Stage.build(origin: BlockPos) {

        clearGround(origin, 8)
        // A straight run, so that the station has a stretch of rails to belong to.
        for (along in -TRACK_REACH..TRACK_REACH) {
            setBlock(origin.south(along), "create:track[shape=zo]")
        }

        // The block whose face the station is put against, which is what decides where it lands.
        setBlock(anchor(origin), "minecraft:stone")

        holdItem("create:track_station")
        serverTicks(SETTLE_TICKS)

        // The first click chooses which rails this station is for. Track lies flat along the bottom
        // of its block, so it is looked down on from one side rather than aimed at head-on, which
        // would pass over it and reach the next piece along.
        rightClickAt(surfaceOf(origin), besideAndAbove(origin), origin)
        serverTicks(SETTLE_TICKS)

        assertTrue(
            railsWereChosen(),
            "Clicking the rails did not choose them for the station",
        )

        // The second puts the station down, beside the rails rather than on top of them.
        rightClickBlock(anchor(origin))
        serverTicks(SETTLE_TICKS)

        assertTrue(
            belongsToTrack(origin),
            "The station was put down but does not belong to any track",
        )

        // Whether the screen opens at all is the client's decision, and it will not make it until it
        // has been told which stretch of track this station belongs to.
        client(watcher, station(origin)) { where ->
            awaitUntil {
                val be = level.getBlockEntity(where)
                be is StationBlockEntity && be.station != null
            }
        }
    }

    private suspend fun Stage.railsWereChosen(): Boolean = server(watcher) { name ->
        playerNamed(name).mainHandItem.has(AllDataComponents.TRACK_TARGETING_ITEM_SELECTED_POS)
    }

    private suspend fun Stage.belongsToTrack(origin: BlockPos): Boolean =
        server(station(origin)) { pos -> stationAt(pos).station != null }

    private suspend fun Stage.nameOf(origin: BlockPos): String = server(station(origin)) { pos ->
        stationAt(pos).station?.name ?: throw AssertionError("The station at $pos belongs to no track")
    }

    private suspend fun Stage.assembling(origin: BlockPos): Boolean =
        server(station(origin)) { pos -> serverLevel.getBlockState(pos).getValue(StationBlock.ASSEMBLING) }

    /** Where the station ends up: beside the rails, in the space beyond the block that was clicked. */
    private fun Stage.station(origin: BlockPos) = origin.east()

    /** The block whose south face is clicked, which is what decides where the station lands. */
    private fun Stage.anchor(origin: BlockPos) = station(origin).north()

    private companion object {

        const val SETTLE_TICKS = 10

        /** Long enough for the screen's packet to have crossed and been applied. */
        const val SEND_TICKS = 20

        /** How far the track runs either side of the station. */
        const val TRACK_REACH = 4

        const val NAME = "Quarry Line"

        /** Just inside the block, low enough to be on the rails rather than in the air above them. */
        fun surfaceOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.2, pos.z + 0.5)

        /**
         * Above and off to the side, looking down.
         *
         * To the side across the run rather than along it, so that nothing else on the line stands
         * between the player and the piece being aimed at.
         */
        fun besideAndAbove(pos: BlockPos) = Vec3(pos.x + 2.5, pos.y + 3.0, pos.z + 0.5)
    }
}

/** The station at [pos], on the server. */
private fun ServerScope.stationAt(pos: BlockPos): StationBlockEntity =
    serverLevel.getBlockEntity(pos) as? StationBlockEntity
        ?: throw AssertionError("There is no station at $pos")
