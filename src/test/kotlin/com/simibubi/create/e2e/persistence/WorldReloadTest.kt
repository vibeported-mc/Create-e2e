package com.simibubi.create.e2e.persistence

import com.simibubi.create.Create
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.PrivateServer
import dev.vibeported.mc.driver.SERVER_NODE
import dev.vibeported.mc.driver.ServerHandle
import dev.vibeported.mc.driver.clientReconnect
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.runOnServer
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.serverTicks
import dev.vibeported.mc.driver.whileItLives
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A railway, a train on it and a working machine, all still there after the server is stopped.
 *
 * The one thing this suite has never done. `TrainRunTest` writes a train to a tag and reads it back
 * inside one live server, and says so in its own comment -- those two methods are what stands between
 * a working railway and a world that comes back with its trains gone, and a break in them is
 * invisible until somebody stops their server. So this stops one.
 *
 * On a server of its own, on its own port and in its own directory, so the rest of the suite carries
 * on running beside it. Its world starts empty and what a reload has to find is exactly what this
 * test put there.
 *
 * The stage exists only to borrow a client. Nothing is built on it -- everything here happens on the
 * private server -- but borrowing is the only way to hold a client that another test running at the
 * same time will not also be driving.
 */
@DrivesMinecraft
class WorldReloadTest {

    @Test
    @DisplayName("A railway, a train and a machine all come back after the server is stopped and started")
    fun `a world survives being saved and reloaded`(cluster: ClusterScope) =
        cluster.stage(within = 10.minutes, radius = BORROWING_ONLY) {
            val watcher = theClient()
            val own = cluster.startServer(RELOAD_SERVER)

            try {
                // Over there before anything is built, and not merely for tidiness: laying a curve
                // goes through Create's own placement, which reads the look angle of a player to work
                // out which way the track leaves. On a server with nobody on it there is no player to
                // ask, and the refusal says only that the curve is invalid.
                cluster.clientReconnect(watcher, own.node)

                own.whileItLives {
                    own.layTheGround()
                    own.buildTheRailway()
                    own.buildTheTrain()
                    own.buildTheMachine()
                    own.serverTicks(GRINDING)
                }

                val before = own.readEverything()

                assertTrue(before.railways == 1, "The railway did not come out as one network: $before")
                assertTrue(before.stations == 2, "Both stations should be on it: $before")
                assertTrue(before.curves > 0, "There is no curve on this railway, so a reload proves less: $before")
                assertTrue(before.millstoneSpeed != 0.0f, "The millstone is not turning: $before")
                assertTrue(before.trainId.isNotEmpty(), "No train was assembled, so its half of this proves nothing: $before")
                assertEquals(FAR_STATION, before.scheduleBoundFor, "The train took no schedule: $before")

                // Everything above is only the setup. This is the test.
                own.saveAndStop()
                own.startAgain()

                cluster.clientReconnect(watcher, own.node)

                own.whileItLives {
                    own.runOnServer("forceload add ${ORIGIN.x} ${ORIGIN.z - RUN} ${ORIGIN.x + REACH} ${ORIGIN.z + REACH}")
                    own.serverTicks(SETTLING)
                }

                val after = own.readEverything()

                // Field by field before the whole record, so a failure names what was lost rather
                // than printing two long lines and leaving the reader to diff them.
                assertEquals(before.railways, after.railways, "The railway network did not come back: $after")
                assertEquals(before.nodes, after.nodes, "The railway came back a different shape: $after")
                assertEquals(before.edges, after.edges, "The railway came back a different shape: $after")
                assertEquals(
                    before.curves, after.curves,
                    "The curves are gone. A curve is a BezierConnection on a track block entity, so " +
                        "this is that block entity's NBT and not the railway graph: $after",
                )
                assertEquals(
                    before.stationNames, after.stationNames,
                    "The stations did not come back by name: $after",
                )
                assertEquals(
                    0, after.signalsAdrift,
                    "A signal came back without a section either side of it, which is " +
                        "RailwaySavedData re-linking its edge points and getting it wrong: $after",
                )

                assertEquals(
                    before.trainId, after.trainId,
                    "The train is gone, which is exactly what a world reloading wrong looks like: $after",
                )
                assertEquals(before.carriages, after.carriages, "The train came back short: $after")
                assertEquals(
                    before.scheduleBoundFor, after.scheduleBoundFor,
                    "The train came back without its schedule, so it has its carriages and no idea " +
                        "where it was going: $after",
                )
                assertEquals(before.scheduleStops, after.scheduleStops, "The schedule came back short: $after")
                assertTrue(
                    after.trainOnAGraph,
                    "The train came back but belongs to no railway, so `Train.read` did not find " +
                        "the graph again: $after",
                )

                assertEquals(
                    before.millstoneSpeed, after.millstoneSpeed,
                    "The millstone is not turning any more. Its speed is written to disk but its " +
                        "kinetic network is rebuilt from scratch, so this is the reconciliation: $after",
                )
                assertTrue(
                    after.millstoneOnTheMotorsNetwork,
                    "The millstone and its motor came back on different kinetic networks: $after",
                )
                // Not equality, and the difference is the point. The millstone is still grinding, so
                // its input falls between the two readings -- and a count that came back *higher*, or
                // back at the number it started with, would mean the world was reloaded from an
                // earlier save than the one this test asked for.
                assertTrue(
                    after.wheat in 1..before.wheat,
                    "The millstone came back holding ${after.wheat} wheat where it had ${before.wheat}. " +
                        "Fewer is right -- it is still grinding -- but not more, and not none: $after",
                )
            } finally {
                // Home first, and unconditionally. A client left on a server that is about to be
                // discarded is a client the pool hands to the next test, whose reset looks for a
                // player that is not there -- failing a test with nothing to do with this one.
                runCatching { cluster.clientReconnect(watcher, SERVER_NODE) }
                runCatching { own.discard() }
            }
        }

