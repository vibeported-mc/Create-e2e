package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.ALEX
import dev.vibeported.mc.driver.client
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ServerScope
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.Vec3

/*
 * Create's server-side game tests, driven against a real server with a real client watching.
 *
 * These are a different animal to the screen tests. Each one is a saved structure -- a working
 * machine, built and photographed once and kept as a file -- which the game lays into the world; the
 * test then pulls a lever and waits to see what comes out of a chest at the other end. Upstream they
 * run headless, in a world nobody looks at, and what they say when they fail is a chest that did not
 * fill.
 *
 * Here they are laid down in front of a client, and the camera is put where the machine can be seen
 * working. That is the whole reason for the port: a picture of the machine at the moment it failed
 * says which half of it stopped, and no assertion on a chest ever will.
 */

/**
 * One of Create's saved machines, laid into the world and watched.
 *
 * The structure is placed by the game rather than built here, which is what makes these tests worth
 * porting at all: the machine in the file is the machine Create's own tests run, down to the block.
 */
internal class Scene(
    /** Where the structure's own corner sits in the world. */
    val origin: BlockPos,
    /** How big it turned out, which is what the camera is framed from. */
    val size: BlockPos,
) {

    /**
     * Where a square of the structure is in the world.
     *
     * The step down is Create's own and is not an accident. These tests were written when a
     * structure was laid one block above the marker naming it, so every coordinate in them counts
     * from a floor at one; the game now lays it on the marker itself. Create puts the step back in
     * its helper rather than move sixty-five tests' worth of coordinates, and so does this -- which
     * is also what lets a position here be read straight against the upstream test it came from.
     */
    fun at(x: Int, y: Int, z: Int): BlockPos = origin.offset(x, y - 1, z)

    fun at(relative: BlockPos): BlockPos = at(relative.x, relative.y, relative.z)

    /** The middle of the whole machine, which is what the camera looks at. */
    fun middle(): Vec3 = Vec3(
        origin.x + size.x / 2.0,
        origin.y + size.y / 2.0,
        origin.z + size.z / 2.0,
    )
}

/**
 * Lays a machine down and puts the camera where it can be watched.
 *
 * @param name the structure's own name, as the upstream test gives it
 * @param group the folder it lives in, which upstream is the `@GameTestGroup` on the class
 */
internal suspend fun stage(group: String, name: String): Scene {
    // The camera goes first, and this is the one thing every one of these has to get right. A
    // dedicated server keeps loaded only the chunks somebody is near, and a structure placed into a
    // chunk nobody is near is a structure that does not stay: the command reports success and the
    // ground is still empty. What that arrives as is "there is no lever at ..." -- a machine that
    // looks like it was never built, because it was not.
    //
    // It only bites the first of these to run, which is what makes it worth a comment: every test
    // after it inherits a camera already pointed here by the one before.
    spectateAt(
        Vec3(ORIGIN.x + 20.0, ORIGIN.y + 16.0, ORIGIN.z + 20.0),
        Vec3(ORIGIN.x + 8.0, ORIGIN.y + 4.0, ORIGIN.z + 8.0),
    )

    // The ground the last machine stood on, taken back -- what was built and whatever is still
    // standing on it. These share one world and one spot, and a chest left over from the test before
    // is a chest this one would read as its own output.
    val swept = clearTheStage()

    check(swept.refused.isEmpty()) {
        "The stage could not be cleared before $name was laid down: ${swept.refused} would not go"
    }
    check(swept.ghosts.isEmpty()) {
        "The stage was cleared before $name was laid down, but the client is still drawing " +
            "${swept.ghosts}"
    }

    val id = "create:gametest/$group/$name"
    val size = sizeOf(id)

    runCommand("place template $id ${ORIGIN.x} ${ORIGIN.y} ${ORIGIN.z}")

    // And then every block in it is told its neighbours have changed.
    //
    // `/place template` puts the blocks down without the updates that placing one by hand would
    // cause, so anything that decides what to do by looking around it never looks: a redstone lamp
    // saved lit stays lit over an empty chest, and a comparator beside a full depot goes on
    // reporting whatever it was reporting when the structure was saved. Upstream never meets this
    // because the game's own test framework places its structures with updates.
    nudge(ORIGIN, ORIGIN.offset(size.x, size.y, size.z))

    serverTicks(SETTLE_TICKS)

    val scene = Scene(ORIGIN, size)

    watch(scene)

    // What was built, before a lever is touched. A machine that fails on its very first assertion
    // takes no other picture, and "the lamp was already lit" says nothing without one.
    shot("${name}_placed")

    return scene
}

