package com.simibubi.create.e2e.compat

import com.simibubi.create.compat.jei.CreateJEI
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.shotFile
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.recipe.RecipeIngredientRole
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What Create's JEI categories actually show, and whether a player can find it.
 *
 * [RecipeSyncTest] proves the client has every recipe; this proves JEI shows them. The two are apart
 * on purpose, because a recipe can reach the client and still never appear: Create hands JEI its
 * recipes, and JEI keeps quiet about any it will not display.
 *
 * Browsing a category and looking an item up are different paths through JEI. A category lists
 * whatever it was given; a lookup -- R on an item for how it is made, U for what it is used in --
 * finds recipes through the ingredients each one declares in its slots. A recipe can be in the first
 * and invisible to the second, which from a player's chair is the same as not being there.
 */
@DrivesMinecraft
class JeiTest {

    @Test
    @DisplayName("JEI shows every sequenced assembly recipe, and finds each by what it makes and uses")
    fun `sequenced assembly in JEI`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val waited = waitForTicks(JEI_PATIENCE) { jeiStarted() }
        assertTrue(jeiStarted(), "JEI had not started on the client after $waited ticks")

        // In the category at all. Checked as a subset: other mods add sequenced assembly recipes of
        // their own, and this is about Create's.
        val shown = sequencedAssembly(includeHidden = false)
        val given = sequencedAssembly(includeHidden = true)
        assertTrue(
            shown.containsAll(RECIPES),
            "JEI's Recipe Sequence category is missing ${RECIPES - shown.toSet()}. " +
                "With hidden recipes included it holds: $given",
        )

