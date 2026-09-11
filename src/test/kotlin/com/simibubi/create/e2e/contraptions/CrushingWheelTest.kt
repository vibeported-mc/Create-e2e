package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.dropItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.waitForTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A pair of crushing wheels taking in an item thrown onto them.
 *
 * The item is dropped rather than fed. Every other crushing test in the suite feeds its wheels with
 * belts, belt funnels and chutes, and all of those insert through the controller's item capability
 * -- which never goes near the path a dropped item or a mob takes, `entityInside` on the controller
 * block. That path is how a player uses a crusher by hand, and it had no coverage at all.
 *
 * Cobblestone because it has no crushing recipe of its own: the controller falls back to milling for
 * it and makes gravel, so a pass also covers that fallback.
 *
 * What this caught: the controller was registered `air()`, as it was in 1.21.1, and 26.2's
 * `Entity.checkInsideBlocks` skips air blocks before asking them anything. The item landed in the gap,
 * sat on the controller's collision surface in exactly the right place, and was never offered to it.
 * Every belt-fed test passed throughout, because none of them go through `entityInside`.
 */
@DrivesMinecraft
class CrushingWheelTest {

    @Test
    @DisplayName("Crushing wheels take in an item dropped onto them and crush it")
    fun `takes a dropped item`(cluster: ClusterScope) = cluster.stage {
        theClient()

        clearGround(GAP, 5)
        watchTheWheels()

        // Two wheels on the same axis, exactly two apart: the wheels themselves make the controller
        // in the gap between them, once they are turning in opposite directions.
        setBlock(WHEEL_A, "create:crushing_wheel[axis=x]")
        setBlock(WHEEL_B, "create:crushing_wheel[axis=x]")
        setBlock(MOTOR_A, "create:creative_motor[facing=east]")
        setBlock(MOTOR_B, "create:creative_motor[facing=east]")
        serverTicks(SETTLE_TICKS)

        // Opposite speeds, so the wheels counter-rotate. Same magnitude, or the controller has no
        // single speed to crush at.
        server(MOTOR_A, MOTOR_B, WHEEL_RPM) { a, b, rpm ->
            (serverLevel.getBlockEntity(a) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
            (serverLevel.getBlockEntity(b) as CreativeMotorBlockEntity).generatedSpeed.setValue(-rpm)
        }
        serverTicks(SETTLE_TICKS)

        // Checked first and separately: a crusher that never formed would also fail the assertion
        // this test exists for, and would say nothing true about intake.
        assertEquals(
            "valid controller", controllerState(),
            "The wheels did not form a working controller in the gap between them",
        )
        // And turning. The controller takes nothing at a crushing speed of zero, which it learns
        // from the wheels rather than working out for itself -- so a controller can be valid and
        // still refuse every entity that touches it.
        assertTrue(
            crushingSpeed() > 0f,
            "The controller formed but was never told a crushing speed (it is ${crushingSpeed()}), " +
                "so it refuses everything",
        )

        shot("crusher_before")

        dropItem(GAP.x + 0.5, GAP.y + 1.5, GAP.z + 0.5, INPUT)

        val waited = waitForTicks(PATIENCE_TICKS) { itemsAround().contains(OUTPUT) }
        val around = itemsAround()

        shot("crusher_after")
        restoreHud()

        assertTrue(
            around.contains(OUTPUT),
            "No $OUTPUT came out of the crusher after $waited ticks. " +
                "Item entities around the wheels: ${around.ifEmpty { "none" }}",
        )
    }

    /** What stands in the gap, reduced to the one question that matters about it. */
    private suspend fun Stage.controllerState(): String = server(GAP) { gap ->
        val state = serverLevel.getBlockState(gap)
        when {
            state.block !is CrushingWheelControllerBlock ->
                "no controller, found ${BuiltInRegistries.BLOCK.getKey(state.block)}"
            !state.getValue(CrushingWheelControllerBlock.VALID) -> "invalid controller"
            else -> "valid controller"
        }
    }

    private suspend fun Stage.crushingSpeed(): Float = server(GAP) { gap ->
        (serverLevel.getBlockEntity(gap) as? CrushingWheelControllerBlockEntity)?.crushingspeed ?: -1f
    }

    /**
     * Every item entity near the wheels, as `id x count at dy above the gap's floor, on ground`.
     *
     * Reported whole on failure rather than as a yes or no, because the difference between "the
     * cobblestone is still lying on the wheels" and "nothing is there at all" is the difference
     * between intake being broken and output being broken. The height says which surface it is
     * lying on: the controller's collision shape, the top of a wheel, or neither.
     */
    private suspend fun Stage.itemsAround(): String = server(GAP, SEARCH_RADIUS) { gap, radius ->
        serverLevel.getEntitiesOfClass(ItemEntity::class.java, AABB(gap).inflate(radius))
            .joinToString(", ") { entity ->
                val stack = entity.item
                "${BuiltInRegistries.ITEM.getKey(stack.item)} x${stack.count} " +
                    "at dy=%.3f dx=%.3f dz=%.3f onGround=%s".format(
                        java.util.Locale.ROOT,
                        entity.y - gap.y, entity.x - gap.x - 0.5, entity.z - gap.z - 0.5,
                        entity.onGround(),
                    )
            }
    }

    private suspend fun Stage.watchTheWheels() {
        spectateAt(
            Vec3(GAP.x + 4.0, GAP.y + 3.0, GAP.z + 0.5),
            Vec3.atCenterOf(GAP),
        )
    }

    private companion object {
        const val SETTLE_TICKS = 20

        const val WHEEL_RPM = 64

        /** A milling recipe takes 250 units of processing time; this is several times that. */
        const val PATIENCE_TICKS = 400

        const val SEARCH_RADIUS = 4.0

        const val INPUT = "minecraft:cobblestone"
        const val OUTPUT = "minecraft:gravel"

        /** Two up, so what comes out of the bottom of the gap has somewhere to fall. */
        val Stage.GAP: BlockPos get() = at(0, 2, 1)

        val Stage.WHEEL_A: BlockPos get() = GAP.north()
        val Stage.WHEEL_B: BlockPos get() = GAP.south()

        /** One each, driving the wheel along its own axis from the west. */
        val Stage.MOTOR_A: BlockPos get() = WHEEL_A.west()
        val Stage.MOTOR_B: BlockPos get() = WHEEL_B.west()
    }
}