/**
 * Stands the camera off the machine, far enough back to hold all of it.
 *
 * Worked out from the structure's own size rather than given per test: these are seventy machines of
 * every shape from a single funnel to a two-storey factory, and a distance that frames one of them
 * has the next either filling the sky or lost in the middle of the picture.
 */
internal suspend fun watch(scene: Scene) {
    val middle = scene.middle()
    val reach = maxOf(scene.size.x, scene.size.y, scene.size.z).coerceAtLeast(4)

    // Off one corner and above, which shows three faces of a machine rather than one flat wall.
    spectateAt(
        Vec3(
            middle.x + reach * 1.1,
            middle.y + reach * 0.8,
            middle.z + reach * 1.1,
        ),
        middle,
    )
}

/**
 * The machines' ground put back to nothing: the blocks it was built of, and everything standing on
 * it.
 *
 * All of it through the level rather than through commands, and each half for its own reason.
 *
 * The blocks, because `/fill` is a command and a command that will not parse fails quietly --
 * `performPrefixedCommand` hands back a result nobody reads -- so a mistake in one looks exactly
 * like ground that was already clear. This counts what it took, which is a thing that can be
 * believed.
 *
 * The entities, because clearing blocks does not touch them and most of what these scenes leave
 * behind is not a block. A contraption is an entity, and so are the minecart carrying it, the seat
 * somebody sat in, the armour stand a backtank hung on, the zombie dropped in lava and the cow that
 * rode a lift. Replacing the ground beneath them does nothing at all: they stay where they were, and
 * the next machine is built around and through them.
 *
 * Discarded rather than killed. Killing a mob leaves its drops on the floor and killing a contraption
 * scatters the casings it was built from, which is the same mess wearing a different hat.
 */
internal suspend fun clearTheStage(): Swept {
    val swept = sweepTheGround()

    // A removal is not a thing the client is told about at once: the server marks the entity gone
    // and its tracker sends word on a later tick. Sweeping and then stopping -- which is what a
    // teardown does -- leaves the client drawing something that no longer exists, and a window
    // showing a contraption is indistinguishable from a stage that was never cleared.
    serverTicks(SWEEP_SETTLE)

    return swept.copy(ghosts = whatTheClientStillDraws())
}

/** What the client still has near the stage, which after a sweep should be nothing. */
private suspend fun whatTheClientStillDraws(): List<String> = client(ALEX, CLEAR_LOW) { low ->
    val level = minecraft.level ?: return@client Ghosts(emptyList())

    Ghosts(
        level.entitiesForRendering()
            .filter { entity ->
                entity !is net.minecraft.world.entity.player.Player &&
                    kotlin.math.abs(entity.blockPosition().x - low.x) <= STRAY_REACH &&
                    kotlin.math.abs(entity.blockPosition().z - low.z) <= STRAY_REACH
            }
            .map { entity ->
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.type).toString() + " at " + entity.blockPosition().toShortString()
            }
    )
}.values

@Serializable
internal data class Ghosts(val values: List<String>)

private const val SWEEP_SETTLE = 4

