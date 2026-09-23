package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The same machines, drawn by both backends, from the same place.
 *
 * <p>A benchmark says the Vulkan backend is fast and says nothing about whether it is right. This
 * is the other half: one scene, one camera, two runs, and the two pictures put side by side. Any
 * difference between them is the new backend's, because everything else about the two runs is
 * identical.
 *
 * <p>It is deliberately a spread of machines rather than one, because the failures found so far
 * have been per instance type rather than global -- rotating shafts drew correctly while belts and
 * cogs did not appear at all, which a scene of shafts would have called a pass.
 *
 * ```
 * ./gradlew test --tests '*BackendParityTest*' -Pclients=1 -Pgraphics=opengl --rerun
 * ./gradlew test --tests '*BackendParityTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 *
 * <p>The assertion is only that the backend is on and drawing; the pictures are for a human, and
 * `parity` in the name is an aspiration rather than something this can check by itself -- comparing
 * two runs' screenshots automatically would mean pinning the camera, the time of day and the
 * weather, and would still fail on a driver's idea of rounding.
 */
@DrivesMinecraft
class BackendParityTest {

    @Test
    @DisplayName("A spread of Create's machines, drawn for comparison between backends")
    fun `the machines draw`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = 24)

        buildMachines()
        serverTicks(80)

        // Close enough that a missing part is obvious rather than a few pixels.
        spectateAt(middleOf(at(6, 5, 10)), middleOf(at(6, 2, 0)))
        serverTicks(40)

        val beltSpeed = beltSpeed()
        println("PARITY beltSpeed=$beltSpeed")

        val state = backend()
        println("PARITY graphics=${state.graphics} backend=${state.backend} instancing=${state.instancing}")

        val work = drawWork()
        println(
            "PARITY indirectInstancers=${work.indirectInstancers} " +
                "directInstancers=${work.directInstancers} " +
                "indirectCalls=${work.indirectCalls} directCalls=${work.directCalls}",
        )

        shot("parity_machines")

        // The same camera, seven ticks on. Seven and not twenty: twenty ticks is exactly one
        // second, and both a rotation angle and a belt's scroll are functions of time, so at a
        // whole second they can land back where a whole cycle left them and two frames can match
        // while everything is moving.
        serverTicks(7)
        shot("parity_machines_moved")

        // A second angle, because a part drawn at the wrong depth or facing the wrong way can look
        // correct head-on.
        spectateAt(middleOf(at(-6, 4, 6)), middleOf(at(6, 2, 2)))
        serverTicks(20)
        shot("parity_machines_side")

        assertTrue(
            state.instancing,
            "Flywheel is not instancing on ${state.graphics} -- its backend is `${state.backend}`, " +
                "so this scene is being drawn by ordinary block entity renderers and comparing it " +
                "against the other backend proves nothing",
        )

        // Only the Blaze3D backend publishes these; the OpenGL one leaves them at zero, and asserting
        // on them there would fail a run that is perfectly correct.
        if (state.backend == "flywheel:indirect_blaze3d") {
            // The picture above would look identical either way. An instance type whose cull shader
            // will not build still draws, through the plain path over an identity list, so a
            // screenshot cannot tell the two apart -- and the whole point of this backend is the one
            // it cannot see.
            assertTrue(
                work.indirectInstancers > 0,
                "Nothing was drawn indirectly: all ${work.directInstancers} instancers fell back to " +
                    "the plain path, so no cull pass ran and the scene above proves only that the " +
                    "fallback works",
            )
            assertTrue(
                work.directInstancers == 0,
                "${work.directInstancers} instancers fell back to the plain path while " +
                    "${work.indirectInstancers} drew indirectly -- some instance type's cull shader " +
                    "did not build, and the log says which",
            )
        }
    }

    /**
     * One of each of the things that have gone wrong, plus the one that has not.
     *
     * Every kinetic machine gets its own creative motor rather than sharing a network, so a single
     * jam cannot stop the whole scene and make everything look equally broken.
     */
    private suspend fun Stage.buildMachines() {
        // Shafts and cogs: the rotating instance type, which is the one already known to work.
        setBlock(at(0, 2, 0), "create:creative_motor[facing=east]")
        setBlock(at(1, 2, 0), "create:shaft[axis=x]")
        setBlock(at(2, 2, 0), "create:cogwheel[axis=x]")

        setBlock(at(0, 2, 3), "create:creative_motor[facing=east]")
        setBlock(at(1, 2, 3), "create:shaft[axis=x]")
        setBlock(at(2, 2, 3), "create:large_cogwheel[axis=x]")

        // An encased fan: a rotating part inside a housing, so a part drawn in the wrong place
        // shows up as sticking out of its own casing.
        setBlock(at(5, 2, 0), "create:creative_motor[facing=east]")
        setBlock(at(6, 2, 0), "create:encased_fan[facing=east]")

        // A mechanical pump. Driven through a cogwheel rather than a shaft, which is how one is
        // actually powered -- a pump takes its drive off the side.
        // The cog sits beside the pump on the pump's own shaft axis. A pump facing north takes its
        // drive east-west, so a cog on the x axis directly west of it meshes; one placed along z
        // touches nothing and leaves the pump idle while the scene still looks assembled.
        setBlock(at(4, 2, 4), "create:creative_motor[facing=east]")
        setBlock(at(5, 2, 4), "create:cogwheel[axis=x]")
        setBlock(at(6, 2, 4), "create:mechanical_pump[facing=north]")

        // A mixer over a basin, likewise cog-driven. The head only turns when there is something in
        // the basin to mix, so the basin is filled -- an empty one leaves the mixer idle and its
        // moving parts untested while the scene looks complete.
        setBlock(at(9, 4, 0), "create:creative_motor[facing=east]")
        setBlock(at(9, 4, 1), "create:cogwheel[axis=x]")
        setBlock(at(9, 3, 0), "create:mechanical_mixer")
        setBlock(at(9, 2, 0), "create:basin")
        give(at(9, 2, 0), 0, "minecraft:cobblestone", 16)

        // A chute, reported as missing its dark parts.
        setBlock(at(9, 3, 3), "create:chute")

        // A saw, which animates its own texture as well as turning.
        setBlock(at(12, 2, 0), "create:creative_motor[facing=north]")
        setBlock(at(12, 2, 1), "create:mechanical_saw[facing=north]")

        // A driven belt. Belts are the scrolling instance type, whose whole visible behaviour is a
        // texture that moves -- so a belt that draws is only half the answer.
        // The belt runs along x, so its pulleys turn about z -- the axis across the direction of
        // travel, not along it. The end shafts are therefore axis=z and the motor stands beside the
        // near pulley facing into it. Turned ninety degrees from this the motor touches nothing,
        // the belt reads zero speed, and a still belt looks exactly like a broken shader.
        setBlock(at(4, 2, 5), "create:creative_motor[facing=south]")
        setBlock(at(4, 2, 6), "create:shaft[axis=z]")
        setBlock(at(10, 2, 6), "create:shaft[axis=z]")
        layBelt()
    }

    /** A belt between the two shafts, which only `BeltConnectorItem` knows how to build. */
    private suspend fun Stage.layBelt() {
        server(at(4, 2, 6), at(10, 2, 6)) { from, to ->
            com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem
                .createBelts(serverLevel, from, to)
        }
    }

    /** What the belt is doing, so a still belt is not mistaken for a broken shader. */
    private suspend fun Stage.beltSpeed(): Float = server(at(7, 2, 6)) { where ->
        (serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.kinetics.belt.BeltBlockEntity)
            ?.speed ?: 0.0f
    }

    private suspend fun Stage.backend(): Backend = client(watcher) {
        Backend(
            graphics = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.backendName,
            backend = dev.engine_room.flywheel.api.backend.Backend.REGISTRY
                .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
                .toString(),
            instancing = dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn(),
        )
    }

    @Serializable
    private data class Backend(val graphics: String, val backend: String, val instancing: Boolean)
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
