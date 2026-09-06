package com.simibubi.create.e2e.compat

import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.fill
import net.minecraft.core.Direction
import dev.vibeported.mc.driver.server
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAt
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.openScreenName
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.PlayerMode
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.runOnServer
import dev.vibeported.mc.driver.setPlayerMode
import dev.vibeported.mc.driver.scroll
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create's railway network, drawn onto JourneyMap's map.
 *
 * Create ships a `@JourneyMapPlugin` -- `JourneyTrainMap` -- which hangs an overlay off JourneyMap's
 * fullscreen map and paints every stretch of track, every station and every moving train onto it.
 * Like the Sodium compatibility next door it is code that cannot run at all unless the other mod is
 * installed, which makes it some of the least-exercised in the mod and, in a port, some of the most
 * likely to have quietly stopped working.
 *
 * What makes this testable rather than merely watchable is that Create does not draw the network
 * straight to the screen. `TrainMapRenderer` rasterises it into 128-by-128 tiles indexed by world
 * coordinates, and JourneyMap's overlay then blits those. So the question "is the railroad on the
 * map" has an exact answer -- ask the raster what colour it is above the rail -- and needs no
 * screenshot comparison and no guessing at pixels.
 *
 * @see SodiumTest for the other mod this suite is checked against, and the same argument for why
 */
@DrivesMinecraft
class JourneyMapTest {

    @Test
    @DisplayName("JourneyMap is loaded on the game client")
    fun `journeymap is loaded`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val mods = modsOnTheClient()