private suspend fun sweepTheGround(): Swept = server(CLEAR_LOW, CLEAR_HIGH) { low, high ->
    val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
    var blocks = 0

    // The ground first, so that anything a removal shakes loose -- a bearing letting go of what it
    // was holding -- is still there to be swept up by the pass that follows.
    for (pos in net.minecraft.core.BlockPos.betweenClosed(low, high)) {
        if (serverLevel.getBlockState(pos).isAir) continue

        // Told to the clients but not to the neighbours: this is a whole region going at once, and
        // a cascade of updates through blocks that are themselves about to go is work for nothing.
        serverLevel.setBlock(pos.immutable(), air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
        blocks++
    }

    // And then whatever is standing about. The box reaches well past the ground the structures stand
    // on, because some of these do not merely drift: a roller rides a minecart along its own track
    // and is a good way from where it started by the time its test has finished.
    val box = net.minecraft.world.phys.AABB(
        (low.x - STRAY_REACH).toDouble(),
        (low.y - STRAY_HEIGHT).toDouble(),
        (low.z - STRAY_REACH).toDouble(),
        (high.x + STRAY_REACH).toDouble(),
        (high.y + STRAY_HEIGHT).toDouble(),
        (high.z + STRAY_REACH).toDouble(),
    )

    val standing = serverLevel.getEntities(null as net.minecraft.world.entity.Entity?, box) {
        it !is net.minecraft.world.entity.player.Player
    }

    standing.forEach { it.discard() }

    // What did not go. An entity that refuses to be discarded is worth naming rather than assuming
    // away: the whole point of doing this through the level was to be able to tell an empty stage
    // from a sweep that quietly did nothing.
    val refused = standing.filter { !it.isRemoved }.map {
        "${net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(it.type)} " +
            "at ${it.blockPosition().toShortString()}"
    }

    Swept(standing.size, blocks, refused)
}

/** What a sweep took, so that one which took nothing can be told from one that was never asked. */
@Serializable
internal data class Swept(
    val entities: Int,
    val blocks: Int,
    val refused: List<String> = emptyList(),
    val ghosts: List<String> = emptyList(),
)

/**
 * How far past the cleared ground to look for whatever wandered off it.
 *
 * Only the entities are looked for this far. The blocks are cleared over the ground the structures
 * are actually laid on, because a box this wide is two million of them and none of them are built.
 *
 * Nowhere near anything else either way: the nearest scene of any other kind is three hundred blocks
 * up the z axis, so this reaches only over ground that belongs to these machines.
 */
private const val STRAY_REACH = 64

private const val STRAY_HEIGHT = 32

/** How big a saved structure is, asked of the server that has the file. */
private suspend fun sizeOf(id: String): BlockPos = server(id) { named ->
    val template = serverLevel.server.structureManager.get(Identifier.parse(named))

    if (template.isEmpty) throw AssertionError("There is no structure called $named")

    val size = template.get().size

    BlockPos(size.x, size.y, size.z)
}

/**
 * Waits for something to come true of the machine, and says what it was doing when it did not.
 *
 * The upstream `succeedWhen` polls an assertion until it stops throwing, and a test that never
 * succeeds is reported as a timeout and nothing else. This takes a picture at the end either way,
 * which is the point of running these where somebody can see them.
 */
internal suspend fun Scene.succeedWhen(
    picture: String,
    within: Int,
    describe: suspend () -> String = { "" },
    condition: suspend () -> Boolean,
) {
    var waited = 0

    while (waited < within && !condition()) {
        serverTicks(POLL_TICKS)
        waited += POLL_TICKS
    }

    shot(picture)

    if (!condition()) {
        val reading = describe()

        throw AssertionError(
            "The machine did not finish within ${within / 20} seconds. " +
                "The picture `$picture` is what it looked like when time ran out." +
                if (reading.isEmpty()) "" else " It was reading $reading."
        )
    }
}

/**
 * Runs something once a number of seconds have passed since the machine was laid down.
 *
 * The upstream helper schedules these against the test's own clock, so the seconds named are counted
 * from the start rather than from one another. This keeps that reading by waiting out the difference.
 */
internal class Clock {

    private var elapsed = 0

    suspend fun atSecond(second: Int, action: suspend () -> Unit) {
        val target = second * 20

        if (target > elapsed) {
            serverTicks(target - elapsed)
            elapsed = target
        }

        action()
    }
}

/* --- what a machine is asked and told, all of it on the server --- */

/** Flips a lever, which is how every one of these machines is started. */
internal suspend fun pullLever(pos: BlockPos) {
    server(pos) { where ->
        val state = serverLevel.getBlockState(where)

        if (!state.`is`(Blocks.LEVER)) {
            throw AssertionError("There is no lever at $where but ${state.block.descriptionId}")
        }

        (state.block as LeverBlock).pull(state, serverLevel, where, null)
        true
    }
}

/** Turns a lever on if it is off, which is not the same as flipping it. */
internal suspend fun powerLever(pos: BlockPos) {
    if (!leverIsOn(pos)) pullLever(pos)
}

internal suspend fun unpowerLever(pos: BlockPos) {
    if (leverIsOn(pos)) pullLever(pos)
}

private suspend fun leverIsOn(pos: BlockPos): Boolean = server(pos) { where ->
    serverLevel.getBlockState(where).getValue(LeverBlock.POWERED)
}

/**
 * Whether a container holds at least this much of an item.
 *
 * Asked the way Create's own helper asks it -- a simulated extraction of the amount wanted -- so that
 * a chest holding a stack spread over several slots answers the same as one holding it in a single
 * slot.
 */
internal suspend fun containerHolds(pos: BlockPos, item: String, count: Int = 1): Boolean =
    server(pos, Wanted(item, count)) { where, wanted -> holds(where, wanted) }

internal suspend fun containerIsEmpty(pos: BlockPos): Boolean = server(pos) { where ->
    val handler = itemsAt(where) ?: return@server true

    (0 until handler.size()).all {
        net.neoforged.neoforge.transfer.item.ItemUtil.getStack(handler, it).isEmpty
    }
}

/** Whether any of these items is in the container, which is how a pool of outcomes is checked. */
internal suspend fun containerHoldsAnyOf(pos: BlockPos, items: List<String>): Boolean =
    server(pos, Items(items)) { where, wanted ->
        wanted.values.any { holds(where, Wanted(it, 1)) }
    }

/** What a container actually has in it, for a failure that has to say what was there instead. */
internal suspend fun contentsOf(pos: BlockPos): String = server(pos) { where ->
    val handler = itemsAt(where) ?: return@server "there is nothing at $where that holds items"

    val found = (0 until handler.size())
        .map { net.neoforged.neoforge.transfer.item.ItemUtil.getStack(handler, it) }
        .filter { !it.isEmpty }
        .joinToString { "${it.count} x ${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item)}" }

    found.ifEmpty { "nothing" }
}

/** How many items a container is holding altogether, however they are spread across its slots. */
internal suspend fun totalItemsIn(pos: BlockPos): Int = server(pos) { where ->
    val handler = itemsAt(where) ?: return@server 0

    (0 until handler.size()).sumOf {
        net.neoforged.neoforge.transfer.item.ItemUtil.getStack(handler, it).count
    }
}

/**
 * Everything a container holds, as names against amounts.
 *
 * For the tests that move a chest's worth of oddments from one end of a machine to the other and then
 * ask whether all of it arrived. What is compared is the tally, not the stacks: the same fourteen
 * items land in a different number of slots depending on what carried them.
 */
internal suspend fun tallyOf(pos: BlockPos): Tally = server(pos) { where ->
    val handler = itemsAt(where) ?: return@server Tally(emptyList(), emptyList())

    val counted = LinkedHashMap<String, Int>()

    for (slot in 0 until handler.size()) {
        val stack = net.neoforged.neoforge.transfer.item.ItemUtil.getStack(handler, slot)
        if (stack.isEmpty) continue

        val name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
        counted[name] = (counted[name] ?: 0) + stack.count
    }

    Tally(counted.keys.toList(), counted.values.toList())
}

/**
 * A container's contents, flattened into two lists.
 *
 * A map cannot cross the wire -- a generic type erases to its class -- so the names and the amounts
 * travel side by side and are put back together here.
 */
@Serializable
internal data class Tally(val items: List<String>, val counts: List<Int>) {

    fun countOf(item: String): Int {
        val at = items.indexOf(item)

        return if (at < 0) 0 else counts[at]
    }

    /** Whether this holds at least everything [wanted] does. */
    fun holdsAllOf(wanted: Tally): Boolean =
        wanted.items.indices.all { countOf(wanted.items[it]) >= wanted.counts[it] }
}

/** What block is at a spot, by name, which is how a machine's output is checked when it is a block. */
internal suspend fun blockAt(pos: BlockPos): String = server(pos) { where ->
    net.minecraft.core.registries.BuiltInRegistries.BLOCK
        .getKey(serverLevel.getBlockState(where).block).toString()
}

/**
 * Whether a block's own property reads a certain way -- a lamp being lit, a burner being kindled.
 *
 * Asked by name rather than by handing over the property itself, which is a registry-ish object that
 * does not travel. The name is the one the block state prints, so `lit` and `blaze_level` read here
 * exactly as they do in the game.
 */
internal suspend fun blockProperty(pos: BlockPos, property: String): String? =
    server(pos, property) { where, named ->
        val state = serverLevel.getBlockState(where)
        val found = state.properties.firstOrNull { it.name == named } ?: return@server null

        // The name the property gives its value, which is what a block state prints and what these
        // tests are written against. An enum's own `toString` shouts it -- `KINDLED` where the game
        // says `kindled` -- and a comparison against that quietly never matches.
        @Suppress("UNCHECKED_CAST")
        nameOfValue(found as net.minecraft.world.level.block.state.properties.Property<Nothing>, state)
    }

/** Puts a block down, for the tests that take a machine apart while it runs. */
internal suspend fun setBlockAt(pos: BlockPos, block: String) {
    runCommand("setblock ${pos.x} ${pos.y} ${pos.z} $block")
}

/** Drops items onto a spot, which is how the vault tests fill one up. */
internal suspend fun spawnItems(pos: BlockPos, item: String, count: Int) {
    server(pos, Wanted(item, count)) { where, wanted ->
        val stackType = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getValue(Identifier.parse(wanted.item))

        var left = wanted.count

        while (left > 0) {
            val taken = minOf(left, stackType.defaultMaxStackSize)
            val stack = net.minecraft.world.item.ItemStack(stackType, taken)

            // Standing still, which is the whole of it. The ordinary constructor gives an item a
            // little random throw, and a few hundred of them thrown at one point scatter over the
            // ground rather than falling into the funnel they were dropped above.
            serverLevel.addFreshEntity(
                net.minecraft.world.entity.item.ItemEntity(
                    serverLevel,
                    where.x + 0.5,
                    where.y + 0.5,
                    where.z + 0.5,
                    stack,
                    0.0,
                    0.0,
                    0.0,
                )
            )
            left -= taken
        }

        true
    }
}

/** What a nixie tube is reading, which several machines use as their display. */
internal suspend fun nixieText(pos: BlockPos): String = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity) {
        throw AssertionError("There is no nixie tube at $where but $be")
    }

    be.fullText.string
}