    /**
     * A train standing at the near station, assembled without anybody clicking.
     *
     * `TrainRunTest`'s recipe, and every piece of it is required by Create rather than chosen here: a
     * bogey on the rail one step along from the station, a floor for it to carry, forward-facing
     * controls, a lit blaze burner behind them to be the conductor, and superglue over the lot --
     * a bogey grips only along its own length, so the floor above it is scenery until it is glued.
     */
    private suspend fun PrivateServer.buildTheTrain() {
        put(at(0, 1, 1), "create:small_bogey[axis=z,waterlogged=false]")

        for (across in -1..1) {
            for (along in 0..2) {
                if (across == 0 && along == 1) continue
                put(at(across, 1, along), "create:railway_casing")
            }
        }

        put(at(-1, 2, 1), "create:controls[facing=south]")
        put(at(-1, 2, 2), "create:blaze_burner[blaze=smouldering]")

        server(at(-1, 1, 0), at(1, 2, 2)) { low, high ->
            serverLevel.addFreshEntity(
                com.simibubi.create.content.contraptions.glue.SuperGlueEntity(
                    serverLevel,
                    com.simibubi.create.content.contraptions.glue.SuperGlueEntity.span(low, high),
                )
            )
            true
        }

        serverTicks(SETTLING)

        val complaint = assembleIt()
        assertTrue(complaint.isEmpty(), "The station would not build a train: $complaint")

        // And somewhere to be going. A schedule is the state a train carries that nothing else in
        // this suite saves and reloads: it is written by `ScheduleRuntime.write` into the train's own
        // tag, and a train that comes back with its carriages but no orders is a train that has
        // forgotten what it was for.
        assertTrue(scheduleTo(FAR_STATION), "The train would not take a schedule")

        serverTicks(SETTLING)
    }

    /** A one-stop schedule, built as objects rather than typed into a screen. */
    private suspend fun PrivateServer.scheduleTo(called: String): Boolean = server(called) { name ->
        val train = Create.RAILWAYS.trains.values.firstOrNull() ?: return@server false

        val instruction = com.simibubi.create.content.trains.schedule.destination.DestinationInstruction()
        val text = net.minecraft.nbt.CompoundTag()
        text.putString("Text", name)
        instruction.setData(serverLevel.registryAccess(), text)

        val entry = com.simibubi.create.content.trains.schedule.ScheduleEntry(instruction, mutableListOf())
        train.runtime.setSchedule(
            com.simibubi.create.content.trains.schedule.Schedule(mutableListOf(entry), false, 0),
            false,
        )

        true
    }

