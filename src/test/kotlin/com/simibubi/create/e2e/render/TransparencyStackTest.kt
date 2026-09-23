package com.simibubi.create.e2e.render

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
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
 * Four kinds of transparency stacked in one line of sight, looked at end on.
 *
 * The earlier [FluidPipeTest] asked whether order-independent transparency is worth building and
 * answered no, on one scene: a run of glass pipes seen lengthwise. That scene turned out not to
 * stack anything -- each segment's fluid is boxed in by the opaque pipe around it -- so it could not
 * have shown a difference either way, and the conclusion it supported was worth no more than the
 * scene was.
 *
 * This one stacks things that really do overlap, and of different kinds, because they reach the
 * screen by different paths: vanilla glass and leaves are chunk geometry, entities are their own
 * render pass, and the fluid in a Create pipe is the one thing Flywheel draws with
 * `Transparency.ORDER_INDEPENDENT`. Sorting faults show up between those paths rather than within
 * any one of them.
 *
 * The pipes stand vertically so their fluid is a tall surface across the view rather than a slot
 * seen end on, and the camera looks down the row so everything in it overlaps everything behind it.
 *
 * ```
 * ./gradlew test --tests '*TransparencyStackTest*' -Pclients=1 -Pgraphics=opengl --rerun
 * ./gradlew test --tests '*TransparencyStackTest*' -Pclients=1 -Pgraphics=vulkan --rerun
 * ```
 *
 * OpenGL runs `flywheel:indirect`, which has real OIT. Any difference between the two pictures is
 * the thing this test exists to find.
 *
 * ## What the comparison said
 *
 * Nothing, and this time on a scene that can say it. Run on 2026-09-23 with the Blaze3D backend
 * falling back to ordinary blending, the two pictures differ by a mean of under one part in 255.
 * Restricted to the band holding the glass, the stacked fluid and the leaves -- excluding the sky,
 * where clouds drift between runs, and the motors, whose cogs are at different angles in two
 * separate sessions -- the difference is 0.55/255 head on and 0.92/255 angled. There is no sorting
 * fault to see.
 *
 * The OpenGL side really did run its chain -- `oitChainRan` is asserted, because two matching
 * pictures say nothing at all if the backend that has order independence never used it.
 *
 * ## Why they match
 *
 * Not because the approximation is good. OpenGL enters its chain whenever any material is
 * `ORDER_INDEPENDENT`, with no check that anything actually overlaps, and Create's only such
 * material is the fluid inside a glass pipe. A pipe is an opaque tube with a window: the near pipe's
 * far wall sits between its fluid and the next pipe's fluid, so no two order-independent surfaces
 * ever blend with each other. The chain runs its four passes and reaches the answer ordinary
 * blending already had, because nothing was out of order.
 *
 * So what is established is narrower than "OIT is unnecessary": Create's one order-independent
 * material is always enclosed, and therefore cannot produce the artifact. A mod that attached
 * `ORDER_INDEPENDENT` to unenclosed geometry would be drawn wrongly by the Blaze3D backend, and this
 * test would not catch it -- it has no such geometry to offer.
 */
@DrivesMinecraft
class TransparencyStackTest {