/** How much redstone a nixie tube is being given, which is what a comparator's output is read from. */
internal suspend fun nixiePower(pos: BlockPos): Int = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity) {
        throw AssertionError("There is no nixie tube at $where but $be")
    }

    be.redstoneStrength
}

/** What a depot is holding, if anything: the item's name and how many of it. */
internal suspend fun onDepot(pos: BlockPos): Held = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.logistics.depot.DepotBlockEntity) {
        throw AssertionError("There is no depot at $where but $be")
    }

    val stack = be.heldItem

    if (stack.isEmpty) {
        Held(null, 0)
    } else {
        Held(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count)
    }
}

@Serializable
internal data class Held(val item: String?, val count: Int)

/**
 * Sets which way a brass tunnel shares what it is given.
 *
 * Done after the machine has been started rather than before, and that is not a preference: a tunnel
 * works out its neighbours when the belt beside it comes to life, and a tunnel that reconnects forgets
 * the mode it was set to.
 */
internal suspend fun setTunnelMode(pos: BlockPos, mode: String) {
    server(pos, mode) { where, named ->
        val be = serverLevel.getBlockEntity(where)

        if (be !is com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity) {
            throw AssertionError("There is no brass tunnel at $where but $be")
        }

        val wanted = com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity.SelectionMode
            .valueOf(named)

        val behaviour = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
            be,
            com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour.TYPE,
        ) ?: throw AssertionError("The tunnel at $where has no mode to set")

        behaviour.setValue(wanted.ordinal)
        true
    }
}

