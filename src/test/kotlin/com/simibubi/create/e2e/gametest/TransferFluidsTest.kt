package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Fluid and item transfer, asked directly rather than through a machine.
 *
 * These borrow a structure for its floor, clear the space above it, and build what they need. What
 * they are about happens inside the server in a tick or two, so like the item transfer tests each is
 * a body run there that either finds nothing to complain about or says what it found.
 *
 * Ported from Create's `TestTransferFluids`.
 */
@DrivesMinecraft
class TransferFluidsTest {

    @Test
    @DisplayName("Two tanks joined into one keep what they were holding between them")
    fun `merging tanks conserves fluid`(cluster: ClusterScope) = cluster.driving {
        val scene = arena()

        val lower = scene.at(2, 2, 2)
        val upper = scene.at(2, 4, 2)
        val middle = scene.at(2, 3, 2)

        setBlockAt(lower, TANK)
        setBlockAt(upper, TANK)
        serverTicks(SETTLE)

        fillTank(lower, 500)
        fillTank(upper, 500)
        serverTicks(SETTLE)

        // Only now do the two become one.
        setBlockAt(middle, TANK)
        serverTicks(SETTLE)
        shot("tank_merge")

        // Merged, so every part answers for the whole: ask one of them, not all three.
        assertEquals(
            1000, tankHolds(lower).amount,
            "The merged tank holds ${tankHolds(lower).amount}mB of the 1000mB put into it",
        )

        restoreHud()
    }

    @Test
    @DisplayName("A tank taken apart keeps what the remaining half can hold")
    fun `splitting tanks conserves fluid`(cluster: ClusterScope) = cluster.driving {
        val scene = arena()

        val lower = scene.at(2, 2, 2)
        val upper = scene.at(2, 3, 2)

        setBlockAt(lower, TANK)
        setBlockAt(upper, TANK)
        serverTicks(SETTLE)

        // Less than one tank holds, so breaking the other costs nothing.
        fillTank(lower, 3000)
        serverTicks(SETTLE)

        setBlockAt(upper, "minecraft:air")
        serverTicks(SETTLE)
        shot("tank_split")

        assertEquals(
            3000, tankHolds(lower).amount,
            "The tank kept ${tankHolds(lower).amount}mB of the 3000mB it held before the one above it went",
        )

        restoreHud()
    }

    @Test
    @DisplayName("What an insertion says it could not take is what it left behind")
    fun `insertion reports its remainder`(cluster: ClusterScope) = cluster.driving {
        val scene = arena()
        val chest = scene.at(2, 2, 2)

        setBlockAt(chest, "minecraft:chest")
        serverTicks(SETTLE)
        shot("insert_remainder")

        assertEquals("", remainderComplaint(chest), "The insertion did not report what it left behind")

        restoreHud()
    }

    @Test
    @DisplayName("What a fill says it took is what the tank gained")
    fun `filling reports what it took`(cluster: ClusterScope) = cluster.driving {
        val scene = arena()
        val tank = scene.at(2, 2, 2)

        setBlockAt(tank, TANK)
        serverTicks(SETTLE)
        shot("fill_reports")

        assertEquals("", fillComplaint(tank), "The fill did not report what it took")

        restoreHud()
    }

    @Test
    @DisplayName("A basin recipe takes its fluid out of the basin rather than keeping it")
    fun `a basin consumes its fluid`(cluster: ClusterScope) = cluster.driving {
        val scene = arena()

        val basin = scene.at(2, 2, 2)
        val mixer = scene.at(2, 4, 2)
        val cog = scene.at(3, 4, 2)
        val motor = scene.at(3, 5, 2)

        // Basin, a gap for the mixer's pole, the mixer, and a cogwheel beside it being turned. The
        // mixer is itself a cogwheel and takes no shaft, so power reaches it by meshing with the one
        // next to it.
        setBlockAt(basin, "create:basin")
        setBlockAt(mixer, "create:mechanical_mixer")
        setBlockAt(cog, "create:cogwheel[axis=y]")
        setBlockAt(motor, "create:creative_motor[facing=down]")
        serverTicks(SETTLE)

        // Faster than the mixer's minimum, so the only thing being timed is the recipe.
        turnTheMotorAt(motor, 64)

        // Twice what the recipe asks for, so a basin that consumed nothing and one that consumed
        // everything both read differently from one that took the right amount.
        fillTank(basin, 500)
        putInto(basin, "minecraft:dirt", 1)

        // Dirt and 250mB of water make mud.
        scene.succeedWhen(
            "basin_consumes_fluid",
            THIRTY_SECONDS,
            describe = { "the basin holds ${contentsOf(basin)} and ${tankHolds(basin)}" },
        ) {
            containerHolds(basin, "minecraft:mud") && tankHolds(basin).amount == 250
        }

        restoreHud()
    }

