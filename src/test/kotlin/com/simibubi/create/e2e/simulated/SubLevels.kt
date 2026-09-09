package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos

/*
 * The sub-level vocabulary: assembling one, finding where its blocks went, and reading what the
 * physics is doing to it.
 *
 * Everything here addresses a sub-level by its **UUID**, and that is the whole point of the file.
 * `SubLevelContainer.getAllSubLevels().last()` is the obvious way to get hold of the one a test just
 * made, and it is wrong: every stage in a run shares one server and one physics pipeline, so `last()`
 * means "whichever test assembled most recently". It is correct only while `clientPool` is 1, and it
 * fails intermittently and unreadably the moment it is not. `assembleArea` and friends diff the UUID
 * set across the call and hand back the one that appeared, so a test holds a handle to its own body
 * and nobody else's.
 *
 * UUIDs cross the wire as `String`, because the RPC plugin encodes primitives, enums, `BlockPos` and
 * `@Serializable` classes, and a `java.util.UUID` is none of those.
 */

/** Where a sub-level's blocks really are: its plot's centre, out near x/z = 2e7. */
@Serializable
data class Origin(val x: Int, val y: Int, val z: Int)

/** A sub-level's pose, flattened for the wire. */
@Serializable
data class Pose(
    val x: Double,
    val y: Double,
    val z: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val qw: Double,
)

/** A sub-level's velocity, linear and angular, flattened for the wire. */
@Serializable
data class Velocity(
    val lx: Double,
    val ly: Double,
    val lz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
) {
    val linearSpeed: Double get() = Math.sqrt(lx * lx + ly * ly + lz * lz)
    val angularSpeed: Double get() = Math.sqrt(ax * ax + ay * ay + az * az)
}

/** What is inside a sub-level, block id by block id. */
@Serializable
data class Census(val ids: List<String>, val counts: List<Int>) {
    val total: Int get() = counts.sum()

    fun countOf(id: String): Int {
        val at = ids.indexOf(id)
        return if (at < 0) 0 else counts[at]
    }

    /** The tally as one line, for a failure message. */
    fun describe(): String =
        if (ids.isEmpty()) "nothing" else ids.indices.joinToString(", ") { ids[it] + " x" + counts[it] }
}

@Serializable
internal data class Uuids(val values: List<String>)

/** Sub-levels found near a point, nearest first, with how far away each one is. */
@Serializable
internal data class Nearby(val values: List<String>, val distances: List<Double>) {
    fun nearest(): String = values.first()

    /** Every one of them and how far off it was, for a failure message. */
    fun describe(): String =
        values.indices.joinToString(", ") { values[it] + " at " + "%.1f".format(distances[it]) }
}

/**
 * Assembles every block in the box into one sub-level, and says which one it made.
 *
 * `/sable assemble area` takes the whole box and needs neither an assembler block nor glue, which
 * makes it the cheapest way to turn a rig a test has just built into a physics body. The box is
 * inclusive at both ends.
 */
internal suspend fun Stage.assembleArea(from: BlockPos, to: BlockPos): String {
    val centre = BlockPos((from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2)

    return theBodyAt(centre) {
        runCommand("sable assemble area ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z}")
    }
}

/**
 * Assembles whatever is connected to [seed], and says which sub-level it made.
 *
 * Use this over [assembleArea] only when the connectivity is the thing under test -- it walks the
 * structure, so what it picks up is a claim in itself rather than a box the test drew.
 */
internal suspend fun Stage.assembleConnected(seed: BlockPos, capacity: Int = 2048): String =
    theBodyAt(seed) {
        runCommand("sable assemble connected ${seed.x} ${seed.y} ${seed.z} $capacity")
    }

/**
 * Pulls a physics assembler's lever, and says which sub-level it made.
 *
 * Straight to `assembleOrDisassemble`, rather than standing a player in front of it and holding right
 * click. The lever is a way of reaching this method and not a thing under test here.
 */
internal suspend fun Stage.assembleWith(assembler: BlockPos): String =
    theBodyAt(assembler) {
        server(assembler) { at ->
            val be = serverLevel.getBlockEntity(at)

            if (be !is dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity) {
                throw AssertionError("There is no physics assembler at $at but $be")
            }

            be.assembleOrDisassemble()
        }
    }

/** Every sub-level the server currently holds, by UUID. */
internal suspend fun Stage.subLevelIds(): List<String> = server {
    val container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel)
    Uuids(container?.getAllSubLevels()?.map { it.uniqueId.toString() } ?: emptyList())
}.values

