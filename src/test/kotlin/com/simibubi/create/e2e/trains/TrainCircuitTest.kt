package com.simibubi.create.e2e.trains

import com.simibubi.create.Create
import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.contraptions.ContraptionHandlerClient
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket
import net.createmod.catnip.api.client.network.ClientNetworkHelper
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.trains.TrainHUD
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity
import com.simibubi.create.content.trains.station.StationBlockEntity
import com.simibubi.create.content.trains.track.TrackBlockEntity
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.closeWithEscape
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAt
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.typeText
import com.simibubi.create.e2e.waitForScreenNamed
import dev.vibeported.mc.driver.ClientScope
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.ServerScope
import dev.vibeported.mc.driver.MouseButton
import dev.vibeported.mc.driver.click
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.Container
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.transfer.item.ItemUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * A railway laid, a train built on it, and a run between two stations -- all of it clicked together
 * the way a player does it rather than conjured with commands.
 *
 * Track in particular cannot be conjured. There is no curve to place: only straights and diagonals
 * exist as blocks, and every real turn is a curve the placement code works out from where the player
 * stood and which way they were looking. Clicking it together is the only way to get one, which is
 * why this builds the circuit the long way round.
 *
 * The shape is a running track: two long straights joined at each end by a half turn, and each half
 * turn is two quarter turns with a short piece between them -- a single sweep through half a circle
 * is more than the placement will agree to.
 *
 * The ground is the world's own: flat grass at one height across the whole circuit, so nothing needs
 * levelling first. That is also why the circuit is given the far end of the world to itself rather
 * than a scene's worth of room -- it is forty blocks square, and it stands on ground no other test
 * has flattened.
 *
 * The one thing worked differently from the original is the driving. There each tick of the run was
 * a handful of round trips from the test to the client, and the offer to draw in at a station is a
 * passing thing -- a train at speed is past a station within a few ticks of being told it is coming
 * up. Here the watching and the key both happen on the client, in one body, so the answer to an
 * offer is sent on the tick it appears rather than a round trip later.
 *
 * Ported from Create's `TrainCircuitTest`.
 */
