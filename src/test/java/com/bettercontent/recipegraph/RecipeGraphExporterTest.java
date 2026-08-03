package com.bettercontent.recipegraph;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
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
}
