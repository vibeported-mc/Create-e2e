package com.simibubi.create.e2e

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.UiLayer
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.screenshot
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.setUiLayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.minecraft.core.BlockPos
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
    startServer()
    startClient(ALEX)
    requireGamesAlive("before this test started")

    try {
        withTimeout(within) { body() }
    } finally {
        // After the body, whatever it did. A game that has gone is the real reason for whatever the
        // body reported, and saying so here is the difference between one honest failure and a dozen
        // confusing ones.
        requireGamesAlive("during this test")
    }
}

/**
 * Fails plainly when a game has died, rather than letting the next call time out.
 *
 * Create crashing the dedicated server is exactly the kind of thing this module exists to catch, and
 * it is worth reading as that. Without this the crash surfaces as a driver call to a node that is no
 * longer there -- and then as the same thing again for every test after it, none of which mention a
 * server at all. The crash report the game wrote says what actually happened; this points at it.
 */
private fun ClusterScope.requireGamesAlive(when_: String) {
    val dead = deadProcess() ?: return
    error(
        "mcdriver: the $dead process died $when_. Nothing after this can run. " +
            "Look at its log under build/e2e/logs, and at run/driverServer/crash-reports."
    )
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