@DrivesMinecraft
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TrainCircuitTest {

    /** Where the corners ended up, kept from the laying so the phases after can check them. */
    private val turns = mutableListOf<BlockPos>()

    @Test
    @Order(1)
    @DisplayName("A closed circuit of track is laid by clicking, half turns and all")
    fun `lays a circuit of track`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        // Somewhere to watch from that also loads the chunks. Track laid into a chunk nobody is near
        // is track the server does not keep, and a curve is worked out from the pieces at both ends.
        watchTheCircuit()

        holdItem("create:track", 64)
        serverTicks(SETTLE_TICKS)

        // The first piece goes down like any block. Which way it lies follows the way the player
        // faces, so it is clicked at from one side rather than from straight overhead.
        placeTrackFacing(start(), SIDES[0])

        var cursor = start()
        turns.clear()

        for (side in SIDES.indices) {
            val along = SIDES[side]
            val next = SIDES[(side + 1) % SIDES.size]

            // Along the same line, picking the far end and clicking on runs the track out straight.
            selectEnd(cursor, along)

            val turn = cursor.relative(along, LENGTHS[side])
            placeTrackFacing(turn, along)
            turns.add(turn)

            // Across it, the placement works the quarter turn out for itself.
            selectEnd(turn, along)

            val landing = turn.relative(along, CORNER).relative(next, CORNER)

            // The last turn comes back round onto the piece it all started from, closing the ring.
            if (side == SIDES.size - 1) connectToExisting(start(), next) else placeTrackFacing(landing, next)

            cursor = landing
        }

        watchTheCircuit()
        serverTicks(SETTLE_TICKS)
        shot("circuit_laid")
        restoreHud()

        for (turn in turns) {
            assertTrue(
                hasCurveAt(turn),
                "The corner at $turn was not turned into a curve; the track there is " +
                    "${describe(turn)} | map ${map()}",
            )
        }

        assertEquals(1, graphCount(), "The circuit did not come out as one railway but as ${graphCount()}")
    }

    @Test
    @Order(2)
    @DisplayName("Three stations are put on the circuit and given their names")
    fun `places three stations`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        assertEquals(
            1, graphCount(),
            "There is no railway to put stations on -- did the circuit fail to be laid?",
        )

        // Two on the near straight and one across the circuit. The parking station is where the train
        // is built and where it comes home to, so that a lap of the circuit is a thing of its own and
        // has nothing to do with loading or unloading.
        // Each is named where it stands, before moving on to the next: the player is already there,
        // and going back round afterwards is another journey for nothing.
        placeAndName(PARKING, PARKING_NAME)
        placeAndName(LOADING, LOADING_NAME)
        placeAndName(UNLOADING, UNLOADING_NAME)

        watchTheCircuit()
        serverTicks(SETTLE_TICKS)
        shot("stations_placed")
        restoreHud()

        assertEquals(PARKING_NAME, stationName(PARKING), "The parking station kept the wrong name")
        assertEquals(LOADING_NAME, stationName(LOADING), "The loading station kept the wrong name")
        assertEquals(UNLOADING_NAME, stationName(UNLOADING), "The unloading station kept the wrong name")
    }

    @Test
    @Order(3)
    @DisplayName("A train is built on the rails at the parking station and assembled from its screen")
    fun `builds a train at the parking station`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        assertEquals(
            PARKING_NAME, stationName(PARKING),
            "There is no named station to build a train at -- did the station phase fail?",
        )

        enterAssemblyMode()

        // A bogey appears on the rail wherever the marked stretch is clicked. One is enough for a
        // carriage, and every bogey put down needs a body of its own hung off it.
        holdItem("create:railway_casing")
        serverTicks(SETTLE_TICKS)

        clickTrack(bogeyTrack(0))

        // A station only counts up what is standing on its rails on a lazy tick, so what it says about
        // them is a good deal older than the click that put them there.
        serverTicks(SETTLE_TICKS * 4)

        assertTrue(
            bogeyOffsets().startsWith("[0, -1,"),
            "Clicking the marked rail did not put bogeys down; the station has ${bogeyOffsets()}" +
                " | ${assemblyState()}",
        )

        // The carriage itself is set out rather than clicked together: the fittings have to sit
        // exactly where they belong, and a bogey answers a click of its own instead of taking a block.
        buildCarriage()
        serverTicks(SETTLE_TICKS)

        // Glue holds the carriage together. A bogey only grips along its own length, so anything
        // stacked on top of it -- which is the whole body -- is not part of the train until it is
        // glued on. The box takes in the floor and everything standing on it.
        glueTheCarriage()
        serverTicks(SETTLE_TICKS)

        assemble()

        assertEquals(
            1, trainCount(),
            "The station did not assemble a train; its bogeys were at ${bogeyOffsets()}, it could " +
                "build over ${assemblyLength()} blocks | ${assemblyState()}",
        )

        // Everything the carriage was built out of has to have come with it. Whatever the glue missed
        // would have been left standing on the rails instead, so this is what shows it took the whole
        // floor.
        for (fitting in FITTINGS) {
            assertTrue(
                carriageHolds(fitting),
                "The train was assembled without its $fitting, so the glue did not take the whole " +
                    "floor with it. It carries ${carriageContents()} and what is still standing on " +
                    "the rails is ${leftBehind()}",
            )
        }

        watchTheTrain()
        shot("train_assembled")
        restoreHud()
    }

    @Test
    @Order(4)
    @DisplayName("The train is ridden a full circuit by hand and parked back at the parking station")
    fun `rides the train round the circuit`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        assertEquals(1, trainCount(), "There is no train to ride -- did the building phase fail?")

        // A lap and nothing else: away down the far side of the circuit, past the other two stations
        // without stopping at either, and home to the one it set off from.
        driveTo(PARKING_NAME, aLapFirst = true, pictures = "circuit")

        assertEquals(
            theTrainsName(), trainWaitingAt(PARKING),
            "The train did not end up parked at the station it set off from",
        )
    }

    @Test
    @Order(5)
    @DisplayName("A loading building and an unloading building are put up, both standing idle")
    fun `builds the loading and unloading stations`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        assertEquals(1, trainCount(), "There is no train to load -- did the building phase fail?")

        build(loadingSide(), loading = true)
        build(unloadingSide(), loading = false)
        serverTicks(SETTLE_TICKS)

        // Both stand still to begin with. A belt running before the train is there feeds an interface
        // with nothing on the other side of it, and whatever it sends that way is simply gone.
        stopTheBelt(loadingSide())
        stopTheBelt(unloadingSide())

        watchTheBuilding(loadingSide())
        shot("loading_station")

        watchTheBuilding(unloadingSide())
        shot("unloading_station")
        restoreHud()

        for (where in listOf(loadingSide(), unloadingSide())) {
            assertEquals(
                "create:portable_storage_interface", blockAt(where),
                "The building at $where has no interface for the train to draw up against",
            )

            // A funnel that finds itself standing on a belt becomes a belt funnel, which is the whole
            // point of putting it there, so either name means it is where it should be.
            assertTrue(
                blockAt(stationFunnel(where)).endsWith("funnel"),
                "The building at $where has no funnel on the belt beside its interface, but " +
                    blockAt(stationFunnel(where)),
            )
            assertTrue(
                blockAt(farFunnel(where)).endsWith("funnel"),
                "The building at $where has no funnel on the belt beside its chest, but " +
                    blockAt(farFunnel(where)),
            )
        }

        // Nothing is asked here about where the load has got to. The funnel at the chest empties it
        // onto the belt the moment it is put there, stopped belt or not, so it is staged along the run
        // rather than sitting in the chest; whether it reaches the train is what the next phase is for.
    }

    @Test
    @Order(6)
    @DisplayName("The train draws in at the loading station and takes the goods aboard")
    fun `loads the train at the loading station`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        driveTo(LOADING_NAME, aLapFirst = false, pictures = "to_the_loading_station")

        assertEquals(
            theTrainsName(), trainWaitingAt(LOADING),
            "The train did not draw in at the loading station",
        )

        // Off the train and stood back, so that the whole of it and the whole of the building it has
        // drawn up against are in one view before anything moves.
        stepOffTheTrain()
        watchTheWholeSetup(loadingSide())
        shot("train_at_the_loading_station")

        // Only now is the belt set going. Until the train drew in there was nothing on the far side of
        // the interface, and a belt running into one that is not coupled up loses what it carries.
        driveTheBelt(loadingSide(), towardsTheTrain = true)
        serverTicks(SETTLE_TICKS)

        // And only then the goods. Put in any earlier they would be drawn along a belt into an
        // interface with nothing on the other side of it, which is the same as throwing them away.
        fillTheSourceChest()

        waitUntil(LOADING_TIMEOUT) { cargo() >= CARGO }

        shot("train_loaded")
        restoreHud()

        assertEquals(CARGO, cargo(), "The train did not take its load aboard; ${whereTheGoodsWent()}")
    }

    @Test
    @Order(7)
    @DisplayName("The train carries the goods across the circuit and is emptied at the unloading station")
    fun `unloads the train at the unloading station`(cluster: ClusterScope) = cluster.driving(within = 8.minutes) {
        assertEquals(
            CARGO, cargo(),
            "The train is not carrying anything to unload -- did the loading phase fail?",
        )

        driveTo(UNLOADING_NAME, aLapFirst = false, pictures = "to_the_unloading_station")

        assertEquals(
            theTrainsName(), trainWaitingAt(UNLOADING),
            "The train did not draw in at the unloading station",
        )

        stepOffTheTrain()
        watchTheWholeSetup(unloadingSide())
        shot("train_at_the_unloading_station")

        driveTheBelt(unloadingSide(), towardsTheTrain = false)

        waitUntil(LOADING_TIMEOUT) { inTheChestOf(unloadingSide()) >= CARGO }

        shot("train_unloaded")
        restoreHud()

        assertEquals(
            CARGO, inTheChestOf(unloadingSide()),
            "The unloading building did not get the load off the train; it still holds ${cargo()}",
        )
    }

    // -- driving ---------------------------------------------------------------------------------

    /**
     * Takes the controls and drives the train to a named station, drawing it in when it offers.
     *
     * Nothing here is asked of the server: the keys are held down for real and the client works out
     * what to send from them, and every step of the run is read off what the train tells its driver.
     *
     * @param aLapFirst whether the train has to have got well away before a station's offer is taken
     *   up, which is the difference between a lap of the circuit and a run down the straight
     */
    private suspend fun driveTo(station: String, aLapFirst: Boolean, pictures: String) {
        takeTheControls()

        // Under way, and the camera turned to wherever it has actually started going. Which way it
        // pulls away is the train's business rather than this test's.
        //
        // Bounded, and that is the whole of why this reads as it does. A train that will not move --
        // the controls not really taken, the way ahead not really connected -- is a train that never
        // covers the two blocks being waited for, and an unbounded wait on that is a test that stops
        // saying anything at all until its deadline runs out. The state at the moment it gave up is
        // what says which of those it was.
        val pulledAway = client(ALEX, PULL_AWAY_PATIENCE) { patience ->
            val setOffFrom = trainAt()
            keyDown(Key.W)

            var moved = false

            for (tick in 0 until patience) {
                awaitTicks(1)
                if (trainAt().distanceTo(setOffFrom) > 2) {
                    moved = true
                    break
                }
            }

            if (moved) {
                val gone = trainAt().subtract(setOffFrom)
                val way = Direction.getApproximateNearest(gone.x, 0.0, gone.z)
                clientPlayer?.yRot = way.toYRot()
                clientPlayer?.xRot = 0f
                awaitTicks(BEAT_TICKS)
            }

            moved
        }

        assertTrue(pulledAway, "The train would not pull away. " + whatTheDriverSees())

        shot("${pictures}_under_way")

        // Somewhere on a corner, from over the driver's shoulder. Everything a contraption is made of
        // has to turn with it, and a block drawn by a renderer of its own -- the chest here -- is the
        // one that shows when something does not; on a straight it would look right either way. The
        // view has to be pulled back out of the carriage to see any of this, since the driver is
        // inside it. Only worth doing on a lap, which is the run that has corners to spare.
        if (aLapFirst) {
            val turning = client(ALEX, CORNER_PATIENCE) { patience -> driveUntilTurning(patience) }

            if (turning) {
                watchOverTheShoulder(back = true)
                shot("${pictures}_on_a_corner")
                watchOverTheShoulder(back = false)
            }
        }

        // The rest of the run, watched and answered on the client. The offer to draw in is a passing
        // thing -- a train at speed is past a station within a few ticks of being told it is coming up
        // -- so the key that takes the offer is pressed on the tick the offer appears rather than a
        // round trip later.
        val run = client(ALEX, Drive(station, aLapFirst)) { asked -> driveUntilArrived(asked) }

        client(ALEX) {
            keyUp(Key.W)
            keyUp(Key.SPACE)
            awaitTicks(BEAT_TICKS)
        }
        serverTicks(SETTLE_TICKS)

        val summary = "; it got ${Math.round(run.furthest)} blocks off and heard ${run.heard}"

        if (!run.arrived) shot("${pictures}_gave_up") else shot("${pictures}_arrived")

        assertNull(
            run.wrongStation,
            "The train was sent to $station and drew in somewhere else: ${run.wrongStation}$summary",
        )
        assertTrue(run.beenAway, "The train never got away from where it started$summary")
        assertTrue(run.askedToStop, "The train never offered to draw in at $station$summary")
        assertTrue(run.arrived, "The train was asked to draw in at $station but never got there$summary")
    }

    /**
     * Gets the player aboard and at the controls.
     *
     * Sitting down and taking hold are both clicks on the train itself, which is an entity rather
     * than blocks in the world, so what the crosshair is on has to be looked for rather than worked
     * out -- and where the train is at the time is asked of the train, since it is not where it was
     * built.
     */
    private suspend fun takeTheControls() {
        // Both, and not either. Putting the camera anywhere else moves the player, and a passenger
        // that is moved is a passenger that gets off -- but the client goes on believing it holds
        // the controls, because nothing told it otherwise. From there the keys reach the player's
        // own legs rather than the train, which is a train that will not pull away and a driver who
        // is certain they are driving it.
        if (driving() && riding()) return

        // So a hold left over from an earlier phase is let go of first, rather than climbed on top
        // of. The click that would normally release it is not available here -- it is a click on the
        // train, and the player is no longer near the train -- so this is the other way the game
        // lets go, which is what pressing escape at the controls does.
        //
        // Both halves of it, and that is the point. Letting go on the client alone leaves the server
        // still holding the player as the driver, and the next click on the controls is then read as
        // the one that stops driving rather than the one that starts.
        if (driving()) letGoOfTheControls()

        standBeside()

        assertTrue(clickOnTrain("create:red_seat"), "Could not find the seat to sit on")
        assertTrue(riding(), "Clicking the seat did not sit the player on the train")

        assertTrue(
            clickOnTrain("create:controls"),
            "Could not find the controls from the seat; the crosshair was on " +
                whatIsUnderTheCrosshair(),
        )
        assertTrue(driving(), "Clicking the controls did not hand the player the train")
    }

    /**
     * Lets go of a contraption's controls from both ends.
     *
     * What {@code ControlsHandler} does when escape is held: it forgets the contraption on the
     * client and tells the server the same, which is the half a client-side release leaves out.
     */
    private suspend fun letGoOfTheControls() {
        client(ALEX) {
            val entity = ControlsHandler.getContraption()
            val pos = ControlsHandler.getControlsPos()

            if (entity != null && pos != null) {
                ControlsHandler.stopControlling()
                ClientNetworkHelper.INSTANCE.sendToServer(
                    ControlsInputPacket(ControlsHandler.currentlyPressed, false, entity.id, pos, true)
                )
                awaitTicks(BEAT_TICKS)
            }
        }
    }

    /**
     * Gets the driver out of the train, which is what lets them be moved about again.
     *
     * A passenger goes where the thing carrying them goes, so until they are off it nothing that puts
     * the player somewhere has any effect.
     */
    private suspend fun stepOffTheTrain() {
        // Letting go of the controls comes first, and it is done the same way they were taken hold
        // of: with another click on them. While they are held, the key that means get off is one of
        // the train's own controls, and the train takes it before the game ever sees it.
        if (driving()) {
            clickOnTrain("create:controls")
            serverTicks(SETTLE_TICKS)
        }

        assertFalse(driving(), "Clicking the controls again did not let go of them")

        // And then the key a person presses to get off. Not escape, whatever else it would also do
        // here: that opens the game's own menu, and a paused game does not tick, so this would sit
        // there waiting on a world that has stopped.
        repeat(6) {
            if (!riding()) return@repeat
            client(ALEX) {
                keyDown(Key.SHIFT)
                awaitTicks(20)
                keyUp(Key.SHIFT)
            }
            serverTicks(SETTLE_TICKS)
        }

        assertFalse(riding(), "The player would not get off the train")
        serverTicks(SETTLE_TICKS)
        shot("stepped_off")
    }

    /**
     * Looks about until the crosshair is on one of the train's own blocks, then clicks it.
     *
     * A rough turn towards where the block stands first, since the sweep only reaches so far to
     * either side, and then the sweep itself to land on it. All of it in one body: a sweep is dozens
     * of small turns each of which has to be looked at, and a round trip apiece would be a minute of
     * turning to find something a foot away.
     */
    private suspend fun clickOnTrain(wanted: String): Boolean {
        val roughly = whereOnTheTrain(wanted)

        return client(ALEX, Aim(wanted, roughly)) { aim -> lookAtAndClick(aim) }
    }

    /**
     * What the driver has in front of them, for when the train does not answer the controls.
     *
     * The three things that keep a train standing still look identical from outside: the player is
     * not really at the controls, the controls are held but the train has nowhere to go, or it is
     * moving so slowly that two blocks take longer than the patience allowed. What the train tells
     * its driver separates them, and the rest says which of the first two it was.
     */
    private suspend fun whatTheDriverSees(): String = client(ALEX) {
        val train = trainEntity()

        "driving: " + (ControlsHandler.getContraption() != null) +
            ", riding: " + (clientPlayer?.vehicle is CarriageContraptionEntity) +
            ", the train is " + (train?.position()?.toString() ?: "not on this client") +
            ", it says \"" + prompt() + "\""
    }

    // -- what the client knows -------------------------------------------------------------------

    private suspend fun riding(): Boolean = client(ALEX) {
        clientPlayer?.vehicle is CarriageContraptionEntity
    }

    private suspend fun driving(): Boolean = client(ALEX) { ControlsHandler.getContraption() != null }

    private suspend fun whatIsUnderTheCrosshair(): String = client(ALEX) { describeTheCrosshair() }

    private suspend fun watchOverTheShoulder(back: Boolean) {
        client(ALEX, back) { third ->
            minecraft.options.setCameraType(
                if (third) net.minecraft.client.CameraType.THIRD_PERSON_BACK
                else net.minecraft.client.CameraType.FIRST_PERSON
            )
            awaitTicks(SETTLE_TICKS)
        }
    }

    // -- the buildings ---------------------------------------------------------------------------

    /**
     * A station building, all of it in one line and all of it at the height of the interface: the
     * chest at the far end, a funnel, the belt, another funnel, and the interface the train draws up
     * against.
     *
     * A funnel's inventory is the block behind its mouth, so each of the two faces away from the
     * thing it is attached to and towards the belt. Which way goods then move through them is the
     * whole difference between the two buildings. Where the train is filled, the far funnel empties
     * the chest onto the belt and the near one takes what arrives and pushes it into the interface;
     * where the train is emptied, both work the other way about. The belt is turned to run the way its
     * goods travel.
     */
    private suspend fun build(where: BlockPos, loading: Boolean) {
        val out = towardsTheTrain(where).opposite

        setBlock(where, "create:portable_storage_interface[facing=${towardsTheTrain(where).serializedName}]")

        // Clear the ground the building stands on, both the row the belt runs along and the one above.
        for (along in 1..BELT_LENGTH + 2) {
            setBlock(where.relative(out, along), "minecraft:air")
            setBlock(where.below().relative(out, along), "minecraft:air")
        }

        setBlock(
            stationFunnel(where),
            "create:andesite_funnel[facing=${out.serializedName},extracting=${!loading}]",
        )

        belt(beltStart(where), beltEnd(where))

        // Something has to turn it. A belt hangs off shafts at its ends, so the motor stands beside
        // one of them and drives it from there.
        val drive = driveSideOf(where)
        setBlock(
            beltEnd(where).relative(drive.opposite),
            "create:creative_motor[facing=${drive.serializedName}]",
        )

        setBlock(
            farFunnel(where),
            "create:andesite_funnel[facing=${out.opposite.serializedName},extracting=$loading]",
        )
        setBlock(chestOf(where), "minecraft:chest")
    }

    /** Lays a belt between two ends, which is what the belt item does when it is clicked on both. */
    private suspend fun belt(from: BlockPos, to: BlockPos) {
        server(from, to) { one, other ->
            BeltConnectorItem.createBelts(serverLevel, one, other)
            true
        }
    }

    /** Leaves a building's belt standing still, which is how it is put up. */
    private suspend fun stopTheBelt(where: BlockPos) = turnTheMotor(where, 0)

    /**
     * Turns a building's belt the way its goods have to travel.
     *
     * A belt is laid from the train's end outwards, and that laying is what its plain direction of
     * travel means, so carrying goods back in towards the train is the motor turned the other way.
     */
    private suspend fun driveTheBelt(where: BlockPos, towardsTheTrain: Boolean) =
        turnTheMotor(where, if (towardsTheTrain) -BELT_RPM else BELT_RPM)

    private suspend fun turnTheMotor(where: BlockPos, rpm: Int) {
        val motor = beltEnd(where).relative(driveSideOf(where).opposite)

        server(motor, rpm) { pos, speed ->
            val be = serverLevel.getBlockEntity(pos)
            if (be is CreativeMotorBlockEntity) be.generatedSpeed.setValue(speed)
            be is CreativeMotorBlockEntity
        }
    }

    /** What the loading building has to send, put where a chest at the end of a belt would hold it. */
    private suspend fun fillTheSourceChest() {
        val chest = chestOf(loadingSide())
        runCommand(
            "item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:cobblestone $CARGO"
        )
    }

    // -- building the train ----------------------------------------------------------------------

    /** Turns the station over to assembly through its own screen, then gets out of the way. */
    private suspend fun enterAssemblyMode() {
        rightClickBlock(PARKING)
        waitForScreenNamed("StationScreen")

        clickWidget("newTrainButton")
        waitForScreenNamed("AssemblyScreen")

        closeWithEscape()

        // The station only works out how far it can build, and marks out the stretch of rail a click
        // may put a bogey on, on one of its slower ticks. Clicking before that lands on nothing.
        serverTicks(SETTLE_TICKS * 4)
    }

    /** Opens the station again -- which now offers the assembly screen -- and presses the button. */
    private suspend fun assemble() {
        rightClickBlock(PARKING)
        waitForScreenNamed("AssemblyScreen")
        serverTicks(SETTLE_TICKS)

        clickWidget("toggleAssemblyButton")
        serverTicks(SETTLE_TICKS * 3)

        closeAnyScreen()
        serverTicks(SETTLE_TICKS)
    }

    /**
     * The carriage: a floor three squares by three with the bogey at its middle, and the fittings on
     * it.
     *
     * Where each piece goes matters. The controls have to face the way the train is built or the
     * station refuses the whole thing, and the seat has to be the square they face. Those two take the
     * left-hand side with nothing standing over them, the cargo goes across the back, and the two
     * interfaces take the right -- which is the side facing into the circuit, where whatever loads and
     * empties the train will stand.
     */
    private suspend fun buildCarriage() {
        for (across in -1..1) {
            for (along in -1..1) {
                if (across != 0 || along != 0) setBlock(floor(across, along), CASING)
            }
        }

        setBlock(controls(), "create:controls[facing=${ASSEMBLY.serializedName}]")
        setBlock(seat(), "create:red_seat")

        setBlock(itemInterface(), "create:portable_storage_interface[facing=$INTERFACES]")
        setBlock(fluidInterface(), "create:portable_fluid_interface[facing=$INTERFACES]")

        setBlock(chest(), "minecraft:chest")
        setBlock(tank(), "create:fluid_tank")
    }

    private suspend fun glueTheCarriage() {
        server(floor(-1, -1), floor(1, 1).above()) { low, high ->
            serverLevel.addFreshEntity(SuperGlueEntity(serverLevel, SuperGlueEntity.span(low, high)))
            true
        }
    }

    private suspend fun clickTrack(track: BlockPos) {
        rightClickAt(surfaceOf(track), standingBack(track, ASSEMBLY), track)
        serverTicks(SETTLE_TICKS)
    }

    // -- laying the track ------------------------------------------------------------------------

    /**
     * Picks the end of a piece of track to build on from.
     *
     * Which end is chosen follows the way the player looks at it, so this stands back on the near side
     * and looks along the track towards the end it wants.
     */
    private suspend fun selectEnd(pos: BlockPos, end: Direction) {
        rightClickAt(surfaceOf(pos), standingBack(pos, end), pos)
        serverTicks(SETTLE_TICKS)
    }

    /** Clicks the ground where a piece belongs, with the player facing the way it should run. */
    private suspend fun placeTrackFacing(pos: BlockPos, facing: Direction) {
        val ground = pos.below()
        rightClickAt(topOf(ground), standingBack(ground, facing), ground)
        serverTicks(SETTLE_TICKS)
    }

    /** The same, onto track already there, which is how the ring is closed rather than extended. */
    private suspend fun connectToExisting(pos: BlockPos, facing: Direction) {
        rightClickAt(surfaceOf(pos), standingBack(pos, facing), pos)
        serverTicks(SETTLE_TICKS)
    }

    // -- the stations ----------------------------------------------------------------------------

    /** Puts a station down and gives it its name while the player is still standing at it. */
    private suspend fun placeAndName(pos: BlockPos, name: String) {
        placeStation(pos, trackFor(pos), travelAt(pos))
        nameStation(pos, name)
    }

    /**
     * Puts a station beside the track, the way one is put down in play.
     *
     * Two clicks: the rail itself, which is what the station will watch and which way along it a train
     * is built, and then the ground a couple of blocks to the side, which is where the station lands.
     */
    private suspend fun placeStation(pos: BlockPos, track: BlockPos, along: Direction) {
        holdItem("create:track_station")
        serverTicks(SETTLE_TICKS)

        rightClickAt(surfaceOf(track), standingBack(track, along), track)
        serverTicks(SETTLE_TICKS)

        val ground = pos.below()
        rightClickAt(topOf(ground), standingBack(ground, along), ground)
        serverTicks(SETTLE_TICKS)
    }

    /** Opens the station and types its name in, which is the only way a station gets one. */
    private suspend fun nameStation(pos: BlockPos, name: String) {
        // The screen only opens once the client has been told which rails this station belongs to.
        client(ALEX, pos) { where ->
            awaitUntil {
                val be = level.getBlockEntity(where)
                be is StationBlockEntity && be.station != null
            }
        }

        rightClickBlock(pos)
        waitForScreenNamed("StationScreen")

        clickWidget("nameBox")
        typeText(name)
        serverTicks(SETTLE_TICKS)

        // A station has no button to accept it; what was typed is sent as the screen is taken away.
        closeWithEscape()
        serverTicks(SETTLE_TICKS)
    }

    // -- where the camera goes -------------------------------------------------------------------

    /** High up and off one end, far enough back that the whole circuit is in frame. */
    private suspend fun watchTheCircuit() {
        spectateAt(
            Vec3(MIDDLE_X.toDouble(), (RAIL_Y + 44).toDouble(), (MIDDLE_Z + 52).toDouble()),
            Vec3(MIDDLE_X.toDouble(), RAIL_Y.toDouble(), MIDDLE_Z.toDouble()),
        )
    }

    /** Stands off the carriage's corner and above it, so the floor and everything on it are in view. */
    private suspend fun watchTheTrain() {
        spectateAt(
            Vec3(bogey(0).x + 6.5, (bogey(0).y + 5).toDouble(), bogey(0).z + 6.5),
            middleOf(bogey(0).above()),
        )
    }

    /** Stands off a building's corner and above it, so the whole of it is in view. */
    private suspend fun watchTheBuilding(where: BlockPos) {
        val out = towardsTheTrain(where).opposite

        // Aimed at the interface the train draws up against rather than at the middle of the building,
        // and stood off beyond the far end of it, so the whole run and whatever is on the rails are
        // both in view rather than the building alone.
        spectateAt(middleOf(where.relative(out, BELT_LENGTH + 7)).add(0.0, 7.0, 10.0), middleOf(where))
    }

    /** Stands well back from a building, with the train it serves in the same view. */
    private suspend fun watchTheWholeSetup(building: BlockPos) {
        val out = towardsTheTrain(building).opposite
        val side = driveSideOf(building)

        // The middle of the whole thing: the train on one side of the interface, the belt and its
        // chest running away on the other. Watched from off to one side and a little above, close
        // enough to see what is on the belt.
        val middle = middleOf(building.relative(out, 2))

        spectateAt(middle.add(side.stepX * 10.0, 7.0, side.stepZ * 10.0), middle)
    }

    /**
     * Puts the player on the ground beside the carriage, on their feet and within reach of it.
     *
     * Beside meaning on the outside of the circuit, which is the side with nothing on it, and beside
     * wherever the train is now rather than where it was built.
     */
    private suspend fun standBeside() {
        val controls = whereOnTheTrain("create:controls")
        val out = towardsTheCircuit(controls).opposite

        server(controls, out.ordinal) { where, side ->
            val facing = Direction.entries[side]
            val player = playerNamed(ALEX)

            player.setGameMode(GameType.CREATIVE)
            player.abilities.flying = false
            player.onUpdateAbilities()
            player.connection.teleport(
                where.x + 0.5 + facing.stepX * 3.0,
                RAIL_Y.toDouble(),
                where.z + 0.5 + facing.stepZ * 3.0,
                90f,
                0f,
            )
            true
        }
        serverTicks(SETTLE_TICKS)
    }

    // -- what the server knows -------------------------------------------------------------------

    /**
     * How many separate railways there are *on this circuit*, which for one closed ring is one.
     *
     * Counted by what has a node inside the circuit's own ground rather than by asking the world how
     * many railways it has. The world has others: every station test lays a run of track of its own,
     * and a railway laid once stays on the books after the blocks are cleared away -- so the global
     * count says three whatever this circuit did, and says it whether the ring closed or not.
     */
    private suspend fun graphCount(): Int = server(MIDDLE_X, MIDDLE_Z) { middleX, middleZ ->
        Create.RAILWAYS.trackNetworks.values.count { graph ->
            graph.nodes.any { node ->
                // Through `location`, not off the node itself. A `TrackNodeLocation` is a `Vec3i`
                // holding twice each coordinate -- it has to name half-block positions, and that is
                // how it does it -- so a node compared against world coordinates never matches
                // anything and the count is silently zero.
                val where = node.location

                kotlin.math.abs(where.x - middleX) <= CIRCUIT_REACH &&
                    kotlin.math.abs(where.z - middleZ) <= CIRCUIT_REACH
            }
        }
    }

    private suspend fun trainCount(): Int = server(ALEX) { Create.RAILWAYS.trains.size }

    private suspend fun hasCurveAt(pos: BlockPos): Boolean = server(pos) { where ->
        val be = serverLevel.getBlockEntity(where)
        be is TrackBlockEntity && be.connections.isNotEmpty()
    }

    private suspend fun describe(pos: BlockPos): String =
        server(pos) { where -> serverLevel.getBlockState(where).toString() }

    private suspend fun blockAt(pos: BlockPos): String =
        server(pos) { where -> nameOf(serverLevel.getBlockState(where)) }

    /** A picture of what track ended up where, so a circuit can be read at a glance. */
    private suspend fun map(): String = server(MIDDLE_Z) { middle ->
        buildString {
            for (z in middle - 20..middle + 22 step 2) {
                for (x in 50..76 step 2) {
                    val at = BlockPos(x, RAIL_Y, z)
                    append(
                        when {
                            serverLevel.getBlockEntity(at) is TrackBlockEntity -> 'C'
                            serverLevel.getBlockState(at).block ==
                                com.simibubi.create.AllBlocks.TRACK.get() -> '#'
                            else -> '.'
                        }
                    )
                }
                append('/')
            }
        }
    }

    private suspend fun stationName(pos: BlockPos): String = server(pos) { where ->
        val be = serverLevel.getBlockEntity(where)
        if (be !is StationBlockEntity || be.station == null) {
            throw AssertionError("There is no station on the railway at $where but $be")
        }
        be.station!!.name
    }

    /** Which train, if any, is standing at a station. */
    private suspend fun trainWaitingAt(pos: BlockPos): String = server(pos) { where ->
        val be = serverLevel.getBlockEntity(where)
        if (be !is StationBlockEntity) return@server "there is no station at $where"

        val global = be.station ?: return@server "the station is not on the track"

        global.presentTrain?.name?.string ?: "no train is there"
    }

    /** What the assembled train ended up called, which is whatever a station reports when it is there. */
    private suspend fun theTrainsName(): String =
        server(ALEX) { Create.RAILWAYS.trains.values.first().name.string }

    private suspend fun bogeyOffsets(): String = server(PARKING) { where ->
        val be = serverLevel.getBlockEntity(where) as StationBlockEntity
        (com.simibubi.create.e2e.readField(be, "bogeyLocations") as IntArray).contentToString()
    }

    private suspend fun assemblyLength(): String = server(PARKING) { where ->
        com.simibubi.create.e2e.readField(serverLevel.getBlockEntity(where), "assemblyLength").toString()
    }

    private suspend fun assemblyState(): String = server(PARKING) { where ->
        val be = serverLevel.getBlockEntity(where)
        "assembling=${serverLevel.getBlockState(where)}" +
            " direction=${com.simibubi.create.e2e.readField(be, "assemblyDirection")}"
    }

    /** Whether the assembled train carries the named block, which is how the glue is checked. */
    private suspend fun carriageHolds(wanted: String): Boolean =
        server(wanted) { name -> name in everythingTheTrainCarries() }

    /** Everything the assembled train carries, for when something was left behind. */
    private suspend fun carriageContents(): String =
        server(ALEX) { everythingTheTrainCarries().toString() }

    /** What is still standing where the carriage was built, which is whatever the train did not take. */
    private suspend fun leftBehind(): String = server(floor(-1, -1)) { corner ->
        val standing = mutableListOf<String>()

        for (across in 0..2) {
            for (along in 0..2) {
                for (up in 0..1) {
                    val pos = corner.offset(across, up, along)
                    val state = serverLevel.getBlockState(pos)
                    if (!state.isAir) standing.add("${pos.toShortString()} ${nameOf(state)}")
                }
            }
        }

        standing.toString()
    }

    /** Where one of the train's own blocks has got to, which is not where it was built. */
    private suspend fun whereOnTheTrain(wanted: String): BlockPos = server(wanted) { name ->
        for (train in Create.RAILWAYS.trains.values) {
            for (carriage in train.carriages) {
                val entity = carriage.anyAvailableEntity() ?: continue

                for ((at, info) in entity.contraption.blocks) {
                    if (nameOf(info.state()) == name) {
                        return@server BlockPos.containing(entity.toGlobalVector(Vec3.atCenterOf(at), 1f))
                    }
                }
            }
        }

        BlockPos.ZERO
    }

    /** How much the train is carrying, read the way a schedule's own conditions read it. */
    private suspend fun cargo(): Int = server(ALEX) {
        var found = 0

        for (train in Create.RAILWAYS.trains.values) {
            for (carriage in train.carriages) {
                val storage = carriage.storage ?: continue
                val items = storage.allItems

                for (slot in 0 until items.size()) found += ItemUtil.getStack(items, slot).count
            }
        }

        found
    }

    /** How much of the load a building's chest is holding. */
    private suspend fun inTheChestOf(building: BlockPos): Int = server(chestOf(building)) { where ->
        var found = 0
        val chest = serverLevel.getBlockEntity(where)

        if (chest is Container) {
            for (slot in 0 until chest.containerSize) found += chest.getItem(slot).count
        }

        found
    }

    /**
     * What became of what the loading building was given, for when none of it reached the train.
     *
     * Goods that miss their way do not vanish -- they end up on the ground beside the building -- so
     * what is left in the chest and what is lying about between them say where the run broke down.
     */
    private suspend fun whereTheGoodsWent(): String {
        val left = inTheChestOf(loadingSide())
        val riding = onTheBelt(loadingSide())

        return server(loadingSide()) { building ->
            val onTheFloor = serverLevel
                .getEntitiesOfClass(
                    ItemEntity::class.java,
                    AABB(building).inflate((BELT_LENGTH + 4).toDouble()),
                )
                .sumOf { it.item.count }

            val psi = serverLevel.getBlockEntity(building)
            val coupled = psi is PortableStorageInterfaceBlockEntity && psi.canTransfer()

            "$onTheFloor on the ground and the pair are " +
                if (coupled) "coupled up" else "not coupled up"
        } + "; $left is still in the chest and $riding is riding the belt"
    }

    /** What is riding a building's belt, which is where a load that never arrived tends to sit. */
    private suspend fun onTheBelt(building: BlockPos): Int =
        server(beltStart(building), beltEnd(building)) { start, end ->
            var riding = 0

            for (pos in listOf(start, end)) {
                val belt = serverLevel.getBlockEntity(pos)
                if (belt !is BeltBlockEntity) continue

                for (offset in 0..BELT_LENGTH) {
                    riding += belt.inventory.getStackAtOffset(offset)?.stack?.count ?: 0
                }
            }

            riding
        }

    // -- the shape of it -------------------------------------------------------------------------

    /** The one piece everything else hangs off, and the point the ring closes back onto. */
    private fun start() = BlockPos(52, RAIL_Y, 52 + ZONE)

    /** The piece of rail each station watches: the middle of its own straight. */
    private fun trackFor(station: BlockPos) = when (station) {
        PARKING -> BlockPos(52, RAIL_Y, 62 + ZONE)
        LOADING -> BlockPos(70, RAIL_Y, 80 + ZONE)
        else -> BlockPos(88, RAIL_Y, 62 + ZONE)
    }

    /**
     * Which way a train runs past a station.
     *
     * The circuit is laid one side at a time and a train follows it round the same way, so a station's
     * straight is the side of the square it stands on and the way that side was laid is the way the
     * train goes past it.
     */
    private fun travelAt(station: BlockPos) = when (station) {
        PARKING -> SIDES[0]
        LOADING -> SIDES[1]
        else -> SIDES[2]
    }

    /** The side of the track the middle of the circuit is on, which is the side everything is built on. */
    private fun insideAt(station: BlockPos) = towardsTheCircuit(trackFor(station))

    /**
     * Which way a station builds, which is back against the way trains run past it.
     *
     * A station puts the front of its train at its own mark and builds the rest away from there, so a
     * train that comes to rest facing the way it was travelling has been built against it.
     */
    private fun assemblyAt(station: BlockPos) = travelAt(station).opposite

    /**
     * Where the train's own interface ends up when it has drawn in at a station.
     *
     * A train comes to rest at a station exactly where it would have been built there, so this is the
     * carriage's own plan measured out from the station's stretch of rail.
     */
    private fun trainInterfaceAt(station: BlockPos): BlockPos = trackFor(station)
        .relative(assemblyAt(station))
        .above()
        .relative(insideAt(station))
        .relative(assemblyAt(station))
        .above()

    /**
     * Where a station's building stands.
     *
     * Two blocks in from the interface the train presents when it draws up there, which leaves the gap
     * of one between the pair that they reach across to take hold of one another.
     */
    private fun buildingFor(station: BlockPos) = trainInterfaceAt(station).relative(insideAt(station), 2)

    private fun loadingSide() = buildingFor(LOADING)

    private fun unloadingSide() = buildingFor(UNLOADING)

    /** Which way the middle of the circuit lies from somewhere on its edge. */
    private fun towardsTheCircuit(pos: BlockPos): Direction {
        val across = MIDDLE_X - pos.x
        val along = MIDDLE_Z - pos.z

        if (kotlin.math.abs(across) >= kotlin.math.abs(along)) {
            return if (across > 0) Direction.EAST else Direction.WEST
        }

        return if (along > 0) Direction.SOUTH else Direction.NORTH
    }

    /** Which way the train lies from a building, which is the way its interface has to look. */
    private fun towardsTheTrain(building: BlockPos) = towardsTheCircuit(building).opposite

    /**
     * Which way the motor drives a belt from.
     *
     * Across the belt rather than along it. A belt hangs off shafts at its ends and those lie across
     * the way it runs, so a motor put in line with the belt drives nothing -- and each of these
     * buildings runs its belt along a different axis, since each stands on a different side.
     */
    private fun driveSideOf(where: BlockPos) = towardsTheTrain(where).clockWise

    /**
     * The funnel beside the interface, which is the one the train's goods pass through.
     *
     * It stands on the belt, which is the arrangement a funnel and a belt are meant to be in: a funnel
     * over a belt takes what the belt brings it, or lays what it is given down on it, where one merely
     * standing beside a belt does neither.
     */
    private fun stationFunnel(where: BlockPos) = where.relative(towardsTheTrain(where).opposite)

    /** The belt runs a step below the interface, so that the funnels at its ends are level with it. */
    private fun beltStart(where: BlockPos) = stationFunnel(where).below()

    private fun beltEnd(where: BlockPos) =
        beltStart(where).relative(towardsTheTrain(where).opposite, BELT_LENGTH - 1)

    /** The funnel at the far end, standing on the other end of the belt with the chest beside it. */
    private fun farFunnel(where: BlockPos) = beltEnd(where).above()

    private fun chestOf(where: BlockPos) = farFunnel(where).relative(towardsTheTrain(where).opposite)

    /** The rail a bogey is put on, counted out from the piece the station watches. */
    private fun bogeyTrack(offset: Int) = trackFor(PARKING).relative(ASSEMBLY, offset + 1)

    /** Where that bogey ends up: one above its rail. */
    private fun bogey(offset: Int) = bogeyTrack(offset).above()

    /**
     * A square of the carriage floor, counted out from the bogey at its middle.
     *
     * Across is to the right of the way the train is built, and along is the way it is built, so along
     * -1 is towards the front of the train.
     */
    private fun floor(across: Int, along: Int) =
        bogey(0).relative(Direction.EAST, across).relative(ASSEMBLY, -along)

    /**
     * The controls, in the middle of the left-hand side, facing the way the train is built.
     *
     * Not a free choice either way. A station refuses to assemble anything unless some set of controls
     * faces the way it is building, and it only counts a seat as the driver's when the controls beside
     * it face that seat -- so the controls face along the train and the seat is the square ahead.
     */
    private fun controls() = floor(-1, 0).above()

    /** The seat, which is the square the controls face, and where the driver goes. */
    private fun seat() = floor(-1, -1).above()

    private fun tank() = floor(-1, 1).above()

    /**
     * The hold, directly behind the interface that fills it.
     *
     * Behind meaning on the train's side of it: the interface reaches out one way to whatever the
     * station has waiting, and puts what it takes into the square at its back.
     */
    private fun chest() = floor(0, -1).above()

    private fun itemInterface() = floor(1, -1).above()

    private fun fluidInterface() = floor(1, 0).above()

    /** Waits on the server for something to come true, in steps rather than one long sleep. */
    private suspend fun waitUntil(patience: Int, condition: suspend () -> Boolean) {
        var waited = 0

        while (waited < patience && !condition()) {
            serverTicks(20)
            waited += 20
        }
    }

    /**
     * What a drive is asked for.
     *
     * A record rather than two arguments, because the whole of the run happens inside one client body
     * and everything it needs has to travel with it.
     */
    @Serializable
    data class Drive(val station: String, val aLapFirst: Boolean)

    /** What a drive turned out like, which is everything the assertions afterwards ask about. */
    @Serializable
    data class Run(
        val arrived: Boolean,
        val beenAway: Boolean,
        val askedToStop: Boolean,
        val wrongStation: String?,
        val furthest: Double,
        val heard: List<String>,
    )

    /** Where a block of the train is and what it is called, for the sweep that goes looking for it. */
    @Serializable
    data class Aim(
        val wanted: String,
        @kotlinx.serialization.Serializable(with = dev.vibeported.mc.driver.BlockPosSerializer::class)
        val roughly: BlockPos,
    )

    companion object {

        const val ZONE = Zones.TRAIN_CIRCUIT

        const val SETTLE_TICKS = 10

        const val BEAT_TICKS = 5

        /** The height the track sits at; the grass it rests on is the block below. */
        const val RAIL_Y = -60

        /** How far a quarter turn reaches, across and along, which is what makes it wide enough to drive. */
        const val CORNER = 8

        /** The circuit, side by side: the way each runs and how far it goes before it turns. */
        val SIDES = arrayOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST)

        val LENGTHS = intArrayOf(20, 20, 20, 20)

        /**
         * How far the circuit reaches from its middle, for telling its own rails from other tests'.
         *
         * The ring is a little over twenty across each way and the corners swing wider still, so this
         * takes in the whole of it and nothing near it -- the nearest other railway is hundreds of
         * blocks off.
         */
        const val CIRCUIT_REACH = 40

        /** The middle of the square, which every straight faces. */
        const val MIDDLE_X = 70

        const val MIDDLE_Z = 62 + ZONE

        /**
         * Where the train is built and where it comes back to. Nothing is loaded or unloaded here.
         *
         * It has the middle of the straight because it is the one that has to assemble a train, and a
         * station can only build over the run of plain track ahead of it -- which ends at the next
         * station's mark, or at the first bend.
         */
        val PARKING = BlockPos(50, RAIL_Y, 62 + ZONE)

        /** The next straight round: where goods are put aboard. */
        val LOADING = BlockPos(70, RAIL_Y, 82 + ZONE)

        /** And the one after that, where they are taken off again. */
        val UNLOADING = BlockPos(90, RAIL_Y, 62 + ZONE)

        const val PARKING_NAME = "Parking Station"
        const val LOADING_NAME = "Loading Station"
        const val UNLOADING_NAME = "Unloading Station"

        /**
         * Which way a train grows out of the parking station.
         *
         * Not a free choice: the station marks out the stretch of rail it will build over, and that
         * runs back against the way the rail was pointed at when the station was put down.
         */
        val ASSEMBLY: Direction = Direction.NORTH

        const val CASING = "create:andesite_casing"

        /** The train's right-hand side, which is the one that faces in towards the middle. */
        const val INTERFACES = "east"

        val FITTINGS = listOf(
            "create:controls",
            "create:red_seat",
            "minecraft:chest",
            "create:fluid_tank",
            "create:portable_storage_interface",
            "create:portable_fluid_interface",
        )

        const val BELT_LENGTH = 5

        const val BELT_RPM = 32

        /** Few enough to count exactly, and enough to watch travel. */
        const val CARGO = 10

        /** Long enough for a belt to carry a stack the length of itself and a train to take it aboard. */
        const val LOADING_TIMEOUT = 600

        /** A lap of the circuit under its own steam takes a while; this is longer than it can need. */
        const val DRIVE_TIMEOUT = 1600

        /** Long enough for a train that is going to move at all to have covered two blocks. */
        const val PULL_AWAY_PATIENCE = 200

        /** How long to keep watching for a corner before settling for a picture of a straight. */
        const val CORNER_PATIENCE = 400

        /**
         * How far off the train has to have got for it to have gone round rather than shuffled about.
         *
         * The circuit is a little over twenty across and thirty long, so this is comfortably past the
         * far straight and comfortably short of the furthest the train can get.
         */
        const val FAR_SIDE = 20.0

        /** Above and back along the given way, so the player both looks that way and looks down on it. */
        fun standingBack(pos: BlockPos, facing: Direction) = Vec3(
            pos.x + 0.5 - facing.stepX * 3.0,
            (pos.y + 2).toDouble(),
            pos.z + 0.5 - facing.stepZ * 3.0,
        )

        /** Just inside the block, low enough to be on the rails rather than in the air above them. */
        fun surfaceOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.2, pos.z + 0.5)

        fun topOf(pos: BlockPos) = Vec3(pos.x + 0.5, (pos.y + 1).toDouble(), pos.z + 0.5)

        fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

        fun nameOf(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).toString()
    }
}

