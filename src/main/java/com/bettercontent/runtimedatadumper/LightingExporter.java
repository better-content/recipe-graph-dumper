package com.bettercontent.runtimedatadumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

final class LightingExporter {
    private static final String DYNAMIC_LIGHTS_MOD = "sodiumdynamiclights";
    private static final String DYNAMIC_LIGHTS_PATH = "/dynamiclights/item/";

    private LightingExporter() {}

    static JsonObject export() {
        JsonObject out = new JsonObject();
        JsonArray blockStates = new JsonArray();
        JsonArray fluids = new JsonArray();
        JsonArray issues = new JsonArray();

        exportBlockStates(blockStates, issues);
        exportFluids(fluids, issues);
        JsonObject portable = exportPortableDynamicLights(issues);

        out.addProperty("evidence_mode", "live_registry_and_loaded_mod_resource_scan");
        out.addProperty("limitation", "Block-state and fluid emission are live server registry evidence. Portable entries describe Sodium Dynamic Lights behavior and loaded declarations, not each client's configuration or resource-pack overrides. Entity, spell, projectile, shader, emissive-texture, and position-dependent lighting are not universally enumerable here.");
        out.addProperty("block_state_count", blockStates.size());
        out.addProperty("fluid_count", fluids.size());
        out.addProperty("portable_source_count", portable.get("source_count").getAsInt());
        out.addProperty("error_count", issues.size());
        out.addProperty("complete", issues.isEmpty());
        out.add("block_states", blockStates);
        out.add("fluids", fluids);
        out.add("portable_dynamic_lights", portable);
        out.add("issues", issues);
        return out;
    }

