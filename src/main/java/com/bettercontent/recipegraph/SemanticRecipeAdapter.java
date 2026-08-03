package com.bettercontent.recipegraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Normalizes common public recipe display APIs without linking optional mods. */
final class SemanticRecipeAdapter {
    private static final int MAX_DEPTH = 4;

    private SemanticRecipeAdapter() {}

    static Result inspect(Recipe<?> recipe) {
        Collector collector = new Collector();
        List<Method> methods = new ArrayList<>(List.of(recipe.getClass().getMethods()));
        methods.sort(Comparator.comparing(Method::toGenericString));
        for (Method method : methods) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || method.isBridge()) continue;
            Direction direction = direction(method.getName());
            String requirement = requirement(method.getName());
            if (direction == Direction.UNKNOWN && requirement == null) continue;
            try {
                Object value = method.invoke(recipe);
                if (requirement != null && value instanceof Number number) {
                    collector.requirement(requirement, number, method.getName() + "()");
                } else if (direction != Direction.UNKNOWN) {
                    collector.collect(value, direction, method.getName() + "()", 0);
                }
            } catch (Throwable ignored) {
                // A context-dependent accessor is not evidence. Other accessors may still be exact.
            }
        }
        collector.publicFields(recipe);
        return collector.result();
    }

    static Direction direction(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("output") || value.contains("result") || value.contains("byproduct")) return Direction.OUTPUT;
        if (value.contains("input") || value.contains("ingredient") || value.contains("reagent")) return Direction.INPUT;
        if (value.contains("catalyst") || value.contains("tool")) return Direction.CATALYST;
        return Direction.UNKNOWN;
    }

    static String requirement(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("temperature") || value.equals("getheat") || value.equals("heat")) return "heat";
        if (value.contains("pressure")) return "pressure";
        if (value.contains("time") || value.contains("ticks") || value.contains("duration")) return "time";
        if (value.contains("energy") || value.contains("syphon") || value.contains("mana")) return "energy";
        return null;
    }

    enum Direction { INPUT, OUTPUT, CATALYST, UNKNOWN }

    record Result(
            JsonArray inputGroups,
            JsonArray inputs,
            JsonArray outputGroups,
            JsonArray outputs,
            JsonArray catalysts,
            JsonArray fluidsIn,
            JsonArray fluidsOut,
            JsonObject requirements,
            JsonArray evidence
    ) {
        boolean hasEdges() {
            return !inputs.isEmpty() || !outputs.isEmpty() || !fluidsIn.isEmpty() || !fluidsOut.isEmpty() || !catalysts.isEmpty();
        }
    }

    private static final class Collector {
        private final JsonArray inputGroups = new JsonArray();
        private final JsonArray inputs = new JsonArray();
        private final JsonArray outputGroups = new JsonArray();
        private final JsonArray outputs = new JsonArray();
        private final JsonArray catalysts = new JsonArray();
        private final JsonArray fluidsIn = new JsonArray();
        private final JsonArray fluidsOut = new JsonArray();
        private final JsonObject requirements = new JsonObject();
        private final JsonArray evidence = new JsonArray();
        private final Set<String> seenEdges = new TreeSet<>();
        private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        Result result() {
            return new Result(inputGroups, inputs, outputGroups, outputs, catalysts, fluidsIn, fluidsOut, requirements, evidence);
        }

        void requirement(String kind, Number value, String path) {
            if (requirements.has(kind)) return;
            requirements.addProperty(kind, value);
            evidence(kind, path);
        }

        void publicFields(Object root) {
            List<Field> fields = new ArrayList<>(List.of(root.getClass().getFields()));
            fields.sort(Comparator.comparing(Field::toGenericString));
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Direction direction = direction(field.getName());
                String requirement = SemanticRecipeAdapter.requirement(field.getName());
                if (direction == Direction.UNKNOWN && requirement == null) continue;
                try {
                    Object value = field.get(root);
                    if (requirement != null && value instanceof Number number) {
                        requirement(requirement, number, field.getName());
                    } else {
                        collect(value, direction, field.getName(), 0);
                    }
                } catch (Throwable ignored) {
                    // Only successfully read public state is evidence.
                }
            }
        }

        void collect(Object value, Direction direction, String path, int depth) {
            if (value == null || depth > MAX_DEPTH) return;
            if (value instanceof Ingredient ingredient) {
                ingredient(ingredient, direction, path);
                return;
            }
            if (value instanceof ItemStack stack) {
                item(stack, direction, path);
                return;
            }
            if (value instanceof FluidStack stack) {
                fluid(stack, direction, path);
                return;
            }
            if (value instanceof Collection<?> collection) {
                int index = 0;
                for (Object element : collection) collect(element, direction, path + "[" + index++ + "]", depth + 1);
                return;
            }
            if (value.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) {
                    collect(Array.get(value, index), direction, path + "[" + index + "]", depth + 1);
                }
                return;
            }
            if (isTerminal(value) || !visited.add(value)) return;
            List<Field> fields = allFields(value.getClass());
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || !field.trySetAccessible()) continue;
                Direction nested = direction(field.getName());
                if (nested == Direction.UNKNOWN) nested = direction;
                String nestedPath = path + "." + field.getName();
                try {
                    Object child = field.get(value);
                    String req = SemanticRecipeAdapter.requirement(field.getName());
                    if (req != null && child instanceof Number number) requirement(req, number, nestedPath);
                    collect(child, nested, nestedPath, depth + 1);
                } catch (Throwable ignored) {
                    // Inaccessible implementation detail is not evidence.
                }
            }
        }

        private void ingredient(Ingredient ingredient, Direction direction, String path) {
            if (direction == Direction.UNKNOWN || direction == Direction.OUTPUT) return;
            JsonElement definition;
            try {
                definition = ingredient.toJson();
            } catch (Throwable ignored) {
                return;
            }
            JsonObject group = new JsonObject();
            group.addProperty("slot", inputGroups.size());
            group.add("ingredient", definition);
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            String tagId = RecipeGraphExporter.ingredientTag(definition);
            if (tagId != null) {
                JsonObject edge = edge("tag", tagId, RecipeGraphExporter.ingredientCount(definition, ingredient), path);
                addEdge(direction, edge);
                group.addProperty("membership", "generated/runtime-dumps/tags.json#item_tags/" + tagId);
            } else {
                try {
                    for (ItemStack stack : ingredient.getItems()) {
                        JsonObject edge = itemEdge(stack, path);
                        alternatives.add(edge.deepCopy());
                        addEdge(direction, edge);
                    }
                } catch (Throwable ignored) {
                    return;
                }
            }
            group.add("alternatives", alternatives);
            inputGroups.add(group);
            evidence("edge", path);
        }

        private void item(ItemStack stack, Direction direction, String path) {
            if (stack.isEmpty() || direction == Direction.UNKNOWN) return;
            JsonObject edge = itemEdge(stack, path);
            addEdge(direction, edge);
            if (direction == Direction.OUTPUT) {
                JsonObject group = new JsonObject();
                group.addProperty("slot", outputGroups.size());
                group.addProperty("semantic_path", path);
                JsonArray alternatives = new JsonArray();
                alternatives.add(edge.deepCopy());
                group.add("alternatives", alternatives);
                outputGroups.add(group);
            }
            evidence("edge", path);
        }

        private void fluid(FluidStack stack, Direction direction, String path) {
            if (stack.isEmpty() || direction == Direction.UNKNOWN || direction == Direction.CATALYST) return;
            String id = String.valueOf(ForgeRegistries.FLUIDS.getKey(stack.getFluid()));
            JsonObject edge = edge("fluid", id, stack.getAmount(), path);
            if (stack.hasTag()) edge.addProperty("nbt", stack.getTag().toString());
            addUnique(direction == Direction.OUTPUT ? fluidsOut : fluidsIn, edge);
            evidence("edge", path);
        }

        private void addEdge(Direction direction, JsonObject edge) {
            JsonArray target = switch (direction) {
                case INPUT -> inputs;
                case OUTPUT -> outputs;
                case CATALYST -> catalysts;
                case UNKNOWN -> null;
            };
            if (target != null) addUnique(target, edge);
        }

        private void addUnique(JsonArray target, JsonObject edge) {
            String key = System.identityHashCode(target) + ":" + edge;
            if (seenEdges.add(key)) target.add(edge);
        }

        private void evidence(String kind, String path) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("path", path);
            evidence.add(row);
        }

        private static JsonObject itemEdge(ItemStack stack, String path) {
            JsonObject edge = edge("item", String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())), stack.getCount(), path);
            if (stack.hasTag()) edge.addProperty("nbt", stack.getTag().toString());
            return edge;
        }

        private static JsonObject edge(String kind, String id, int count, String path) {
            JsonObject edge = new JsonObject();
            edge.addProperty("kind", kind);
            edge.addProperty("id", id);
            edge.addProperty("count", Math.max(1, count));
            edge.addProperty("semantic_path", path);
            return edge;
        }

        private static boolean isTerminal(Object value) {
            Class<?> type = value.getClass();
            return type.isPrimitive() || value instanceof Number || value instanceof CharSequence
                    || value instanceof Boolean || value instanceof Enum<?> || type.getName().startsWith("java.time.")
                    || type.getName().startsWith("net.minecraft.resources.");
        }

        private static List<Field> allFields(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                fields.addAll(List.of(cursor.getDeclaredFields()));
            }
            fields.sort(Comparator.comparing(Field::toGenericString));
            return fields;
        }
    }
}