/* --- the halves that run inside a game, where the objects they touch actually live --- */

/** Everything the assembled train is made of, by name. */
private fun ServerScope.everythingTheTrainCarries(): List<String> {
    val carried = mutableListOf<String>()

    for (train in Create.RAILWAYS.trains.values) {
        for (carriage in train.carriages) {
            val entity = carriage.anyAvailableEntity() ?: continue

            for (info in entity.contraption.blocks.values) {
                carried.add(BuiltInRegistries.BLOCK.getKey(info.state().block).toString())
            }
        }
    }

    return carried
}

/* --- and the halves that run on the client, where the train and its HUD are --- */

/** The carriage this client can see, which is the only train these tests ever build. */
private fun ClientScope.trainEntity(): AbstractContraptionEntity? =
    minecraft.level?.entitiesForRendering()?.firstOrNull { it is CarriageContraptionEntity }
        as? CarriageContraptionEntity

private fun ClientScope.trainAt(): Vec3 = trainEntity()?.position() ?: Vec3.ZERO

/** What the train is telling its driver, which is what every step of the run is read from. */
private fun prompt(): String = TrainHUD.currentPrompt?.string ?: ""

/** The drawing-in bar is thirty of the same character; there is nothing to learn from repeating it. */
private fun shorten(saying: String): String =
    if (saying.all { it == BAR }) "<drawing in>" else saying

