package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.shotFile
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A furnished sub-level, thrown into the air, landing on the floor beneath it.
 *
 * The point is what rides along. A sub-level is a body in Sable's physics with a whole chunk section
 * of Minecraft inside it, and the things most likely to break when one moves are the things that
 * think they know where they are: a Create machine whose block entity ticks and turns, a door and a
 * bed that are two blocks pretending to be one, a chest holding items. Each of those is placed, then
 * flung, and then asked whether it is still there and still itself.
 *
 * The belt is the reason this is worth doing at all. It is not one block: `BeltConnectorItem` lays a
 * run of them between two shafts and wires every segment back to a controller at one end, so a belt
 * inside a moving sub-level is a block entity holding a position that has to keep meaning something
 * after the body it sits in has moved. It cannot be placed with `/setblock`, and is built here the
 * way the item builds it.
 *
 * Screenshots are taken at each stage -- furnished, at the top of the throw, and after it settles --
 * because "it fell back" and "it fell through the floor" are the same assertion until someone looks.
 */
@DrivesMinecraft
class SubLevelPhysicsTest {

    @Test
    @DisplayName("A furnished sub-level is thrown, falls back, and arrives intact")
    fun `a furnished sub-level falls back onto the platform`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // A floor for it to come back down to, and nothing else in the way.
        clearGround(at(0, 0, 0), radius = GROUND)
        setBlock(at(0, 0, 0), "minecraft:stone")

        // Spawned above the floor, so the drop is a drop rather than a landing it started in.
        spawnPlatform(at(0, SPAWN_HEIGHT, 0), size = PLATFORM)
        serverTicks(SETTLE_TICKS)

        val origin = plotOrigin()

        furnish(origin)
        serverTicks(SETTLE_TICKS)

        val furnished = shotOfTheSubLevel("sublevel_furnished")

        // Before any of the block-by-block checks, because they read the server and this reads the
        // screen: a sub-level whose blocks are all present on the server and absent from the client
        // passes every one of them.
        val furnishedOak = oakFractionOf(furnished)

        assertTrue(
            furnishedOak > MIN_OAK,
            "The platform is not on the screen: only ${"%.4f".format(furnishedOak)} of the picture " +
                "is oak, where a platform this size fills about a twentieth of it. Its blocks are " +
                "in the level -- the checks below say so -- so this is the sub-level's terrain not " +
                "being drawn, which is what Sodium's chunk renderer does to it unless Sable draws " +
                "it itself. See $furnished",
        )

        val built = contentsOfTheSubLevel(origin)

        // Checked before it moves, so a later failure means the flight broke it rather than that it
        // was never built. A belt that did not connect leaves no belt blocks at all.
        assertTrue(built.belts > 0, "No belt was built inside the sub-level: $built")
        assertTrue(built.motor, "The creative motor is not in the sub-level: $built")
        assertTrue(built.doorHalves == 2, "The door is not both of its halves: $built")
        assertTrue(built.bedHalves == 2, "The bed is not both of its halves: $built")
        assertTrue(built.chest, "The chest is not in the sub-level: $built")

        // The platform itself, in the material it was asked for. It doubles as a check that the
        // spawn command took the material argument at all -- the screenshots are useless if the
        // platform is the same stone as the floor it lands on.
        assertTrue(
            built.platform > 0,
            "The platform is not made of $MATERIAL, so the spawn command ignored the material: $built",
        )

        // Turning before it is thrown, so what is being tested is a machine in motion rather than a
        // decoration. A belt with no rotation in it is a belt whose block entity is not doing the
        // work that could go wrong.
        assertTrue(
            built.beltSpeed != 0.0f,
            "The belt is not turning, so the motor never reached it and this is testing scenery: $built",
        )

        // Where the floor actually is. Every test gets its own patch of world, so the stage's own
        // origin is the reference rather than y = 0.
        val floorY = at(0, 0, 0).y.toDouble()
        val restingY = subLevelY()

        assertTrue(
            restingY > floorY,
            "The sub-level is already below its floor before being thrown: resting $restingY, " +
                "floor $floorY",
        )

        // Straight up. Sable takes the impulse in world space, and the sub-level's own mass decides
        // how far it goes -- which is why the assertion below is that it rose at all rather than that
        // it rose to a particular height.
        runCommand("sable physics impulse @l linear 0 $IMPULSE 0")
        serverTicks(RISE_TICKS)

        val apexY = subLevelY()
        val thrown = shotOfTheSubLevel("sublevel_thrown", moving = true)

