package com.simibubi.create.e2e

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.input.HeldKeys
import net.minecraft.client.KeyMapping
import dev.vibeported.mc.driver.MouseButton
import dev.vibeported.mc.driver.UiLayer
import dev.vibeported.mc.driver.allowFlight
import dev.vibeported.mc.driver.click
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.screenshot
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.setUiLayer
import dev.vibeported.mc.driver.useBlock
import dev.vibeported.mc.driver.whileGamesLive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * What every ported gametest needs before it can do anything.
 *
 * The originals are Fabric client gametests: one process, one client, an integrated server, and a
 * fresh world each. These run against a real dedicated server and a real client, in three
 * processes, which is the arrangement Create actually ships into -- and the arrangement that caught
 * the dist problems this module exists for. Three things follow from that, and they are the whole
 * difference between the two files read side by side.
 *
 * The world is shared. One server, booted once for the run, so every class builds in a strip of the
 * world of its own -- see Zones.
 *
 * Nothing crosses that cannot be serialised. A body runs in another process, so it may not capture
 * anything: no `this`, no local, no enclosing value. Everything it needs arrives as an argument, and
 * the compiler refuses the rest. That is why the originals' inner records become plain geometry
 * here, with each server call taking the positions it touches.
 *
 * The camera is a client, not a hook. `context.runOnClient(...)` becomes a call to a process with
 * its own render thread and its own idea of what it has loaded, so moving the camera means moving a
 * real player and then waiting for that client to agree it has arrived.
 */

/** The one client these tests drive. Also its username. */
internal const val ALEX: String = "alex"

/**
 * Boots what is needed and runs [body] under a deadline.
 *
 * Both starts are idempotent -- they return at once when the node is already on the roster -- so a
 * suite of thirteen tests boots one server and one client rather than thirteen of each. The boot is
 * outside the timeout on purpose: a client takes the better part of a minute to reach a world, that
 * happens once for the whole run, and it is not the thing under test.
 *
 * `runBlocking` rather than `runTest`: everything here waits on wall-clock events in other
 * processes, which virtual time cannot advance.
 */
internal fun ClusterScope.driving(
    within: Duration = 4.minutes,
    body: suspend () -> Unit,
): Unit = runBlocking {
    // Both are idempotent, and after a crash the driver has already put back whatever died -- so
    // these are also what re-arms a test that follows one. Nothing here has to know that: a game
    // dying is the driver's business, and it fails the test that broke it and rebuilds the cluster
    // before the next one starts. @see dev.vibeported.mc.driver.junit.DriverExtension
    startServer()
    startClient(ALEX)

    // `whileGamesLive` is what makes a crash arrive promptly. Without it the body simply stops
    // getting answers -- its next call is to a game that has gone -- and the test spends its whole
    // deadline waiting before anything says why. With it, the crash is the failure.
    var leftOpen: String? = null
    try {
        whileGamesLive {
            withTimeout(within) {
                aCleanSlate()
                body()
            }
        }

        // Only asked of a test that got through. One that failed has a real failure to report and
        // whatever it left on screen is a consequence of that, not a finding of its own.
        leftOpen = openScreenName()
    } finally {
        // Outside `whileGamesLive` and outside the deadline on purpose. A test that timed out or
        // whose game died is cancelled, and a suspending call made from the `finally` of a
        // cancelled coroutine is refused before it is sent -- which is exactly the case where
        // something is most likely to be left standing. `runCatching` because a client that has
        // died cannot be tidied up and that is not this test's failure to report either.
        withContext(NonCancellable) { runCatching { closeAnyScreen() } }
    }

    // Left as a failure of the test that did it rather than of whichever test runs next and finds
    // its first click eaten. That failure names the wrong test and says nothing about why.
    if (leftOpen != null) {
        throw AssertionError(
            "The test finished with $leftOpen still on screen. A screen left open is the next " +
                "test's problem, so close it where it was opened."
        )
    }
}

/**
 * What every test is handed before it starts.
 *
 * These tests share one world and one client, so each of them inherits whatever the last one left
 * behind -- and the two things that carry over both present as something else entirely. A screen
 * still standing eats the next test's first click, which reads as a screen that never opened; a
 * hidden heads-up display and a world at night make every screenshot from then on a picture of
 * something other than what was meant.
 *
 * So the slate is wiped here rather than left to each test to remember at its end, where a test that
 * fails never reaches it.
 */