private const val BAR = '|'

/** Which way something moving is headed, in the degrees the game turns a head by. */
private fun headingOf(moved: Vec3): Double = -Mth.atan2(moved.x, moved.z) * 180.0 / Math.PI

/**
 * Whether the train is going somewhere between the compass points, which is to say round a corner.
 *
 * Only worth asking of a train that is actually moving, since a heading read off a step of nothing is
 * whatever rounding says it is.
 */
private fun onACorner(moved: Vec3): Boolean {
    if (moved.horizontalDistance() < 0.05) return false

    val offAxis = Math.floorMod(Math.round(headingOf(moved)).toInt(), 90)

    return kotlin.math.abs(offAxis - 45) < 25
}

/**
 * Watches until the train is rounding a corner, or gives up.
 *
 * Tick by tick and on the client, because a heading is the step between two positions and a step read
 * across a round trip is several ticks of travel rolled into one.
 */
private suspend fun ClientScope.driveUntilTurning(patience: Int): Boolean {
    var wasAt = trainAt()

    for (tick in 0 until patience) {
        awaitTicks(1)
        val nowAt = trainAt()
        val moved = nowAt.subtract(wasAt)
        wasAt = nowAt

        if (onACorner(moved)) return true
    }

    return false
}

/**
 * Holds the train on course until it draws in where it was sent, and answers the offer when it comes.
 *
 * Nothing here measures how close the station is: the train says when it is near enough to hand the
 * rest of the run to, and it names the one it means, so the others are driven past. The key that takes
 * the offer is pressed from in here rather than from the test, because the offer lasts a few ticks and
 * a round trip is most of them.
 */