        // Drawn in the air, and not only at rest. A sub-level is re-posed every frame while it
        // moves, and its sections are re-collected against that pose; the picture is the only thing
        // that says the two still agree once it is off the ground.
        val thrownOak = oakFractionOf(thrown)

        assertTrue(
            thrownOak > MIN_OAK,
            "The platform is not on the screen in flight: only ${"%.4f".format(thrownOak)} of the " +
                "picture is oak, against ${"%.4f".format(furnishedOak)} at rest. See $thrown",
        )


        assertTrue(
            apexY > restingY + 1.0,
            "The sub-level did not rise: resting at $restingY, after the impulse $apexY. Either the " +
                "impulse did not reach it or its body is not simulated",
        )

        // Long enough to come back down and stop bouncing.
        serverTicks(FALL_TICKS)

        val landedY = subLevelY()
        val landed = shotOfTheSubLevel("sublevel_landed")

        assertTrue(
            landedY < apexY - 1.0,
            "The sub-level did not come back down: apex $apexY, now $landedY",
        )

        // Landed on the floor, not through it. The floor is at y=0 and the platform is one block
        // thick, so a sub-level that fell through would keep going and read far below this.
        assertTrue(
            landedY > floorY - 2.0,
            "The sub-level fell through the floor rather than onto it: it is at $landedY, and the " +
                "floor it should have landed on is at $floorY",
        )

        // And came back to about where it started, rather than to some new resting place.
        assertTrue(
            kotlin.math.abs(landedY - restingY) < 2.0,
            "The sub-level did not settle back where it started: was resting at $restingY, now at " +
                "$landedY",
        )

        // And still on the screen where it landed. A sub-level that stopped being drawn somewhere
        // over the course of the flight -- its sections dropped and never rebuilt -- would come
        // through every block check below intact.
        val landedOak = oakFractionOf(landed)

        assertTrue(
            landedOak > MIN_OAK,
            "The platform is not on the screen after landing: only ${"%.4f".format(landedOak)} of " +
                "the picture is oak, against ${"%.4f".format(furnishedOak)} before the throw. See " +
                landed,
        )

        val after = contentsOfTheSubLevel(plotOrigin())

