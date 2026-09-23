package com.simibubi.create.e2e

import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.MouseButton
import dev.vibeported.mc.driver.awaitNoScreen
import dev.vibeported.mc.driver.awaitScreen
import dev.vibeported.mc.driver.click
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.currentScreen
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.mouseDown
import dev.vibeported.mc.driver.mouseUp
import dev.vibeported.mc.driver.moveMouseTo
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.scroll
import dev.vibeported.mc.driver.type
import kotlinx.serialization.Serializable
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Driving Create's screens, which is the third shape a ported gametest takes.
 *
 * The originals reach a control by reflection -- `widget(context, "confirmButton")` -- and click the
 * middle of it. That names what a test means rather than counting through the widgets a screen added,
 * and it goes on meaning it when the screen gains another. The same idea survives the port, but the
 * pieces move differently: the widget itself cannot cross a wire, so what crosses is where it is.
 *
 * So each of these is two halves. A `client { }` body does the reflection on the open screen and
 * returns a Bounds -- four ints -- and then the pointer is driven to the middle of it by a second
 * call. The reflection runs on the client because that is where the screen is; nothing else could
 * work, since a screen is a client object through and through.
 *
 * The other difference is the coordinate space. A screen is laid out in its own units and the mouse
 * handlers want window pixels, so every aim converts one to the other. The originals did this too;
 * here the conversion happens on the client, inside the same body that read the bounds, because only
 * the client knows its own window.
 */

/** Where a control is, in the screen's own coordinates. */
@Serializable
internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val middleX: Int get() = x + width / 2
    val middleY: Int get() = y + height / 2
}

/** A beat between one gesture and the next, so the screen has ticked before the next thing happens. */
private const val BEAT_TICKS = 2

// -- what is open -------------------------------------------------------------------------------

/**
 * Waits for a screen of this simple class name, and says what was open instead when it never comes.
 *
 * Bounded, unlike the driver's own `awaitScreen`. A click that misses is the most ordinary failure in
 * a screen test, and left unbounded it presents as a run that has stopped doing anything for minutes
 * -- with nothing to say afterwards but a timeout. A deadline here turns that into "the screen never
 * opened; this was open instead", which names the problem.
 */
internal suspend fun Stage.waitForScreenNamed(name: String, within: Duration = 30.seconds) {
    try {
        withTimeout(within) { client(watcher, name) { wanted -> awaitScreen(wanted) } }
    } catch (timedOut: TimeoutCancellationException) {
        throw AssertionError(
            "`$name` never opened within $within -- the screen showing is ${openScreenName() ?: "none"}.\n" +
                "  ${whereThePlayerIs()}",
            timedOut,
        )
    }
}

/**
 * Where the player is standing and what the crosshair is on.
 *
 * The two questions a screen that did not open always raises, and neither is answerable from this
 * process. A click misses for one of a few reasons -- the player was moved into a block and shoved
 * somewhere unexpected, the crosshair is on air, or it is on the wrong block -- and they look
 * identical from the outside.
 */
internal suspend fun Stage.whereThePlayerIs(): String = client(watcher) {
    val player = clientPlayer ?: return@client "there is no player on this client"

    val hit = minecraft.hitResult
    val aimedAt = when (hit) {
        is net.minecraft.world.phys.BlockHitResult ->
            if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) {
                "nothing"
            } else {
                "${level.getBlockState(hit.blockPos).block.descriptionId} at ${hit.blockPos}"
            }

        else -> hit?.type?.toString() ?: "nothing"
    }

    val feet = player.blockPosition()
    "player at ${"%.2f, %.2f, %.2f".format(player.x, player.y, player.z)}" +
        " looking ${"yaw %.1f pitch %.1f".format(player.yRot, player.xRot)}" +
        " as ${minecraft.gameMode?.playerMode}" +
        " (feet in ${level.getBlockState(feet).block.descriptionId}," +
        " head in ${level.getBlockState(feet.above()).block.descriptionId}," +
        " below ${level.getBlockState(feet.below()).block.descriptionId})" +
        ", crosshair on $aimedAt"
}

internal suspend fun Stage.openScreenName(): String? = client(watcher) { currentScreen() }

internal suspend fun Stage.screenIsOpen(): Boolean = openScreenName() != null

/**
 * Closes whatever screen is showing, and does nothing at all when none is.
 *
 * Escape is only ever sent at a screen that is really there. Sent at nothing it opens the game menu
 * -- which is itself a screen, so a blind escape does not tidy anything up, it leaves the next test
 * staring at the pause menu. That is what a test with an uncertain screen has to use.
 */