/** How many sub-levels exist right now, across every test sharing this server. */
internal suspend fun Stage.subLevelCount(): Int = subLevelIds().size

/**
 * Runs [make] and returns the UUID of the sub-level now standing at [centre].
 *
 * By position rather than by diffing the set of sub-levels before and after. The diff looked like the
 * obvious way to do this and is not reliable: a sub-level's plot is an ordinary region that loads and
 * unloads, and `getAllSubLevels` reports what is loaded, so a body from an earlier test drops out of
 * the list and comes back into it as its plot cycles. A diff taken across that reads the reappearance
 * as a brand new body -- which showed up as tests failing with two bodies holding identical rigs, and
 * only when another test had run first.
 *
 * Where a body is does not have that problem. `SubLevelAssemblyHelper.assembleBlocks` sets the new
 * body's pose to the world position it was assembled from, so the one that belongs to this call is
 * the one standing where the rig was.
 */
internal suspend fun Stage.theBodyAt(centre: BlockPos, make: suspend () -> Unit): String {
    make()
    serverTicks(SETTLE_AFTER_ASSEMBLY)

    val here = subLevelsNear(centre, NEARBY)

    if (here.values.isEmpty()) {
        throw AssertionError(
            "No sub-level was assembled at $centre. The assembly refused -- usually because the " +
                "blocks named are not all there, or because they are already part of a body",
        )
    }

    // The nearest, rather than insisting there is only one.
    //
    // A stage's patch of world is reused once the test that had it finishes, so a body another test
    // failed to clean up can still be standing in it. Those are metres away at worst and the next
    // stage along is two thousand blocks away, so "the closest body to where this rig was just
    // assembled" picks this test's own with an enormous margin.
    return here.nearest()
}

/** Every sub-level whose body is standing within [within] blocks of [centre], nearest first. */
internal suspend fun Stage.subLevelsNear(centre: BlockPos, within: Double): Nearby =
    server(centre, within) { at, reach ->
        val container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel)
            ?: return@server Nearby(emptyList(), emptyList())

        val found = container.getAllSubLevels()
            .map { it to it.logicalPose().position().distance(at.x + 0.5, at.y + 0.5, at.z + 0.5) }
            .filter { it.second <= reach }
            .sortedBy { it.second }

        Nearby(found.map { it.first.uniqueId.toString() }, found.map { it.second })
    }

/** Long enough for the new body to be registered and posed. */
private const val SETTLE_AFTER_ASSEMBLY = 5

/**
 * How far from where a rig was built its body may be and still be recognised as the one.
 *
 * A body is posed at its assembly anchor and then settles under gravity, so this has to allow for a
 * short drop while staying far inside the 2048 blocks between one test's plot and the next.
 */
private const val NEARBY = 24.0

/**
 * Where the sub-level [id] keeps its blocks.
 *
 * A sub-level's blocks are real blocks in the level, parked in a plot around x/z = 2e7 and addressed
 * locally from its centre. Every "read the blocks back" assertion needs this offset, and reading it
 * from the plot rather than remembering where the rig was built is what keeps working after the body
 * has moved.
 */
internal suspend fun Stage.plotOriginOf(id: String): Origin = server(id) { uuid ->
    val centre = subLevelNamed(serverLevel, uuid).plot.centerBlock
    Origin(centre.x, centre.y, centre.z)
}

/** Where the sub-level [id] is, and how it is turned. */
internal suspend fun Stage.poseOf(id: String): Pose = server(id) { uuid ->
    val pose = subLevelNamed(serverLevel, uuid).logicalPose()
    val at = pose.position()
    val turn = pose.orientation()

    Pose(at.x(), at.y(), at.z(), turn.x(), turn.y(), turn.z(), turn.w())
}

/**
 * What the physics is doing to the sub-level [id].
 *
 * Read off the rigid body rather than inferred from two positions a few ticks apart. A velocity is
 * what the solver actually holds, and sampling positions turns "it is being pushed" into "it happened
 * to be somewhere else", which is a much weaker claim and a flakier one.
 */
