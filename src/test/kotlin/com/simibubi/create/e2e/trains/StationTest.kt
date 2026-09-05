package com.simibubi.create.e2e.trains

import com.simibubi.create.Create
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Stations, bound to track the way a signal is, and put into assembly mode without a screen.
 *
 * A station is the other kind of point a railway carries, and it is the more complicated one: a signal
 * only ever says stop or go, where a station is a named place a schedule can be pointed at, the thing
 * a train arrives *at*, and the thing that builds a train in the first place. Assembly mode is where
 * that last part starts -- and it is a mode of the station rather than of the screen that turns it on,
 * which is what makes it reachable from here.
 *
 * `TrainCircuitTest` reaches the same mode by clicking the station's screen, and that is the point of
 * it. This does not, so that what is left when the clicking is taken away can be checked on its own,
 * and checked beside its neighbours rather than after them.
 *
 * @see SignalTest for the same binding, done to the other kind of point
 */
@DrivesMinecraft
class StationTest {

    @Test
    @DisplayName("A station placed over track binds to it and takes a name")
    fun `a station binds to the track under it`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        placeStation(at(0, 1, THE_STATION))
        serverTicks(SETTLE_TICKS)
        shot("station_bound")

        val station = stationAt(at(0, 1, THE_STATION))

        assertTrue(
            station.bound,
            "The station never found the track one block under it: $station",
        )