        // The whole point. Everything that rode along is still there, and the belt is still a belt
        // rather than a scattering of unconnected segments.
        assertEquals(
            built.belts, after.belts,
            "The belt did not survive the flight: $built before, $after after",
        )
        assertTrue(after.motor, "The motor did not survive the flight: $after")
        assertEquals(2, after.doorHalves, "The door did not survive the flight intact: $after")
        assertEquals(2, after.bedHalves, "The bed did not survive the flight intact: $after")
        assertTrue(after.chest, "The chest did not survive the flight: $after")
        assertEquals(
            built.chestItems, after.chestItems,
            "The chest's contents changed over the flight: $built before, $after after",
        )
    }

    private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    /**
     * Spawns the platform, in a material that is not the floor's.
     *
     * The default is stone, and the floor beneath it is stone: the first run of this produced a
     * screenshot in which the platform was invisible against the ground it was standing on.
     */
    private suspend fun Stage.spawnPlatform(at: BlockPos, size: Int) {
        runCommand("execute positioned ${at.x} ${at.y} ${at.z} run sable spawn platform $size $MATERIAL")
    }

    /**
     * Where the sub-level's own (0, 0, 0) sits in the world.
     *
     * A sub-level's blocks are real blocks in the level, parked in a plot around x/z = 2e7. Sable
     * addresses them locally and offsets by the plot's centre; everything here works in world
     * coordinates, so it asks for that offset once and adds it.
     */
    private suspend fun Stage.plotOrigin(): Origin = server {
        val sub = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel)!!
            .getAllSubLevels().last()
        val centre = sub.getPlot().getCenterBlock()

        Origin(centre.x, centre.y, centre.z)
    }

    /** Builds the machine and the furniture inside the sub-level. */
    private suspend fun Stage.furnish(origin: Origin) {
        // Placed with commands at world coordinates, because that is where a sub-level's blocks
        // actually are, and because a door and a bed placed this way still get their neighbour
        // updates and settle into halves of one another.
        val ox = origin.x
        val oy = origin.y
        val oz = origin.z

        // The kinetics. A shaft at each end of the run, then a belt between them, then a motor
        // driving one of its pulleys.
        //
        // The shafts are on the z axis for a belt that runs along x: `createBelts` takes the belt's
        // rotation from the shaft it starts at, and a pulley turns about the axis across the run
        // rather than along it. They do not survive as shafts -- the connector replaces both ends
        // with belt blocks -- so the motor drives a pulley, and has to approach it along that same z
        // axis to mesh with it.
        runCommand("setblock ${ox - 2} ${oy + 1} $oz create:shaft[axis=z]")
        runCommand("setblock ${ox + 2} ${oy + 1} $oz create:shaft[axis=z]")

        // The belt between them, built the way the connector item builds it rather than placed.
        connectBelt(origin)

        // The motor's `facing` is the side it drives, so it points at the pulley.
        runCommand("setblock ${ox - 2} ${oy + 1} ${oz - 1} create:creative_motor[facing=south]")

        // The furniture. A door and a bed are each two blocks that have to agree with each other.
        runCommand("setblock ${ox + 1} ${oy + 1} ${oz + 3} minecraft:oak_door[half=lower,facing=north]")
        runCommand("setblock ${ox + 1} ${oy + 2} ${oz + 3} minecraft:oak_door[half=upper,facing=north]")
        runCommand("setblock ${ox - 1} ${oy + 1} ${oz + 3} minecraft:red_bed[part=foot,facing=north]")
        runCommand("setblock ${ox - 1} ${oy + 1} ${oz + 2} minecraft:red_bed[part=head,facing=north]")

        runCommand("setblock ${ox + 3} ${oy + 1} ${oz - 2} minecraft:chest[facing=north]")
        runCommand("item replace block ${ox + 3} ${oy + 1} ${oz - 2} container.0 with minecraft:diamond 7")
    }

    /**
     * Lays a real belt between the two shafts.
     *
     * `BeltConnectorItem.createBelts` is what the connector item calls once it has both ends, and it
     * is what wires every segment back to a controller. A belt cannot be made with `/setblock`: the
     * blocks would be there and the block entities would have no controller, which looks like a belt
     * and is not one.
     */
    private suspend fun Stage.connectBelt(origin: Origin) {
        server(origin) { at ->
            com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(
                serverLevel,
                net.minecraft.core.BlockPos(at.x - 2, at.y + 1, at.z),
                net.minecraft.core.BlockPos(at.x + 2, at.y + 1, at.z),
            )
        }
    }

    /** Where the sub-level's body is, as the physics has it. */
    private suspend fun Stage.subLevelPos(): Pos = server {
        val at = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel)!!
            .getAllSubLevels().last().logicalPose().position()

        Pos(at.x(), at.y(), at.z())
    }

    private suspend fun Stage.subLevelY(): Double = subLevelPos().y

    /**
     * Points the camera at the sub-level wherever it currently is, and takes the picture.
     *
     * Following rather than fixed, because the interesting frames are the ones where it has moved.
     * A fixed camera caught the first throw as an empty field with a wireframe against the clouds.
     */
    private suspend fun Stage.shotOfTheSubLevel(name: String, moving: Boolean = false): String {
        aimAtTheSubLevel()

        // Long enough for the sub-level's sections to be built and drawn from this angle. Under
        // Sodium they are built by a dispatcher of Sable's own, which is driven once a frame.
        //
        // Skipped while it is in flight: by then the sections are long built, and twenty ticks is
        // enough of a ballistic arc to take it out of frame. The picture of the top of the throw
        // came back as an empty sky until this stopped waiting.
        if (!moving) {
            serverTicks(WATCH_TICKS)
            aimAtTheSubLevel()
        }

        return shotFile(name)
    }

    private suspend fun Stage.aimAtTheSubLevel() {
        val at = subLevelPos()

        spectateAt(
            Vec3(at.x + VIEW_BACK, at.y + VIEW_UP, at.z + VIEW_BACK),
            Vec3(at.x, at.y, at.z),
            settle = AIM_TICKS,
        )
    }

    /**
     * How much of the picture is the platform.
     *
     * The platform is oak and everything it can be confused with is not: the floor and the
     * superflat around it are stone grey, the sky is blue, and nothing else in frame is warm. So
     * "warm" is the whole test -- red clearly above blue -- rather than a match against a particular
     * plank colour, which JPEG would not preserve anyway.
     *
     * This is the assertion the port needed. Under Sodium the sub-level's block entities drew and
     * its blocks did not, which every other check in this file passed through without noticing:
     * the belt turned, the door was two halves, the chest kept its diamonds, and the platform they
     * were standing on was not on the screen.
     */
    private fun oakFractionOf(path: String): Double {
        val image = javax.imageio.ImageIO.read(java.io.File(path))
            ?: throw AssertionError("No image was written at $path")

        var warm = 0

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF

                if (r - b > WARMTH && r > g && g > b) {
                    warm++
                }
            }
        }

        return warm.toDouble() / (image.width * image.height)
    }

    /** What is actually inside the sub-level, read block by block. */
    private suspend fun Stage.contentsOfTheSubLevel(origin: Origin): Contents = server(origin) { at ->
        val level = serverLevel
        var belts = 0
        var doorHalves = 0
        var bedHalves = 0
        var motor = false
        var chest = false
        var chestItems = 0
        var beltSpeed = 0.0f
        var platform = 0

        // The sub-level moves, so its blocks are not where they were put. They are found by sweeping
        // the plot the sub-level owns, which does not move -- the body moves, the plot is storage.
        val sub = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)!!
            .getAllSubLevels().last()
        val box = sub.getPlot().getBoundingBox().toAABB()

        for (pos in net.minecraft.core.BlockPos.betweenClosed(
            net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ),
            net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ),
        )) {
            val block = level.getBlockState(pos).block
            val name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString()

            when {
                name == "create:belt" -> {
                    belts++
                    val be = level.getBlockEntity(pos)
                    if (be is com.simibubi.create.content.kinetics.belt.BeltBlockEntity && beltSpeed == 0.0f) {
                        beltSpeed = be.getSpeed()
                    }
                }

                name == MATERIAL -> platform++
                name == "create:creative_motor" -> motor = true
                name == "minecraft:oak_door" -> doorHalves++
                name == "minecraft:red_bed" -> bedHalves++
                name == "minecraft:chest" -> {
                    chest = true
                    val be = level.getBlockEntity(pos)
                    if (be is net.minecraft.world.Container) {
                        for (slot in 0 until be.containerSize) {
                            chestItems += be.getItem(slot).count
                        }
                    }
                }
            }
        }

        Contents(belts, motor, doorHalves, bedHalves, chest, chestItems, beltSpeed, platform)
    }

    @Serializable
    data class Origin(val x: Int, val y: Int, val z: Int)

    @Serializable
    data class Pos(val x: Double, val y: Double, val z: Double)

    @Serializable
    data class Contents(
        val belts: Int,
        val motor: Boolean,
        val doorHalves: Int,
        val bedHalves: Int,
        val chest: Boolean,
        val chestItems: Int,
        val beltSpeed: Float,
        val platform: Int,
    )

    companion object {
        const val GROUND = 16
        const val PLATFORM = 4
        /** High enough that the fall is a fall, low enough that it is over quickly. */
        const val SPAWN_HEIGHT = 8

        /**
         * Straight up, and hard enough to matter.
         *
         * An impulse is a change in momentum, so what it buys depends on the body's mass, and the
         * useful range is narrow. It is calibrated against *this* platform -- four across and made
         * of oak -- and not against the nine-by-nine of stone an earlier version threw: at the mass
         * of that one, 900 moved it five hundredths of a block. At the mass of this one, 4000 sent
         * it 130 blocks up, where it stopped dead and stayed, to the same three decimal places on
         * two runs. Whatever that is, it is not a throw, and the test after it is not measuring one.
         *
         * This lifts it a few blocks and lets it come back, which is what the test is about, and
         * keeps it in frame while it does.
         */
        const val IMPULSE = 400

        /** Oak against a stone floor, so the platform is visible in the pictures. */
        const val MATERIAL = "minecraft:oak_planks"

        /** How far back and up the camera sits from whatever it is following. */
        const val VIEW_BACK = 11.0
        const val VIEW_UP = 6.0

        /**
         * How red has to lead blue for a pixel to be the platform.
         *
         * Oak planks are around (185, 150, 95) and everything else in frame is grey or blue, so this
         * separates them with room to spare on either side of what JPEG does to an edge.
         */
        const val WARMTH = 30

        /**
         * The smallest share of the picture the platform can be and still be there.
         *
         * It fills about a twentieth of the frame from this angle. A twentieth of that is far below
         * anything the camera drifting could explain and far above the nothing that a sub-level
         * whose terrain is not drawn produces.
         */
        const val MIN_OAK = 0.0025

        const val SETTLE_TICKS = 40
        const val WATCH_TICKS = 20

        /** Camera settle for a re-aim, which is a turn on the spot rather than a teleport. */
        const val AIM_TICKS = 10
        const val RISE_TICKS = 20
        const val FALL_TICKS = 200
    }
}