        // First, for the same reason as everywhere else: Create's map overlay is behind an
        // `isLoaded` check, so without JourneyMap present every other assertion here would be
        // testing that nothing happens.
        assertTrue(
            mods.loaded,
            "JourneyMap is not loaded on the client, so Create's map overlay cannot run at all. " +
                "The client has ${mods.count} mods: ${mods.some}",
        )
    }

    @Test
    @DisplayName("A railway is painted onto the map where the track was laid")
    fun `the railway is drawn on the map`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()

        // Track and nothing else. A station would give the map a marker to draw and make the
        // picture easier to read, and that is exactly why there is not one: what is being checked
        // here is that the *rail* is drawn, and a marker sitting on it would answer for it.
        layStraightTrack()
        serverTicks(SETTLE_TICKS)

        // The client has to know about the railway before anything can draw it. `CreateClient.RAILWAYS`
        // is the client's own copy, kept up to date by the server, and the map is drawn from that
        // rather than from blocks -- so a railway the client has not heard of is a blank map for
        // reasons nothing to do with JourneyMap.
        val known = railwaysTheClientKnows()

        assertTrue(
            known > 0,
            "The client does not know about any railway, so there is nothing for the map to draw",
        )

        openTheMap()
        serverTicks(DRAWING_TICKS)

        // Zoomed in before the picture is taken. At the zoom the map opens on, a run of two dozen
        // blocks is a couple of pixels, and a railway that is drawn correctly and a railway that is
        // not drawn at all look much the same.
        client(watcher) { repeat(ZOOM_STEPS) { scroll(1.0) } }
        serverTicks(SETTLE_TICKS)

        shot("journeymap_railway")

        val painted = whatTheMapPainted(at(0, 0, 0), at(0, 0, RUN - 1))

        // Both halves matter. Something drawn over the rail says the overlay ran; nothing drawn well
        // away from it says what ran was the railway rather than a fill.
        assertTrue(
            painted.onTheRail > 0,
            "Nothing is painted on the map where the track was laid: $painted",
        )
        assertTrue(
            painted.offTheRail == 0,
            "The map has paint where there is no track at all, so what it drew is not the " +
                "railway: $painted",
        )

        closeAnyScreen()
    }

    @Test
    @DisplayName("A curved corner reaches the map, through the markers Create lays along it")
    fun `a corner is drawn on the map`(cluster: ClusterScope) = cluster.stage(radius = ROOM) {
        theClient()
        clearGround(at(CORNER / 2, RAIL, LEG), radius = GROUND)

        // Grass rather than the stone the stage lays, so the picture is worth looking at: Create's
        // track and its map markers are both grey, and grey rail over grey floor is exactly as
        // legible on a map as it sounds. Green underneath is what makes a missing corner obvious.
        fill(
            at(CORNER / 2 - GRASS, 0, LEG - GRASS),
            at(CORNER / 2 + GRASS, 0, LEG + GRASS),
            "minecraft:grass_block",
        )
        serverTicks(SETTLE_TICKS)

        val connected = layACorner()
        assertTrue(connected.valid, "Create would not join the two straights into a curve -- $connected")
        serverTicks(SETTLE_TICKS)
        shot("journeymap_corner_world")

        // The HUD put back, so the minimap is in the picture at all. What it shows is JourneyMap
        // drawing blocks -- the straights, and the curve's markers -- rather than Create's overlay,
        // which is a fullscreen thing only.
        restoreHud()

        // Stood to one side of the corner rather than on it. The minimap is centred on the player,
        // and a curve directly underneath is a curve behind the player marker; eight blocks east of
        // it puts the whole turn in the open where a gap in it would be plain.
        standBesideTheCorner()

        // Waited out rather than dismissed, twice over. Joining a server raises a "Chat messages
        // can't be verified" toast in the top right, which is exactly where the minimap is drawn --
        // and JourneyMap maps the ground on its own schedule, so a picture taken as soon as the
        // warning clears is a picture of a minimap that has not looked at anything yet.
        serverTicks(TOAST_TICKS)
        serverTicks(MAP_MAKING_TICKS)

        // Zoomed in with the key a player uses. At the zoom the minimap opens on, the whole corner
        // is a few pixels across and a missing curve is indistinguishable from a drawn one.
        client(watcher) { repeat(MINIMAP_ZOOM_STEPS) { press(ZOOM_IN) } }
        serverTicks(SETTLE_TICKS)

        shot("journeymap_minimap")

        // A curve carries no track blocks of its own -- it is a `BezierConnection` held by the two
        // block entities at its ends -- so Create lays `create:fake_track` along it, an invisible
        // block whose only job is to give map mods something to draw. Everything below is about
        // whether that marker survives the journey to a map.
        val corner = whatStandsOnTheCorner()

        assertTrue(corner.curved, "The two straights were never joined into a curve: $corner")

        // The marker blocks are the whole mechanism by which a curve reaches any map that draws
        // blocks -- the minimap included. No markers means nothing for a minimap to show, however
        // well the fullscreen overlay draws the same curve from the graph.
        assertTrue(
            corner.markers > 0,
            "The curve laid down no `create:fake_track` markers, so nothing that draws blocks can " +
                "show it: $corner",
        )

        // And the same count again, asked of the client. The minimap draws from the client's own
        // copy of the world, so markers that exist only on the server are markers no minimap can
        // show -- while the fullscreen overlay, which is drawn from the railway graph, would carry
        // on looking perfect. That is exactly the shape of "straights fine, corners missing".
        val onTheClient = markersTheClientHas()

        assertTrue(
            onTheClient > 0,
            "The markers exist on the server but not on the client, so nothing the client draws " +
                "could ever show the curve: $corner",
        )

        // And the assertion this whole investigation came down to. A map mod draws a block only if
        // it has metadata for it, and JourneyMap builds none for a block whose render shape is
        // INVISIBLE -- which `FakeTrackBlock` used to report. With no metadata, JourneyMap's own
        // Create handler never ran, so the `Force` flag it exists to add was never added, and the
        // marker was skipped: a railway drawn on the minimap with its corners missing, while the
        // fullscreen overlay -- which comes from the railway graph rather than from blocks -- stayed
        // perfect. The block now reports MODEL and draws nothing, its model being empty.
        val seen = whatJourneyMapMakesOfTrack()

        assertTrue(
            seen.contains("Force"),
            "JourneyMap has no forced metadata for the curve's markers, so it will draw the " +
                "straights and lose every corner. What it has: $seen",
        )

        openTheMap()
        serverTicks(DRAWING_TICKS)
        client(watcher) { repeat(ZOOM_STEPS) { scroll(1.0) } }
        serverTicks(SETTLE_TICKS)
        shot("journeymap_corner")

        val painted = whatTheMapPaintedOnTheCorner()

        // And yet the map has it. Create paints the network itself, from the railway graph, so the
        // curve is drawn over ground that holds nothing -- which is exactly what a map drawing
        // blocks cannot do, and why JourneyMap's minimap shows straights and loses corners.
        assertTrue(
            painted > 0,
            "The curve is not on the map: nothing is painted along it, though the straights either " +
                "side of it are blocks the map could have drawn anyway",
        )

        closeAnyScreen()
    }

    /** Track along z, laid the way [com.simibubi.create.e2e.trains.TrackGraphTest] lays it. */
    private suspend fun Stage.layStraightTrack() {
        for (along in 0 until RUN) setBlock(at(0, 0, along), "create:track[shape=zo]")
    }

    /**
     * JourneyMap's fullscreen map, opened the way a player opens it.
     *
     * It has to be the fullscreen map specifically. `JourneyTrainMap.tick` does nothing at all unless
     * the open screen is JourneyMap's own `Fullscreen`, so an overlay drawn over the minimap, or over
     * no map, is not this feature.
     */
    private suspend fun Stage.openTheMap() {
        closeAnyScreen()
        client(watcher) { press(MAP_KEY) }
        serverTicks(SETTLE_TICKS)

        // JourneyMap greets a fresh profile with its About screen and shows that instead of the map.
        // A client borrowed from the pool has whatever profile the last test left, so this cannot be
        // done once at start-up -- it is dismissed here, and the key pressed again.
        if (openScreenName() == GREETING) {
            closeAnyScreen()
            client(watcher) { press(MAP_KEY) }
            serverTicks(SETTLE_TICKS)
        }

        val screen = openScreenName()

        assertTrue(
            screen != null && screen.contains("Fullscreen", ignoreCase = true),
            "Pressing the map key did not open JourneyMap's fullscreen map; what came up was $screen",
        )
    }

    /**
     * An L of track: a straight run, then a quarter turn, made without anybody clicking.
     *
     * The straights go down with `/setblock` like any block. The curve cannot -- a `BezierConnection`
     * is worked out from two selected ends rather than placed -- so the two calls the track item
     * makes are made directly instead. `TrackBlockItem.select` is the first click, recording which
     * end of which piece the curve leaves from onto the held stack, and `TrackPlacement.tryConnect`
     * is the second, which works the turn out and, on a server, places it.
     *
     * Which way the player is looking is not incidental to either. `select` picks the nearer of the
     * track's two axes by the look vector, and `tryConnect` does the same at the far end, so the two
     * ends have to be looked along or the curve comes out backwards or not at all.
     */
    private suspend fun Stage.layACorner(): Connected {
        // Creative, and not for convenience. `tryConnect` only counts out the track a curve would
        // cost when the player is not in creative, and refuses with `not_enough_tracks` if the held
        // stack is short -- so a player left in spectator by the camera fails the count rather than
        // the geometry, and says so in words about track it never needed.
        setPlayerMode(watcher, PlayerMode.CREATIVE)

        for (along in 0..LEG) setBlock(at(0, RAIL, along), "create:track[shape=zo]")
        for (across in CORNER..CORNER + LEG) setBlock(at(across, RAIL, LEG + CORNER), "create:track[shape=xo]")
        serverTicks(SETTLE_TICKS)

        return connectTheCorner(watcher, at(0, RAIL, LEG), at(CORNER, RAIL, LEG + CORNER))
    }

    private suspend fun Stage.connectTheCorner(who: String, from: BlockPos, to: BlockPos): Connected =
        server(who, from, to) { name, start, landing ->
            val player = minecraftServer.playerList.getPlayerByName(name)
                ?: return@server Connected(false, "there is no player called $name on the server")

            val stack = com.simibubi.create.AllBlocks.TRACK.asStack()
            stack.count = 64

            // The first click: the south end of the straight run.
            val south = net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
            if (!com.simibubi.create.content.trains.track.TrackBlockItem
                    .select(serverLevel, start, south, stack)
            ) {
                return@server Connected(false, "the end of the straight run could not be selected")
            }

            // And the second, with the player turned to look east along the piece being joined to.
            // Yaw is measured from south, so east is -90.
            player.setYRot(-90.0f)
            player.setXRot(0.0f)

            val info = com.simibubi.create.content.trains.track.TrackPlacement.tryConnect(
                serverLevel,
                player,
                landing,
                serverLevel.getBlockState(landing),
                stack,
                false,
                false,
            )

            // `valid` and `message` are package-private, and they are the only account Create gives
            // of why a curve was refused -- the same words the item would have put on the screen.
            val kind = info.javaClass
            val valid = kind.getDeclaredField("valid").also { it.isAccessible = true }.getBoolean(info)
            val why = kind.getDeclaredField("message").also { it.isAccessible = true }.get(info) as String?

            Connected(
                valid,
                why ?: if (valid) "" else "no reason given",
            )
        }

    /** What Create made of the request, in a shape that can cross a wire. */
    @Serializable
    private data class Connected(val valid: Boolean, val why: String) {
        override fun toString(): String = if (valid) "connected" else "refused: $why"
    }

    /**
     * What is standing along the curve, scanned as a box rather than sampled along the chord.
     *
     * The chord is the straight line between the two ends, and a curve bulges away from it -- so
     * sampling there answers a question about ground the curve never crosses. What is being looked
     * for is `create:fake_track`, whose registered name is "Track Marker for Maps": an invisible,
     * collisionless block with a map colour, placed along every curve by
     * `TrackBlockEntity.manageFakeTracksAlong` for the sole purpose of giving map mods something to
     * draw where a bezier has no blocks of its own.
     */
    private suspend fun Stage.whatStandsOnTheCorner(): Corner =
        server(at(0, RAIL, LEG), at(CORNER, RAIL, LEG + CORNER)) { from, to ->
            val curved = serverLevel.getBlockEntity(from)
                .let { it is com.simibubi.create.content.trains.track.TrackBlockEntity && it.connections.isNotEmpty() }

            var markers = 0
            var solid = 0

            for (x in minOf(from.x, to.x)..maxOf(from.x, to.x)) {
                for (z in minOf(from.z, to.z)..maxOf(from.z, to.z)) {
                    for (y in from.y - 1..from.y + 2) {
                        val state = serverLevel.getBlockState(net.minecraft.core.BlockPos(x, y, z))
                        if (com.simibubi.create.AllBlocks.FAKE_TRACK.has(state)) markers++
                        else if (state.block == com.simibubi.create.AllBlocks.TRACK.get()) solid++
                    }
                }
            }

            Corner(curved, markers, solid)
        }

    /**
     * What JourneyMap's own block metadata says about the curve's marker.
     *
     * This is the check the whole thing came down to, and it needs no eyes: `BlockMD` is where
     * JourneyMap decides whether a block is drawn and what colour it is, and at minimap zoom a rail
     * is one pixel, so a screenshot cannot tell a drawn curve from a missing one.
     *
     * By reflection, because JourneyMap's classes ship in a nested jar that is on the game's
     * classpath and not on this module's.
     */
    private suspend fun Stage.whatJourneyMapMakesOfTrack(): String = client(watcher) {
        val kind = Class.forName("journeymap.client.model.block.BlockMD")

        val all = kind.getMethod("getAll").invoke(null) as Collection<*>
        val blockId = kind.getMethod("getBlockId")
        val ignored = kind.getMethod("isIgnore")
        val flags = kind.getMethod("getFlags")
        val textureColour = kind.getMethod("getTextureColor")

        val marker = all.firstOrNull { blockId.invoke(it) == "create:fake_track" }
            ?: return@client "no metadata for create:fake_track at all, out of ${all.size} blocks"

        val colour = try {
            "%06x".format(textureColour.invoke(marker) as Int)
        } catch (wrong: Throwable) {
            "threw " + (wrong.cause ?: wrong).javaClass.simpleName
        }

        "create:fake_track [ignore=${ignored.invoke(marker)}, flags=${flags.invoke(marker)}, " +
            "texture=$colour]"
    }

    /** The player put beside the corner, on the grass, looking west across it. */
    private suspend fun Stage.standBesideTheCorner() {
        setPlayerMode(watcher, PlayerMode.CREATIVE)

        val where = at(CORNER + ASIDE_OF_CORNER, RAIL, LEG + CORNER / 2)
        runOnServer("tp $watcher ${where.x} ${where.y} ${where.z} 90 20")
        serverTicks(SETTLE_TICKS)
    }

    /** The same markers, counted in the client's world rather than the server's. */
    private suspend fun Stage.markersTheClientHas(): Int =
        client(watcher, at(0, RAIL, LEG), at(CORNER, RAIL, LEG + CORNER)) { from, to ->
            val level = minecraft.level ?: return@client 0

            var markers = 0
            for (x in minOf(from.x, to.x)..maxOf(from.x, to.x)) {
                for (z in minOf(from.z, to.z)..maxOf(from.z, to.z)) {
                    for (y in from.y - 1..from.y + 2) {
                        val state = level.getBlockState(net.minecraft.core.BlockPos(x, y, z))
                        if (com.simibubi.create.AllBlocks.FAKE_TRACK.has(state)) markers++
                    }
                }
            }

            markers
        }

    /** How much paint the map put along the curve, where there is nothing standing. */
    private suspend fun Stage.whatTheMapPaintedOnTheCorner(): Int =
        client(watcher, at(0, RAIL, LEG)) { from ->
            val map = com.simibubi.create.compat.trainmap.TrainMapRenderer.INSTANCE

            var painted = 0
            for (step in 1 until CORNER) {
                if (!map.isEmpty(from.x + step, from.z + step)) painted++
            }

            painted
        }

    /** What the corner turned out to be made of, in a shape that can cross a wire. */
    @Serializable
    private data class Corner(val curved: Boolean, val markers: Int, val rails: Int) {
        override fun toString(): String =
            (if (curved) "a real curve" else "no curve at all") +
                ", with $markers map marker(s) and $rails track block(s) around it"
    }

    private suspend fun Stage.modsOnTheClient(): Mods = client(watcher) {
        val mods = net.neoforged.fml.ModList.get()

        Mods(
            loaded = mods.isLoaded(JOURNEYMAP),
            count = mods.size(),
            some = mods.mods.take(12).joinToString(", ") { it.modId },
        )
    }

    private suspend fun Stage.railwaysTheClientKnows(): Int = client(watcher) {
        com.simibubi.create.CreateClient.RAILWAYS.trackNetworks.size
    }

    /**
     * What Create painted onto the map raster along the rail, and what it painted away from it.
     *
     * Sampled off `TrainMapRenderer`, which is the tile the overlay blits rather than the screen it
     * is blitted to -- so this is asking what was drawn, not what a camera happened to catch.
     */
    private suspend fun Stage.whatTheMapPainted(from: BlockPos, to: BlockPos): Painted =
        client(watcher, from, to) { start, end ->
            val map = com.simibubi.create.compat.trainmap.TrainMapRenderer.INSTANCE

            var on = 0
            for (along in start.z..end.z) if (!map.isEmpty(start.x, along)) on++

            // Far enough aside to be off any line the renderer might have thickened, and still well
            // inside the same tile, so an empty answer there means nothing was drawn rather than that
            // no tile exists.
            var off = 0
            for (along in start.z..end.z) if (!map.isEmpty(start.x + ASIDE, along)) off++

            Painted(on, off, end.z - start.z + 1)
        }

    /** What a game has loaded, in a shape that can cross a wire. */
    @Serializable
    private data class Mods(val loaded: Boolean, val count: Int, val some: String)

    /** And what ended up on the map, in the same. */
    @Serializable
    private data class Painted(val onTheRail: Int, val offTheRail: Int, val sampled: Int) {
        override fun toString(): String =
            "$onTheRail of $sampled painted along the rail, $offTheRail painted $ASIDE blocks aside"
    }

    private companion object {

        fun standingBack(pos: BlockPos, facing: Direction) = net.minecraft.world.phys.Vec3(
            pos.x + 0.5 - facing.stepX * 3.0,
            (pos.y + 2).toDouble(),
            pos.z + 0.5 - facing.stepZ * 3.0,
        )

        fun surfaceOf(pos: BlockPos) = net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 0.2, pos.z + 0.5)

        fun topOf(pos: BlockPos) = net.minecraft.world.phys.Vec3(pos.x + 0.5, (pos.y + 1).toDouble(), pos.z + 0.5)


        const val JOURNEYMAP = "journeymap"

        /** What JourneyMap puts up the first time it is opened on a profile. */
        const val GREETING = "AboutScreen"

        /** J, by JourneyMap's own default, given by GLFW code because the driver names few keys. */
        val MAP_KEY: Key = Key.code(74)

        /** Long enough to be a railway worth drawing, short enough to lay quickly. */
        const val RUN = 24

        /** Room for the run, which is longer than a stage hands out by default. */
        const val ROOM = 48

        /** Notches of zoom before the picture, so the railway is more than a pixel or two. */
        const val ZOOM_STEPS = 4

        /** How far aside to look for paint that should not be there. */
        const val ASIDE = 12

        /** Floor for the corner to be clicked onto: a track item is used on the block below. */
        const val GROUND = 18

        /**
         * How far the grass reaches, which is further than the stone the stage lays.
         *
         * Wider on purpose. Create's track and its map markers are both grey, and the stage's floor
         * is grey too -- so grey-on-grey is exactly as readable on a map as it sounds, and any stone
         * left showing around the edge is stone a curve could be hiding against.
         */
        const val GRASS = 26

        /** Rails one above the floor the stage lays. */
        const val RAIL = 1

        /** How far the straight runs before it turns. */
        const val LEG = 6

        /**
         * How wide the quarter turn is, which is also how many places along it are looked at.
         *
         * Eight rather than four, which Create refuses outright with `track.too_sharp`: a curve has
         * a minimum radius, and a quarter turn drawn across four blocks is inside it. The circuit
         * test uses the same eight.
         */
        const val CORNER = 8

        /** Long enough for the join warning to expire off the corner the minimap lives in. */
        const val TOAST_TICKS = 160

        /** And long enough after that for JourneyMap to have actually mapped the ground. */
        const val MAP_MAKING_TICKS = 600

        /** How far east of the corner the player stands, so the curve is beside them not under. */
        const val ASIDE_OF_CORNER = 8

        /** JourneyMap's minimap zoom-in, on the numpad, by GLFW code. */
        val ZOOM_IN: Key = Key.code(334)

        const val MINIMAP_ZOOM_STEPS = 4

        const val SETTLE_TICKS = 10

        /** Long enough for a client tick to notice the map is open and redraw the network. */
        const val DRAWING_TICKS = 40
    }
}