/** Puts what is in a container into it, for the tests that fill one to a threshold. */
internal suspend fun putInContainer(pos: BlockPos, item: String, count: Int) {
    runCommand("item replace block ${pos.x} ${pos.y} ${pos.z} container.0 with $item $count")
}

/** What a flap display is spelling out, a line at a time. */
internal suspend fun flapDisplayLines(pos: BlockPos): List<String> = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.trains.display.FlapDisplayBlockEntity) {
        throw AssertionError("There is no flap display at $where but $be")
    }

    val controller = be.controller ?: be

    Lines(
        controller.lines.map { line ->
            line.sections.joinToString("") { it.text.string }.lowercase().trim()
        }
    )
}.values

/**
 * Lines of a display, wrapped.
 *
 * A `List<String>` cannot cross on its own -- a generic type erases to its class -- so it travels
 * inside something that is not generic.
 */
@Serializable
internal data class Lines(val values: List<String>)

/** Puts a stack into one particular slot of a container. */
internal suspend fun fillSlot(pos: BlockPos, slot: Int, item: String, count: Int) {
    runCommand("item replace block ${pos.x} ${pos.y} ${pos.z} container.$slot with $item $count")
}

/** How many items are lying on the ground near a machine, for a load that never got where it was sent. */
internal suspend fun looseItemsAround(middle: Vec3, reach: Double): Int =
    server(BlockPos.containing(middle), reach) { where, howFar ->
        serverLevel.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity::class.java,
            net.minecraft.world.phys.AABB(where).inflate(howFar),
        ).sumOf { it.item.count }
    }

/** Tells every block in a box that its neighbours have changed, without disturbing what they hold. */
private suspend fun nudge(low: BlockPos, high: BlockPos) {
    server(low, high) { from, to ->
        var nudged = 0

        for (pos in BlockPos.betweenClosed(from, to)) {
            val state = serverLevel.getBlockState(pos)
            if (state.isAir) continue

            // Neighbours only. Setting the block again would answer the same question and take the
            // block entity's contents with it, which for a depot holding a stack is the very thing
            // being asked about.
            serverLevel.updateNeighborsAt(pos.immutable(), state.block)
            nudged++
        }

        nudged
    }
}

/** What every depot in a box is holding, for when a comparator disagrees with one. */
internal suspend fun depotsIn(low: BlockPos, high: BlockPos): String = server(low, high) { from, to ->
    val found = mutableListOf<String>()

    for (pos in BlockPos.betweenClosed(from, to)) {
        val be = serverLevel.getBlockEntity(pos)
        if (be !is com.simibubi.create.content.logistics.depot.DepotBlockEntity) continue

        val stack = be.heldItem
        found.add(
            "${pos.toShortString()} holds " +
                if (stack.isEmpty) "nothing"
                else "${stack.count} x ${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)}"
        )
    }

    found.joinToString().ifEmpty { "no depots at all" }
}