    /**
     * The structure these borrow is only wanted for its floor.
     *
     * Everything above it is taken away, because what each of these builds has to stand on its own --
     * a pipe left over from the structure would be another way into a tank being measured.
     */
    private suspend fun arena(): Scene {
        val scene = stage(GROUP, "hose_pulley_transfer")

        val low = scene.at(0, 2, 0)
        val high = scene.at(12, 6, 6)
        runCommand("fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} air")
        serverTicks(SETTLE)

        return scene
    }

    private suspend fun fillTank(pos: BlockPos, amount: Int) {
        server(pos, amount) { where, howMuch ->
            val handler = serverLevel.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK,
                where,
                null,
            ) ?: throw AssertionError("There is nothing at $where that holds fluid")

            net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { pour ->
                handler.insert(
                    net.neoforged.neoforge.transfer.fluid.FluidResource.of(net.minecraft.world.level.material.Fluids.WATER),
                    howMuch,
                    pour,
                )
                pour.commit()
            }

            true
        }
    }

    private suspend fun putInto(pos: BlockPos, item: String, count: Int) {
        server(pos, Wanted(item, count)) { where, wanted ->
            val handler = serverLevel.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK,
                where,
                null,
            ) ?: throw AssertionError("There is nothing at $where that holds items")

            val kind = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.Identifier.parse(wanted.item))

            net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { put ->
                net.neoforged.neoforge.transfer.ResourceHandlerUtil.insertStacking(
                    handler,
                    net.neoforged.neoforge.transfer.item.ItemResource.of(
                        net.minecraft.world.item.ItemStack(kind)
                    ),
                    wanted.count,
                    put,
                )
                put.commit()
            }

            true
        }
    }

    private suspend fun turnTheMotorAt(pos: BlockPos, rpm: Int) {
        server(pos, rpm) { where, speed ->
            val be = serverLevel.getBlockEntity(where)

            if (be !is com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) {
                throw AssertionError("There is no creative motor at $where but $be")
            }

            be.generatedSpeed.setValue(speed)
            true
        }
    }

    /**
     * A chest with room for exactly four more, offered ten.
     *
     * Most of the places that move items act on the number an insertion returns -- and a few do not,
     * and then whatever it could not take is gone. Either way the number has to be right, so it is
     * checked against a container with a known amount of room rather than through a machine.
     */
    private suspend fun remainderComplaint(chest: BlockPos): String = server(chest) { where ->
        val container = serverLevel.getBlockEntity(where) as? net.minecraft.world.Container
            ?: return@server "there is nothing at $where that holds items"

        for (slot in 0 until container.containerSize) {
            container.setItem(slot, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64))
        }
        container.setItem(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 60))

        fun total(): Int {
            var found = 0
            for (slot in 0 until container.containerSize) found += container.getItem(slot).count
            return found
        }

        val before = total()

        val handler = serverLevel.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK,
            where,
            null,
        ) ?: return@server "the chest at $where offers no item handler"

        val accepted = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { put ->
            val took = net.neoforged.neoforge.transfer.ResourceHandlerUtil.insertStacking(
                handler,
                net.neoforged.neoforge.transfer.item.ItemResource.of(
                    net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND)
                ),
                10,
                put,
            )
            put.commit()
            took
        }

        val gained = total() - before

        if (gained != 4) return@server "the chest took $gained of the ten offered, with room for four"
        if (10 - accepted != 6) {
            return@server "the insertion handed back ${10 - accepted} of the ten offered, not six"
        }

        ""
    }

    /**
     * A tank with room for exactly 200mB, offered 500mB.
     *
     * The same question as the insertion one, for fluid: several places fill a tank and ignore the
     * answer, so anything the tank would not take is gone.
     */
    private suspend fun fillComplaint(tank: BlockPos): String = server(tank) { where ->
        val handler = serverLevel.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK,
            where,
            null,
        ) ?: return@server "there is nothing at $where that holds fluid"

        var capacity = 0
        for (index in 0 until handler.size()) {
            capacity += handler.getCapacityAsInt(
                index,
                net.neoforged.neoforge.transfer.fluid.FluidResource.EMPTY,
            )
        }

        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { pour ->
            handler.insert(
                net.neoforged.neoforge.transfer.fluid.FluidResource.of(net.minecraft.world.level.material.Fluids.WATER),
                capacity - 200,
                pour,
            )
            pour.commit()
        }

        fun held(): Int {
            var found = 0
            for (index in 0 until handler.size()) found += handler.getAmountAsInt(index)
            return found
        }

        val before = held()

        val accepted = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { pour ->
            val took = handler.insert(
                net.neoforged.neoforge.transfer.fluid.FluidResource.of(net.minecraft.world.level.material.Fluids.WATER),
                500,
                pour,
            )
            pour.commit()
            took
        }

        val gained = held() - before

        if (gained != 200) return@server "the tank gained ${gained}mB of the 500mB offered, with room for 200"
        if (accepted != 200) return@server "the fill reported taking ${accepted}mB of the 500mB offered, not 200"

        ""
    }

    private companion object {

        const val GROUP = "fluids"

        const val TANK = "create:fluid_tank"

        const val SETTLE = 10
        const val THIRTY_SECONDS = 600
    }
}