private suspend fun aCleanSlate() {
    runCommand("time set day")

    // Nothing is to wander into a scene. A cow standing on a belt is counted as something the belt
    // is carrying, a slime pushes a contraption off its rails, and either of them in front of the
    // crosshair is a click that lands on an animal rather than on the block being tested -- and all
    // three read as the mod misbehaving rather than as the weather.
    //
    // The gamerule is the whole of it, and deliberately so. Peaceful would also do the job and was
    // what this did at first, but it does more than stop things arriving: a zombie cannot exist at
    // all on peaceful, and one of these tests dresses one in a backtank to see whether it survives
    // being dropped in lava. Nothing spawns of its own accord either way.
    //
    // What is deliberately not done is killing what is left standing: the stock keeper's shop is
    // minded by a pig, a train is a contraption entity, and a blanket kill would take both.
    runCommand("gamerule doMobSpawning false")
    runCommand("difficulty normal")

    setUiLayer(ALEX, UiLayer.GUI, true)

    closeAnyScreen()

    // And nothing still held down. A test that failed part way through a gesture -- driving a train
    // is a key held for a minute at a time -- leaves it held, and the next test then starts walking
    // forwards for reasons it has no way of discovering.
    client(ALEX) {
        HeldKeys.releaseAll()
        KeyMapping.releaseAll()
        awaitTicks(1)
    }
}

/**
 * A strip of the world for each ported class.
 *
 * The originals each got a world to themselves. Here there is one, so they are laid out along z
 * instead -- far enough apart that no scene sits in another's chunks, and each class adds its own
 * offset to every z it names so its coordinates otherwise read as they did.
 *
 * Within a class the originals already did this where they had to: `BrassTunnelTest` gives each
 * selection mode a patch of its own, and that arithmetic is carried over unchanged.
 */
internal object Zones {
    const val FLUID_TRANSFER: Int = 0
    const val HOSE_PULLEY: Int = 128
    const val ITEM_LOGISTICS: Int = 256
    const val BRASS_TUNNEL: Int = 384
    const val LOGISTICS: Int = 640

    // The contraption scenes are small -- twenty blocks at the widest -- so they sit closer together
    // than the belt runs above, which are long.
    const val WATER_WHEEL: Int = 1024
    const val ENCASED_FAN: Int = 1088
    const val MIXER: Int = 1152
    const val MECHANICAL_ARM: Int = 1216
    const val MECHANICAL_CRAFTER: Int = 1280
    const val PORTABLE_FLUID: Int = 1344
    const val PORTABLE_HARVEST: Int = 1408
    const val PROCESSING: Int = 1472

    // The screen tests mostly need one block and a place to stand, so they sit closer still.
    const val SEQUENCED_GEARSHIFT: Int = 2048
    const val VALUE_SETTINGS: Int = 2080
    const val THRESHOLD_SWITCH: Int = 2112
    const val DISPLAY_LINK: Int = 2144
    const val ELEVATOR_CONTACT: Int = 2176
    const val FACTORY_PANEL: Int = 2208
    const val FILTER: Int = 2240
    const val LINKED_CONTROLLER: Int = 2272
    const val PACKAGE_PORT: Int = 2304
    const val REDSTONE_REQUESTER: Int = 2336
    const val SCHEMATICANNON: Int = 2368
    const val SCHEMATIC_TABLE: Int = 2400
    const val STOCK_KEEPER: Int = 2432
    const val TOOLBOX: Int = 2464
    const val CLIPBOARD: Int = 2496
    const val BLUEPRINT: Int = 2528
    const val RADIAL_WRENCH: Int = 2560
    const val GOGGLE_CONFIG: Int = 2592
    const val WORLDSHAPER: Int = 2624
    const val SCHEMATIC_EDIT: Int = 2656

