package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shotFile
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.Stage
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue

/*
 * The same rig, built twice: once on the ground and once inside a sub-level, side by side.
 *
 * This is the spine of the Simulated block tests, and the reason is that a sub-level is a second
 * level -- its own coordinates, its own lighting, its own chunk sections, its own physics tick -- so
 * a block that works on open ground and misbehaves inside one is the most likely shape of a 26.2 port
 * defect. Testing the block on the ground alone would miss all of it.
 *
 * One test makes three claims: the block works; it works in a sub-level; and the sub-level does not
 * change its answer. It also takes one picture with both rigs in it, and that picture is *evidence*
 * rather than an assertion -- whether the two look right is a judgement for a person reading the
 * report, and counting pixels only ever produced a number that had to be tuned until it agreed with
 * what the eye already knew.
 */

/** How the two readings should relate. */
internal sealed interface Parity {

    /** The sub-level must not change the answer. */
    data class Same(val tolerance: Double = 0.001) : Parity

    /**
     * The sub-level legitimately changes the answer, and [why] says how.
     *
     * For the blocks that measure something about where they are -- an altitude sensor is higher, a
     * velocity sensor is on something that moves. Asserting equality on those would be asserting the
     * block is broken.
     */
    data class Differs(val why: String, val check: (ground: Double, sub: Double) -> Boolean) : Parity
}

/** What both halves read, and where the pictures of them went. */
internal data class ParityResult(
    val ground: Double,
    val sub: Double,
    val subLevel: String,
    val picture: String,
    val pictureAgain: String,
) {
    /** Both pictures, for a failure message to point at. */
    val pictures: String get() = "$picture and $pictureAgain"
}

/**
 * Builds [build] twice, drives both with [stimulate], reads both with [read], and compares.
 *
 * [build] is called with the origin its rig should occupy and **must be deterministic** -- the whole
 * claim rests on the two rigs being the same. [read] is called with the origin of each rig, which for
 * the sub-level half is where its blocks ended up in the plot grid rather than where they were built:
 * assembling moves them roughly twenty million blocks away, and this hands back the position that
 * actually addresses them.
 *
 * **What [build] lays must be one connected structure.** Assembly makes a body out of blocks that
 * touch each other, so a rig in two pieces -- two machines on their own little pads with air between
 * them -- becomes one sub-level and one pile of blocks left behind in the world. The half that was
 * left behind then reads as air, and it is visible in the pictures as a second rig sitting on the
 * ground next to the one that flew. Lay a continuous floor under everything.
 *
 * [reach] is how far the rig extends from its origin in each direction. It decides the box that gets
 * assembled, so a rig with a part outside it is quietly left behind on the ground.
 *
 * The sub-level is cleaned up before returning. It is not left for the stage sweep, which does not
 * cover the plot grid.
 */
internal suspend fun Stage.bothWays(
    name: String,
    reach: Int = 3,
    build: suspend Stage.(origin: BlockPos) -> Unit,
    stimulate: suspend Stage.(origin: BlockPos) -> Unit = {},
    read: suspend Stage.(origin: BlockPos) -> Double,
    expect: Parity = Parity.Same(),
): ParityResult {
    theClient()
    clearGround(at(0, 0, 0), radius = GROUND)

    val groundAt = at(-SEPARATION, 1, 0)
    val builtAt = at(SEPARATION, 1, 0)

    build(groundAt)
    build(builtAt)
    serverTicks(SETTLE)

    // Assembled after both are built, so the two rigs are the same age and have had the same number
    // of ticks to wire themselves up before either is driven.
    val corner = builtAt.offset(-reach, -1, -reach)
    val subLevel = assembleArea(corner, builtAt.offset(reach, reach, reach))
    serverTicks(SETTLE)

    // Where the rig's origin ended up inside the plot.
    //
    // Assembly keeps the structure's shape and its axes -- `SubLevelAssemblyHelper.assembleBlocks`
    // builds an `AssemblyTransform` from the box's anchor to the plot's centre block, with no
    // rotation -- and the anchor is the box's minimum corner, because that is what
    // `BlockPos.betweenClosedStream` yields first. So a block that was at `p` is now at
    // `plotCentre + (p - corner)`, and the origin specifically is `plotCentre` offset by how far it
    // sits from the corner. Reading the plot's centre alone would address the corner, not the rig.
    val plot = plotOriginOf(subLevel)
    val subAt = BlockPos(plot.x, plot.y, plot.z).offset(
        builtAt.x - corner.x,
        builtAt.y - corner.y,
        builtAt.z - corner.z,
    )

    // Everything from here on is in a `finally`, because the body of it can fail.
    //
    // A failing assertion used to leave the sub-level behind: the stage sweep does not reach the plot
    // grid, so the body stayed on the shared server, the physics pipeline went on stepping it, and
    // the next test to build in the same place assembled a *second* one on top of it. Two overlapping
    // rigs in a screenshot is what that looks like from the outside, and it is caused by the previous
    // test having failed rather than by anything the current one did.
    try {
        stimulate(groundAt)
        stimulate(subAt)
        serverTicks(SETTLE)

        val ground = read(groundAt)
        val sub = read(subAt)

        val (picture, pictureAgain) = picturesOfBoth(name, groundAt, builtAt)

        when (expect) {
            is Parity.Same -> assertTrue(
                Math.abs(ground - sub) <= expect.tolerance,
                "On the ground this reads $ground and inside a sub-level $sub. A sub-level is not " +
                    "supposed to change this block's answer, so one of the two is wrong -- and the " +
                    "sub-level half is the one running through Sable's coordinates, lighting and " +
                    "tick. See $picture and $pictureAgain",
            )

            is Parity.Differs -> assertTrue(
                expect.check(ground, sub),
                "On the ground this reads $ground and inside a sub-level $sub, which does not " +
                    "hold: ${expect.why}. See $picture and $pictureAgain",
            )
        }

        return ParityResult(ground, sub, subLevel, picture, pictureAgain)
    } finally {
        removeSubLevel(subLevel)
    }
}

/**
 * Two pictures with both rigs in them, a few ticks apart.
 *
 * Framed from the side so the two sit in opposite halves, ground on the left and sub-level on the
 * right, which makes them directly comparable at a glance.
 *
 * Two rather than one because a single frame cannot show rotation. A shaft turning at 32 rpm and a
 * shaft standing still are the same picture; put two frames [APART] ticks apart side by side and the
 * difference is obvious -- and it is obvious *per rig*, which is the thing worth seeing here.
 *
 * Nothing is asserted about either. The paths are returned and carried into every failure message, so
 * whichever assertion fails names the pictures that show why.
 */
private suspend fun Stage.picturesOfBoth(
    name: String,
    groundAt: BlockPos,
    builtAt: BlockPos,
): Pair<String, String> {
    val middle = Vec3(
        (groundAt.x + builtAt.x) / 2.0 + 0.5,
        groundAt.y + 1.0,
        groundAt.z + 0.5,
    )

    spectateAt(Vec3(middle.x, middle.y + VIEW_UP, middle.z + VIEW_BACK), middle)
    serverTicks(WATCH)

    val first = shotFile(name)
    serverTicks(APART)

    return first to shotFile(name + "_again")
}

/**
 * How far apart the two frames are.
 *
 * Long enough that a slow wheel has visibly turned, short enough that nothing else in the scene has
 * moved on.
 */
private const val APART = 12

/** How far apart the two rigs are built, in each direction from the stage origin. */
private const val SEPARATION = 8

private const val GROUND = 24
private const val SETTLE = 20
private const val WATCH = 20
private const val VIEW_UP = 5.0
private const val VIEW_BACK = 14.0