private suspend fun ClientScope.driveUntilArrived(asked: TrainCircuitTest.Drive): TrainCircuitTest.Run {
    val setOffFrom = trainAt()
    val heard = mutableListOf<String>()

    var furthest = 0.0
    var beenAway = !asked.aLapFirst
    var askedToStop = false
    var arrived = false
    var wrongStation: String? = null

    for (tick in 0 until TrainCircuitTest.DRIVE_TIMEOUT) {
        if (arrived || wrongStation != null) break

        awaitTicks(1)

        val saying = prompt()
        val away = trainAt().distanceTo(setOffFrom)

        furthest = kotlin.math.max(furthest, away)

        if (saying.isNotEmpty() && shorten(saying) !in heard) heard.add(shorten(saying))

        if (!beenAway && away > TrainCircuitTest.FAR_SIDE) beenAway = true

        if (beenAway && !askedToStop && saying.contains("approach " + asked.station)) {
            keyDown(Key.SPACE)
            askedToStop = true
        }

        arrived = askedToStop && saying.contains("Arrived at " + asked.station)

        // Drawn in somewhere else entirely. Worth stopping on rather than waiting out the clock,
        // since it says the offer was taken a moment too late and the train was given the next
        // station along.
        if (!arrived && saying.startsWith("Arrived at ")) wrongStation = saying
    }

    return TrainCircuitTest.Run(arrived, beenAway, askedToStop, wrongStation, furthest, heard)
}