    /** The station told to build, and its own account of it when it declines. */
    private suspend fun PrivateServer.assembleIt(): String = server(at(2, 0, 0)) { where ->
        val be = serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.trains.station.StationBlockEntity
            ?: return@server "there is no station there"

        if (!be.enterAssemblyMode(null)) return@server "the station would not enter assembly mode"

        be.assemble(java.util.UUID.randomUUID())

        val complaint = com.simibubi.create.content.trains.station.StationBlockEntity::class.java
            .getDeclaredField("lastException")
            .also { it.isAccessible = true }
            .get(be) as? com.simibubi.create.content.contraptions.AssemblyException

        // Out of assembly mode whatever happened. A station left assembling is one no train can be
        // routed off, and `assemble` does not leave the mode for itself.
        be.exitAssemblyMode()

        if (be.station?.getPresentTrain() != null) "" else (complaint?.component?.string ?: "no reason given")
    }

    /** Ground to build on, and chunks that stay resident while nobody is standing in them. */
    private suspend fun PrivateServer.layTheGround() {
        runOnServer("forceload add ${ORIGIN.x} ${ORIGIN.z - RUN} ${ORIGIN.x + REACH} ${ORIGIN.z + REACH}")
        runOnServer(
            "fill ${ORIGIN.x - 4} ${ORIGIN.y - 1} ${ORIGIN.z - RUN - 4} " +
                "${ORIGIN.x + REACH} ${ORIGIN.y - 1} ${ORIGIN.z + REACH} minecraft:stone"
        )
        serverTicks(SETTLING)
    }

    /**
     * Track with a real curve in it, two stations and two signals.
     *
     * The curve matters more than its length. A straight run is track blocks, which any world save
     * would carry; a curve is a `BezierConnection` held by the block entities at its two ends, with
     * markers along it and nothing else, so it is the piece that tests the block entity's own NBT.
     */
    private suspend fun PrivateServer.buildTheRailway() {
        for (along in -RUN..LEG) put(at(0, 0, along), "create:track[shape=zo]")
        for (across in CORNER..CORNER + LEG) put(at(across, 0, LEG + CORNER), "create:track[shape=xo]")
        serverTicks(SETTLING)

        val refused = connectTheCorner()
        assertTrue(refused.isEmpty(), "Create would not join the two straights into a curve: $refused")

        put(at(2, 0, 0), "create:track_station{TargetTrack:[I;-2,0,0],TargetDirection:1b}")
        put(at(2, 0, -RUN + 4), "create:track_station{TargetTrack:[I;-2,0,0],TargetDirection:0b}")
        serverTicks(SETTLING)
        nameTheFarStation()

        put(at(0, 1, -8), "create:track_signal{TargetTrack:[I;0,-1,0],TargetDirection:1b}")
        put(at(0, 1, -20), "create:track_signal{TargetTrack:[I;0,-1,0],TargetDirection:1b}")
        serverTicks(SETTLING)
    }

    /**
     * A millstone driven by a creative motor, with wheat in it.
     *
     * Both halves of the question in one block. Its timer and its two inventories are written to
     * disk, while its speed, its network and its source are not -- those are rebuilt from the blocks
     * around it when the world loads, and reconciled against what was written. A machine that comes
     * back holding its wheat but turning at zero has failed half of this and would look fine in a
     * screenshot.
     */
    private suspend fun PrivateServer.buildTheMachine() {
        put(MOTOR, "create:creative_motor[facing=up]{ScrollValue:$MOTOR_RPM}")
        put(MILL, "create:millstone")
        serverTicks(SETTLING)

        // Put in through the handler the millstone actually keeps its contents in. `/item replace
        // block ... container.0` addresses a vanilla container, and a millstone has none -- the
        // command parses, runs, and quietly puts nothing anywhere.
        server(MILL) { where ->
            val mill = serverLevel.getBlockEntity(where)
                as? com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity
                ?: return@server false

            mill.inputInv.set(
                0,
                net.neoforged.neoforge.transfer.item.ItemResource.of(
                    net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, WHEAT)
                ),
                WHEAT,
            )
            mill.setChanged()
            true
        }

