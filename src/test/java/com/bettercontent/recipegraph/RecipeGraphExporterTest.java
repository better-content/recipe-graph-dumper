package com.bettercontent.recipegraph;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

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
}