/**
 * Turns towards one of the train's own blocks and clicks it once the crosshair is really on it.
 *
 * A rough turn first, since a sweep only reaches so far to either side; then down a nudge at a time,
 * which is how a thing just below the eye is found; and only then wider, for when it was not on the
 * way down.
 */
private suspend fun ClientScope.lookAtAndClick(aim: TrainCircuitTest.Aim): Boolean {
    val player = clientPlayer ?: return false
    val roughly = Vec3.atCenterOf(aim.roughly)

    val toBlock = roughly.subtract(player.eyePosition)
    player.yRot = (Mth.atan2(toBlock.z, toBlock.x) * 180.0 / Math.PI).toFloat() - 90f
    player.xRot = (-(Mth.atan2(toBlock.y, toBlock.horizontalDistance()) * 180.0 / Math.PI)).toFloat()
    awaitTicks(2)

    if (!sweep(aim.wanted, down = true) && !sweep(aim.wanted, down = false)) return false

    if (minecraft.gui.screen() == null && !minecraft.mouseHandler.isMouseGrabbed) {
        minecraft.mouseHandler.grabMouse()
        awaitTicks(1)
    }

    click(MouseButton.RIGHT)
    awaitTicks(SWEEP_SETTLE)
    return true
}

/**
 * Nudges the view about until the crosshair is on the wanted block.
 *
 * Down first is not arbitrary: everything on the carriage is at or below the eye of somebody standing
 * beside it, so dropping the aim finds it far more often than casting about does.
 */