internal suspend fun Stage.closeAnyScreen() {
    client(watcher) {
        repeat(ESCAPES) {
            if (minecraft.gui.screen() == null) return@client
            press(Key.ESCAPE)
            awaitTicks(BEAT_TICKS)
        }

        // Something that will not take the hint -- taken away rather than left in the way.
        if (minecraft.gui.screen() != null) {
            minecraft.gui.setScreen(null)
            awaitTicks(BEAT_TICKS)
        }
    }
}

/** How many times a screen is asked politely before it is simply removed. */
private const val ESCAPES = 3

internal suspend fun Stage.closeWithEscape() {
    client(watcher) {
        press(Key.ESCAPE)
        awaitNoScreen()
    }
}

internal suspend fun Stage.waitForNoScreen() {
    client(watcher) { awaitNoScreen() }
}

// -- finding controls ---------------------------------------------------------------------------

/**
 * Where the widget the screen keeps in this field is.
 *
 * By the name the screen calls it, as the originals did. A field that is not there, or is not a
 * widget, fails on the client with the screen's own class in the message -- which is the only place
 * that information exists.
 */
internal suspend fun Stage.widget(field: String): Bounds =
    client(watcher, field) { named -> boundsOf(readField(openScreen(), named), named) }

/** One of a list of widgets: a row of buttons, or the brush parameters. */
internal suspend fun Stage.widget(field: String, index: Int): Bounds =
    client(watcher, field, index) { named, which ->
        val held = readField(openScreen(), named)
        val widgets = held as? List<*>
            ?: throw AssertionError("$named is not a list of widgets but a ${held.javaClass}")

        if (which >= widgets.size) {
            throw AssertionError("$named holds only ${widgets.size} widgets, so there is no $which")
        }

        boundsOf(widgets[which], "$named[$which]")
    }

/** One of a grid of widgets: a list of rows, each a list of controls, as the sequencer keeps them. */
internal suspend fun Stage.widget(field: String, row: Int, column: Int): Bounds =
    client(watcher, field, row, column) { named, r, c -> boundsOf(nested(openScreen(), named, r, c), named) }

/**
 * What a scroll input is showing.
 *
 * Held against what the block was left with, so a test says the two agree rather than naming a number
 * that depends on where the control started and how far a notch moves it.
 */
internal suspend fun Stage.scrollState(field: String): Int =
    client(watcher, field) { named -> stateOf(readField(openScreen(), named), named) }

internal suspend fun Stage.scrollState(field: String, row: Int, column: Int): Int =
    client(watcher, field, row, column) { named, r, c -> stateOf(nested(openScreen(), named, r, c), named) }

/** The value of one of the screen's own numbers, such as where it decided to put itself. */
internal suspend fun Stage.screenNumber(field: String): Int =
    client(watcher, field) { named -> readField(openScreen(), named) as Int }

/** The value of one of the screen's own strings, for the parts a test reads rather than clicks. */
internal suspend fun Stage.screenText(field: String): String =
    client(watcher, field) { named -> readField(openScreen(), named).toString() }

/**
 * The class of whatever the screen keeps in this field.
 *
 * For the objects a test compares but cannot move: which kind of mirror a wand ended up on, say. The
 * object stays where it is and its name travels instead.
 */
internal suspend fun Stage.screenFieldClass(field: String): String =
    client(watcher, field) { named -> readField(openScreen(), named).javaClass.name }

// -- driving them -------------------------------------------------------------------------------

internal suspend fun Stage.clickWidget(bounds: Bounds) {
    hoverWidget(bounds)
    client(watcher) {
        click(MouseButton.LEFT)
        awaitTicks(BEAT_TICKS)
    }
}

/** Clicks the control this field holds, which is the common case in one step. */
internal suspend fun Stage.clickWidget(field: String) = clickWidget(widget(field))

/**
 * Turns the wheel over a widget, a notch at a time, since a scroll input moves one step to the notch.
 *
 * Positive is the wheel turned up, as a player would turn it. Which way that moves a given control is
 * the control's own business -- a list of options runs the other way to a number, so that in both
 * cases turning the wheel down goes down.
 */
internal suspend fun Stage.scrollWidget(bounds: Bounds, notches: Int) {
    hoverWidget(bounds)
    client(watcher, notches) { turns ->
        repeat(kotlin.math.abs(turns)) {
            scroll(kotlin.math.sign(turns.toDouble()))
            awaitTicks(BEAT_TICKS)
        }
    }
}

internal suspend fun Stage.scrollWidget(field: String, notches: Int) = scrollWidget(widget(field), notches)

internal suspend fun Stage.hoverWidget(bounds: Bounds) = hoverGui(bounds.middleX.toDouble(), bounds.middleY.toDouble())