        serverTicks(SETTLING)
    }

    /** Every fact this test cares about, read in one body so they are all of one moment. */
    private suspend fun PrivateServer.readEverything(): Everything = server(ORIGIN, MILL, MOTOR) { origin, mill, motor ->
        var railways = 0
        var nodes = 0
        var edges = 0
        val stationNames = mutableListOf<String>()
        var signalsAdrift = 0

        for (graph in Create.RAILWAYS.trackNetworks.values) {
            if (graph.nodes.isEmpty()) continue
            railways++
            nodes += graph.nodes.size
            edges += graph.nodes.sumOf { where ->
                graph.locateNode(where)?.let { graph.getConnectionsFrom(it)?.size ?: 0 } ?: 0
            } / 2

            graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION)
                .forEach { stationNames.add(it.name) }

            for (boundary in graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.SIGNAL)) {
                for (side in listOf(true, false)) {
                    val group: java.util.UUID? = boundary.groups.get(side)
                    if (group == null) signalsAdrift++
                }
            }
        }

        // Curves counted off the block entities rather than the graph: the graph would say a curve is
        // there because two nodes are joined, which is true whether or not the bezier survived.
        var curves = 0
        for (along in -40..20) {
            for (across in 0..16) {
                val be = serverLevel.getBlockEntity(origin.offset(across, 0, along))
                if (be is com.simibubi.create.content.trains.track.TrackBlockEntity &&
                    be.connections.isNotEmpty()
                ) {
                    curves++
                }
            }
        }

        val train = Create.RAILWAYS.trains.values.firstOrNull()
        val schedule = train?.runtime?.getSchedule()
        val bound = (schedule?.entries?.firstOrNull()?.instruction
            as? com.simibubi.create.content.trains.schedule.destination.DestinationInstruction)
            ?.filter ?: ""

        val millBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(serverLevel.getBlockState(mill).block).toString()
        val motorBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(serverLevel.getBlockState(motor).block).toString()

        val millstone = serverLevel.getBlockEntity(mill)
            as? com.simibubi.create.content.kinetics.base.KineticBlockEntity
        val creativeMotor = serverLevel.getBlockEntity(motor)
            as? com.simibubi.create.content.kinetics.base.KineticBlockEntity

        Everything(
            railways = railways,
            nodes = nodes,
            edges = edges,
            curves = curves,
            stations = stationNames.size,
            stationNames = stationNames.sorted().joinToString(", "),
            signalsAdrift = signalsAdrift,
            trainId = train?.id?.toString() ?: "",
            carriages = train?.carriages?.size ?: 0,
            trainOnAGraph = train?.graph != null,
            scheduleStops = schedule?.entries?.size ?: 0,
            scheduleBoundFor = bound,
            millBlock = millBlock,
            motorBlock = motorBlock,
            motorSpeed = creativeMotor?.speed ?: 0.0f,
            millstoneSpeed = millstone?.speed ?: 0.0f,
            millstoneOnTheMotorsNetwork = millstone?.network != null &&
                millstone.network == creativeMotor?.network,
            wheat = (serverLevel.getBlockEntity(mill)
                as? com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity)
                ?.let { net.neoforged.neoforge.transfer.item.ItemUtil.getStack(it.inputInv, 0).count }
                ?: -1,
        )
    }

    /**
     * The quarter turn, made the way the track item makes one and without anybody clicking.
     *
     * `TrackBlockItem.select` is the first click and `TrackPlacement.tryConnect` the second, both
     * public statics. Two things this cost a run each to learn, in `JourneyMapTest` before it: a turn
     * drawn across fewer than eight blocks is refused as `track.too_sharp`, and `tryConnect` only
     * counts out the track a curve would cost when the player is *not* creative -- so a player left
     * in spectator fails with `not_enough_tracks`, a message about track it never needed.
     */
    private suspend fun PrivateServer.connectTheCorner(): String =
        server(ORIGIN, at(0, 0, LEG), at(CORNER, 0, LEG + CORNER)) { origin, start, landing ->
            val player = minecraftServer.playerList.players.firstOrNull()
                ?: return@server "there is no player on this server to work the curve out from"
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE)

            val stack = com.simibubi.create.AllBlocks.TRACK.asStack()
            stack.count = 64

            val south = net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
            if (!com.simibubi.create.content.trains.track.TrackBlockItem
                    .select(serverLevel, start, south, stack)
            ) {
                return@server "the end of the straight run could not be selected"
            }

            player.setYRot(-90.0f)
            player.setXRot(0.0f)

            val info = com.simibubi.create.content.trains.track.TrackPlacement.tryConnect(
                serverLevel, player, landing, serverLevel.getBlockState(landing), stack, false, false,
            )

            // `valid` and `message` are package-private, and they are the only account Create gives
            // of a refusal -- the same words the item would have put on the screen.
            val kind = info.javaClass
            val valid = kind.getDeclaredField("valid").also { it.isAccessible = true }.getBoolean(info)
            val why = kind.getDeclaredField("message").also { it.isAccessible = true }.get(info) as String?

            if (valid) "" else (why ?: "no reason given")
        }

    private suspend fun PrivateServer.nameTheFarStation() {
        server(at(2, 0, -RUN + 4)) { where ->
            (serverLevel.getBlockEntity(where)
                as? com.simibubi.create.content.trains.station.StationBlockEntity)
                ?.updateName(FAR_STATION)
            Unit
        }
    }

    private suspend fun ServerHandle.put(at: BlockPos, state: String) {
        runOnServer("setblock ${at.x} ${at.y} ${at.z} $state")
    }

    /** Everything this test cares about, in a shape that can cross a wire. */
    @Serializable
    private data class Everything(
        val railways: Int,
        val nodes: Int,
        val edges: Int,
        val curves: Int,
        val stations: Int,
        val stationNames: String,
        val signalsAdrift: Int,
        val millBlock: String,
        val motorBlock: String,
        val motorSpeed: Float,
        val trainId: String,
        val carriages: Int,
        val trainOnAGraph: Boolean,
        val scheduleStops: Int,
        val scheduleBoundFor: String,
        val millstoneSpeed: Float,
        val millstoneOnTheMotorsNetwork: Boolean,
        val wheat: Int,
    )

    private companion object {

        const val RELOAD_SERVER = "server-reload"

        /** The stage is borrowed for its client and nothing else, so it needs no ground. */
        const val BORROWING_ONLY = 4

        /** Well clear of the lobby pad the driver lays at the origin of every world. */
        val ORIGIN: BlockPos = BlockPos(64, 64, 0)

        /**
         * The motor sits *under* the millstone, which is not the obvious way round.
         *
         * `MillstoneBlock.hasShaftTowards` answers true for `DOWN` and nothing else, so a millstone
         * takes its rotation on its underside. A motor on top of one drives nothing at all and says
         * so only by the millstone reading zero.
         */
        val MILL: BlockPos = ORIGIN.offset(12, 1, 0)
        val MOTOR: BlockPos = ORIGIN.offset(12, 0, 0)

        const val RUN = 32
        const val LEG = 6
        const val CORNER = 8
        const val REACH = 24

        const val FAR_STATION = "Aberdeen"

        /** Enough to be worth counting, and few enough that grinding cannot finish them all. */
        /** Set through block-entity NBT, the way the stations take their targets. */
        const val MOTOR_RPM = 64

        const val WHEAT = 32

        const val SETTLING = 10
        const val GRINDING = 60
    }
}

/** A position on the reload server's own grid, which has nothing to do with any stage. */
private fun at(across: Int, up: Int, along: Int): BlockPos =
    BlockPos(64 + across, 64 + up, along)