internal suspend fun Stage.velocityOf(id: String): Velocity = server(id) { uuid ->
    val handle = handleOf(serverLevel, uuid)
    val linear = handle.linearVelocity
    val angular = handle.angularVelocity

    Velocity(
        linear.x(), linear.y(), linear.z(),
        angular.x(), angular.y(), angular.z(),
    )
}

/**
 * Shoves the sub-level [id], linearly.
 *
 * Through the rigid body rather than through `/sable physics impulse`, whose selector picks a
 * sub-level by proximity or by "the last one" -- the very ambiguity this file exists to avoid.
 */
internal suspend fun Stage.linearImpulse(id: String, x: Double, y: Double, z: Double) {
    server(id, Push(x, y, z)) { uuid, push ->
        handleOf(serverLevel, uuid).applyLinearImpulse(org.joml.Vector3d(push.x, push.y, push.z))
    }
}

/** Spins the sub-level [id]. */
internal suspend fun Stage.angularImpulse(id: String, x: Double, y: Double, z: Double) {
    server(id, Push(x, y, z)) { uuid, push ->
        handleOf(serverLevel, uuid).applyTorqueImpulse(org.joml.Vector3d(push.x, push.y, push.z))
    }
}

@Serializable
data class Push(val x: Double, val y: Double, val z: Double)

/**
 * What blocks are inside the sub-level [id], counted by id.
 *
 * Swept from the plot rather than from where the rig was built, because the plot does not move and
 * the body does.
 */
internal suspend fun Stage.blocksIn(id: String): Census = server(id) { uuid ->
    val level = serverLevel
    val box = subLevelNamed(level, uuid).plot.boundingBox.toAABB()
    val tally = linkedMapOf<String, Int>()

    for (pos in net.minecraft.core.BlockPos.betweenClosed(
        net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ),
        net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ),
    )) {
        val state = level.getBlockState(pos)

        if (state.isAir) {
            continue
        }

        val name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
        tally[name] = (tally[name] ?: 0) + 1
    }

    Census(tally.keys.toList(), tally.values.toList())
}

/**
 * Removes every sub-level on the server.
 *
 * Not optional, and not covered by the stage sweep. `Stage.sweep()` clears the plot a test was given;
 * a sub-level's blocks are twenty million blocks away in the plot grid and are never swept. Left
 * alone they accumulate over a run, and every one of them is a body the physics pipeline keeps
 * stepping -- so a suite that assembles fifty bodies ends up measuring the fiftieth against the
 * accumulated cost of the other forty-nine.
 *
 * Call it in a teardown, not between assertions in one test.
 */
internal suspend fun Stage.removeSubLevel(id: String) {
    // One body, named by its own UUID.
    //
    // Not "every body": the tests share a server and run several at a time, so a teardown that
    // cleared everything would delete the bodies of whatever else happened to be mid-flight. Sable's
    // selector argument takes a UUID as well as an `@` selector, which is what makes this possible.
    runCommand("sable remove $id")
    serverTicks(REMOVAL_TICKS)
}

/** Enough ticks for the container to process the queued removal. */
private const val REMOVAL_TICKS = 20

/*
 * The two lookups every body above needs, as top-level functions.
 *
 * They are not private methods of anything, and that is deliberate: an RPC body may not capture a
 * receiver, so a helper it calls has to be resolvable by name on the far node. A top-level `internal`
 * function compiled into this module is; a method on a test class is not.
 */

/** The sub-level with this UUID, or a failure that says what was there instead. */
internal fun subLevelNamed(
    level: net.minecraft.server.level.ServerLevel,
    uuid: String,
): dev.ryanhcode.sable.sublevel.SubLevel {
    val container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
        ?: throw AssertionError("This level has no sub-level container at all")

    return container.getSubLevel(java.util.UUID.fromString(uuid))
        ?: throw AssertionError(
            "There is no sub-level $uuid any more. The ones there are: " +
                container.getAllSubLevels().map { it.uniqueId.toString() },
        )
}

/** The rigid body of the sub-level with this UUID. */
internal fun handleOf(
    level: net.minecraft.server.level.ServerLevel,
    uuid: String,
): dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle =
    dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(
        subLevelNamed(level, uuid) as dev.ryanhcode.sable.sublevel.ServerSubLevel,
    ) ?: throw AssertionError("Sub-level $uuid has no rigid body, so it is not being simulated")
