package com.bettercontent.recipegraph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeGraphExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GRAPH_SCHEMA = "bc.recipe_graph.v2";
    private static final String REGISTRY_SCHEMA = "bc.registries.v2";
    private static final String TAG_SCHEMA = "bc.tags.v2";
    private static final String MOD_SCHEMA = "bc.mods.v2";

    private RecipeGraphExporter() {}

    public static DumpResult dump(MinecraftServer server) {
        Path output = server.getServerDirectory().toPath().resolve("generated/runtime-dumps").normalize();
        String generatedAt = Instant.now().toString();
        String snapshotId = shortHash(generatedAt + "\n" + server.getWorldData().getLevelName());
        try {
            Files.createDirectories(output);
            List<Recipe<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
            recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));

            JsonArray recipeRows = new JsonArray();
            Map<String, int[]> coverage = new LinkedHashMap<>();
            int partial = 0;
            int errors = 0;
            for (Recipe<?> recipe : recipes) {
                RecipeExport exported = exportRecipe(recipe, server.registryAccess());
                recipeRows.add(exported.row());
                int[] counts = coverage.computeIfAbsent(exported.type(), ignored -> new int[3]);
                counts[0]++;
                if (exported.partial()) {
                    partial++;
                    counts[1]++;
                }
                if (exported.error()) {
                    errors++;
                    counts[2]++;
                }
            }

            JsonObject graph = envelope(GRAPH_SCHEMA, snapshotId, generatedAt, server);
            graph.addProperty("recipe_count", recipes.size());
            graph.addProperty("partial_count", partial);
            graph.addProperty("error_count", errors);
            graph.add("coverage", coverageJson(coverage));
            graph.add("recipes", recipeRows);

            JsonObject registries = envelope(REGISTRY_SCHEMA, snapshotId, generatedAt, server);
            registries.add("items", registryRows(BuiltInRegistries.ITEM));
            registries.add("blocks", registryRows(BuiltInRegistries.BLOCK));
            registries.add("fluids", registryRows(BuiltInRegistries.FLUID));
            registries.add("entities", registryRows(BuiltInRegistries.ENTITY_TYPE));

            JsonObject tags = envelope(TAG_SCHEMA, snapshotId, generatedAt, server);
            RegistryAccess access = server.registryAccess();
            tags.add("item_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.ITEM)));
            tags.add("block_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)));
            tags.add("fluid_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.FLUID)));
            tags.add("entity_tags", tagRows(access.registryOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE)));

            JsonObject mods = envelope(MOD_SCHEMA, snapshotId, generatedAt, server);
            JsonObject modRows = new JsonObject();
            ModList.get().getMods().stream().sorted(Comparator.comparing(info -> info.getModId())).forEach(info -> {
                JsonObject row = new JsonObject();
                row.addProperty("display_name", info.getDisplayName());
                row.addProperty("version", info.getVersion().toString());
                modRows.add(info.getModId(), row);
            });
            mods.add("mods", modRows);

            writeAtomic(output.resolve("recipes.json"), graph);
            writeAtomic(output.resolve("registries.json"), registries);
            writeAtomic(output.resolve("tags.json"), tags);
            writeAtomic(output.resolve("mods.json"), mods);

            JsonObject completion = envelope("bc.runtime_dump_completion.v1", snapshotId, generatedAt, server);
            completion.addProperty("recipe_count", recipes.size());
            completion.addProperty("partial_count", partial);
            completion.addProperty("error_count", errors);
            writeAtomic(output.resolve("snapshot.json"), completion);
            return new DumpResult(true, snapshotId, recipes.size(), partial, errors, output.toString(), "ok");
        } catch (Exception error) {
            return DumpResult.failure(error.getClass().getSimpleName() + ": " + error.getMessage(), output.toString());
        }
    }

    private static RecipeExport exportRecipe(Recipe<?> recipe, RegistryAccess access) {
        JsonObject row = new JsonObject();
        String serializer = id(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        String type = typeKey == null ? serializer : typeKey.toString();
        row.addProperty("id", recipe.getId().toString());
        row.addProperty("type", type);
        row.addProperty("serializer", serializer);
        row.addProperty("recipe_class", recipe.getClass().getName());
        row.addProperty("serializer_class", recipe.getSerializer().getClass().getName());
        row.addProperty("special", recipe.isSpecial());
        row.addProperty("incomplete", recipe.isIncomplete());

        JsonArray issues = new JsonArray();
        JsonArray groups = new JsonArray();
        JsonArray flatInputs = new JsonArray();
        boolean partial = false;
        try {
            int slot = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                JsonObject group = new JsonObject();
                group.addProperty("slot", slot++);
                JsonElement ingredientJson = null;
                try {
                    ingredientJson = ingredient.toJson();
                    group.add("ingredient", ingredientJson);
                } catch (Exception error) {
                    group.addProperty("ingredient_error", describe(error));
                    partial = true;
                }
                JsonArray alternatives = new JsonArray();
                String tagId = ingredientTag(ingredientJson);
                if (tagId != null) {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("kind", "tag");
                    tag.addProperty("id", tagId);
                    flatInputs.add(tag);
                    group.addProperty("membership", "generated/runtime-dumps/tags.json#item_tags/" + tagId);
                } else {
                    try {
                        for (ItemStack stack : ingredient.getItems()) {
                            JsonObject alternative = stackJson(stack, "item");
                            alternatives.add(alternative);
                            flatInputs.add(alternative.deepCopy());
                        }
                    } catch (Exception error) {
                        group.addProperty("alternatives_error", describe(error));
                        partial = true;
                    }
                }
                group.add("alternatives", alternatives);
                groups.add(group);
            }
        } catch (Exception error) {
            issues.add("ingredients: " + describe(error));
            partial = true;
        }
        row.add("input_groups", groups);
        row.add("inputs", flatInputs);

        JsonArray outputs = new JsonArray();
        try {
            ItemStack result = recipe.getResultItem(access);
            if (!result.isEmpty()) outputs.add(stackJson(result, "item"));
            else if (!recipe.isSpecial()) {
                issues.add("no static primary output");
                partial = true;
            }
        } catch (Exception error) {
            issues.add("primary_output: " + describe(error));
            partial = true;
        }
        row.add("outputs", outputs);
        row.add("catalysts", new JsonArray());
        row.add("fluids_in", new JsonArray());
        row.add("fluids_out", new JsonArray());

        JsonObject requirements = new JsonObject();
        requirements.add("energy", null);
        requirements.add("time", null);
        requirements.add("heat", null);
        requirements.add("pressure", null);
        row.add("requirements", requirements);
        JsonArray machines = new JsonArray();
        JsonObject machine = new JsonObject();
        machine.addProperty("kind", "recipe_type");
        machine.addProperty("id", type);
        machines.add(machine);
        row.add("machines", machines);

        boolean payloadError = false;
        JsonObject payload = new JsonObject();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeNetworkPayload(recipe, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            payload.addProperty("encoding", "minecraft_recipe_serializer_network_v1");
            payload.addProperty("byte_length", bytes.length);
            payload.addProperty("sha256", hash(bytes));
            payload.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
        } catch (Exception error) {
            payload.addProperty("error", describe(error));
            issues.add("serializer_payload: " + describe(error));
            payloadError = true;
            partial = true;
        } finally {
            buffer.release();
        }
        row.add("serializer_payload", payload);
        row.add("issues", issues);
        row.addProperty("normalization", payloadError ? "error" : partial ? "partial" : "standard");
        row.addProperty("parsed", !partial);
        return new RecipeExport(row, type, partial, payloadError);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeNetworkPayload(Recipe<?> recipe, FriendlyByteBuf buffer) {
        ((RecipeSerializer) recipe.getSerializer()).toNetwork(buffer, recipe);
    }

    private static JsonObject stackJson(ItemStack stack, String kind) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", kind);
        out.addProperty("id", id(ForgeRegistries.ITEMS.getKey(stack.getItem())));
        out.addProperty("count", stack.getCount());
        if (stack.hasTag()) out.addProperty("nbt", stack.getTag().toString());
        return out;
    }

    static String ingredientTag(JsonElement ingredient) {
        if (ingredient == null || !ingredient.isJsonObject()) return null;
        JsonObject object = ingredient.getAsJsonObject();
        JsonElement tag = object.get("tag");
        return tag != null && tag.isJsonPrimitive() ? tag.getAsString() : null;
    }

    private static <T> JsonObject registryRows(Registry<T> registry) {
        JsonObject rows = new JsonObject();
        registry.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(key -> {
            T value = registry.get(key);
            JsonObject row = new JsonObject();
            row.addProperty("namespace", key.getNamespace());
            if (value != null) row.addProperty("java_class", value.getClass().getName());
            if (value instanceof Item item) {
                row.addProperty("description_id", item.getDescriptionId());
                row.addProperty("max_stack_size", item.getMaxStackSize());
                row.addProperty("max_damage", item.getMaxDamage());
            } else if (value instanceof Block block) {
                row.addProperty("description_id", block.getDescriptionId());
                Item blockItem = block.asItem();
                if (blockItem != net.minecraft.world.item.Items.AIR) {
                    row.addProperty("item_id", id(ForgeRegistries.ITEMS.getKey(blockItem)));
                }
            }
            rows.add(key.toString(), row);
        });
        return rows;
    }

    private static <T> JsonObject tagRows(Registry<T> registry) {
        JsonObject rows = new JsonObject();
        registry.getTagNames().sorted(Comparator.comparing(tag -> tag.location().toString())).forEach(tag -> {
            JsonArray values = new JsonArray();
            registry.getTag(tag).ifPresent(named -> named.stream()
                    .map(holder -> registry.getKey(holder.value()))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(key -> values.add(key.toString())));
            rows.add(tag.location().toString(), values);
        });
        return rows;
    }

    private static JsonObject coverageJson(Map<String, int[]> coverage) {
        JsonObject out = new JsonObject();
        coverage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject row = new JsonObject();
            row.addProperty("total", entry.getValue()[0]);
            row.addProperty("partial", entry.getValue()[1]);
            row.addProperty("errors", entry.getValue()[2]);
            out.add(entry.getKey(), row);
        });
        return out;
    }

    private static JsonObject envelope(String schema, String snapshotId, String generatedAt, MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("schema", schema);
        out.addProperty("snapshot_id", snapshotId);
        out.addProperty("generated_at", generatedAt);
        out.addProperty("world", server.getWorldData().getLevelName());
        out.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        out.addProperty("forge", FMLLoader.versionInfo().forgeVersion());
        out.addProperty("loader", "forge");
        out.addProperty("generated_by", RecipeGraphMod.MOD_ID + ":/bcgraph dump");
        return out;
    }

    private static void writeAtomic(Path target, JsonElement value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String id(ResourceLocation id) {
        return id == null ? "UNKNOWN" : id.toString();
    }

    private static String shortHash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RecipeExport(JsonObject row, String type, boolean partial, boolean error) {}
}
