package com.simibubi.create.e2e.trains

import com.simibubi.create.AllDataComponents
import com.simibubi.create.content.trains.schedule.Schedule
import com.simibubi.create.e2e.ALEX
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.clickGui
import com.simibubi.create.e2e.clickWidget
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.openScreen
import com.simibubi.create.e2e.readField
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.screenNumber
import com.simibubi.create.e2e.scrollWidget
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.widget
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The train schedule screen, opened and worked the way a player opens and works it.
 *
 * A right-click on a held schedule, and then the controls clicked where they are on screen. Unlike
 * the worldshaper this one is a menu, so the screen only arrives after the server has been asked for
 * it, and what it edits only reaches the item once the container is closed again.
 *
 * The schedule's card list is drawn and hit-tested by hand rather than built out of widgets, so
 * adding an entry means clicking the place the screen looks for a click, not a button.
 *
 * Ported from Create's `ScheduleScreenTest`.
 */
@DrivesMinecraft
class ScheduleScreenTest {

    @Test
    @DisplayName("Looping is turned off, saved, read back, and turned on again")
    fun `carries the loop setting both ways`(cluster: ClusterScope) = cluster.driving {
        giveSchedule()
        openIt()
        shot("schedule_opened")

        // A schedule with nothing in it is not a schedule: the server takes an empty one back off
        // the item rather than storing it. So there has to be somewhere to go before looping means
        // anything.
        addDestinationEntry()

        // A schedule loops to begin with, so the first click turns looping off.
        val loopedToBeginWith = shownSchedule().cyclic
        clickWidget("cyclicButton")
        val loopedAfterFirstClick = shownSchedule().cyclic
        shot("schedule_loop_turned_off")

        assertNotEquals(
            loopedToBeginWith, loopedAfterFirstClick,
            "The first click on the loop button did not change what the screen shows",
        )

        close()

        val afterFirstClose = savedSchedule()
        assertTrue(afterFirstClose.present, "Closing the screen did not write a schedule onto the item")
        assertEquals(1, afterFirstClose.entries, "The entry the screen showed did not reach the item")
        assertEquals(
            loopedAfterFirstClick, afterFirstClose.cyclic,
            "Turning looping off was not what the item was left with",
        )

        // Opening it again is the other half of the round trip: what was saved has to come back up.
        openIt()
        shot("schedule_reopened")

        assertEquals(
            afterFirstClose.cyclic, shownSchedule().cyclic,
            "Reopening the schedule did not show the loop setting that was saved on it",
        )
        assertEquals(
            1, shownSchedule().entries,
            "Reopening the schedule did not show the entry that was saved on it",
        )

        clickWidget("cyclicButton")
        val loopedAfterSecondClick = shownSchedule().cyclic
        shot("schedule_loop_turned_on")

        close()

        val afterSecondClose = savedSchedule()
        assertTrue(afterSecondClose.present, "Closing the screen again did not write a schedule onto the item")
        assertEquals(
            loopedAfterSecondClick, afterSecondClose.cyclic,
            "Turning looping back on was not what the item was left with",
        )
        assertNotEquals(
            afterFirstClose.cyclic, afterSecondClose.cyclic,
            "The loop setting on the item came out the same both times round",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructionTypes")
    @DisplayName("Every instruction the editor offers can be picked and lands on the schedule")
    fun `picks every instruction`(id: String, cluster: ClusterScope) = cluster.driving {
        val wanted = instructionTypes().indexOf(id)

        giveSchedule()
        openIt()

        clickInTheCardList(ADD_ENTRY_X, ADD_ENTRY_Y)

        // The editor opens on the first instruction, and the list runs the other way to the wheel, so
        // reaching the one wanted means turning the wheel down that many times.
        if (wanted > 0) scrollWidget(widget("scrollInput"), -wanted)

        shot("schedule_instruction_${id.replace(':', '_')}")

        clickWidget("editorConfirm")
        serverTicks(SETTLE_TICKS)

        val shown = shownSchedule()
        assertEquals(1, shown.entries, "Confirming the editor did not add an entry to the schedule")
        assertEquals(
            id, shown.firstInstruction,
            "The editor did not leave the entry on the instruction that was picked",
        )

        close()

        val saved = savedSchedule()
        assertTrue(saved.present, "Closing the screen did not write a schedule onto the item")
        assertEquals(
            id, saved.firstInstruction,
            "The instruction the screen showed did not reach the item",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conditionTypes")
    @DisplayName("Every wait condition the editor offers can be picked and lands on the schedule")
    fun `picks every condition`(id: String, cluster: ClusterScope) = cluster.driving {
        val wanted = conditionTypes().indexOf(id)

        giveSchedule()
        openIt()

        // A new entry comes with one wait condition already on it, which is the one to edit.
        addDestinationEntry()

        clickInTheCardList(FIRST_CONDITION_X, FIRST_CONDITION_Y)

        if (wanted > 0) scrollWidget(widget("scrollInput"), -wanted)

        shot("schedule_condition_${id.replace(':', '_')}")

        clickWidget("editorConfirm")
        serverTicks(SETTLE_TICKS)

        assertEquals(
            id, shownSchedule().firstCondition,
            "The editor did not leave the entry on the condition that was picked",
        )

        close()

        val saved = savedSchedule()
        assertTrue(saved.present, "Closing the screen did not write a schedule onto the item")
        assertEquals(id, saved.firstCondition, "The condition the screen showed did not reach the item")
    }

    @Test
    @DisplayName("A destination can be added to a schedule and is written onto the item")
    fun `adds an entry`(cluster: ClusterScope) = cluster.driving {
        giveSchedule()
        openIt()

        shot("schedule_before_entry")
        addDestinationEntry()
        shot("schedule_entry_added")

        assertEquals(
            1, shownSchedule().entries,
            "Confirming the editor did not add an entry to the schedule",
        )

        close()

        val saved = savedSchedule()
        assertTrue(saved.present, "Closing the screen did not write a schedule onto the item")
        assertEquals(1, saved.entries, "The entry the screen showed did not reach the item")
    }

    /** An empty schedule in the player's hand, ready to be right-clicked. */
    private suspend fun giveSchedule() {
        // Ground of its own and a look at open sky. A schedule opens on a plain right-click at
        // whatever the crosshair is on, so a player left in front of somebody else's scene opens
        // that scene's screen instead of this one.
        clearGround(here(), 4)
        standAt(
            Vec3(here().x + 0.5, here().y.toDouble(), here().z + 0.5),
            Vec3(here().x + 0.5, here().y + 30.0, here().z + 0.5),
        )

        holdItem("create:schedule")
        serverTicks(SETTLE_TICKS)
    }

    /**
     * Right-clicks the held schedule, which asks the server for the menu behind the screen.
     *
     * No sneaking for this one: a schedule opens on a plain right-click and does nothing on a
     * sneaking one.
     */
    private suspend fun openIt() {
        rightClickAhead()
        waitForScreenNamed("ScheduleScreen")
    }

    /** Accepts the screen, which closes the container and is what sends the schedule to the server. */
    private suspend fun close() {
        clickWidget("confirmButton")
        waitForNoScreen()
        serverTicks(SETTLE_TICKS)
        restoreHud()
    }

    /**
     * Adds one destination to the open schedule, the way the screen offers it: a click on the spot
     * it watches for one opens an editor on a new destination, and accepting that editor makes it an
     * entry.
     */
    private suspend fun addDestinationEntry() {
        clickInTheCardList(ADD_ENTRY_X, ADD_ENTRY_Y)
        shot("schedule_editing_entry")

        clickWidget("editorConfirm")
        serverTicks(SETTLE_TICKS)
    }

    /**
     * Clicks a spot of the card list, which is drawn and hit-tested by hand rather than built out of
     * widgets -- so this is a click at a place, not a click on a button. The offsets are from the
     * screen's own corner, which is where the list is laid out from.
     */
    private suspend fun clickInTheCardList(x: Int, y: Int) {
        clickGui(
            (screenNumber("leftPos") + x).toDouble(),
            (screenNumber("topPos") + y).toDouble(),
        )
        serverTicks(SETTLE_TICKS)
    }

    /**
     * The schedule the screen that is open right now is editing.
     *
     * Asked of whatever screen is open rather than of one remembered from earlier, since taking a
     * picture resizes the window and a screen laid out again is not always the same object.
     */
    private suspend fun shownSchedule(): Summary = client(ALEX) {
        summarise(readField(openScreen(), "schedule") as Schedule)
    }

    /** The schedule written onto the held item, read on the server where the item really lives. */
    private suspend fun savedSchedule(): Summary = server(ALEX) { name ->
        val player = playerNamed(name)
        val tag = player.mainHandItem.get(AllDataComponents.TRAIN_SCHEDULE)
            ?: return@server Summary(present = false)

        summarise(Schedule.fromTag(player.registryAccess(), tag))
    }

    /**
     * As much of a schedule as an assertion here needs, and no more.
     *
     * A `Schedule` is a live object of instructions and conditions and cannot cross a wire, so what
     * crosses is what is being asked about: whether there is one at all, whether it loops, how many
     * places it goes, and what the first of them says.
     */
    @Serializable
    data class Summary(
        val present: Boolean,
        val cyclic: Boolean = false,
        val entries: Int = 0,
        val firstInstruction: String? = null,
        val firstCondition: String? = null,
    )

    private fun here() = BlockPos(60, -58, ZONE)

    companion object {

        const val ZONE = Zones.SCHEDULE

        /** Long enough for the server to be asked for a menu and to answer. */
        const val SETTLE_TICKS = 10

        /**
         * Where the screen watches for a click on "add entry", in its own coordinates: the card list
         * starts 25 in from each corner of the window, and the button sits at the top left of it.
         */
        const val ADD_ENTRY_X = 25 + 26
        const val ADD_ENTRY_Y = 25 + 7

        /**
         * Where the first condition of the first entry sits, in the same coordinates.
         *
         * The screen takes a click in the card list, moves it into the card, and then moves it again
         * into the condition area by 26 across and 28 down before dividing by the row height. So this
         * is a point inside the first row of the first column of the first card.
         */
        const val FIRST_CONDITION_X = 25 + 46
        const val FIRST_CONDITION_Y = 25 + 37

        /** Every kind of instruction the editor offers, named by what it is called in the schedule. */
        @JvmStatic
        fun instructionTypes(): List<String> =
            Schedule.INSTRUCTION_TYPES.map { it.first.toString() }

        /** Every kind of wait condition the editor offers. */
        @JvmStatic
        fun conditionTypes(): List<String> =
            Schedule.CONDITION_TYPES.map { it.first.toString() }

        /**
         * The parts of a schedule the assertions ask about.
         *
         * Called on whichever side is holding the real thing -- the client for the open screen, the
         * server for the item -- so it lives out here rather than on the test object, which does not
         * travel with either.
         */
        fun summarise(schedule: Schedule): Summary = Summary(
            present = true,
            cyclic = schedule.cyclic,
            entries = schedule.entries.size,
            firstInstruction = schedule.entries.firstOrNull()?.instruction?.id?.toString(),
            firstCondition = schedule.entries.firstOrNull()
                ?.conditions?.firstOrNull()?.firstOrNull()?.id?.toString(),
        )
    }
}