/** Moves the pointer to a point of the screen without clicking, for a board that is dragged across. */
internal suspend fun Stage.hoverGui(x: Double, y: Double) {
    client(watcher, x, y) { gx, gy ->
        moveMouseTo(guiToWindowX(gx), guiToWindowY(gy), Duration.ZERO)
        awaitTicks(BEAT_TICKS)
    }
}

/**
 * Clicks a point of the screen rather than a widget.
 *
 * For the parts of a screen that are drawn and hit-tested by hand instead of being widgets at all --
 * the schedule's card list among them.
 */
internal suspend fun Stage.clickGui(x: Double, y: Double) {
    hoverGui(x, y)
    client(watcher) {
        click(MouseButton.LEFT)
        awaitTicks(BEAT_TICKS)
    }
}

/**
 * Right-clicks a precise spot of a block rather than its middle.
 *
 * For the blocks that keep more than one thing behind a single face: a factory gauge holds four
 * panels in one, and the middle of the face is the corner where all four meet. Aiming at the same
 * quarter twice is what puts a panel there and then opens that same one.
 */
internal suspend fun Stage.rightClickAt(point: Vec3, expected: BlockPos) {
    rightClickAt(point, Vec3(expected.x + 0.5, expected.y.toDouble(), expected.z + 3.5), expected)
}

/**
 * The same, aimed from a chosen place, for what cannot be seen from in front.
 *
 * Track lies flat along the bottom of its block, so a piece of it has to be looked down on from one
 * side. Aimed at head-on the line of sight passes over the near piece entirely and lands on the next
 * one along, which is a different piece of track and a different answer.
 */
internal suspend fun Stage.rightClickAt(point: Vec3, from: Vec3, expected: BlockPos) {
    standAt(from, point, settle = 10)
    requireLookingAt(expected)

    client(watcher) {
        // The first click after a screen closes is spent grabbing the mouse rather than reaching the
        // world, so the grab is taken here instead of costing the click.
        if (minecraft.gui.screen() == null && !minecraft.mouseHandler.isMouseGrabbed) {
            minecraft.mouseHandler.grabMouse()
            awaitTicks()
        }
        click(MouseButton.RIGHT)
        awaitTicks(BEAT_TICKS)
    }
}

/**
 * Holds the right button down on a precise spot of a block, rather than clicking it.
 *
 * How a block's value board is summoned: it appears only once the button has been held for a few
 * ticks, and what it sets is decided by where the cursor rests when the button is let go. So this
 * presses and holds, and [releaseRightClick] is what commits.
 *
 * Aimed at a point rather than at the block's middle, because the box that opens the board is drawn
 * on one face and the middle of the block is not on it.
 */
internal suspend fun Stage.holdRightClickAt(point: Vec3, expected: BlockPos) {
    standAt(Vec3(expected.x + 0.5, expected.y.toDouble(), expected.z + 3.5), point, settle = 10)
    requireLookingAt(expected)

    client(watcher) {
        // What `useBlock` does before its own click: the first click after a screen closes is spent
        // grabbing the mouse rather than reaching the world.
        if (minecraft.gui.screen() == null && !minecraft.mouseHandler.isMouseGrabbed) {
            minecraft.mouseHandler.grabMouse()
            awaitTicks()
        }
        mouseDown(MouseButton.RIGHT)
        awaitTicks(BEAT_TICKS)
    }
}

/**
 * Stands in front of a block looking at it, without clicking.
 *
 * For the screens that are opened by something other than a click on the block -- a keybind, say --
 * but which still care what the crosshair is resting on when it happens.
 */
internal suspend fun Stage.standLookingAt(pos: BlockPos) {
    standAt(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 3.5), Vec3.atCenterOf(pos), settle = 10)
    requireLookingAt(pos)
}

/**
 * Right-clicks whatever is standing at [pos] rather than the block there.
 *
 * The check is the whole of it: a seat with somebody on it and a seat with nobody on it are the same
 * block, and a click that lands on the block instead sits the player down rather than opening the
 * screen -- which fails a good deal later and says nothing about why.
 */
internal suspend fun Stage.rightClickEntityAt(pos: BlockPos) {
    standAt(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 3.5), Vec3.atCenterOf(pos), settle = 10)

    val looking = client(watcher) {
        minecraft.hitResult?.type?.name ?: "MISS"
    }
    if (looking != "ENTITY") {
        throw AssertionError(
            "The player was put in front of $pos but is looking at $looking, not an entity. " +
                whereThePlayerIs()
        )
    }

    client(watcher) {
        if (minecraft.gui.screen() == null && !minecraft.mouseHandler.isMouseGrabbed) {
            minecraft.mouseHandler.grabMouse()
            awaitTicks()
        }
        click(MouseButton.RIGHT)
        awaitTicks(BEAT_TICKS)
    }
}

