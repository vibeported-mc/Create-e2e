package com.simibubi.create.e2e.render

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.clearGround
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
import net.minecraft.core.BlockPos
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.fluids.FluidStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Water in a run of glass pipes, looked at down the length of the run.
 *
 * This is the only scene in Create where order-independent transparency actually matters.
 * `FluidMesh` is the one material that asks for `Transparency.ORDER_INDEPENDENT`, and the only thing
 * that uses it is `GlassPipeVisual` -- the fluid stream and surface drawn inside a glass pipe. One
 * pipe seen from the side is a single translucent layer and sorts correctly however it is drawn; a
 * run of them seen end-on is a dozen, and the order they are blended in is the whole question.
 *
 * So the camera looks along the run rather than across it. A scene that cannot stack the layers
 * cannot tell a real OIT implementation from ordinary blending, which is what the existing fluid
 * tests happen to be: their tanks show one surface each and look right either way.
 *
 * The assertion is only that the water arrived and is being drawn. Whether it is blended in the
 * right order is for the two screenshots to say, taken from the same camera on both backends:
 *
 * ```
 * ./gradlew test --tests '*FluidPipeTest*' -Pclients=1 -Pgraphics=opengl --rerun
 * ./gradlew test --tests '*FluidPipeTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 *
 * OpenGL runs `flywheel:indirect`, which has the real thing.
 *
 * ## What the comparison said
 *
 * The two pictures are the same. Run on 2026-09-23 with the Blaze3D backend falling back to ordinary
 * blending for `ORDER_INDEPENDENT`, this scene is indistinguishable from the same scene on the
 * OpenGL backend's moment-based OIT.
 *
 * That is not because the approximation is good. It is because the layers never actually overlap:
 * each pipe segment's fluid is boxed in by the opaque pipe around it, so what looks end-on like a
 * dozen stacked translucent surfaces is a dozen surfaces each with opaque geometry in front of it.
 * Create asks for order independence in exactly one material and then never builds a scene that
 * needs it.
 *
 * So the OIT rebuild is deferred, and this test is the evidence for that decision rather than a
 * placeholder for it. If a scene ever does show the difference, it belongs here.
 */
@DrivesMinecraft
class FluidPipeTest {

    @Test
    @DisplayName("Water in a run of glass pipes, seen end on")
    fun `fluid draws through a pipe run`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = 24)

        buildPipeRun()

        // Long enough for the pump to push water the length of the run and for the far tank to
        // start filling. A run photographed before the water arrives is a run of empty pipes.
        serverTicks(160)

        var moved = fluidIn(at(2, 2, -(RUN + 1)))
        println("FLUID delivered=$moved source=${fluidIn(at(2, 2, 1))} pumpSpeed=${speedAt(at(2, 2, 0))}")
        println("FLUID cog=${speedAt(at(1, 2, 0))} pump=${speedAt(at(2, 2, 0))}")

        // Fluid networks take their time, and a run this long more than most. Waited for rather
        // than guessed at, because the first version of this gave it 160 ticks and photographed
        // twelve empty pipes.
        var waited = 160
        while (moved == 0 && waited < 900) {
            serverTicks(100)
            waited += 100
            moved = fluidIn(at(2, 2, -(RUN + 1)))
        }
        println("FLUID delivered=$moved after $waited ticks")

        // Down the length of the run and slightly above it, so a dozen pipe segments overlap in
        // the picture. Across the run each segment stands alone and sorts correctly either way.
        spectateAt(middleOf(at(2, 3, -(RUN + 6))), middleOf(at(2, 2, 0)))
        serverTicks(40)

        shot("fluid_pipes_lengthwise")

        // And from the side, as a control: this is the view the rest of the suite has of fluids, and
        // it should look the same on both backends whatever the blending does.
        spectateAt(middleOf(at(9, 4, -(RUN / 2))), middleOf(at(2, 2, -(RUN / 2))))
        serverTicks(20)

        shot("fluid_pipes_side")

        assertTrue(
            moved > 0,
            "No water reached the far tank in 160 ticks, so the pipes in the screenshots are " +
                "empty and there is nothing transparent in the scene to sort",
        )
    }

    /**
     * A tank, a pump, a long run of glass pipe, and a tank to catch it.
     *
     * How the pump is driven is the part that took several tries, so here is the rule: the cogwheel
     * beside the pump turns on the axis the pipe runs along, and the motor sits behind that cogwheel
     * on the same axis. Pipes run north-south here, so the cog is `axis=z` and the motor faces north
     * into it.
     *
     * Four other arrangements were measured and every one of them left the pump at zero while the
     * drive line beside it turned at full speed -- a shaft on the perpendicular axis, a cog on the
     * perpendicular axis, a drive from above, and a motor straight into the pump's back. None of
     * them fails visibly: the scene looks assembled, the motor spins, and the pipes stay dry.
     */
    private suspend fun Stage.buildPipeRun() {
        setBlock(at(1, 2, 1), "create:creative_motor[facing=north]")
        setBlock(at(1, 2, 0), "create:cogwheel[axis=z]")
        setBlock(at(2, 2, 0), "create:mechanical_pump[facing=north]")

        // South of the pump, which is the side it draws from.
        setBlock(at(2, 2, 1), "create:fluid_tank")

        for (i in 1..RUN) {
            setBlock(at(2, 2, -i), "create:glass_fluid_pipe[axis=z]")
        }

        setBlock(at(2, 2, -(RUN + 1)), "create:fluid_tank")

        fill(at(2, 2, 1), SOURCE_WATER)
        turn(at(1, 2, 1), PUMP_RPM)
    }

    private suspend fun Stage.fill(tank: BlockPos, amount: Int) {
        server(tank, amount) { pos, howMuch ->
            (serverLevel.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE
                .tankInventory.setFluid(FluidStack(Fluids.WATER, howMuch))
        }
    }

    private suspend fun Stage.turn(motor: BlockPos, rpm: Int) {
        server(motor, rpm) { pos, speed ->
            (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
            true
        }
    }

    /** What is really at a position, since a block that failed to place reads as a stopped one. */
    private suspend fun Stage.stateAt(pos: BlockPos): String = server(pos) { where ->
        serverLevel.getBlockState(where).toString()
    }

    /** What the pump is actually turning at, since a cog that does not mesh looks identical. */
    private suspend fun Stage.speedAt(pos: BlockPos): Float = server(pos) { where ->
        (serverLevel.getBlockEntity(where)
            as? com.simibubi.create.content.kinetics.base.KineticBlockEntity)?.speed ?: 0.0f
    }

    private suspend fun Stage.fluidIn(tank: BlockPos): Int = server(tank) { pos ->
        (serverLevel.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE
            .tankInventory.fluidAmount
    }

    private companion object {
        /** Long enough that end-on the segments stack up several deep. */
        const val RUN = 12

        const val SOURCE_WATER = 8000

        const val PUMP_RPM = 64
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
