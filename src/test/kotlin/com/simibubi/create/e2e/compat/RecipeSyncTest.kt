package com.simibubi.create.e2e.compat

import com.simibubi.create.AllRecipeTypes
import com.simibubi.create.foundation.recipe.ClientRecipes
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeInput
import net.minecraft.world.item.crafting.RecipeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every recipe the server loaded reaches the client.
 *
 * 26.2 stopped sending recipes to clients; Create asks for its own types back on datapack sync, and
 * the client keeps what arrives in `ClientRecipes`. Everything that looks recipes up on the client
 * reads from there -- JEI above all, but also the blueprint overlay and the factory panel's crafting
 * preview. A recipe that loads on the server and never arrives is invisible in JEI and says nothing
 * about why: no error, just a category one page shorter than it should be.
 *
 * Compared as ids, per type, so a failure names the exact recipes that were lost on the way.
 */
@DrivesMinecraft
class RecipeSyncTest {

    @Test
    @DisplayName("The client is sent every recipe of every type Create syncs")
    fun `client has what the server has`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val onServer = serverRecipes()
        val onClient = clientRecipes()

        // Not vacuous: two empty sets agree perfectly. These are known to exist in Create's data, and
        // sequenced assembly is where recipes were first seen going missing in JEI.
        SEQUENCED_ASSEMBLY.forEach { recipe ->
            assertTrue(recipe in onServer, "The server did not load $recipe at all, so this compares nothing")
        }

        val missing = (onServer - onClient).sorted()
        val extra = (onClient - onServer).sorted()

        assertEquals(
            emptyList<String>(), missing,
            "${missing.size} of ${onServer.size} recipes loaded on the server never reached the client",
        )
        assertEquals(emptyList<String>(), extra, "The client holds recipes the server does not have")
    }

    /** `type | id` for every recipe of every synced type, as the server loaded them. */
    private suspend fun Stage.serverRecipes(): Set<String> = server {
        val recipes = serverLevel.server.recipeManager.recipeMap()
        AllRecipeTypes.syncedTypes().flatMap { type ->
            val typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type)
            @Suppress("UNCHECKED_CAST")
            recipes.byType(type as RecipeType<Recipe<RecipeInput>>).map { "$typeId | ${it.id().identifier()}" }
        }.joinToString("\n")
    }.lines().filter { it.isNotBlank() }.toSet()

    /** The same, as the client received them. */
    private suspend fun Stage.clientRecipes(): Set<String> = client(watcher) {
        AllRecipeTypes.syncedTypes().flatMap { type ->
            val typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type)
            @Suppress("UNCHECKED_CAST")
            ClientRecipes.all(type as RecipeType<Recipe<RecipeInput>>).map { "$typeId | ${it.id().identifier()}" }
        }.joinToString("\n")
    }.lines().filter { it.isNotBlank() }.toSet()

    private companion object {
        val SEQUENCED_ASSEMBLY = listOf("precision_mechanism", "sturdy_sheet", "track")
            .map { "create:sequenced_assembly | create:sequenced_assembly/$it" }
    }
}