/** Types into whatever the open screen is editing. */
internal suspend fun Stage.typeText(text: String) {
    client(watcher, text) { what ->
        type(what)
        awaitTicks(BEAT_TICKS)
    }
}

/** Presses a key by its GLFW code, for the bindings a test has to set up itself. */
internal suspend fun Stage.pressKeyCode(code: Int) {
    client(watcher, code) { key ->
        press(Key(key))
        awaitTicks(BEAT_TICKS)
    }
}

/** Lets go of the right button, which is what commits whatever was being dragged out. */
internal suspend fun Stage.releaseRightClick() {
    client(watcher) {
        mouseUp(MouseButton.RIGHT)
        awaitTicks(BEAT_TICKS)
    }
}

/**
 * Fails unless the crosshair is really on the block that was aimed at.
 *
 * A click that lands on the wrong block does something plausible and wrong, which is worse to debug
 * than one that lands on nothing -- so the aim is checked before the button is pressed.
 *
 * Given a moment to come true rather than sampled once. `minecraft.hitResult` is recomputed per
 * frame from wherever the player is *now*, and a player told to stand somewhere is still settling
 * for a few ticks after arriving -- so a single read taken straight after a move is a read of a
 * position that is about to change. That failed as a crosshair one block short of its target, with
 * the player's reported y differing between runs (64.23 against 64.00), and only ever under load,
 * which is exactly when those few ticks take longer to pass.
 */
internal suspend fun Stage.requireLookingAt(expected: BlockPos) {
    val looking = client(watcher, expected) { wanted ->
        var onTarget = false

        repeat(AIM_PATIENCE_TICKS) {
            val hit = minecraft.hitResult
            onTarget = hit is net.minecraft.world.phys.BlockHitResult &&
                hit.type != net.minecraft.world.phys.HitResult.Type.MISS &&
                hit.blockPos == wanted

            if (onTarget) return@repeat
            awaitTicks(1)
        }

        onTarget
    }

    if (!looking) throw AssertionError("The crosshair is not on $expected. ${whereThePlayerIs()}")
}

/**
 * How long to let an aim settle, in client ticks.
 *
 * Waited out on the client, inside one body, rather than by pumping the server. That distinction
 * cost a suite run: `waitForTicks` advances the *server*, which every client in the pool shares, and
 * a crosshair is a *client* value recomputed per frame. Polling the one through the other made every
 * aim check burn up to two seconds of everybody's server time -- and where a screen was open, so the
 * hit result never moved, it burned all of it every time. Four-minute timeouts in the screen tests
 * were the result.
 */
private const val AIM_PATIENCE_TICKS = 20

/** A point in the screen's own coordinates, as a screen's own layout reports one. */
@Serializable
internal data class Point(val x: Double, val y: Double)

/**
 * The result of asking the open screen where something of its own is.
 *
 * For the screens that lay themselves out and can say so -- a value board knows where each of its
 * steps is drawn, and dragging to a place it named beats working one out here.
 */
internal suspend fun Stage.screenPoint(method: String, a: Int, b: Int): Point =
    client(watcher, method, a, b) { named, first, second ->
        val coordinate = invokeOn(openScreen(), named, first, second)
            ?: throw AssertionError("$named returned nothing")

        Point(
            (readField(coordinate, "x") as Number).toDouble(),
            (readField(coordinate, "y") as Number).toDouble(),
        )
    }

/**
 * The gesture that opens a held item's own screen: the sneak key held down, then a right-click.
 *
 * The key has to be down for a tick or two first, since the screen only opens for a player the game
 * already considers to be sneaking rather than one who has only just pressed the key.
 */
internal suspend fun Stage.sneakRightClick() {
    client(watcher) {
        keyDown(Key(minecraft.options.keyShift.key.value))
        awaitTicks(3)
        click(MouseButton.RIGHT)
        awaitTicks(BEAT_TICKS)
        keyUp(Key(minecraft.options.keyShift.key.value))
        awaitTicks(BEAT_TICKS)
    }
}

// -- menus and their slots ----------------------------------------------------------------------

/**
 * Where a slot of the open menu is.
 *
 * A slot is not a widget: a menu screen keeps its own list and draws them itself, so they are found
 * through the menu rather than among the screen's controls.
 */
