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
import dev.vibeported.mc.driver.runOnServer
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A train built and driven with nothing in anybody's hand.
 *
 * The gap this is aimed at is a specific one, and the coverage numbers name it exactly: `Train.tick`
 * is about half covered while `ScheduleRuntime` is at four per cent, which is the signature of a train
 * that gets built, sits at its station ticking, and never departs. Everything downstream of departing
 * -- the travelling points walking along edges, the carriages following them, signals being taken and
 * given back -- is unreached because nothing has ever left a platform.
 *
 * Assembling one without clicking turns out to be straightforward, and the reason is worth writing
 * down: `StationBlockEntity.refreshAssemblyInfo` finds bogeys by scanning blockstates along the rail
 * (`getBlockState(...) instanceof AbstractBogeyBlock`), not by remembering the click that placed them.
 * So a bogey put down with `/setblock` is a bogey as far as the station is concerned, and `assemble`
 * is a public method taking nothing but the owner's id.
 *
 * The station sits *beside* the rail rather than over it, which is not decoration: the carriage is a
 * three-by-three floor centred on the bogey, and one of those nine squares is directly over the rail
 * the station watches. A station block there would be inside the train.
 *
 * @see TrainCircuitTest for the same train built by hand, which is the test that this one is not
 */
@DrivesMinecraft
class TrainRunTest {

    @Test
    @DisplayName("A train can be assembled from blocks alone, with nothing clicked")
    fun `a train assembles without clicking`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()

        val built = buildATrain()
        shot("train_assembled")

