package com.simibubi.create.e2e.transportation

import com.simibubi.create.AllItems
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget.ChainConveyorFrogportTarget
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity
import com.simibubi.create.content.logistics.packager.PackagingRequest
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateFrom
import com.simibubi.create.e2e.waitForTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import org.apache.commons.lang3.mutable.MutableBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * A crate of andesite alloy sent from one chest to another by Create's package logistics.
 *
 * The route is the one the mod is built around: a packager empties the first chest into an addressed
 * cardboard package, the frogport above it throws the package onto a chain conveyor, the chain
 * carries it to the far post, the frogport there catches it because the address matches its filter,
 * and a hopper drops it into a second packager that unwraps it into the second chest.
 *
 * Everything a player would do with a wrench and a coil of chain is done here by hand, which is
 * worth knowing about if this ever breaks: the two posts are strung together by putting each one's
 * position into the other's connection set, and each frogport is told which post it hangs from.
 *
 * Ported from Create's `LogisticsTest` client gametest. All of that hand-wiring lives in one server
 * body, because that is what it was already: one block of code touching live block entities on the
 * server thread. What the port changes is only how it gets there.
 */
@DrivesMinecraft
class LogisticsTest {

    @Test
    @DisplayName("A chain conveyor carries a package from one chest to another")
    fun `chest to chest`(cluster: ClusterScope) = cluster.driving {
        watchFromTheSide()

        // Sending: chest, packager, frogport, post. The packager looks up, so it takes from the
        // chest below it and hands what it packs to the frogport above.
        setBlock(SEND, "minecraft:chest")
        setBlock(SEND.above(1), "create:packager[facing=up]")
        setBlock(SEND.above(2), "create:package_frogport")
        setBlock(SEND.above(3), "create:chain_conveyor")
        setBlock(SEND.above(4), "create:creative_motor[facing=down]")

        // Receiving: the same in reverse, with a hopper between the port and the packager. Nothing
        // in a frogport pushes what it catches anywhere, and a packager only unwraps what is put
        // into it, so something has to carry the package the one block between them. A chute looks
        // like the Create answer but does not pull from a frogport; a hopper does.
        setBlock(RECEIVE, "minecraft:chest")
        setBlock(RECEIVE.above(1), "create:packager[facing=up]")
        setBlock(RECEIVE.above(2), "minecraft:hopper[facing=down]")
        setBlock(RECEIVE.above(3), "create:package_frogport")
        setBlock(RECEIVE.above(4), "create:chain_conveyor")

        runCommand(
            "item replace block ${SEND.x} ${SEND.y} ${SEND.z} container.0 with create:andesite_alloy $COUNT"
        )

        stringThePostsTogether()

        // Long enough for the posts to tell each other which addresses they can reach.
        serverTicks(60)
        shot("logistics_before")

        sendThePackage()

        serverTicks(100)
        shot("logistics_in_transit")

        val waited = 100 + waitForTicks(PATIENCE_TICKS - 100) { delivered() > 0 }

        shot("logistics_after")
        restoreHud()

        assertEquals(
            COUNT, delivered(),
            "The andesite alloy never arrived in the far chest after $waited ticks",
        )
        assertEquals(0, remaining(), "The near chest should have been emptied into the package")
    }

    /**
     * Joins the two posts and points each frogport at the one above it.
     *
     * One call, not several: these are live block entities that have to be changed together and then
     * told about it at once, and every intermediate state is one the server would happily tick.
     */
    private suspend fun stringThePostsTogether() {
        server(SEND.above(3), RECEIVE.above(4), SEND.above(2), RECEIVE.above(3), ADDRESS) {
            postA, postB, sender, receiver, address ->

            val a = serverLevel.getBlockEntity(postA) as ChainConveyorBlockEntity
            val b = serverLevel.getBlockEntity(postB) as ChainConveyorBlockEntity
            a.connections.add(postB.subtract(postA))
            b.connections.add(postA.subtract(postB))

            // The length and angle of each connection is worked out once and cached, and that
            // happened before these connections existed, so it has to be thrown away and done again.
            a.connectionStats = null
            b.connectionStats = null
            a.prepareStats()
            b.prepareStats()
            a.notifyUpdate()
            b.notifyUpdate()

            // Each frogport hangs on the ring around the post directly above it.
            (serverLevel.getBlockEntity(sender) as PackagePortBlockEntity).target =
                ChainConveyorFrogportTarget(BlockPos(0, 1, 0), 0f, Optional.empty<BlockPos>(), false)

            val port = serverLevel.getBlockEntity(receiver) as PackagePortBlockEntity
            port.target = ChainConveyorFrogportTarget(BlockPos(0, 1, 0), 0f, Optional.empty<BlockPos>(), false)
            port.addressFilter = address
            port.filterChanged()
        }
    }

    /** What an order from a stock keeper eventually becomes. */
    private suspend fun sendThePackage() {
        server(SEND.above(1), ADDRESS, COUNT) { pos, address, count ->
            val packager = serverLevel.getBlockEntity(pos) as PackagerBlockEntity

            // The packager takes requests off the list as it fills them, so it has to be a list it
            // can modify.
            packager.attemptToSend(
                mutableListOf(
                    PackagingRequest.create(
                        AllItems.ANDESITE_ALLOY.asStack(count), count, address, 0,
                        MutableBoolean(true), 0, 0, null,
                    )
                )
            )
        }
    }

    /** How much andesite alloy is sitting in the far chest. */
    private suspend fun delivered(): Int = server(RECEIVE) { pos -> countIn(serverLevel, pos) }

    private suspend fun remaining(): Int = server(SEND) { pos -> countIn(serverLevel, pos) }

    private companion object {

        const val ZONE = Zones.LOGISTICS

        /** The chest at each end. Everything else is stacked above it. */
        val SEND: BlockPos = BlockPos(0, -60, ZONE)
        val RECEIVE: BlockPos = BlockPos(8, -61, ZONE)

        const val ADDRESS = "warehouse"
        const val COUNT = 32

        /** Roughly a minute, which is far longer than the journey takes. */
        const val PATIENCE_TICKS = 600

        /**
         * Counted straight off the chest rather than through an item handler, because this runs on
         * the server thread while it is busy and opening a transaction there is not allowed.
         */
        fun countIn(level: ServerLevel, pos: BlockPos): Int {
            val container = level.getBlockEntity(pos) as? Container ?: return 0

            var found = 0
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (AllItems.ANDESITE_ALLOY.isIn(stack)) found += stack.count
            }
            return found
        }

        /**
         * Stands the camera off to the south so both posts, the chain slung between them and the
         * chests underneath are all in frame.
         */
        suspend fun watchFromTheSide() {
            val centreX = (SEND.x + RECEIVE.x) / 2.0 + 0.5
            val centreY = RECEIVE.y + 2.5
            val centreZ = SEND.z + 0.5

            val eyeX = centreX
            val eyeY = centreY + 4
            val eyeZ = centreZ + 13

            val toY = centreY - eyeY
            val toZ = centreZ - eyeZ

            // Looking north, since the camera stands to the south of everything.
            spectateFrom(
                eyeX, eyeY, eyeZ,
                yaw = 180.0,
                pitch = Math.toDegrees(-Math.atan2(toY, Math.abs(toZ))),
            )
        }
    }
}