    // The train scenes need room: a station has to belong to a run of track, and a run of track is
    // long. They also cannot share one -- a stretch of rails stays claimed by the railway after the
    // blocks are cleared away, so a second station laid over the same rails is refused by something
    // that no longer exists in the world.
    const val STATION_NAMING: Int = 2720
    const val STATION_ASSEMBLY: Int = 2784
    const val SCHEDULE: Int = 2848

    /**
     * The circuit is not a scene but a landscape: forty blocks across and forty along, laid on the
     * world's own grass rather than on a floor of its own. So it is given the far end to itself.
     */
    const val TRAIN_CIRCUIT: Int = 3200

    /**
     * Where Create's own saved machines are laid down, one after another in the same spot.
     *
     * They are structures rather than scenes -- the game places them from a file -- so they need
     * room to be cleared and rebuilt rather than a patch each.
     */
    const val GAMETEST: Int = 3600
}

/**
 * Runs a command on the server, as the server.
 *
 * `/setblock` and `/fill` rather than the driver's own `worldBuild`, and the difference matters:
 * `worldBuild` places blocks with `UPDATE_KNOWN_SHAPE` so a fixture is exactly what was written,
 * while every scene here is built out of blocks that are supposed to react to each other -- a funnel
 * finding the belt beneath it, a pipe finding the pump beside it, a tunnel joining the group next
 * door. Those need the neighbour updates the command gives them, which is what the originals got.
 */
internal suspend fun runCommand(command: String) {
    server(command) { line ->
        minecraftServer.commands.performPrefixedCommand(minecraftServer.createCommandSourceStack(), line)
        Unit
    }
}

internal suspend fun setBlock(pos: BlockPos, state: String) {
    runCommand("setblock ${pos.x} ${pos.y} ${pos.z} $state")
}

internal suspend fun fill(low: BlockPos, high: BlockPos, block: String) {
    runCommand("fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} $block")
}

/** A stack of [count] [item] into one slot of the container at [pos]. */
internal suspend fun give(pos: BlockPos, slot: Int, item: String, count: Int) {
    runCommand("item replace block ${pos.x} ${pos.y} ${pos.z} container.$slot with $item $count")
}

/** Suspends until the server has ticked [count] more times. */
internal suspend fun serverTicks(count: Int) {
    server(count) { n -> awaitTicks(n) }
}

/**
 * Polls [condition] every [step] server ticks until it holds, and says how long that took.
 *
 * The originals' `while (waited < PATIENCE && !arrived)` loop, kept rather than replaced by an
 * `awaitUntil` inside a server body. Two reasons: the number of ticks waited goes into the failure
 * message, and a condition that never comes true fails against this process's own deadline rather
 * than leaving a body spinning on the server's game loop.
 */
internal suspend fun waitForTicks(patience: Int, step: Int = 20, condition: suspend () -> Boolean): Int {
    var waited = 0
    while (waited < patience && !condition()) {
        serverTicks(step)
        waited += step
    }
    return waited
}

/**
 * Puts the camera somewhere with a view, and does not return until the client is really there.
 *
 * Spectator so nothing of the player is in the way, and the heads-up display goes with it -- both as
 * the originals did, since these screenshots are the record of what the scene looked like. `/tp`
 * places feet rather than eyes, so the eye height comes off the server and is taken back out of the
 * y that was asked for, which is the arithmetic the originals were doing too.
 *
 * The wait at the end has no counterpart there. A client gametest's camera is the same process as
 * the world; here it is a second game that has to receive the move, then load and render the chunks
 * it lands in -- and every scene these tests build is somewhere it has never been.
 */
internal suspend fun spectateFrom(
    x: Double,
    y: Double,
    z: Double,
    yaw: Double,
    pitch: Double,
    settle: Int = 60,
) {
    setUiLayer(ALEX, UiLayer.GUI, false)
    runCommand("gamemode spectator $ALEX")

    val eyeHeight = server(ALEX) { name -> playerNamed(name).eyeHeight.toDouble() }
    val feet = y - eyeHeight

    runCommand("tp %s %.2f %.2f %.2f %.2f %.2f".format(Locale.ROOT, ALEX, x, feet, z, yaw, pitch))

    val target = BlockPos(kotlin.math.floor(x).toInt(), kotlin.math.floor(feet).toInt(), kotlin.math.floor(z).toInt())
    client(ALEX, target) { where ->
        awaitUntil { clientPlayer?.blockPosition()?.closerThan(where, 3.0) == true }
    }
    client(ALEX, settle) { ticks -> awaitTicks(ticks) }
}