        assertTrue(
            built.assembled,
            "The station would not build a train out of what was put in front of it: $built",
        )
        assertTrue(
            built.carriages == 1,
            "One bogey with a floor on it is one carriage, and this came out as $built",
        )
    }

    @Test
    @DisplayName("A train sent to a station drives itself the length of the line")
    fun `a train drives itself to a station`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()

        val built = buildATrain()
        assertTrue(built.assembled, "There was no train to drive: $built")

        val before = whereTheTrainIs()

        // Sent, rather than shoved. Setting `targetSpeed` by hand does nothing, and finding out why
        // is what this test is really about: `tickPassiveSlowdown` brakes any train that is neither
        // being driven by a player this tick nor has a destination, so a train with a speed and
        // nowhere to go is brought to a stop on the very next tick. Somewhere to go is the mechanism.
        assertEquals("", sendTo(FAR_STATION), "The train could not be sent to $FAR_STATION")

        var waited = 0
        var travelled = 0.0

        while (waited < PATIENCE_TICKS && travelled < FAR_ENOUGH) {
            serverTicks(POLL_TICKS)
            waited += POLL_TICKS
            travelled = whereTheTrainIs().distanceFrom(before)
        }

        shot("train_moving")

        assertTrue(
            travelled >= FAR_ENOUGH,
            "The train was sent to $FAR_STATION and went $travelled blocks in ${waited / 20} " +
                "seconds, from $before to ${whereTheTrainIs()}",
        )

        // And then arrives, rather than merely setting off. A train that rolls a few blocks and
        // stalls has exercised the travelling and none of the arriving.
        var arrived = isAtTheFarStation()

        while (waited < PATIENCE_TICKS && !arrived) {
            serverTicks(POLL_TICKS)
            waited += POLL_TICKS
            arrived = isAtTheFarStation()
        }

        shot("train_arrived")

        assertTrue(
            arrived,
            "The train set off but never reached $FAR_STATION; it got as far as " +
                "${whereTheTrainIs()} in ${waited / 20} seconds",
        )
    }


    @Test
    @DisplayName("A train given a schedule drives itself to the station on it")
    fun `a train follows a schedule`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()

        val built = buildATrain()
        assertTrue(built.assembled, "There was no train to schedule: $built")

        val before = whereTheTrainIs()

        // A schedule rather than a route: the difference is who decides. Sending a train is telling
        // its navigation where to go once; a schedule hands `ScheduleRuntime` a list of places and
        // lets it work out the next one, start the navigation, notice the arrival and move on. That
        // runtime is the least-covered thing in trains by proportion, and nothing reaches it but
        // this -- a schedule that is written and never run leaves it untouched.
        assertEquals("", scheduleTo(FAR_STATION), "The train would not take a schedule")

        var waited = 0
        var arrived = isAtTheFarStation()

        while (waited < PATIENCE_TICKS && !arrived) {
            serverTicks(POLL_TICKS)
            waited += POLL_TICKS
            arrived = isAtTheFarStation()
        }

        shot("train_scheduled")

        assertTrue(
            arrived,
            "The train was scheduled to $FAR_STATION and got as far as ${whereTheTrainIs()} in " +
                "${waited / 20} seconds, having started at $before. The runtime says " +
                scheduleState(),
        )
    }

    @Test
    @DisplayName("A train written out and read back is the same train")
    fun `a train survives a round trip through nbt`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()

        val built = buildATrain()
        assertTrue(built.assembled, "There was no train to save: $built")

        // Sent on its way first, so that what is saved is a train in motion rather than one parked.
        // A train standing at a station has null-ish navigation and empty signal state, and saving
        // that would exercise the easy half of `write` and none of the half that goes wrong.
        assertEquals("", sendTo(FAR_STATION), "The train could not be set going before being saved")
        serverTicks(SETTLE_TICKS * 3)

        val roundTrip = writeAndReadBack()

        // This is the only thing in the suite that touches `Train.write` and `Train.read` at all,
        // and they are what stands between a working railway and a world that comes back after a
        // restart with its trains gone. A break here is invisible until somebody stops their server.
        assertEquals(
            "", roundTrip.complaint,
            "The train did not survive being written out and read back: ${roundTrip.complaint}",
        )
        assertEquals(
            roundTrip.before, roundTrip.after,
            "The train came back different from how it went in",
        )
    }

    /**
     * Track, a station beside it, a bogey on it and a floor over that -- the least that is a train.
     *
     * Every piece goes down with `/setblock`, including the two that a player would place by clicking
     * a rail: the station's `TargetTrack` and the bogey. What comes back says enough to tell a failure
     * apart from a refusal, because a station that declines to build says so in `lastException` and
     * otherwise does nothing at all.
     */
    private suspend fun Stage.buildATrain(): Built {
        forgetRailwaysHere()

        // The whole strip held open before a single rail goes down. A railway is longer than a stage
        // is wide, and the far end of it starts out in chunks nobody is standing near -- where
        // `/setblock` reports success and puts nothing down. The symptom is worth recognising: the
        // rail appears in patches, and *more* of it each run, as the chunks catch up behind the
        // commands that were supposed to fill them.
        holdTheChunksOpen()

        for (along in FAR_END - APPROACH..BEHIND) setBlock(at(0, 0, along), "create:track[shape=zo]")
        serverTicks(SETTLE_TICKS)

        // Beside the rail, two across, targeting it. The offset is stored against the station's own
        // position rather than the world's, so this is "two west and level with me".
        setBlock(at(2, 0, 0), "create:track_station{TargetTrack:[I;-2,0,0],TargetDirection:1b}")
        serverTicks(SETTLE_TICKS)

        // Somewhere to go, at the far end of the line. Both stations come out of the ground called
        // "Track Station" -- naming one is the screen's job, and here it is done directly -- so the
        // far one is renamed, because a destination is chosen by name and two of the same name is a
        // choice that cannot be made.
        // Facing back the way the train will come. A station can only be arrived at from its front --
        // `canApproachFrom` is `isPrimary(side)`, and the search drops any station reached from
        // behind -- so a destination pointed the same way as the train is a destination the router
        // will not admit exists.
        // Checked before the station goes down, because a station whose target is air throws a bare
        // ClassCastException out of `getTrack()` -- `AirBlock cannot be cast to ITrackBlock` -- which
        // names neither the station nor the missing rail.
        val along = StringBuilder()
        for (step in FAR_END - APPROACH..BEHIND step 8) along.append(" $step=").append(blockAt(at(0, 0, step)))

        assertEquals(
            "create:track", blockAt(at(0, 0, FAR_END)),
            "The rail the far station is meant to watch was not laid. Along the run:$along",
        )

        setBlock(at(2, 0, FAR_END), "create:track_station{TargetTrack:[I;-2,0,0],TargetDirection:1b}")
        serverTicks(SETTLE_TICKS)
        assertTrue(nameFarStation(), "The far station would not take its name")

        assertTrue(enterAssembly(), "The station would not go into assembly mode")
        serverTicks(SETTLE_TICKS)

        // The frontmost bogey has to sit at the station's own end of the marked stretch -- offset
        // zero, which is one step along from the rail the station watches. Anywhere else and the
        // station answers with `frontmost_bogey_at_station` and builds nothing.
        setBlock(at(0, 1, 1), "create:small_bogey[axis=z,waterlogged=false]")

        for (across in -1..1) {
            for (along in 0..2) {
                if (across == 0 && along == 1) continue
                setBlock(at(across, 1, along), CASING)
            }
        }

        // Controls, facing the way the train is being built. Not decoration and not optional: a
        // train with nothing at the front to drive it from is refused outright, with
        // "attach at least one forward-facing Train Controls block". Which is the rule working --
        // the station is checking that what it has been handed is a train rather than a shed.
        setBlock(at(-1, 2, 1), "create:controls[facing=south]")

        // And a conductor behind them, which is the piece that turns a train into one that can be
        // sent anywhere. `Navigation.findPathTo` ends with `canDriveForward = hasForwardConductor()
        // || runtime.paused` and returns no route at all when that is false -- so a train with no
        // conductor cannot be routed, and says only that there is no route, which is a long way from
        // saying why.
        //
        // A conductor is a lit blaze burner sitting where the controls face away from, and Create
        // works that out with `inControl`: the controls have to be one step from the burner in the
        // direction opposite their facing. South-facing controls therefore want their burner to the
        // south of them, and the burner has to have a blaze in it -- `heat_level=none` is a cold
        // burner and does not count. The property is called `blaze`, not `heat_level`.
        setBlock(at(-1, 2, 2), "create:blaze_burner[blaze=smouldering]")

        // And glue over the lot. A bogey grips only along its own length, so the floor sitting on top
        // of it and the controls standing on that are, as far as the station is concerned, scenery the
        // train happens to be parked under. The glue is what makes them the train -- and it goes on as
        // an entity spanning the box, which is exactly what a player's click ends up doing.
        glueTheCarriage()

        serverTicks(SETTLE_TICKS)
        shot("train_before_assembly")

        val built = assembleIt()

        // Out of assembly mode once it is built, which `assemble` does not do for itself. It matters
        // more than it looks: `GlobalStation.canNavigateVia` is false while a station is assembling,
        // so a train left standing at one that never came out of the mode can be routed nowhere --
        // `findPathTo` simply returns no route, and says nothing about why.
        if (built.assembled) {
            assertTrue(exitAssembly(), "The station would not come out of assembly mode")
            serverTicks(SETTLE_TICKS)
        }

        return built
    }

    private suspend fun Stage.glueTheCarriage(): Boolean = server(at(-1, 1, 0), at(1, 2, 2)) { low, high ->
        serverLevel.addFreshEntity(
            com.simibubi.create.content.contraptions.glue.SuperGlueEntity(
                serverLevel,
                com.simibubi.create.content.contraptions.glue.SuperGlueEntity.span(low, high),
            )
        )
        true
    }

    /** The station told to build, and what it made of it. */
    private suspend fun Stage.assembleIt(): Built = server(at(2, 0, 0)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server Built(false, 0, 0, "there is no station there")

        be.assemble(java.util.UUID.randomUUID())

        val complaint = com.simibubi.create.content.trains.station.StationBlockEntity::class.java
            .getDeclaredField("lastException")
            .also { it.isAccessible = true }
            .get(be) as? com.simibubi.create.content.contraptions.AssemblyException

        val bogeys = com.simibubi.create.content.trains.station.StationBlockEntity::class.java
            .getDeclaredField("bogeyCount")
            .also { it.isAccessible = true }
            .getInt(be)

        val train = be.station?.getPresentTrain()

        Built(
            assembled = train != null,
            carriages = train?.carriages?.size ?: 0,
            bogeys = bogeys,
            complaint = complaint?.component?.string ?: "",
        )
    }

    private suspend fun Stage.holdTheChunksOpen() {
        val low = at(-2, 0, FAR_END - APPROACH)
        val high = at(2, 0, BEHIND)
        runOnServer("forceload add ${low.x} ${low.z} ${high.x} ${high.z}")
        serverTicks(SETTLE_TICKS)
    }

    private suspend fun Stage.blockAt(where: BlockPos): String = server(where) { pos ->
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(serverLevel.getBlockState(pos).block)
            .toString()
    }

    private suspend fun Stage.nameFarStation(): Boolean = server(at(2, 0, FAR_END)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server false

        be.updateName(FAR_STATION)
    }

    /**
     * The train pointed at a station, and told to make its own way there.
     *
     * Returns what went wrong rather than a boolean, because there are four different ways for this
     * to come to nothing -- no train, no railway, no such station, no route to it -- and they want
     * telling apart in a failure message.
     */
    private suspend fun Stage.sendTo(called: String): String = server(at(0, 0, 0), called) { corner, name ->
        val train = trainNear(corner) ?: return@server "there is no train over this stage"
        val graph = train.graph ?: return@server "the train belongs to no railway"

        val destination = graph
            .getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION)
            .firstOrNull { it.name == name }
            ?: return@server "there is no station called $name on this railway"

        val path = train.navigation.findPathTo(destination, Double.MAX_VALUE)
            ?: return@server "no route from here to $name" +
                " (forward conductor: ${train.hasForwardConductor()}," +
                " backward: ${train.hasBackwardConductor()}," +
                " schedule paused: ${train.runtime.paused}," +
                " conductors on carriage 0: ${train.carriages[0].presentConductors}," +
                " destination assembling: ${destination.assembling}," +
                " derailed: ${train.derailed})"

        train.navigation.startNavigation(path)
        ""
    }

    /** Whether the train has come to rest at the far station, which is the station's own answer. */
    private suspend fun Stage.isAtTheFarStation(): Boolean = server(at(2, 0, FAR_END)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server false

        be.station?.getPresentTrain() != null
    }

    private suspend fun Stage.enterAssembly(): Boolean = server(at(2, 0, 0)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server false

        be.enterAssemblyMode(null)
    }

    private suspend fun Stage.exitAssembly(): Boolean = server(at(2, 0, 0)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server false

        be.exitAssemblyMode()
    }

    /** Where the front of the train has got to, in the world. */
    private suspend fun Stage.whereTheTrainIs(): Where = server(at(0, 0, 0)) { corner ->
        val train = trainNear(corner) ?: return@server Where(0.0, 0.0, 0.0, present = false)

        val point = train.carriages.firstOrNull()?.leadingPoint
            ?: return@server Where(0.0, 0.0, 0.0, present = false)

        val at = point.getPosition(train.graph)
        Where(at.x, at.y, at.z, present = true)
    }

    /** A one-stop schedule, built as objects rather than typed into a screen. */
    private suspend fun Stage.scheduleTo(called: String): String = server(at(0, 0, 0), called) { corner, name ->
        val train = trainNear(corner) ?: return@server "there is no train over this stage"

        val instruction = com.simibubi.create.content.trains.schedule.destination.DestinationInstruction()
        val text = net.minecraft.nbt.CompoundTag()
        text.putString("Text", name)
        instruction.setData(serverLevel.registryAccess(), text)

        val entry = com.simibubi.create.content.trains.schedule.ScheduleEntry(instruction, mutableListOf())
        val schedule = com.simibubi.create.content.trains.schedule.Schedule(
            mutableListOf(entry),
            false,
            0,
        )

        train.runtime.setSchedule(schedule, false)

        // Read straight back off the instruction, because a filter that did not take is the quiet
        // way this fails: the runtime matches station names against it, and an empty one matches
        // nothing at all while looking, from the outside, exactly like a train that will not move.
        val filter = instruction.filter
        if (filter != name) "the schedule came out filtering on \"$filter\" rather than \"$name\"" else ""
    }

    /** What the schedule runtime is making of it, for when the train has not moved. */
    private suspend fun Stage.scheduleState(): String = server(at(0, 0, 0)) { corner ->
        val train = trainNear(corner) ?: return@server "there is no train over this stage"

        "state=${train.runtime.state}, paused=${train.runtime.paused}," +
            " completed=${train.runtime.completed}, entry=${train.runtime.currentEntry}," +
            " destination=${train.navigation.destination?.name}," +
            " forwardConductor=${train.hasForwardConductor()}," +
            " statusNavigation=${train.status.navigation}," +
            " statusConductor=${train.status.conductor}," +
            " speed=${train.speed}"
    }

    /**
     * The train written to a tag and read straight back, and whether the two agree.
     *
     * Compared on what a save is actually for -- where it is, how fast, what it is called, how many
     * carriages, which signal sections it holds -- rather than on the tag itself. Two tags that
     * differ may both be correct; a train that comes back somewhere else is a train that has been
     * lost, and that is the failure worth catching.
     */
    private suspend fun Stage.writeAndReadBack(): RoundTrip = server(at(0, 0, 0)) { corner ->
        val train = trainNear(corner)
            ?: return@server RoundTrip("", "", "there is no train over this stage")

        try {
            val dimensions = com.simibubi.create.content.trains.graph.DimensionPalette()
            val registries = serverLevel.registryAccess()

            val tag = train.write(dimensions, registries)
            val read = com.simibubi.create.content.trains.entity.Train.read(
                tag,
                registries,
                Create.RAILWAYS.trackNetworks,
                dimensions,
            ) ?: return@server RoundTrip("", "", "reading the train back produced nothing at all")

            RoundTrip(describe(train), describe(read), "")
        } catch (wrong: Throwable) {
            RoundTrip("", "", wrong.javaClass.simpleName + ": " + wrong.message)
        }
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

    /** What a written-out train and the one read back from it each came to. */
    @Serializable
    private data class RoundTrip(val before: String, val after: String, val complaint: String)

    /** What the station made, in a shape that can cross a wire. */
    @Serializable
    private data class Built(
        val assembled: Boolean,
        val carriages: Int,
        val bogeys: Int,
        val complaint: String,
    ) {
        override fun toString(): String = when {
            assembled -> "a train of $carriages carriage(s) on $bogeys bogey(s)"
            complaint.isNotEmpty() -> "no train -- the station said \"$complaint\" (it saw $bogeys bogey(s))"
            else -> "no train and no complaint either, with $bogeys bogey(s) seen"
        }
    }

    /** And where it is, which only means anything against where it was. */
    @Serializable
    private data class Where(val x: Double, val y: Double, val z: Double, val present: Boolean) {
        fun distanceFrom(other: Where): Double =
            if (!present || !other.present) 0.0
            else kotlin.math.sqrt(
                (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
            )

        override fun toString(): String =
            if (!present) "nowhere -- there is no train" else "(%.1f, %.1f, %.1f)".format(x, y, z)
    }

    private companion object {

        /**
         * How much ground this class needs, which is more than a stage hands out by default.
         *
         * A railway fifty-odd blocks long does not fit on a stage sized for a machine, and what
         * happens when it does not fit is quiet: the `setblock`s past the edge report success and
         * put nothing down, so the rail simply is not there and the first thing to notice is a
         * station casting air to `ITrackBlock`.
         */
        const val ROOM = 64

        /**
         * Where the far station sits, which is *behind* the train as it was built.
         *
         * Not arbitrary, and the thing that took longest to see: a train is assembled with its
         * frontmost bogey at the station, extending away in the assembly direction, so its front
         * ends up pointing back along the stretch it was built on. Building southward makes a train
         * that drives north. A destination laid out ahead of the assembly area is a destination
         * behind the train, and the router will not reverse into it without a schedule paused --
         * which reads, from outside, as a train that simply refuses to go anywhere.
         */
        const val FAR_END = -40

        /** Track past the far station, so it is not sitting on the end node. */
        const val APPROACH = 4

        /** And track behind the train, which is the stretch it was assembled along. */
        const val BEHIND = 8

        const val CASING = "create:railway_casing"

        const val FAR_STATION = "Aberdeen"

        /** Further than any settling or nudging could account for. */
        const val FAR_ENOUGH = 4.0

        /** How far from this stage's corner a railway still counts as this test's. */
        const val REACH = 64

        const val SETTLE_TICKS = 10

        const val PATIENCE_TICKS = 60 * 20

        const val POLL_TICKS = 10
    }
}

/**
 * The train running over this stage, if there is one.
 *
 * Found by where its carriages are rather than by remembering an id, because `Create.RAILWAYS.trains`
 * is one map for the whole server and a test that kept an id would still have to say which of them it
 * meant when the id turned out to be stale.
 */
/** A train said in the terms a save has to preserve, and no others. */
private fun describe(train: com.simibubi.create.content.trains.entity.Train): String {
    val point = train.carriages.firstOrNull()?.leadingPoint
    val at = point?.getPosition(train.graph)

    return listOf(
        "name=" + train.name.string,
        "carriages=" + train.carriages.size,
        "speed=" + "%.3f".format(train.speed),
        "derailed=" + train.derailed,
        "sections=" + train.occupiedSignalBlocks.size,
        "at=" + (at?.let { "%.2f,%.2f,%.2f".format(it.x, it.y, it.z) } ?: "nowhere"),
    ).joinToString(" ")
}

private fun dev.vibeported.mc.driver.ServerScope.trainNear(
    corner: BlockPos,
): com.simibubi.create.content.trains.entity.Train? =
    Create.RAILWAYS.trains.values.firstOrNull { train ->
        val graph = train.graph ?: return@firstOrNull false
        train.carriages.any { carriage ->
            val at = carriage.leadingPoint.getPosition(graph)
            kotlin.math.abs(at.x - corner.x) <= 64 && kotlin.math.abs(at.z - corner.z) <= 64
        }
    }