/** How many of an item are lying loose within reach of a spot. */
internal suspend fun looseItemsAt(pos: BlockPos, item: String, reach: Double = 2.0): Int =
    server(pos, Wanted(item, reach.toInt())) { where, wanted ->
        val kind = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getValue(Identifier.parse(wanted.item))

        serverLevel.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity::class.java,
            net.minecraft.world.phys.AABB(where).inflate(wanted.count.toDouble()),
        ).filter { it.item.`is`(kind) }.sumOf { it.item.count }
    }

/** Whether an entity of a kind is standing within reach of a spot. */
internal suspend fun entityNear(pos: BlockPos, type: String, reach: Double = 2.0): Boolean =
    server(pos, Wanted(type, reach.toInt())) { where, wanted ->
        val kind = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getValue(Identifier.parse(wanted.item))

        serverLevel.getEntities(
            kind,
            net.minecraft.world.phys.AABB(where).inflate(wanted.count.toDouble()),
        ) { it.isAlive }.isNotEmpty()
    }

/** Shears the first sheep standing at a spot, which is what a test of shearing needs done to it. */
internal suspend fun shearTheSheep(pos: BlockPos) {
    server(pos) { where ->
        val sheep = serverLevel.getEntitiesOfClass(
            net.minecraft.world.entity.animal.sheep.Sheep::class.java,
            net.minecraft.world.phys.AABB(where).inflate(2.0),
        ).firstOrNull() ?: throw AssertionError("There is no sheep at $where")

        sheep.shear(
            serverLevel,
            net.minecraft.sounds.SoundSource.NEUTRAL,
            net.minecraft.world.item.ItemStack.EMPTY,
        )
        true
    }
}

/**
 * Puts a zombie where the armour stand is standing, wearing what the stand is wearing.
 *
 * Which is a roundabout way of asking whether a backtank keeps its wearer alive, and it is how the
 * upstream test asks: the gear is laid out in the structure on a stand, and the thing that has to
 * survive is dressed in it before it goes into the lava.
 */
internal suspend fun dressAZombieLike(stand: BlockPos, spawnAt: BlockPos) {
    server(stand, spawnAt) { where, spawn ->
        val armorStand = serverLevel.getEntitiesOfClass(
            net.minecraft.world.entity.decoration.ArmorStand::class.java,
            net.minecraft.world.phys.AABB(where).inflate(2.0),
        ).firstOrNull() ?: throw AssertionError("There is no armour stand at $where")

        val zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
            serverLevel,
            net.minecraft.world.entity.EntitySpawnReason.COMMAND,
        ) ?: throw AssertionError("A zombie could not be made")

        zombie.snapTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0f, 0f)

        for (slot in net.minecraft.world.entity.EquipmentSlot.entries) {
            zombie.setItemSlot(slot, armorStand.getItemBySlot(slot).copy())
        }

        serverLevel.addFreshEntity(zombie)
        true
    }
}

/** What a threshold switch says the stock level is, which for a pulley is how far down the rope went. */
internal suspend fun stockLevelAt(pos: BlockPos): Int = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity) {
        throw AssertionError("There is no threshold switch at $where but $be")
    }

    be.stockLevel
}

/** How many entities of a kind are inside a box, corners included. */
internal suspend fun entitiesBetween(low: BlockPos, high: BlockPos, type: String): Int =
    server(low, high, type) { from, to, named ->
        val kind = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getValue(Identifier.parse(named))
        val box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(from, to)

        serverLevel.getEntities(kind) { box.isInside(it.blockPosition()) }.size
    }

/** What a tank is holding: the fluid's name and how much of it. */
internal suspend fun tankHolds(pos: BlockPos): Fluid = server(pos) { where ->
    val handler = serverLevel.getCapability(
        net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK,
        where,
        null,
    ) ?: return@server Fluid(null, 0)

    net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { look ->
        val taken = net.neoforged.neoforge.transfer.ResourceHandlerUtil.extractFirst(
            handler,
            { true },
            Int.MAX_VALUE,
            look,
        ) ?: return@use Fluid(null, 0)

        Fluid(
            net.minecraft.core.registries.BuiltInRegistries.FLUID
                .getKey(taken.resource().fluid).toString(),
            taken.amount(),
        )
    }
}

/** A tank's contents, named and measured, so they can be compared across the wire. */
@Serializable
internal data class Fluid(val name: String?, val amount: Int) {

    val isEmpty: Boolean get() = name == null || amount == 0
}

/** Presses a button, which some machines are started with rather than a lever. */
internal suspend fun pressButton(pos: BlockPos) {
    server(pos) { where ->
        val state = serverLevel.getBlockState(where)
        val block = state.block

        if (block !is net.minecraft.world.level.block.ButtonBlock) {
            throw AssertionError("There is no button at $where but ${block.descriptionId}")
        }

        block.press(state, serverLevel, where, null)
        true
    }
}