/**
 * Straight down over a point, which is how the belt scenes are watched.
 *
 * Pitch 90 is looking at the floor; the yaw only decides which way north points in the picture.
 */
internal suspend fun lookDownOn(x: Double, y: Double, z: Double, settle: Int = 60) {
    spectateFrom(x, y, z, yaw = 0.0, pitch = 90.0, settle = settle)
}

/**
 * Watches [at] from [from], as a spectator.
 *
 * `/tp ... facing ...` rather than the trigonometry the originals wrote out by hand: the command
 * does the same arithmetic, and it is the eye that is aimed either way, so the eye-height correction
 * [spectateFrom] needs does not arise here.
 */
internal suspend fun spectateAt(from: Vec3, at: Vec3, settle: Int = 60) {
    setUiLayer(ALEX, UiLayer.GUI, false)
    runCommand("gamemode spectator $ALEX")
    teleportFacing(from, at)
    awaitCamera(from, settle)
}

/**
 * Stands at [from] looking at [at], in creative and flying.
 *
 * The mode matters and is the reason this is not [spectateAt]: a spectator's clicks pass through the
 * world, so anything that goes on to press a button has to be standing in it. Flying because where a
 * camera is put need not be where the ground is, and a player who is falling is aiming from
 * somewhere they are about to leave.
 */
internal suspend fun standAt(from: Vec3, at: Vec3, settle: Int = 40) {
    setUiLayer(ALEX, UiLayer.GUI, false)
    runCommand("gamemode creative $ALEX")
    allowFlight(ALEX)
    teleportFacing(from, at)
    awaitCamera(from, settle)
}

/**
 * Puts the player at [from], looking at [at].
 *
 * The angles are worked out here rather than handed to `/tp ... facing`, and that is not a
 * preference. The command aims from the entity's *position* -- its feet -- while what has to be
 * pointed at the target is the eye, a block and a half higher. Far away that is a fraction of a
 * degree and nothing notices; at the two or three blocks a screen test stands from what it is
 * clicking it is thirty degrees, and the crosshair sails over the block entirely.
 *
 * Which is why the originals did the trigonometry by hand against `getEyePosition`. This is that,
 * with the eye height read off the server since only it knows how tall the player currently is.
 */
private suspend fun teleportFacing(from: Vec3, at: Vec3) {
    val eyeHeight = server(ALEX) { name -> playerNamed(name).eyeHeight.toDouble() }

    val dx = at.x - from.x
    val dy = at.y - (from.y + eyeHeight)
    val dz = at.z - from.z
    val flat = kotlin.math.sqrt(dx * dx + dz * dz)

    val yaw = Math.toDegrees(kotlin.math.atan2(dz, dx)) - 90.0
    val pitch = -Math.toDegrees(kotlin.math.atan2(dy, flat))

    runCommand(
        "tp %s %.3f %.3f %.3f %.3f %.3f"
            .format(Locale.ROOT, ALEX, from.x, from.y, from.z, yaw, pitch)
    )
}

private suspend fun awaitCamera(from: Vec3, settle: Int) {
    val target = BlockPos.containing(from)
    client(ALEX, target) { where ->
        awaitUntil { clientPlayer?.blockPosition()?.closerThan(where, 3.0) == true }
    }
    client(ALEX, settle) { ticks -> awaitTicks(ticks) }
}

/**
 * Stands within reach of a block and right-clicks it, the way a player uses one.
 *
 * Feet on `pos.y` -- the top of whatever holds the block up -- and three blocks back, so the player
 * is standing on the ground beside it looking level, exactly where a person would be.
 *
 * The originals stood a block *below* the block's centre, and copying that put the player inside the
 * floor. These scenes clear their own ground first and `clearGround` lays its floor one block under
 * the target, so that spot is mid-air: the player falls, clips half a block into the stone, and ends
 * up embedded in it. From in there the view is black, the raycast hits the floor at point-blank
 * range, and the click lands on nothing -- which reads as "the screen never opened" and says nothing
 * about where the player actually was. Standing on solid ground also means the aim no longer depends
 * on flight having taken effect before the teleport arrives.
 *
 * The turn itself is [useBlock]'s job -- it faces the block, makes sure the mouse is on the world
 * rather than a screen, and clicks.
 */