    @Test
    @DisplayName("Glass, leaves, entities and fluid stacked in one line of sight")
    fun `transparency sorts through a stack`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = 32)

        glassWall(GLASS_NEAR)
        fluidColumn(PIPES_A)
        fluidColumn(PIPES_B)
        entities(MOBS)
        glassWall(GLASS_FAR)
        fluidColumn(PIPES_C)
        leafWall(LEAVES_BACK)

        // Long enough for both pumps to fill their columns, or the pipes in the picture are empty
        // and two of the seven layers are missing.
        serverTicks(200)

        val a = fluidIn(at(0, TOP_TANK, PIPES_A))
        val b = fluidIn(at(0, TOP_TANK, PIPES_B))
        val c = fluidIn(at(0, TOP_TANK, PIPES_C))
        println("FLUID a=$a b=$b c=$c oitChainRan=${oitChainRan()}")

        // Down the row at the height the pipes and walls span, so every layer is in front of every
        // layer behind it.
        spectateAt(middleOf(at(0, EYE, GLASS_NEAR - 7)), middleOf(at(0, EYE, LEAVES_BACK + 4)))
        serverTicks(40)

        shot("transparency_stack")

        // Slightly off axis, because a stack viewed dead on can hide a sorting fault behind its own
        // silhouette -- the layers line up pixel for pixel and a wrongly ordered one covers exactly
        // what it should have been blended with.
        spectateAt(middleOf(at(4, EYE + 2, GLASS_NEAR - 7)), middleOf(at(0, EYE, LEAVES_BACK)))
        serverTicks(20)

        shot("transparency_stack_angled")

        assertTrue(
            backendId() != "flywheel:indirect" || oitChainRan(),
            "The OpenGL backend never entered its order-independent chain, so comparing its " +
                "picture against the Vulkan one proves nothing about order independence -- it " +
                "compares two ordinary blends",
        )

        assertTrue(
            a > 0 && b > 0 && c > 0,
            "A pipe column did not fill (a=$a, b=$b, c=$c), so a fluid layer is missing from the " +
                "pictures and the stack has one less thing to sort than it claims",
        )
    }

    /** Vanilla glass, which is chunk geometry rather than anything Flywheel draws. */
    private suspend fun Stage.glassWall(z: Int) {
        fill(at(-1, SPAN_LOW, z), at(1, SPAN_HIGH, z), "minecraft:glass")
    }

    /** Leaves, which are cutout rather than blended, and so sort differently again. */
    private suspend fun Stage.leafWall(z: Int) {
        fill(at(-1, SPAN_LOW, z), at(1, SPAN_HIGH, z), "minecraft:oak_leaves[persistent=true]")
    }

    /**
     * A vertical run of glass pipe with water climbing it, and the machinery to push it.
     *
     * The pump is driven by a cogwheel turning on the axis the pipe runs along, with the motor
     * behind that cogwheel on the same axis -- vertical pipes mean `axis=y` and a motor facing up.
     * Every other arrangement leaves the pump at zero while the drive beside it turns.
     */
    private suspend fun Stage.fluidColumn(z: Int) {
        setBlock(at(0, 2, z), "create:fluid_tank")
        setBlock(at(0, 3, z), "create:mechanical_pump[facing=up]")

        for (y in SPAN_LOW..SPAN_HIGH) {
            setBlock(at(0, y, z), "create:glass_fluid_pipe[axis=y]")
        }

        setBlock(at(0, TOP_TANK, z), "create:fluid_tank")

        // Beside the column, on the pipe's own axis.
        setBlock(at(1, 2, z), "create:creative_motor[facing=up]")
        setBlock(at(1, 3, z), "create:cogwheel[axis=y]")

        fillTank(at(0, 2, z), SOURCE_WATER)
        turn(at(1, 2, z), PUMP_RPM)
    }

    /**
     * Two entities that will not wander off.
     *
     * `NoAI` keeps them where they are put and `NoGravity` keeps them at eye height without a
     * platform under them -- a platform would be one more opaque thing in a line of sight that is
     * supposed to be nothing but transparent ones.
     */
    private suspend fun Stage.entities(z: Int) {
        val armorStand = at(-1, EYE, z)
        val zombie = at(1, EYE, z)

        runCommand(
            "summon minecraft:armor_stand ${armorStand.x} ${armorStand.y} ${armorStand.z} " +
                "{NoGravity:1b,Invulnerable:1b}",
        )
        // A creeper rather than a zombie: an undead mob burns in daylight, and the flames are a
        // big additive transparent effect of their own draped over the middle of the scene.
        runCommand(
            "summon minecraft:creeper ${zombie.x} ${zombie.y} ${zombie.z} " +
                "{NoAI:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b}",
        )
    }

    /**
     * Whether the OpenGL backend actually entered its order-independent chain.
     *
     * Without this the comparison is worthless: if the backend that has OIT never ran it, two
     * matching pictures say only that two ordinary blends agree.
     */
    private suspend fun Stage.oitChainRan(): Boolean = dev.vibeported.mc.driver.client(watcher) {
        dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.oitChainRan
    }

    private suspend fun Stage.backendId(): String = dev.vibeported.mc.driver.client(watcher) {
        dev.engine_room.flywheel.api.backend.Backend.REGISTRY
            .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
            .toString()
    }

    private suspend fun Stage.fillTank(tank: BlockPos, amount: Int) {
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

    private suspend fun Stage.fluidIn(tank: BlockPos): Int = server(tank) { pos ->
        (serverLevel.getBlockEntity(pos) as? FluidTankBlockEntity)?.controllerBE
            ?.tankInventory?.fluidAmount ?: 0
    }

    private companion object {
        /** The band every layer spans, so each one covers the one behind it. */
        const val SPAN_LOW = 4

        const val SPAN_HIGH = 6

        const val TOP_TANK = SPAN_HIGH + 1

        const val EYE = 5

        // The row, near to far.
        //
        // Two pipe columns stand directly one behind the other, because fluid drawn over fluid is
        // the case order-independent transparency exists for and the only one nothing else in the
        // scene produces. A third sits further back behind a glass wall.
        //
        // The leaves are last rather than in the middle: they are cutout, so they are opaque
        // wherever they are not fully clear, and in the middle of the row they simply hide
        // everything behind them -- which is what the first version of this scene did.
        const val GLASS_NEAR = 0
        const val PIPES_A = 2
        const val PIPES_B = 4
        const val MOBS = 6
        const val GLASS_FAR = 8
        const val PIPES_C = 10
        const val LEAVES_BACK = 13

        const val SOURCE_WATER = 8000

        const val PUMP_RPM = 64
    }
}

private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