internal suspend fun Stage.slotBounds(index: Int): Bounds = client(watcher, index) { which ->
    val screen = menuScreen(openScreen())
    val slot = screen.menu.slots[which]
    Bounds(
        (readField(screen, "leftPos") as Int) + slot.x,
        (readField(screen, "topPos") as Int) + slot.y,
        16, 16,
    )
}

/** What a slot of the open menu is showing, which for a ghost slot is what it was set to. */
internal suspend fun Stage.itemInSlot(index: Int): String = client(watcher, index) { which ->
    val item = menuScreen(openScreen()).menu.slots[which].item.item
    BuiltInRegistries.ITEM.getKey(item).toString()
}

/**
 * The first slot of the open menu holding this item, so a test can say which item it means to pick up
 * rather than counting through a layout.
 */
internal suspend fun Stage.slotHolding(item: String): Int = client(watcher, item) { wanted ->
    val slots = menuScreen(openScreen()).menu.slots
    val looking = BuiltInRegistries.ITEM.getValue(Identifier.parse(wanted))

    slots.indexOfFirst { it.item.`is`(looking) }
        .takeIf { it >= 0 }
        ?: throw AssertionError("No slot on this screen is holding $wanted")
}

internal suspend fun Stage.clickSlot(index: Int) = clickWidget(slotBounds(index))

// -- the client-side half, which every body above calls ------------------------------------------

/*
 * Everything below runs on the client. They are ordinary top-level functions rather than members of
 * anything, because a lifted body may only name what it can resolve for itself on the node it lands
 * on -- and these are compiled into this module, which the client loads as a mod.
 */

/** The open screen, or a failure saying there is none. */
internal fun dev.vibeported.mc.driver.ClientScope.openScreen(): Screen =
    minecraft.gui.screen() ?: throw AssertionError("No screen is open on this client")

/** The value of a field of the screen, or of anything it inherits from. */
internal fun readField(target: Any?, fieldName: String): Any {
    if (target == null) throw AssertionError("No screen is open to look for $fieldName on")

    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val field = type.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(target)
                ?: throw AssertionError("$fieldName on ${target.javaClass.simpleName} is not set")
        } catch (lookHigher: NoSuchFieldException) {
            // Declared further up, if anywhere.
        }
        type = type.superclass
    }

    throw AssertionError("No field $fieldName on ${target.javaClass}")
}

/** The result of calling one of the target's own methods, for what is declared on a class a test cannot name. */
internal fun invokeOn(target: Any?, methodName: String, vararg arguments: Any): Any? {
    if (target == null) throw AssertionError("Nothing to call $methodName on")

    val types = arguments.map { it.javaClass }.toTypedArray()
    var type: Class<*>? = target.javaClass
    while (type != null) {
        try {
            val method = type.getDeclaredMethod(methodName, *types)
            method.isAccessible = true
            return method.invoke(target, *arguments)
        } catch (lookHigher: NoSuchMethodException) {
            // Declared further up, if anywhere.
        }
        type = type.superclass
    }

    throw AssertionError("No method $methodName on ${target.javaClass}")
}

private fun boundsOf(widget: Any?, named: String): Bounds {
    val found = widget as? AbstractWidget
        ?: throw AssertionError("$named is not a widget but a ${widget?.javaClass}")

    return Bounds(found.x, found.y, found.width, found.height)
}

private fun nested(screen: Screen, fieldName: String, row: Int, column: Int): Any {
    val held = readField(screen, fieldName)
    val rows = held as? List<*>
        ?: throw AssertionError("$fieldName is not a list of rows but a ${held.javaClass}")
    val controls = rows[row] as? List<*>
        ?: throw AssertionError("$fieldName row $row is not a list of widgets")

    return controls[column] ?: throw AssertionError("$fieldName [$row][$column] is not set")
}

private fun stateOf(control: Any?, named: String): Int =
    invokeOn(control, "getState") as? Int
        ?: throw AssertionError("$named is not a scroll input but a ${control?.javaClass}")

private fun menuScreen(screen: Screen): AbstractContainerScreen<*> =
    screen as? AbstractContainerScreen<*>
        ?: throw AssertionError("The open screen is not a menu but a $screen")

/**
 * A screen is laid out in its own units; the mouse handlers want window pixels.
 *
 * Only the client knows its own window, so the conversion happens there rather than being worked out
 * here and sent -- which would also be a reading that could go stale between the two calls.
 */
internal fun dev.vibeported.mc.driver.ClientScope.guiToWindowX(x: Double): Double =
    x * minecraft.window.screenWidth / minecraft.window.guiScaledWidth

internal fun dev.vibeported.mc.driver.ClientScope.guiToWindowY(y: Double): Double =
    y * minecraft.window.screenHeight / minecraft.window.guiScaledHeight