internal suspend fun rightClickBlock(pos: BlockPos) {
    standAt(
        Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 3.5),
        Vec3.atCenterOf(pos),
        settle = 10,
    )
    client(ALEX, pos) { where -> useBlock(where) }
}

/** Right-clicks whatever the crosshair is on, without moving. */
internal suspend fun rightClickAhead() {
    client(ALEX) { click(MouseButton.RIGHT) }
}

/**
 * Puts one of something in the player's hand.
 *
 * The command fills the slot; selecting it is the client's own state, so that half happens there.
 */
internal suspend fun holdItem(item: String, count: Int = 1) {
    runCommand("item replace entity $ALEX hotbar.0 with $item $count")
    client(ALEX) {
        clientPlayer?.inventory?.setSelectedSlot(0)
        awaitTicks(1)
    }
}

/**
 * Flattens a patch of the world and puts a stone floor under it.
 *
 * The world is shared and lived in, so a scene starts by making room for itself. A wide clearing has
 * to be a shallow one: the box is filled by a single command, and one of more than thirty-two
 * thousand blocks is refused outright, without saying so.
 */
internal suspend fun clearGround(centre: BlockPos, radius: Int, height: Int = radius + 1) {
    // The player goes first, and this is the one thing every scene here has to get right. A
    // dedicated server keeps loaded only the chunks somebody is near: a `/setblock` into an empty
    // one places a block that does not stay resident, and a player teleported into one falls through
    // the terrain before it arrives -- landing on the natural surface, several blocks under the
    // scene, looking at grass. Both failures name something else entirely ("there is no display link
    // at ...", "the screen never opened"), which is why this is here rather than left to callers to
    // remember.
    spectateAt(
        Vec3(centre.x + 0.5, centre.y + 4.0, centre.z + 6.0),
        Vec3.atCenterOf(centre),
    )

    runCommand(
        "fill ${centre.x - radius} ${centre.y - 1} ${centre.z - radius} " +
            "${centre.x + radius} ${centre.y + height} ${centre.z + radius} air"
    )
    runCommand(
        "fill ${centre.x - radius} ${centre.y - 1} ${centre.z - radius} " +
            "${centre.x + radius} ${centre.y - 1} ${centre.z + radius} stone"
    )
    killLooseItems()
}

/** Empties a box without laying a floor, for scenes that stand on the ground they were given. */
internal suspend fun clearBox(low: BlockPos, high: BlockPos) {
    fill(low, high, "air")
    killLooseItems()
}

/** Anything a previous scene dropped, which would otherwise be counted by the next one. */
internal suspend fun killLooseItems() {
    runCommand("kill @e[type=item]")
}

/** Drops an item into the world, which is how anything gets into a basin. */
internal suspend fun dropItem(x: Double, y: Double, z: Double, item: String) {
    runCommand(
        "summon minecraft:item %.2f %.2f %.2f {Item:{id:\"%s\",count:1}}"
            .format(Locale.ROOT, x, y, z, item)
    )
}

/** Puts the heads-up display back, so a failure does not leave it hidden for whatever runs next. */
internal suspend fun restoreHud() {
    setUiLayer(ALEX, UiLayer.GUI, true)
}

/** A picture from the client, kept under the capture directory the build points the driver at. */
internal suspend fun shot(name: String) {
    screenshot(ALEX, name)
}

/**
 * The two fluids these scenes move.
 *
 * An enum rather than a `Fluid`: a `Fluid` is a registry object and cannot cross a wire, an enum
 * can, and this one resolves to the real thing on whichever node asks. The originals passed
 * `Fluids.WATER` straight into their inner records, which were only ever read in one process.
 */
internal enum class Liquid(val blockName: String) {
    WATER("minecraft:water"),
    LAVA("minecraft:lava");

    fun fluid(): Fluid = if (this == LAVA) Fluids.LAVA else Fluids.WATER
}