/** Puts an animal somewhere, which is how a lift gets a passenger. */
internal suspend fun spawnEntity(type: String, pos: BlockPos) {
    runCommand("summon $type ${pos.x + 0.5} ${pos.y} ${pos.z + 0.5}")
}

/** Whether a bearing has a contraption hanging off it, which is how assembly is checked. */
internal suspend fun bearingHasContraption(pos: BlockPos): Boolean = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    be is com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity &&
        be.movedContraption != null
}

/**
 * Turns off every actor of a kind on the contraption a bearing is carrying.
 *
 * Which is what a player does at a set of contraption controls: the controls carry a filter naming
 * what they switch, and clicking them toggles those actors. Done here through the same interaction
 * rather than by reaching into the contraption, so what is being tested is the controls.
 */
internal suspend fun toggleActorsOfType(bearing: BlockPos, item: String): Boolean =
    server(bearing, item) { where, named ->
        val be = serverLevel.getBlockEntity(where)

        if (be !is com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) {
            throw AssertionError("There is no bearing at $where but $be")
        }

        val entity = be.movedContraption ?: throw AssertionError("The bearing at $where carries nothing")
        val contraption = entity.contraption
        val wanted = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getValue(Identifier.parse(named))

        var toggled = false

        for ((localPos, behaviour) in contraption.interactors) {
            if (toggled) break
            if (behaviour !is com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovingInteraction) continue

            val actor = contraption.getActorAt(localPos) ?: continue
            val filter = com.simibubi.create.content.contraptions.actors.contraptionControls
                .ContraptionControlsMovement.getFilter(actor.right) ?: continue

            if (!filter.`is`(wanted)) continue

            behaviour.handlePlayerInteraction(
                serverLevel.players().first(),
                net.minecraft.world.InteractionHand.MAIN_HAND,
                localPos,
                entity,
            )
            toggled = true
        }

        toggled
    }

/** Clicks an elevator pulley, which is what sends a lift on its way. */
internal suspend fun clickElevatorPulley(pos: BlockPos) {
    server(pos) { where ->
        val be = serverLevel.getBlockEntity(where)

        if (be !is com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity) {
            throw AssertionError("There is no elevator pulley at $where but $be")
        }

        be.clicked()
        true
    }
}

/** Whether a second pulley has been adopted by the first, which is what makes a lift wide. */
internal suspend fun pulleyHasParent(pos: BlockPos): Boolean = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    be is com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity &&
        be.mirrorParent != null
}

/** How much fluid a handful of tanks are holding between them. */
internal suspend fun fluidInTanks(tanks: List<BlockPos>): Int {
    var total = 0

    for (tank in tanks) total += tankHolds(tank).amount

    return total
}

/**
 * Turns a block round to face the other way.
 *
 * How a pump is reversed, which is what several of the pipe tests do instead of pulling a lever.
 */
internal suspend fun flipBlock(pos: BlockPos) {
    server(pos) { where ->
        val state = serverLevel.getBlockState(where)
        val facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING

        if (!state.hasProperty(facing)) {
            throw AssertionError("The block at $where does not face anywhere")
        }

        serverLevel.setBlock(where, state.setValue(facing, state.getValue(facing).opposite), 3)
        true
    }
}

/** What a speedometer is reading, in revolutions a minute. */
internal suspend fun speedometerReads(pos: BlockPos): Float = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity) {
        throw AssertionError("There is no speedometer at $where but $be")
    }

    kotlin.math.abs(be.speed)
}

/** What a stressometer says the network can carry. */
internal suspend fun stressometerReads(pos: BlockPos): Float = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity) {
        throw AssertionError("There is no stressometer at $where but $be")
    }

    be.networkCapacity
}

/** Turns a valve handle, which is how these pipe networks are opened and shut. */
internal suspend fun turnValveHandle(pos: BlockPos) {
    server(pos) { where ->
        val be = serverLevel.getBlockEntity(where)

        if (be !is com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity) {
            throw AssertionError("There is no valve handle at $where but $be")
        }

        be.activate(false)
        true
    }
}

/** Whether a fluid valve has been opened. */
internal suspend fun valveIsOpen(pos: BlockPos): Boolean = server(pos) { where ->
    serverLevel.getBlockState(where)
        .getValue(com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock.ENABLED)
}

/** What a water wheel has been clad in, which is whatever plank was last put through it. */
internal suspend fun wheelMaterial(pos: BlockPos): String = server(pos) { where ->
    val be = serverLevel.getBlockEntity(where)

    if (be !is com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity) {
        throw AssertionError("There is no water wheel at $where but $be")
    }

    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.material.block).toString()
}

