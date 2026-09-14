package com.simibubi.create.e2e.compat

import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.shotFile
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create's JEI plugin, loaded into EMI by TooManyRecipeViewers.
 *
 * Only run with `-Ptmrv=true`, which takes JEI out of the game and puts EMI and TMRV in. TMRV answers
 * to JEI's mod id and carries JEI's API, so nothing in Create knows the difference -- which is exactly
 * what is being checked: that the categories Create registers with JEI, and the recipes it puts in
 * them, come out the other side as EMI categories and recipes a player can look up.
 *
 * TMRV gives the recipes it makes ids of its own, so they are found the way a player finds them: by
 * category, and by what they make.
 */
@DrivesMinecraft
class TmrvTest {

    @Test
    @DisplayName("Create's JEI categories and recipes show up in EMI through TMRV")
    fun `create in emi through tmrv`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val waited = waitForTicks(EMI_PATIENCE) { emiLoaded() }
        assertTrue(emiLoaded(), "EMI had not finished loading recipes on the client after $waited ticks")

        assertEquals("loaded", modsPresent(), "EMI and TMRV are not both loaded, so this proves nothing")

        val categories = categoryIds()
        val missingCategories = CATEGORIES.filter { it !in categories }
        println("TMRV CATEGORIES ${categories.filter { it.startsWith("create:") }}")
        assertEquals(emptyList<String>(), missingCategories, "Create's JEI categories missing from EMI; EMI has $categories")

        val empty = CATEGORIES.filter { recipeCount(it) == 0 }
        assertEquals(emptyList<String>(), empty, "Create's JEI categories that reached EMI with no recipes in them")

        val lost = OUTPUTS.filter { (item, category) -> category !in categoriesMaking(item) }
            .map { (item, category) -> "$item has no $category recipe" }
        assertEquals(emptyList<String>(), lost, "Recipes EMI cannot find by what they make")

        println("TMRV FRAME ${shotFile("tmrv_emi")}")
    }

    /**
     * The recipe screens themselves, for a person to look at.
     *
     * Create's categories draw through JEI's API -- animated machines, slot backgrounds, tooltips --
     * and TMRV draws that into EMI's screen with 26.2's GUI render state, so what this checks is only
     * that each screen opens with recipes in it. The frames are the evidence that they draw.
     */
    @Test
    @DisplayName("Create's recipe screens open in EMI through TMRV")
    fun `recipe screens`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val waited = waitForTicks(EMI_PATIENCE) { emiLoaded() }
        assertTrue(emiLoaded(), "EMI had not finished loading recipes on the client after $waited ticks")

        for (category in SCREENS) {
            val opened = client(watcher, category) { id ->
                val manager = dev.emi.emi.api.EmiApi.getRecipeManager()
                val emiCategory = manager.categories.firstOrNull { it.id.toString() == id }
                if (emiCategory == null) "no category"
                else {
                    dev.emi.emi.api.EmiApi.displayRecipeCategory(emiCategory)
                    net.minecraft.client.Minecraft.getInstance().gui.screen()?.javaClass?.simpleName ?: "no screen"
                }
            }
            com.simibubi.create.e2e.serverTicks(30)
            println("TMRV SCREEN $category -> $opened ${shotFile("tmrv_" + category.replace(':', '_'))}")
            assertEquals("RecipeScreen", opened, "EMI did not open a recipe screen for $category")
        }
        client(watcher) { net.minecraft.client.Minecraft.getInstance().gui.setScreen(null) }
    }

    private suspend fun Stage.emiLoaded(): Boolean = client(watcher) {
        dev.emi.emi.runtime.EmiReloadManager.isLoaded()
    }

    private suspend fun Stage.modsPresent(): String = client(watcher) {
        val mods = net.neoforged.fml.ModList.get()
        listOf("emi", "toomanyrecipeviewers", "jei").filterNot { mods.isLoaded(it) }
            .let { if (it.isEmpty()) "loaded" else "missing $it" }
    }

    private suspend fun Stage.categoryIds(): List<String> = client(watcher) {
        dev.emi.emi.api.EmiApi.getRecipeManager().categories.joinToString(",") { it.id.toString() }
    }.split(",").filter { it.isNotBlank() }

    private suspend fun Stage.recipeCount(category: String): Int = client(watcher, category) { id ->
        val manager = dev.emi.emi.api.EmiApi.getRecipeManager()
        manager.categories.firstOrNull { it.id.toString() == id }?.let { manager.getRecipes(it).size } ?: 0
    }

    /** The categories of every recipe EMI lists as making [item]. */
    private suspend fun Stage.categoriesMaking(item: String): Set<String> = client(watcher, item) { id ->
        val stack = net.minecraft.world.item.ItemStack(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)))
        dev.emi.emi.api.EmiApi.getRecipeManager().getRecipesByOutput(dev.emi.emi.api.stack.EmiStack.of(stack))
            .joinToString(",") { it.category.id.toString() }
    }.split(",").filter { it.isNotBlank() }.toSet()

    private companion object {
        /** EMI loads once the client has its recipes and tags, and TMRV runs JEI's plugins inside that. */
        const val EMI_PATIENCE = 1200

        val CATEGORIES = listOf(
            "create:crushing",
            "create:milling",
            "create:mixing",
            "create:pressing",
            "create:mechanical_crafting",
            "create:sequenced_assembly",
            "create:spout_filling",
            "create:deploying",
        )

        /** Categories whose screens are opened and photographed: machines, a grid, a sequence, fluids. */
        val SCREENS = listOf("create:mixing", "create:mechanical_crafting", "create:sequenced_assembly", "create:spout_filling")

        /** Item, and a category that must be among those making it. */
        val OUTPUTS = listOf(
            "create:crushing_wheel" to "create:mechanical_crafting",
            "create:precision_mechanism" to "create:sequenced_assembly",
            "create:andesite_alloy" to "create:mixing",
            "create:iron_sheet" to "create:pressing",
        )
    }
}