        // And found the way a player finds them.
        val lost = LOOKUPS.filter { (role, item, recipe) -> recipe !in lookUp(role, item) }
            .map { (role, item, recipe) -> "${if (role == OUTPUT) "R" else "U"} on $item does not show $recipe" }
        assertEquals(emptyList<String>(), lost, "Sequenced assembly recipes JEI will not find")
    }

    @Test
    @DisplayName("JEI shows the crushing wheel's mechanical crafting recipe whole")
    fun `mechanical crafting in JEI`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val waited = waitForTicks(JEI_PATIENCE) { jeiStarted() }
        assertTrue(jeiStarted(), "JEI had not started on the client after $waited ticks")

        // Laid out as a player sees it. The picture is for a person; the slots are the check.
        val layout = layoutOf("create:mechanical_crafting", CRUSHING_WHEEL)
        println("MECHANICAL CRAFTING LAYOUT $layout")
        println("MECHANICAL CRAFTING FRAME ${shotFile("mechanical_crafting_crushing_wheel")}")
        client(watcher) { net.minecraft.client.Minecraft.getInstance().gui.setScreen(null) }

        val shown = recipesIn("create:mechanical_crafting")
        assertTrue(
            shown.containsAll(MECHANICAL_CRAFTING),
            "JEI's Mechanical Crafting category is missing ${MECHANICAL_CRAFTING - shown.toSet()}; it shows $shown",
        )
        assertEquals(
            "inputs=21 output=crushing_wheel x2", layout.substringBefore(" |"),
            "The crushing wheel's recipe is not laid out as its 21-ingredient grid making two wheels",
        )
        assertTrue(
            CRUSHING_WHEEL in lookUp(OUTPUT, "create:crushing_wheel"),
            "R on a crushing wheel does not show its mechanical crafting recipe",
        )
    }

    /**
     * The fluid a brewing recipe wants, shown as the potion it is.
     *
     * Create's automatic brewing recipes take a potion fluid, asked for by fluid and potion together.
     * 1.21.1 handed JEI that ingredient's whole stacks; the port built stacks from its fluids alone,
     * which dropped the potion, so every input showed as an uncraftable potion -- fire resistance
     * brewed from nothing rather than from an awkward potion.
     */
    @Test
    @DisplayName("JEI shows automatic brewing's input potions as the potions they are")
    fun `brewing inputs in JEI`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val waited = waitForTicks(JEI_PATIENCE) { jeiStarted() }
        assertTrue(jeiStarted(), "JEI had not started on the client after $waited ticks")

        // Every potion fluid in an input slot, and those of them with no potion; then the potions
        // the fire resistance recipes take.
        val report = client(watcher) {
            val runtime = CreateJEI.runtime!!
            val manager = runtime.recipeManager
            val type = manager.getRecipeType(Identifier.parse("create:automatic_brewing")).orElseThrow()
            @Suppress("UNCHECKED_CAST")
            val category = manager.getRecipeCategory(type) as mezz.jei.api.recipe.category.IRecipeCategory<Any>
            val focuses = runtime.jeiHelpers.focusFactory.createFocusGroup(emptyList())
            val potionFluid = com.simibubi.create.AllFluids.POTION.get()

            var inputs = 0
            var withoutPotion = 0
            val fireResistanceFrom = sortedSetOf<String>()
            for (recipe in manager.createRecipeLookup(type).get().toList()) {
                val layout = manager.createRecipeLayoutDrawable(category, recipe, focuses).orElse(null) ?: continue
                val view = layout.recipeSlotsView
                fun potionsIn(role: RecipeIngredientRole): List<String> = view.getSlotViews(role)
                    .flatMap { it.getIngredients(mezz.jei.api.neoforge.NeoForgeTypes.FLUID_STACK).toList() }
                    .filter { it.fluid.isSame(potionFluid) }
                    .map { stack ->
                        stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)
                            ?.potion()?.flatMap { it.unwrapKey() }?.map { it.identifier().toString() }?.orElse(null) ?: "none"
                    }

                val ins = potionsIn(RecipeIngredientRole.INPUT)
                inputs += ins.size
                withoutPotion += ins.count { it == "none" }
                if ("minecraft:fire_resistance" in potionsIn(RecipeIngredientRole.OUTPUT))
                    fireResistanceFrom.addAll(ins)
            }
            "$inputs|$withoutPotion|${fireResistanceFrom.joinToString(",")}"
        }.split("|")

        val (inputs, withoutPotion, fireResistanceFrom) = Triple(report[0].toInt(), report[1].toInt(), report[2])
        println("BREWING INPUTS $inputs potion fluids, $withoutPotion without a potion; fire resistance from $fireResistanceFrom")

        assertTrue(inputs > 0, "JEI's automatic brewing recipes take no potion fluids at all, so this proves nothing")
        assertEquals(0, withoutPotion, "Automatic brewing inputs that show as an uncraftable potion, of $inputs")
        assertTrue("minecraft:awkward" in fireResistanceFrom.split(","),
            "Fire resistance is not shown as brewed from an awkward potion; it is brewed from $fireResistanceFrom")
    }

    private suspend fun Stage.recipesIn(category: String): List<String> =
        client(watcher, category) { categoryId ->
            val manager = CreateJEI.runtime!!.recipeManager
            manager.createRecipeLookup(manager.getRecipeType(Identifier.parse(categoryId)).orElseThrow()).get()
                .map { (it as RecipeHolder<*>).id().identifier().toString() }
                .toList()
                .joinToString(",")
        }.split(",").filter { it.isNotBlank() }

    /**
     * Opens JEI's screen on one recipe, and describes the layout JEI built for it: how many input
     * slots hold anything, what the output slot shows, then every input slot's contents.
     */
    private suspend fun Stage.layoutOf(category: String, recipe: String): String {
        val description = client(watcher, category, recipe) { categoryId, recipeId ->
            val runtime = CreateJEI.runtime!!
            val manager = runtime.recipeManager
            @Suppress("UNCHECKED_CAST")
            val cat = manager.getRecipeCategory(manager.getRecipeType(Identifier.parse(categoryId)).orElseThrow())
                as mezz.jei.api.recipe.category.IRecipeCategory<Any>
            val holder = com.simibubi.create.foundation.recipe.ClientRecipes.byKey(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, Identifier.parse(recipeId))
            ).orElseThrow { AssertionError("The client has no recipe $recipeId") }
            runtime.recipesGui.showRecipes(cat, listOf<Any>(holder), emptyList())

            val layout = manager.createRecipeLayoutDrawable(cat, holder, runtime.jeiHelpers.focusFactory.createFocusGroup(emptyList()))
                .orElseThrow { AssertionError("JEI could not build a layout for $recipeId") }
            val inputs = layout.recipeSlotsView.getSlotViews(RecipeIngredientRole.INPUT)
                .map { slot -> slot.itemStacks.map { BuiltInRegistries.ITEM.getKey(it.item).path }.toList().distinct() }
            val filled = inputs.count { it.isNotEmpty() }
            val output = layout.recipeSlotsView.getSlotViews(RecipeIngredientRole.OUTPUT).firstOrNull()
                ?.displayedItemStack?.map { "${BuiltInRegistries.ITEM.getKey(it.item).path} x${it.count}" }?.orElse("empty")
                ?: "no output slot"
            "inputs=$filled output=$output | ${inputs.joinToString(" ") { it.firstOrNull() ?: "-" }}"
        }
        com.simibubi.create.e2e.serverTicks(20)
        return description
    }

    private suspend fun Stage.jeiStarted(): Boolean = client(watcher) {
        CreateJEI.runtime != null
    }

    /** The recipe ids in JEI's sequenced assembly category, sorted. */
    private suspend fun Stage.sequencedAssembly(includeHidden: Boolean): List<String> =
        client(watcher, includeHidden) { hidden ->
            val manager = CreateJEI.runtime!!.recipeManager
            val lookup = manager.createRecipeLookup(
                manager.getRecipeType(Identifier.fromNamespaceAndPath("create", "sequenced_assembly"))
                    .orElseThrow { AssertionError("JEI has no create:sequenced_assembly category at all") }
            )
            (if (hidden) lookup.includeHidden() else lookup).get()
                .map { (it as RecipeHolder<*>).id().identifier().toString() }
                .sorted()
                .toList()
                .joinToString(",")
        }.split(",").filter { it.isNotBlank() }

    /**
     * Every recipe, in any category, that JEI offers for [item] in [role] -- what pressing R
     * ([OUTPUT]) or U ([INPUT]) on it lists.
     */
    private suspend fun Stage.lookUp(role: String, item: String): Set<String> =
        client(watcher, role, item) { roleName, itemId ->
            val runtime = CreateJEI.runtime!!
            val stack = ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)))
            val focus = runtime.jeiHelpers.focusFactory
                .createFocus(RecipeIngredientRole.valueOf(roleName), VanillaTypes.ITEM_STACK, stack)
            val manager = runtime.recipeManager

            manager.createRecipeCategoryLookup().limitFocus(listOf(focus)).get()
                .flatMap { category -> manager.createRecipeLookup(category.recipeType).limitFocus(listOf(focus)).get() }
                .map { recipe -> (recipe as? RecipeHolder<*>)?.id()?.identifier()?.toString() ?: "" }
                .toList()
                .joinToString(",")
        }.split(",").filter { it.isNotBlank() }.toSet()

    private companion object {
        /** JEI starts once the client has its recipes and tags, which is some seconds after joining. */
        const val JEI_PATIENCE = 600


        const val OUTPUT = "OUTPUT"
        const val INPUT = "INPUT"

        const val PRECISION = "create:sequenced_assembly/precision_mechanism"
        const val STURDY = "create:sequenced_assembly/sturdy_sheet"
        const val TRACK = "create:sequenced_assembly/track"

        val RECIPES = listOf(PRECISION, STURDY, TRACK)

        const val CRUSHING_WHEEL = "create:mechanical_crafting/crushing_wheel"

        val MECHANICAL_CRAFTING = listOf("crushing_wheel", "extendo_grip", "potato_cannon", "wand_of_symmetry")
            .map { "create:mechanical_crafting/$it" }

        /**
         * Role, item, and the recipe that lookup must include.
         *
         * The three recipes differ in the ways that decide how their ingredients reach JEI: the
         * precision mechanism loops five times, so its later steps' ingredients are declared as
         * invisible inputs; the sturdy sheet's first step takes a fluid; the track's steps take a
         * compound ingredient.
         */
        val LOOKUPS = listOf(
            Triple(OUTPUT, "create:precision_mechanism", PRECISION),
            Triple(OUTPUT, "create:sturdy_sheet", STURDY),
            Triple(OUTPUT, "create:track", TRACK),

            Triple(INPUT, "create:golden_sheet", PRECISION),
            Triple(INPUT, "create:powdered_obsidian", STURDY),
            Triple(INPUT, "minecraft:stone_slab", TRACK),

            // Consumed by a step rather than the assembly itself.
            Triple(INPUT, "create:cogwheel", PRECISION),
            Triple(INPUT, "minecraft:iron_nugget", PRECISION),
            Triple(INPUT, "minecraft:iron_nugget", TRACK),
        )
    }
}