        // A name at all, rather than a particular one. Create gives a new station a generated name so
        // that a schedule has something to point at before anyone has opened its screen, and a
        // station with an empty name is one that never got as far as being a place.
        assertTrue(
            station.name.isNotEmpty(),
            "A station is somewhere a schedule can name, and this one is called nothing: $station",
        )
        assertTrue(
            !station.assembling,
            "A station that has just been placed is not building anything yet: $station",
        )
    }

    @Test
    @DisplayName("A station enters and leaves assembly mode")
    fun `a station can be put into assembly mode`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        val where = at(0, 1, THE_STATION)
        placeStation(where)
        serverTicks(SETTLE_TICKS)

        assertTrue(enterAssembly(where), "The station refused to go into assembly mode")
        serverTicks(SETTLE_TICKS)
        shot("station_assembling")

        val building = stationAt(where)

        assertTrue(building.assembling, "The station says it is not assembling: $building")

        // The blockstate as well as the field. Assembly mode changes what the block looks like and
        // what the station will let a train do -- `canNavigateVia` turns false while it is on -- so a
        // station whose flag is set and whose block never changed is half in the mode.
        assertTrue(
            building.blockSaysAssembling,
            "The station block did not change to its assembling state: $building",
        )

        assertTrue(exitAssembly(where), "The station would not come out of assembly mode")
        serverTicks(SETTLE_TICKS)

        val done = stationAt(where)

        assertTrue(!done.assembling, "The station stayed in assembly mode: $done")
        assertTrue(!done.blockSaysAssembling, "The station block stayed assembling: $done")
    }

    @Test
    @DisplayName("Two stations on one railway are two places, and either can be renamed")
    fun `two stations are two places`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        placeStation(at(0, 1, THE_STATION))
        placeStation(at(0, 1, THE_OTHER_STATION))
        serverTicks(SETTLE_TICKS * 2)
        shot("station_pair")

        val placed = stationsHere()

        assertEquals(
            2, placed.names.size,
            "Both stations should be points on the one railway, and the railway has $placed",
        )

        // Both called the same thing, and that is right rather than a bug worth asserting away.
        // `GlobalStation` names every new station "Track Station"; telling two apart is the screen's
        // job, done when a player types into it, and nothing about placing one does it for them.
        assertEquals(
            listOf(PLACED_NAME, PLACED_NAME), placed.names,
            "A station takes its name from nothing but its default until somebody types one: $placed",
        )

        // So the interesting half is the renaming, which is what the screen ends up calling and what
        // a schedule then has to be able to find.
        assertTrue(rename(at(0, 1, THE_STATION), A_NEW_NAME), "The station refused its new name")
        serverTicks(SETTLE_TICKS)

        val renamed = stationsHere()

        assertEquals(
            listOf(A_NEW_NAME, PLACED_NAME).sorted(), renamed.names,
            "Renaming one station of two should have changed exactly that one: $renamed",
        )
    }

    /** A station block, bound to the track one below it, the way its item would have placed it. */
    private suspend fun Stage.placeStation(where: BlockPos) {
        setBlock(where, "create:track_station{TargetTrack:[I;0,-1,0],TargetDirection:1b}")
    }

    private suspend fun Stage.enterAssembly(where: BlockPos): Boolean = server(where) { pos ->
        stationBlockEntity(pos)?.enterAssemblyMode(null) ?: false
    }

    private suspend fun Stage.exitAssembly(where: BlockPos): Boolean = server(where) { pos ->
        stationBlockEntity(pos)?.exitAssemblyMode() ?: false
    }

    private suspend fun Stage.rename(where: BlockPos, called: String): Boolean =
        server(where, called) { pos, name ->
            stationBlockEntity(pos)?.updateName(name) ?: false
        }

    /** What a station there says about itself, asked of the block entity rather than of a picture. */
    private suspend fun Stage.stationAt(where: BlockPos): Station = server(where) { pos ->
        val be = stationBlockEntity(pos)
            ?: return@server Station(false, "", assembling = false, blockSaysAssembling = false)

        val station = be.station
        val assembling = serverLevel.getBlockState(pos)
            .getOptionalValue(com.simibubi.create.content.trains.station.StationBlock.ASSEMBLING)
            .orElse(false)

        Station(
            bound = station != null,
            name = station?.name ?: "",
            assembling = station?.assembling ?: false,
            blockSaysAssembling = assembling,
        )
    }

    /** The names of every station on the railway over this stage. */
    private suspend fun Stage.stationsHere(): Stations = server(at(0, 0, 0)) { corner ->
        val names = mutableListOf<String>()

        for (graph in Create.RAILWAYS.trackNetworks.values) {
            val mine = graph.nodes.any { where ->
                kotlin.math.abs(where.location.x - corner.x) <= REACH &&
                    kotlin.math.abs(where.location.z - corner.z) <= REACH
            }

            if (!mine) continue

            names.addAll(
                graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION)
                    .map { it.name }
            )
        }

        Stations(names.sorted())
    }

    private suspend fun Stage.layStraightTrack(length: Int) {
        for (along in 0 until length) setBlock(at(0, 0, along), "create:track[shape=zo]")
    }

    /** As in [TrackGraphTest]: a swept stage keeps its railways, so each test drops them first. */
    private suspend fun Stage.forgetRailwaysHere() {
        server(at(0, 0, 0)) { corner ->
            val stale = Create.RAILWAYS.trackNetworks.entries
                .filter { (_, graph) ->
                    graph.nodes.any { where ->
                        kotlin.math.abs(where.location.x - corner.x) <= REACH &&
                            kotlin.math.abs(where.location.z - corner.z) <= REACH
                    }
                }
                .map { it.key }

            stale.forEach { Create.RAILWAYS.trackNetworks.remove(it) }
            stale.size
        }
    }

    /** Every station on a railway, in a shape that can cross a wire: a bare list cannot. */
    @Serializable
    private data class Stations(val names: List<String>) {
        override fun toString(): String =
            if (names.isEmpty()) "no stations" else names.joinToString(", ") { "\"$it\"" }
    }

    /** What one station says, in a shape that can cross a wire. */
    @Serializable
    private data class Station(
        val bound: Boolean,
        val name: String,
        val assembling: Boolean,
        val blockSaysAssembling: Boolean,
    ) {
        override fun toString(): String = when {
            !bound -> "a station bound to nothing"
            assembling || blockSaysAssembling ->
                "the station \"$name\", assembling=$assembling, block=$blockSaysAssembling"
            else -> "the station \"$name\""
        }
    }

    private companion object {

        const val RUN = 16

        /** Far enough along the run that the station is not sitting on an end node. */
        const val THE_STATION = 4

        const val THE_OTHER_STATION = 12

        /** What `GlobalStation` calls a station nobody has named yet. */
        const val PLACED_NAME = "Track Station"

        const val A_NEW_NAME = "Aberdeen"


        /** How far from this stage's corner a node still counts as this test's. */
        const val REACH = 64

        const val SETTLE_TICKS = 10
    }
}

/**
 * The station block entity at a position, or null if what is there is something else.
 *
 * A top-level function rather than a method, because it is called from inside bodies that run on the
 * server and those may not reach back into the test object they were written in.
 */
private fun dev.vibeported.mc.driver.ServerScope.stationBlockEntity(
    where: BlockPos,
): com.simibubi.create.content.trains.station.StationBlockEntity? =
    serverLevel.getBlockEntity(where)
        as? com.simibubi.create.content.trains.station.StationBlockEntity
