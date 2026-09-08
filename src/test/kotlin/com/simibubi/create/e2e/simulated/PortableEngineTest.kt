package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a portable engine burns fuel into rotation, and burns a blaze cake into twice as much.
 *
 * Its ponder scene states the contract: the engine generates rotational force by burning fuel, and
 * with a blaze cake it can be superheated for additional power output. "Additional" is exact in the
 * code -- `PortableEngineBlockEntity.getGeneratedSpeed` multiplies by two when superheated -- so this
 * asserts the factor rather than merely that the number went up.
 *
 * The reading is the engine's own generated speed rather than a shaft beside it. That is what the
 * block is claimed to produce, and it does not depend on guessing which face the drive train leaves
 * from.
 *
 * Both tests run on the ground and inside a sub-level. An engine is a block entity that ticks a burn
 * timer and pushes a speed into a kinetic network, and both of those are things a body with its own
 * tick can get wrong.
 *
 * **What this does not prove.** The direction setting on its value panel, or feeding it from a belt.
 * The scene shows both; neither is covered here.
 */
@DrivesMinecraft
class PortableEngineTest {

    @Test
    @DisplayName("A fuelled portable engine generates rotation, the same in a sub-level")
    fun `it generates rotation from fuel`(cluster: ClusterScope) = cluster.stage {
        val burning = bothWays(
            name = "engine_fuelled",
            reach = 2,
            build = { origin -> engineRig(origin) },
            stimulate = { origin ->
                // Nothing: the engine is empty until the reading puts fuel in it, so that the
                // before-and-after happens identically on both rigs.
            },
            read = { origin -> speedFromFuelAt(origin, Fuel.COAL) },
            expect = Parity.Same(tolerance = SPEED_TOLERANCE),
        )

        assertTrue(
            Math.abs(burning.ground) == BASE,
            "A portable engine burning ordinary fuel should generate $BASE, which is the figure " +
                "`tick` puts into `generatedSpeed`. It reads ${burning.ground} on the ground and " +
                "${burning.sub} in the sub-level. See ${burning.pictures}",
        )
    }

    @Test
    @DisplayName("A blaze cake superheats a portable engine to twice its output")
    fun `a blaze cake doubles its output`(cluster: ClusterScope) = cluster.stage {
        val superheated = bothWays(
            name = "engine_superheated",
            reach = 2,
            build = { origin -> engineRig(origin) },
            read = { origin -> speedFromFuelAt(origin, Fuel.BLAZE_CAKE) },
            expect = Parity.Same(tolerance = SPEED_TOLERANCE),
        )

        assertTrue(
            Math.abs(superheated.ground) == BASE * SUPERHEATED,
            "A blaze cake is supposed to superheat the engine, and `getGeneratedSpeed` multiplies " +
                "by exactly $SUPERHEATED when it is -- so ${BASE * SUPERHEATED} against the $BASE " +
                "an ordinary fuel gives. It reads ${superheated.ground} on the ground and " +
                "${superheated.sub} in the sub-level. See ${superheated.pictures}",
        )
    }

    /**
     * Fuels the engine, lets it catch, and reports what it generates.
     *
     * One fuel per engine, and a fresh engine per test. Swapping the stack in a running engine does
     * nothing: it only takes new fuel when `burnTime` reaches zero, and a single coal burns for 1600
     * ticks -- so a test that put coal in, then a blaze cake, then compared the two was reading the
     * same coal both times and finding, correctly, that nothing had changed.
     */
    private suspend fun Stage.speedFromFuelAt(origin: BlockPos, what: Fuel): Double {
        fuel(origin, what)
        serverTicks(CATCH)

        return generatedSpeedAt(origin)
    }

    /**
     * Puts a stack of [what] into the engine at [pos].
     *
     * Through the block entity's own inventory rather than with `/item replace block`, because
     * `PortableEngineBlockEntity` implements `Clearable` and not `Container`: the command has nothing
     * to address, so it reports no error and puts nothing in, and the engine then reads zero for the
     * entirely uninteresting reason that it is empty.
     */
    private suspend fun fuel(pos: BlockPos, what: Fuel) = server(pos, what) { at, which ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity) {
            throw AssertionError("There is no portable engine at $at but $be")
        }

        be.inventory.setItem(
            0,
            when (which) {
                Fuel.COAL -> net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 16)
                Fuel.BLAZE_CAKE -> com.simibubi.create.AllItems.BLAZE_CAKE.asStack(16)
            },
        )
    }

    /** The two fuels these tests use. An enum, because it has to cross to the server. */
    enum class Fuel { COAL, BLAZE_CAKE }

    /** What the engine at [pos] says it is generating. */
    private suspend fun generatedSpeedAt(pos: BlockPos): Double = server(pos) { at ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity) {
            throw AssertionError("There is no portable engine at $at but $be")
        }

        be.generatedSpeed.toDouble()
    }

    /** The engine, a shaft off its facing axis, and a wheel so the pictures show it turning. */
    private suspend fun Stage.engineRig(origin: BlockPos) {
        setBlock(origin, "simulated:red_portable_engine[facing=east,lit=false]")
        setBlock(origin.east(), "create:shaft[axis=x]")
        setBlock(origin.east(2), "create:water_wheel[facing=east]")
        setBlock(origin.below(), "minecraft:stone")
    }

    companion object {
        /** What `tick` writes into `generatedSpeed` while the engine is lit. */
        const val BASE = 32.0

        /** `getGeneratedSpeed` multiplies by this when the engine is superheated. */
        const val SUPERHEATED = 2.0

        /** Long enough for the engine to take the fuel and light. */
        const val CATCH = 40

        const val SPEED_TOLERANCE = 0.05
    }
}