    private static void exportBlockStates(JsonArray rows, JsonArray issues) {
        BuiltInRegistries.BLOCK.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
            Block block = BuiltInRegistries.BLOCK.get(id);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                try {
                    int emission = state.getLightEmission();
                    if (emission <= 0) continue;
                    JsonObject row = new JsonObject();
                    row.addProperty("block", id.toString());
                    row.addProperty("description_id", block.getDescriptionId());
                    Item item = block.asItem();
                    if (item != Items.AIR) row.addProperty("item", registryId(ForgeRegistries.ITEMS.getKey(item)));
                    row.add("state", stateProperties(state));
                    row.addProperty("light_level", emission);
                    rows.add(row);
                } catch (Throwable error) {
                    issues.add(issue("block_state", id.toString(), state.toString(), error));
                }
            }
        });
    }

    private static void exportFluids(JsonArray rows, JsonArray issues) {
        BuiltInRegistries.FLUID.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            try {
                int emission = fluid.getFluidType().getLightLevel();
                if (emission <= 0) return;
                JsonObject row = new JsonObject();
                row.addProperty("fluid", id.toString());
                row.addProperty("java_class", fluid.getClass().getName());
                row.addProperty("fluid_type_class", fluid.getFluidType().getClass().getName());
                row.addProperty("light_level", emission);
                rows.add(row);
            } catch (Throwable error) {
                issues.add(issue("fluid", id.toString(), null, error));
            }
        });
    }

    private static JsonObject exportPortableDynamicLights(JsonArray issues) {
        JsonObject out = new JsonObject();
        boolean providerLoaded = ModList.get().isLoaded(DYNAMIC_LIGHTS_MOD);
        JsonArray automatic = new JsonArray();
        JsonArray declared = new JsonArray();
        JsonArray rejected = new JsonArray();
        out.addProperty("provider", DYNAMIC_LIGHTS_MOD);
        out.addProperty("provider_loaded", providerLoaded);

        if (providerLoaded) {
            exportAutomaticBlockItems(automatic, issues);
            exportDeclaredItemLights(declared, rejected, issues);
        }

        out.addProperty("automatic_block_item_count", automatic.size());
        out.addProperty("declared_item_count", declared.size());
        out.addProperty("rejected_declaration_count", rejected.size());
        out.addProperty("source_count", automatic.size() + declared.size());
        out.add("automatic_block_items", automatic);
        out.add("declared_items", declared);
        out.add("rejected_declarations", rejected);
        return out;
    }

    private static void exportAutomaticBlockItems(JsonArray rows, JsonArray issues) {
        BuiltInRegistries.ITEM.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (!(item instanceof BlockItem blockItem)) return;
            try {
                List<Integer> emissions = blockItem.getBlock().getStateDefinition().getPossibleStates().stream()
                        .map(BlockState::getLightEmission).toList();
                int defaultEmission = blockItem.getBlock().defaultBlockState().getLightEmission();
                int maximumEmission = emissions.stream().max(Integer::compareTo).orElse(0);
                if (maximumEmission <= 0) return;
                JsonObject row = new JsonObject();
                row.addProperty("item", id.toString());
                row.addProperty("block", registryId(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock())));
                row.addProperty("default_light_level", defaultEmission);
                row.addProperty("maximum_state_light_level", maximumEmission);
                row.addProperty("state_from_block_state_tag", true);
                rows.add(row);
            } catch (Throwable error) {
                issues.add(issue("portable_block_item", id.toString(), null, error));
            }
        });
    }

    private static void exportDeclaredItemLights(JsonArray accepted, JsonArray rejected, JsonArray issues) {
        List<ResourceFile> resources = new ArrayList<>();
        for (IModFileInfo modFile : ModList.get().getModFiles()) {
            Path root = modFile.getFile().getSecureJar().getRootPath();
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (Stream<Path> paths = Files.walk(assets)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> portableDefinitionPath(assets, path) != null)
                        .forEach(path -> resources.add(new ResourceFile(modFile.getFile().getFileName(), assets, path)));
            } catch (Throwable error) {
                issues.add(issue("portable_resource_scan", modFile.getFile().getFileName(), null, error));
            }
        }
        resources.sort(Comparator.comparing(ResourceFile::definitionId).thenComparing(ResourceFile::source));
        Map<String, JsonObject> firstAcceptedByItem = new LinkedHashMap<>();
        for (ResourceFile resource : resources) {
            try (Reader reader = Files.newBufferedReader(resource.path(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("root is not an object");
                DefinitionResult result = classifyDefinition(
                        resource.definitionId(), resource.source(), parsed.getAsJsonObject(),
                        LightingExporter::resolveItem,
                        LightingExporter::resolveBlock);
                if (result.accepted()) {
                    String itemId = result.row().get("item").getAsString();
                    JsonObject previous = firstAcceptedByItem.putIfAbsent(itemId, result.row());
                    if (previous == null) accepted.add(result.row());
                    else rejected.add(rejected(resource.definitionId(), resource.source(), "duplicate_item_definition", itemId));
                } else {
                    rejected.add(result.row());
                }
            } catch (Throwable error) {
                issues.add(issue("portable_definition", resource.definitionId(), resource.source(), error));
            }
        }
    }

    static DefinitionResult classifyDefinition(
            String definitionId,
            String source,
            JsonObject json,
            Function<ResourceLocation, ResolvedItem> itemLookup,
            Function<ResourceLocation, ResolvedBlock> blockLookup
    ) {
        if (!json.has("item") || !json.has("luminance")) {
            return new DefinitionResult(false, rejected(definitionId, source, "unsupported_schema", null));
        }
        ResourceLocation itemId = ResourceLocation.tryParse(json.get("item").getAsString());
        ResolvedItem item = itemId == null ? null : itemLookup.apply(itemId);
        if (item == null || !item.exists()) {
            return new DefinitionResult(false, rejected(definitionId, source, "missing_item", itemId == null ? null : itemId.toString()));
        }

        JsonObject row = new JsonObject();
        row.addProperty("definition", definitionId);
        row.addProperty("source", source);
        row.addProperty("item", itemId.toString());
        row.addProperty("water_sensitive", json.has("water_sensitive") && json.get("water_sensitive").getAsBoolean());
        JsonElement luminance = json.get("luminance");
        if (!luminance.isJsonPrimitive()) {
            return new DefinitionResult(false, rejected(definitionId, source, "invalid_luminance", itemId.toString()));
        }
        if (luminance.getAsJsonPrimitive().isNumber()) {
            int level = luminance.getAsInt();
            if (level < 0 || level > 15) return new DefinitionResult(false, rejected(definitionId, source, "invalid_luminance", itemId.toString()));
            row.addProperty("luminance_mode", "fixed");
            row.addProperty("light_level", level);
            return new DefinitionResult(true, row);
        }
        if (!luminance.getAsJsonPrimitive().isString()) {
            return new DefinitionResult(false, rejected(definitionId, source, "invalid_luminance", itemId.toString()));
        }
        String reference = luminance.getAsString();
        ResolvedBlock block;
        if (reference.equals("block") && item.blockId() != null) {
            block = new ResolvedBlock(true, item.blockId(), item.defaultLightLevel());
            row.addProperty("luminance_mode", "item_block_default_state");
        } else {
            ResourceLocation blockId = ResourceLocation.tryParse(reference);
            block = blockId == null ? null : blockLookup.apply(blockId);
            if (block == null || !block.exists()) {
                return new DefinitionResult(false, rejected(definitionId, source, "missing_luminance_block", itemId.toString()));
            }
            row.addProperty("luminance_mode", "referenced_block_default_state");
            row.addProperty("luminance_block", blockId.toString());
        }
        row.addProperty("light_level", block.defaultLightLevel());
        return new DefinitionResult(true, row);
    }

    private static ResolvedItem resolveItem(ResourceLocation id) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) return null;
        if (item instanceof BlockItem blockItem) {
            return new ResolvedItem(true, registryId(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock())),
                    blockItem.getBlock().defaultBlockState().getLightEmission());
        }
        return new ResolvedItem(true, null, 0);
    }

    private static ResolvedBlock resolveBlock(ResourceLocation id) {
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) return null;
        return new ResolvedBlock(true, id.toString(), block.defaultBlockState().getLightEmission());
    }

    private static String portableDefinitionPath(Path assets, Path path) {
        String relative = assets.relativize(path).toString().replace('\\', '/');
        int slash = relative.indexOf('/');
        if (slash <= 0 || !relative.endsWith(".json")) return null;
        String remainder = relative.substring(slash);
        return remainder.startsWith(DYNAMIC_LIGHTS_PATH) ? relative : null;
    }

    private static JsonObject rejected(String definition, String source, String reason, String item) {
        JsonObject row = new JsonObject();
        row.addProperty("definition", definition);
        row.addProperty("source", source);
        row.addProperty("reason", reason);
        if (item != null) row.addProperty("item", item);
        return row;
    }

    static JsonObject stateProperties(BlockState state) {
        Map<String, String> values = new LinkedHashMap<>();
        state.getValues().forEach((property, value) -> values.put(property.getName(), propertyValue(property, value)));
        return propertiesJson(values);
    }

    static JsonObject propertiesJson(Map<String, String> values) {
        JsonObject properties = new JsonObject();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> properties.addProperty(entry.getKey(), entry.getValue()));
        return properties;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(Property property, Comparable value) {
        return property.getName(value);
    }

    private static JsonObject issue(String kind, String id, String detail, Throwable error) {
        JsonObject row = new JsonObject();
        row.addProperty("kind", kind);
        row.addProperty("id", id);
        if (detail != null) row.addProperty("detail", detail);
        row.addProperty("error", describe(error));
        return row;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String registryId(ResourceLocation id) {
        return id == null ? "UNKNOWN" : id.toString();
    }

    record DefinitionResult(boolean accepted, JsonObject row) {}

    record ResolvedItem(boolean exists, String blockId, int defaultLightLevel) {}

    record ResolvedBlock(boolean exists, String id, int defaultLightLevel) {}

    private record ResourceFile(String source, Path assets, Path path) {
        String definitionId() {
            String relative = assets.relativize(path).toString().replace('\\', '/');
            int slash = relative.indexOf('/');
            String namespace = relative.substring(0, slash);
            String value = relative.substring(slash + DYNAMIC_LIGHTS_PATH.length(), relative.length() - ".json".length());
            return namespace + ":" + value;
        }
    }
}