private suspend fun ClientScope.sweep(wanted: String, down: Boolean): Boolean {
    val player = clientPlayer ?: return false

    for (step in 0 until SWEEP_STEPS) {
        if (aimedAt(wanted)) return true

        if (down) {
            player.xRot = (player.xRot + SWEEP_DEGREES).coerceAtMost(89f)
        } else {
            player.yRot += SWEEP_DEGREES
            player.xRot = (player.xRot - SWEEP_DEGREES / 2).coerceAtLeast(-89f)
        }
        awaitTicks(1)
    }

    return aimedAt(wanted)
}

/** Whether the crosshair is on the named block of the train, asked the way the game itself asks. */
private fun ClientScope.aimedAt(wanted: String): Boolean {
    val train = trainEntity() ?: return false
    val player = clientPlayer ?: return false

    val origin = player.eyePosition
    val target = origin.add(player.lookAngle.scale(player.blockInteractionRange()))
    val hit = ContraptionHandlerClient.rayTraceContraption(origin, target, train) ?: return false
    val info = train.contraption.blocks[hit.blockPos] ?: return false

    return BuiltInRegistries.BLOCK.getKey(info.state().block).toString() == wanted
}

/** For when the crosshair did not find what it was after, so the failure can say what it did find. */
private fun ClientScope.describeTheCrosshair(): String {
    val train = trainEntity() ?: return "nothing -- there is no train here"
    val player = clientPlayer ?: return "nothing -- there is no player here"

    val origin = player.eyePosition
    val target = origin.add(player.lookAngle.scale(player.blockInteractionRange()))
    val hit = ContraptionHandlerClient.rayTraceContraption(origin, target, train)
        ?: return "none of the train, from " + origin + " looking " + player.lookAngle

    val info = train.contraption.blocks[hit.blockPos] ?: return "an empty square of the train"

    return BuiltInRegistries.BLOCK.getKey(info.state().block).toString()
}

/** How far each nudge of a sweep turns the view, and how many of them it makes. */
private const val SWEEP_DEGREES = 4f

private const val SWEEP_STEPS = 40

private const val SWEEP_SETTLE = 10