/** Puts a zombie somewhere and tells it to stay put, which is what being rained on requires. */
internal suspend fun standAZombieAt(pos: BlockPos) {
    // An open pipe reaches exactly one column of blocks, and one that wanders a step to the side is
    // one the pipe cannot reach -- which reads as the pipe having failed rather than the zombie.
    runCommand("summon minecraft:zombie ${pos.x + 0.5} ${pos.y} ${pos.z + 0.5} {NoAI:1b,PersistenceRequired:1b}")
}

/** Whether the zombie standing there is alight. */
internal suspend fun zombieIsOnFire(pos: BlockPos): Boolean = server(pos) { where ->
    theZombieAt(where)?.isOnFire ?: false
}

/** Whether the zombie standing there has anything working on it. */
internal suspend fun zombieHasEffects(pos: BlockPos): Boolean = server(pos) { where ->
    theZombieAt(where)?.activeEffects?.isNotEmpty() ?: false
}

private fun ServerScope.theZombieAt(pos: BlockPos): net.minecraft.world.entity.monster.zombie.Zombie? {
    // The nearest, not the first found. These stand two apart and a box wide enough to be sure of
    // catching one is wide enough to catch both -- and then which of them answers is whichever the
    // level happens to list first, so a test about two different fates reads one of them twice.
    val middle = net.minecraft.world.phys.Vec3.atCenterOf(pos)

    return serverLevel.getEntitiesOfClass(
        net.minecraft.world.entity.monster.zombie.Zombie::class.java,
        net.minecraft.world.phys.AABB(pos).inflate(1.5),
    ).minByOrNull { it.position().distanceToSqr(middle) }
}

/** Empties every slot of a container, which is how a deployer is given the next plank to try. */
internal suspend fun emptyContainer(pos: BlockPos) {
    server(pos) { where ->
        val handler = itemsAt(where) ?: return@server false

        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { move ->
            for (slot in 0 until handler.size()) {
                handler.extract(slot, handler.getResource(slot), Int.MAX_VALUE, move)
            }
            move.commit()
        }

        true
    }
}

/** Every kind of plank the game knows, which is what the wheel materials test works through. */
internal suspend fun everyPlank(): Lines = server(ALEX) {
    Lines(
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getTagOrEmpty(net.minecraft.tags.BlockTags.PLANKS)
            .map { net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it.value()).toString() }
    )
}

@Serializable
internal data class Wanted(val item: String, val count: Int)

@Serializable
internal data class Items(val values: List<String>)

/**
 * How long a machine is left alone after it is laid down.
 *
 * Create's block entities do most of their thinking on a lazy tick, which comes round every tenth,
 * and a good deal of what these tests read -- what a comparator says about a depot, whether a
 * threshold switch has looked in the chest yet -- is not decided until a few of those have gone by.
 */
private const val SETTLE_TICKS = 40

/** How often a machine is looked in on while it works. */
private const val POLL_TICKS = 10

/** Where every one of these machines is laid down. */
private val ORIGIN = BlockPos(48, -59, Zones.GAMETEST)

/**
 * The box taken back before each one.
 *
 * Generous in every direction, because these structures run from one block to sixteen and a machine
 * that overhangs the last one's footprint would otherwise be built into its leftovers. Kept under
 * the thirty-two thousand blocks a single fill will accept.
 */
private val CLEAR_LOW = BlockPos(ORIGIN.x - 4, ORIGIN.y - 2, ORIGIN.z - 4)

private val CLEAR_HIGH = BlockPos(ORIGIN.x + 27, ORIGIN.y + 21, ORIGIN.z + 27)

/* --- the halves that run on the server, where the machines are --- */

private fun ServerScope.itemsAt(pos: BlockPos): net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.item.ItemResource>? =
    serverLevel.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, pos, null)

private fun ServerScope.holds(pos: BlockPos, wanted: Wanted): Boolean {
    val handler = itemsAt(pos) ?: return false
    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM
        .getValue(Identifier.parse(wanted.item))

    val extracted = com.simibubi.create.foundation.item.ItemHelper.extract(
        handler,
        { stack -> stack.`is`(item) },
        wanted.count,
        true,
    )

    return !extracted.isEmpty
}

/**
 * The name a block state property gives one of its values.
 *
 * A `Property<T>` names values of its own `T`, and the state it came from cannot promise the
 * compiler that they are the same `T`. They are -- it is the property's own state -- so this is
 * where the cast lives, once, rather than at every call.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> nameOfValue(
    property: net.minecraft.world.level.block.state.properties.Property<T>,
    state: net.minecraft.world.level.block.state.BlockState,
): String where T : Comparable<T> = property.getName(state.getValue(property))
