package com.bettercontent.recipegraph;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
