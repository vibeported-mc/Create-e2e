package com.simibubi.create.e2e

import dev.vibeported.mc.driver.Stage
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
 * Every test gets a stage: a patch of world nobody else is using, a floor on it, and a client
 * borrowed from a pool and standing there. What a test names is relative to that stage, so no class
 * has to reserve a corner of the world, and two of them can run at once.
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

/**
 * Runs a command on the server, as the server.
 *
 * `/setblock` and `/fill` rather than the driver's own `worldBuild`, and the difference matters:
 * `worldBuild` places blocks with `UPDATE_KNOWN_SHAPE` so a fixture is exactly what was written,
 * while every scene here is built out of blocks that are supposed to react to each other -- a funnel
 * finding the belt beneath it, a pipe finding the pump beside it, a tunnel joining the group next
 * door. Those need the neighbour updates the command gives them, which is what the originals got.
 */
/**
 * The client a stage borrowed, which is who every one of these helpers is talking about.
 *
 * There is no ambient client any more and that is the point: a run has several, a stage has whichever
 * one it was given, and a helper that assumed a name would be talking to somebody else's player as
 * soon as two tests ran at once.
 */
internal val Stage.watcher: String
    get() = watchers.firstOrNull() ?: error(
        "This stage has not borrowed a client yet, so there is nobody to ask. Say `val alex = " +
            "client()` at the top of the test."
    )

