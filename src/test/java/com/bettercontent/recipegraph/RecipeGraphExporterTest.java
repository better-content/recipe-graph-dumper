package com.bettercontent.recipegraph;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecipeGraphExporterTest {
    @Test
    void detectsTagSelectorsWithoutMisclassifyingExplicitAlternatives() {
        assertEquals("minecraft:planks", RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("{\"tag\":\"minecraft:planks\"}")));
        assertNull(RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("[{\"item\":\"minecraft:oak_planks\"},{\"item\":\"minecraft:spruce_planks\"}]")));
        assertNull(RecipeGraphExporter.ingredientTag(
                JsonParser.parseString("{\"item\":\"minecraft:oak_planks\"}")));
    }

    @Test
    void tradeSamplingSeedsAreStableAndDistinguishSamples() {
        long first = RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 0);
        assertEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 0));
        assertNotEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:farmer", "minecraft:plains", 2, 3, 1));
        assertNotEquals(first, RuntimeEvidenceExporter.tradeSeed("minecraft:librarian", "minecraft:plains", 2, 3, 0));
    }

    @Test
    void serializedIngredientCountsSurviveNormalization() {
        assertEquals(4, RecipeGraphExporter.ingredientCount(
                JsonParser.parseString("{\"tag\":\"forge:ingots/iron\",\"count\":4}"), null));
    }

    @Test
    void completenessRequiresEveryNormalizerAndRuntimeExporterToSucceed() {
        assertTrue(RecipeGraphExporter.isComplete(0, 0, 0));
        assertFalse(RecipeGraphExporter.isComplete(1, 0, 0));
        assertFalse(RecipeGraphExporter.isComplete(0, 1, 0));
        assertFalse(RecipeGraphExporter.isComplete(0, 0, 1));
    }

    @Test
    void semanticAccessorsAreClassifiedWithoutGuessingUnrelatedGetters() {
        assertEquals(SemanticRecipeAdapter.Direction.INPUT, SemanticRecipeAdapter.direction("getInputFluid"));
        assertEquals(SemanticRecipeAdapter.Direction.OUTPUT, SemanticRecipeAdapter.direction("getOutputWithByproducts"));
        assertEquals(SemanticRecipeAdapter.Direction.INPUT, SemanticRecipeAdapter.direction("getFluidIn"));
        assertEquals(SemanticRecipeAdapter.Direction.OUTPUT, SemanticRecipeAdapter.direction("getFluidOut"));
        assertEquals(SemanticRecipeAdapter.Direction.CATALYST, SemanticRecipeAdapter.direction("getCatalyst"));
        assertEquals(SemanticRecipeAdapter.Direction.UNKNOWN, SemanticRecipeAdapter.direction("getId"));
        assertEquals("pressure", SemanticRecipeAdapter.requirement("getRequiredPressure"));
        assertEquals("heat", SemanticRecipeAdapter.requirement("getTemperature"));
        assertEquals("time", SemanticRecipeAdapter.requirement("getTicks"));
        assertEquals("energy", SemanticRecipeAdapter.requirement("getSourceCost"));
        assertNull(SemanticRecipeAdapter.requirement("getMinimumTier"));
    }

    @Test
    void unavailableOptionalSignaturesAreSkippedInsteadOfCrashingTheDump() {
        assertTrue(SemanticRecipeAdapter.<Method>safeMembers(() -> {
            throw new NoClassDefFoundError("client-only optional recipe display type");
        }).isEmpty());
        assertFalse(SemanticRecipeAdapter.publicMethods(String.class).isEmpty());
        assertEquals("tconstruct:rock#stone", SemanticRecipeAdapter.materialVariantId("MaterialVariant{tconstruct:rock#stone}"));
    }

    @Test
    void semanticAdaptersMayAddTypedRequirementsBeyondTheCoreMachineFields() {
        JsonObject requirements = JsonParser.parseString("{\"energy\":null}").getAsJsonObject();
        RecipeGraphExporter.mergeRequirement(requirements, "max_tool_size", JsonParser.parseString("4"));
        assertEquals(4, requirements.get("max_tool_size").getAsInt());
        RecipeGraphExporter.mergeRequirement(requirements, "max_tool_size", JsonParser.parseString("9"));
        assertEquals(4, requirements.get("max_tool_size").getAsInt());
    }

    @Test
    void contextualFamiliesHaveExplicitMachineNavigableOperationKinds() {
        assertEquals("potion_flask_state_mutation", SemanticRecipeAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionIncreaseLength"));
        assertEquals("potion_flask_state_mutation", SemanticRecipeAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionTransform"));
        assertNull(SemanticRecipeAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.flask.RecipePotionFlaskTransform"));
        assertEquals("material_scaled_melting", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe"));
        assertEquals("conditional_part_recycling", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle"));
        assertEquals("conditional_tool_part_recycling", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle"));
        assertEquals("tool_state_mutation", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe"));
        assertEquals("matter_cannon_ammo_metadata", SemanticRecipeAdapter.operationKind(
                "appeng.recipes.mattercannon.MatterCannonAmmo"));
        assertEquals("non_gameplay_client_recipe_metadata", SemanticRecipeAdapter.operationKind(
                "com.almostreliable.unified.recipe.ClientRecipeTracker"));
        assertEquals("spirit_item_repair", SemanticRecipeAdapter.operationKind(
                "com.sammy.malum.common.recipe.SpiritRepairRecipe"));
        assertEquals("block_heat_property_metadata", SemanticRecipeAdapter.operationKind(
                "me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl"));
        assertEquals("fluid_fuel_property_metadata", SemanticRecipeAdapter.operationKind(
                "me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl"));
        assertEquals("ritual_block_highlight", SemanticRecipeAdapter.operationKind(
                "com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe"));
        assertEquals("living_armor_downgrade_mutation", SemanticRecipeAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.RecipeLivingDowngrade"));
        assertEquals("entity_brewing_effect", SemanticRecipeAdapter.operationKind(
                "com.Polarice3.Goety.common.crafting.BrewingRecipe"));
        assertEquals("soul_absorption", SemanticRecipeAdapter.operationKind(
                "com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes"));
        assertEquals("dynamic_item_state_crafting", SemanticRecipeAdapter.operationKind(
                "net.minecraft.world.item.crafting.ArmorDyeRecipe"));
        assertEquals("tool_overslime_restoration", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.modifiers.adding.OverslimeCraftingTableRecipe"));
        assertEquals("tool_modifier_extraction", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.tools.recipe.ExtractModifierRecipe"));
        assertEquals("ritual_meteor_world_effect", SemanticRecipeAdapter.operationKind(
                "wayoftime.bloodmagic.recipe.RecipeMeteor"));
        assertEquals("placement_policy_metadata", SemanticRecipeAdapter.operationKind(
                "com.aetherteam.aether.recipe.recipes.ban.BlockBanRecipe"));
        assertEquals("item_modifier_application", SemanticRecipeAdapter.operationKind(
                "com.stal111.forbidden_arcanus.common.recipe.ApplyModifierRecipe"));
        assertEquals("armor_tier_upgrade", SemanticRecipeAdapter.operationKind(
                "com.hollingsworth.arsnouveau.api.enchanting_apparatus.ArmorUpgradeRecipe"));
    }

    @Test
    void clientSynchronizationTrackersRemainDistinctFromGameplaySemantics() {
        assertEquals("non_gameplay_client_recipe_metadata", SemanticRecipeAdapter.operationKind(
                "com.almostreliable.unified.recipe.ClientRecipeTracker"));
        assertEquals("tool_part_replacement", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.tables.recipe.TinkerStationPartSwapping"));
        assertEquals("gas_reaction", SemanticRecipeAdapter.operationKind(
                "org.valkyrienskies.clockwork.content.logistics.gas.crafter.GasCraftingRecipe"));
        assertEquals("tool_modifier_set_mutation", SemanticRecipeAdapter.operationKind(
                "slimeknights.tconstruct.library.recipe.worktable.ModifierSetWorktableRecipe"));
    }

    @Test
    void contextualEffectsAreValidOutcomesWithoutInventedStaticOutputs() {
        JsonArray effects = JsonParser.parseString("[{\"kind\":\"add_tool_damage\",\"damage\":5}]").getAsJsonArray();
        assertTrue(RecipeGraphExporter.hasNavigableOutcome(new JsonArray(), new JsonArray(), effects));
        assertFalse(RecipeGraphExporter.hasNavigableOutcome(new JsonArray(), new JsonArray(), new JsonArray()));
    }
}