internal suspend fun runCommand(command: String) {
    // Through the driver's, which parses before it runs. `performPrefixedCommand` returns void: a
    // command with a mistake in it says so to the server log and to nobody else, so a `setblock`
    // that never happened arrives much later as a block entity that is null -- which reads as the
    // mod failing to build something rather than as a line of this file being wrong.
    dev.vibeported.mc.driver.runOnServer(command)
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
internal suspend fun Stage.spectateFrom(
    x: Double,
    y: Double,
    z: Double,
    yaw: Double,
    pitch: Double,
    settle: Int = 60,
) {
    // Counted from this stage's own corner, like everything else a test says. A camera given world
    // coordinates would be sent wherever those happen to be -- which, for numbers written when the
    // suite shared one world, is thousands of blocks away. The stage's chunks then unload behind it,
    // and every `setblock` after that quietly does nothing: the test goes on to read a block entity
    // that was never placed, and reports the mod as having failed to build something.
    val corner = at(0, 0, 0)
    val worldX = corner.x + x
    val worldZ = corner.z + z
    val worldY = corner.y + y

    setUiLayer(watcher, UiLayer.GUI, false)
    runCommand("gamemode spectator $watcher")

    val eyeHeight = server(watcher) { name -> playerNamed(name).eyeHeight.toDouble() }
    val feet = worldY - eyeHeight

    runCommand(
        "tp %s %.2f %.2f %.2f %.2f %.2f"
            .format(Locale.ROOT, watcher, worldX, feet, worldZ, yaw, pitch)
    )

    val target = BlockPos(
        kotlin.math.floor(worldX).toInt(),
        kotlin.math.floor(feet).toInt(),
        kotlin.math.floor(worldZ).toInt(),
    )
    client(watcher, target) { where ->
        awaitUntil { clientPlayer?.blockPosition()?.closerThan(where, 3.0) == true }
    }
    client(watcher, settle) { ticks -> awaitTicks(ticks) }
}

/**
 * Straight down over a point, which is how the belt scenes are watched.
 *
 * Pitch 90 is looking at the floor; the yaw only decides which way north points in the picture.
 */
internal suspend fun Stage.lookDownOn(x: Double, y: Double, z: Double, settle: Int = 60) {
    spectateFrom(x, y, z, yaw = 0.0, pitch = 90.0, settle = settle)
}

/**
 * Watches [at] from [from], as a spectator.
 *
 * `/tp ... facing ...` rather than the trigonometry the originals wrote out by hand: the command
 * does the same arithmetic, and it is the eye that is aimed either way, so the eye-height correction
 * [spectateFrom] needs does not arise here.
 */
internal suspend fun Stage.spectateAt(from: Vec3, at: Vec3, settle: Int = 60) {
    spectateAt(watcher, from, at, settle)
}

/**
 * The same, for a client borrowed from the pool rather than the one this suite used to share.
 *
 * The client is named rather than assumed, which is what lets two of these run at once: a stage
 * borrows whichever client was free and every camera move has to say which one it means.
 */
internal suspend fun spectateAt(client: String, from: Vec3, at: Vec3, settle: Int = 60) {
    setUiLayer(client, UiLayer.GUI, false)
    runCommand("gamemode spectator $client")
    teleportFacing(client, from, at)
    awaitCamera(client, from, settle)
}

/**
 * Stands at [from] looking at [at], in creative and flying.
 *
 * The mode matters and is the reason this is not [spectateAt]: a spectator's clicks pass through the
 * world, so anything that goes on to press a button has to be standing in it. Flying because where a
 * camera is put need not be where the ground is, and a player who is falling is aiming from
 * somewhere they are about to leave.
 */
internal suspend fun Stage.standAt(from: Vec3, at: Vec3, settle: Int = 40) {
    standAt(watcher, from, at, settle)
}

/** The same, for a named client. @see spectateAt */
internal suspend fun standAt(client: String, from: Vec3, at: Vec3, settle: Int = 40) {
    setUiLayer(client, UiLayer.GUI, false)
    runCommand("gamemode creative $client")
    allowFlight(client)
    teleportFacing(client, from, at)
    awaitCamera(client, from, settle)
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
private suspend fun teleportFacing(client: String, from: Vec3, at: Vec3) {
    val eyeHeight = server(client) { name -> playerNamed(name).eyeHeight.toDouble() }

    val dx = at.x - from.x
    val dy = at.y - (from.y + eyeHeight)
    val dz = at.z - from.z
    val flat = kotlin.math.sqrt(dx * dx + dz * dz)

    val yaw = Math.toDegrees(kotlin.math.atan2(dz, dx)) - 90.0
    val pitch = -Math.toDegrees(kotlin.math.atan2(dy, flat))

    runCommand(
        "tp %s %.3f %.3f %.3f %.3f %.3f"
            .format(Locale.ROOT, client, from.x, from.y, from.z, yaw, pitch)
    )
}

private suspend fun awaitCamera(watcher: String, from: Vec3, settle: Int) {
    val target = BlockPos.containing(from)
    client(watcher, target) { where ->
        awaitUntil { clientPlayer?.blockPosition()?.closerThan(where, 3.0) == true }
    }
    client(watcher, settle) { ticks -> awaitTicks(ticks) }
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
internal suspend fun Stage.rightClickBlock(pos: BlockPos) {
    standAt(
        Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 3.5),
        Vec3.atCenterOf(pos),
        settle = 10,
    )
    client(watcher, pos) { where -> useBlock(where) }
}

/** Right-clicks whatever the crosshair is on, without moving. */
internal suspend fun Stage.rightClickAhead() {
    client(watcher) { click(MouseButton.RIGHT) }
}

/**
 * Puts one of something in the player's hand.
 *
 * The command fills the slot; selecting it is the client's own state, so that half happens there.
 */
internal suspend fun Stage.holdItem(item: String, count: Int = 1) {
    runCommand("item replace entity $watcher hotbar.0 with $item $count")
    client(watcher) {
        clientPlayer?.inventory?.setSelectedSlot(0)
        awaitTicks(1)
    }
}

/**
 * Flattens a patch of this stage and puts a stone floor under it.
 *
 * A stage arrives with one floor, laid under its own corner. These scenes were written against a
 * world where each made its own platform first, and their geometry counts from that platform rather
 * than from the ground -- a scene that builds two blocks up expects something solid one block up.
 * This lays it exactly where the original did: at `centre.y - 1`.
 *
 * A wide clearing has to be a shallow one: the box is filled by a single command, and one of more
 * than thirty-two thousand blocks is refused outright.
 */
internal suspend fun Stage.clearGround(centre: BlockPos, radius: Int, height: Int = radius + 1) {
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
}

/**
 * Flattens a patch of this stage and puts a stone floor under it.
 *
 * A stage arrives with one floor, laid under its own corner. These scenes were written against a
 * world where each of them made its own platform first, and their geometry counts from that platform
 * rather than from the ground -- so a scene that built two blocks up expects something solid one
 * block up. This lays it, exactly where the original did: at `centre.y - 1`.
 *
 * Nothing global. The original also killed every item entity in the world to clear up after the last
 * scene, which was safe when one test ran at a time and is not now: a stage two thousand blocks away
 * is somebody else's, and its items are its own. A stage arrives clear, so there is nothing to kill.
 */
/** Empties a box without laying a floor, for scenes that stand on the ground they were given. */
internal suspend fun Stage.clearBox(low: BlockPos, high: BlockPos) {
    fill(low, high, "air")
}

/** Drops an item into the world, which is how anything gets into a basin. */
internal suspend fun dropItem(x: Double, y: Double, z: Double, item: String) {
    runCommand(
        "summon minecraft:item %.2f %.2f %.2f {Item:{id:\"%s\",count:1}}"
            .format(Locale.ROOT, x, y, z, item)
    )
}

/** Puts the heads-up display back, so a failure does not leave it hidden for whatever runs next. */
internal suspend fun Stage.restoreHud() {
    setUiLayer(watcher, UiLayer.GUI, true)
}

/** The same, for a named client. @see spectateAt */
internal suspend fun Stage.restoreHud(client: String) {
    setUiLayer(client, UiLayer.GUI, true)
}

/** A picture from the client, kept under the capture directory the build points the driver at. */
internal suspend fun Stage.shot(name: String) {
    screenshot(watcher, name)
}

/** The same, for a named client. @see spectateAt */
internal suspend fun Stage.shot(client: String, name: String) {
    screenshot(client, name)
}

/**
 * The same picture, and where it was written.
 *
 * For the tests that go on to read the pixels back. The file is on this machine -- the driver writes
 * it under the capture directory the build names -- so the path is one an ordinary [java.io.File]
 * can open.
 */
internal suspend fun Stage.shotFile(name: String): String = screenshot(watcher, name)

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
