package com.bettercontent.recipegraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Normalizes common public recipe display APIs without linking optional mods. */
final class SemanticRecipeAdapter {
    private static final int MAX_DEPTH = 4;

    private SemanticRecipeAdapter() {}

    static Result inspect(Recipe<?> recipe) {
        Collector collector = new Collector();
        List<Method> methods = publicMethods(recipe.getClass());
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
        collector.knownRecipeSemantics(recipe);
        return collector.result();
    }

    /**
     * Optional recipe classes can expose client-only types in otherwise public
     * method signatures. The JVM resolves those signatures while enumerating
     * methods, so reflection itself can throw a linkage error on a dedicated
     * server. Such a signature is unavailable evidence, not a reason to abort
     * the complete live dump.
     */
    static List<Method> publicMethods(Class<?> type) {
        return safeMembers(type::getMethods);
    }

    static <T> List<T> safeMembers(Supplier<T[]> source) {
        try {
            return new ArrayList<>(List.of(source.get()));
        } catch (LinkageError | SecurityException ignored) {
            return new ArrayList<>();
        }
    }

    static String materialVariantId(String display) {
        String prefix = "MaterialVariant{";
        if (display.startsWith(prefix) && display.endsWith("}")) {
            return display.substring(prefix.length(), display.length() - 1);
        }
        return display;
    }

    static String operationKind(String className) {
        if (isBloodMagicPotionStateMutation(className)) return "potion_flask_state_mutation";
        return switch (className) {
            case "slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe" -> "material_scaled_melting";
            case "slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle" -> "conditional_part_recycling";
            case "slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle" -> "conditional_tool_part_recycling";
            case "slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe" -> "tool_state_mutation";
            case "appeng.recipes.mattercannon.MatterCannonAmmo" -> "matter_cannon_ammo_metadata";
            case "com.almostreliable.unified.recipe.ClientRecipeTracker" -> "non_gameplay_client_recipe_metadata";
            case "com.sammy.malum.common.recipe.SpiritRepairRecipe" -> "spirit_item_repair";
            case "me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl" -> "block_heat_property_metadata";
            case "me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl" -> "fluid_fuel_property_metadata";
            case "com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe" -> "ritual_block_highlight";
            case "wayoftime.bloodmagic.recipe.RecipeLivingDowngrade" -> "living_armor_downgrade_mutation";
            case "com.Polarice3.Goety.common.crafting.BrewingRecipe" -> "entity_brewing_effect";
            case "com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes" -> "soul_absorption";
            default -> null;
        };
    }

    private static boolean isBloodMagicPotionStateMutation(String className) {
        return className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionIncreaseLength")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionIncreasePotency")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionEffect")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionTransform")
                || className.equals("wayoftime.bloodmagic.recipe.flask.RecipePotionFill");
    }

    static Direction direction(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("output") || value.contains("result") || value.contains("byproduct") || value.contains("fluidout")) return Direction.OUTPUT;
        if (value.contains("input") || value.contains("ingredient") || value.contains("reagent") || value.contains("fluidin")) return Direction.INPUT;
        if (value.contains("catalyst") || value.contains("tool")) return Direction.CATALYST;
        return Direction.UNKNOWN;
    }

    static String requirement(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("temperature") || value.equals("getheat") || value.equals("heat")) return "heat";
        if (value.contains("pressure")) return "pressure";
        if (value.contains("time") || value.contains("ticks") || value.contains("duration")) return "time";
        if (value.contains("energy") || value.contains("syphon") || value.contains("mana") || value.contains("sourcecost")) return "energy";
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
            String operationKind,
            JsonArray effects,
            JsonObject requirements,
            JsonArray evidence,
            boolean contextualComplete
    ) {
        boolean hasSemantics() {
            return !inputs.isEmpty() || !outputs.isEmpty() || !fluidsIn.isEmpty() || !fluidsOut.isEmpty()
                    || !catalysts.isEmpty() || !effects.isEmpty() || operationKind != null;
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
        private final JsonArray effects = new JsonArray();
        private final JsonObject requirements = new JsonObject();
        private final JsonArray evidence = new JsonArray();
        private final Set<String> seenEdges = new TreeSet<>();
        private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private String operationKind;
        private boolean contextualComplete = true;

        Result result() {
            return new Result(inputGroups, inputs, outputGroups, outputs, catalysts, fluidsIn, fluidsOut,
                    operationKind, effects, requirements, evidence, contextualComplete);
        }

        void requirement(String kind, Number value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, String value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, boolean value, String path) {
            requirement(kind, new com.google.gson.JsonPrimitive(value), path);
        }

        void requirement(String kind, JsonElement value, String path) {
            if (requirements.has(kind) || value == null) return;
            requirements.add(kind, value);
            evidence(kind, path);
        }

        void publicFields(Object root) {
            List<Field> fields = publicFields(root.getClass());
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

        void knownRecipeSemantics(Recipe<?> recipe) {
            String className = recipe.getClass().getName();
            String knownOperation = operationKind(className);
            if (knownOperation != null) operation(knownOperation, className);
            if (isBloodMagicPotionStateMutation(className)) {
                bloodMagicFlask(recipe, className);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.melting.MaterialMeltingRecipe")) {
                tconstructMaterialMelting(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.partbuilder.recycle.PartBuilderRecycle")) {
                tconstructPartRecycle(recipe);
            } else if (className.equals("slimeknights.tconstruct.tables.recipe.PartBuilderToolRecycle")) {
                tconstructToolRecycle(recipe);
            } else if (className.equals("slimeknights.tconstruct.tables.recipe.TinkerStationDamagingRecipe")) {
                tconstructToolDamage(recipe);
            } else if (className.equals("appeng.recipes.mattercannon.MatterCannonAmmo")) {
                matterCannonAmmo(recipe);
            } else if (className.equals("com.almostreliable.unified.recipe.ClientRecipeTracker")) {
                clientRecipeTracker(recipe);
            } else if (className.equals("com.sammy.malum.common.recipe.SpiritRepairRecipe")) {
                malumSpiritRepair(recipe);
            } else if (className.equals("me.desht.pneumaticcraft.common.recipes.other.HeatPropertiesRecipeImpl")) {
                pneumaticHeatProperties(recipe);
            } else if (className.equals("me.desht.pneumaticcraft.common.recipes.other.FuelQualityRecipeImpl")) {
                pneumaticFuelQuality(recipe);
            } else if (className.equals("com.hollingsworth.arsnouveau.api.recipe.ScryRitualRecipe")) {
                arsScryRitual(recipe);
            } else if (className.equals("wayoftime.bloodmagic.recipe.RecipeLivingDowngrade")) {
                bloodMagicLivingDowngrade(recipe);
            } else if (className.equals("com.Polarice3.Goety.common.crafting.BrewingRecipe")) {
                goetyBrewing(recipe);
            } else if (className.equals("com.Polarice3.Goety.common.crafting.SoulAbsorberRecipes")) {
                goetySoulAbsorber(recipe);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.material.MaterialRecipe")) {
                collectAccessor(recipe, "getMaterial", Direction.OUTPUT);
            } else if (className.equals("slimeknights.tconstruct.library.recipe.modifiers.ModifierSalvage")) {
                collectDeclaredField(recipe, "toolIngredient", Direction.INPUT);
                collectAccessor(recipe, "getModifier", Direction.INPUT);
                collectModifierSlots(readDeclaredField(recipe, "slots"), "slots");
                collectNumberAccessor(recipe, "getMaxToolSize", "max_tool_size");
                collectIntRange(readDeclaredField(recipe, "level"), "modifier_level");
            } else if (className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantmentRecipe")) {
                collectField(recipe, "pedestalItems", Direction.INPUT);
                collectField(recipe, "reagent", Direction.INPUT);
                collectField(recipe, "enchantment", Direction.OUTPUT);
            }
        }

        private void bloodMagicFlask(Object recipe, String className) {
            collectNumberAccessor(recipe, "getMinimumTier", "machine_tier");
            if (collectRegistryItemsBySuperclass("wayoftime.bloodmagic.common.item.potion.ItemAlchemyFlask", "eligible_flask_item_class") == 0) {
                incomplete("eligible_flask_item_class");
            }
            if (className.endsWith("RecipePotionIncreaseLength")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object multiplier = readDeclaredField(recipe, "lengthDurationMod");
                if (!(target instanceof MobEffect) || !(multiplier instanceof Number)) incomplete("outputEffect+lengthDurationMod");
                JsonObject effect = effect("set_potion_effect_duration_multiplier", "outputEffect+lengthDurationMod");
                addMobEffectTarget(effect, target);
                addNumber(effect, "multiplier", multiplier);
            } else if (className.endsWith("RecipePotionIncreasePotency")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object amplifier = readDeclaredField(recipe, "amplifier");
                Object durationMultiplier = readDeclaredField(recipe, "ampDurationMod");
                if (!(target instanceof MobEffect) || !(amplifier instanceof Number) || !(durationMultiplier instanceof Number)) {
                    incomplete("outputEffect+amplifier+ampDurationMod");
                }
                JsonObject effect = effect("set_potion_effect_potency", "outputEffect+amplifier+ampDurationMod");
                addMobEffectTarget(effect, target);
                addNumber(effect, "amplifier", amplifier);
                addNumber(effect, "duration_multiplier", durationMultiplier);
            } else if (className.endsWith("RecipePotionEffect")) {
                Object target = readDeclaredField(recipe, "outputEffect");
                Object duration = readDeclaredField(recipe, "baseDuration");
                if (!(target instanceof MobEffect) || !(duration instanceof Number)) incomplete("outputEffect+baseDuration");
                JsonObject effect = effect("add_potion_effect", "outputEffect+baseDuration");
                addMobEffectTarget(effect, target);
                addNumber(effect, "base_duration_ticks", duration);
            } else if (className.endsWith("RecipePotionTransform")) {
                Object inputValue = readDeclaredField(recipe, "inputEffectList");
                JsonArray required = mobEffectIds(inputValue);
                if (!(inputValue instanceof Collection<?>) || required.isEmpty()) incomplete("inputEffectList");
                requirement("required_potion_effects", required, "inputEffectList");
                if (inputValue instanceof Collection<?> inputEffects) {
                    for (Object inputEffect : inputEffects) {
                        JsonObject effect = effect("remove_potion_effect", "inputEffectList");
                        addMobEffectTarget(effect, inputEffect);
                    }
                }
                Object outputValue = readDeclaredField(recipe, "outputEffectList");
                if (outputValue instanceof Collection<?> outputEffects) {
                    for (Object pair : outputEffects) {
                        Object target = invokeNoArg(pair, "getKey");
                        Object duration = invokeNoArg(pair, "getValue");
                        if (!(target instanceof MobEffect) || !(duration instanceof Number)) incomplete("outputEffectList");
                        JsonObject effect = effect("add_potion_effect", "outputEffectList");
                        addMobEffectTarget(effect, target);
                        addNumber(effect, "base_duration_ticks", duration);
                    }
                } else incomplete("outputEffectList");
            } else if (className.endsWith("RecipePotionFill")) {
                Object maximum = readDeclaredField(recipe, "maxEffects");
                if (!(maximum instanceof Number)) incomplete("maxEffects");
                JsonObject effect = effect("truncate_potion_effect_list", "maxEffects");
                addNumber(effect, "maximum_effects", maximum);
                requirement("minimum_existing_effects", 1, "canModifyFlask()");
            }
        }

        private void tconstructMaterialMelting(Object recipe) {
            Object material = readDeclaredField(recipe, "input");
            String materialId = materialVariantId(String.valueOf(material));
            if (material == null || materialId.isBlank() || materialId.equals("null")) incomplete("input");
            else resource("material", materialId, Direction.INPUT, "input");
            if (!collectFluidOutput(readDeclaredField(recipe, "result"), Direction.OUTPUT, "result")) incomplete("result");
            Object byproducts = readDeclaredField(recipe, "byproducts");
            if (byproducts instanceof Collection<?> values) {
                int index = 0;
                for (Object output : values) {
                    if (!collectFluidOutput(output, Direction.OUTPUT, "byproducts[" + index + "]")) incomplete("byproducts[" + index + "]");
                    index++;
                }
            } else incomplete("byproducts");
            Object temperature = readDeclaredField(recipe, "temperature");
            if (temperature instanceof Number number) requirement("heat", number, "temperature");
            else incomplete("temperature");
            requirement("quantity_basis", "tconstruct_part_material_cost", "MaterialCastingLookup.getItemCost()");
            JsonObject effect = effect("scale_fluid_outputs_by_material_cost", "MaterialCastingLookup.getItemCost()");
            effect.addProperty("input_material", materialId);
        }

        private void tconstructPartRecycle(Object recipe) {
            collectDeclaredField(recipe, "tool", Direction.INPUT);
            collectDeclaredField(recipe, "pattern", Direction.INPUT);
            Object resultCount = readDeclaredField(recipe, "resultCount");
            if (resultCount instanceof Number number) requirement("total_recoverable_units", number, "resultCount");
            requirement("tool_must_have_no_upgrades", true, "matches()");
            requirement("quantity_basis", "remaining_durability_fraction", "getAmount()");
            Object results = readDeclaredField(recipe, "results");
            if (results instanceof Map<?, ?> map) {
                map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey()))).forEach(entry -> {
                    Object stackValue = invokeNoArg(entry.getValue(), "get");
                    Object countValue = invokeNoArg(entry.getValue(), "getCount");
                    if (!(stackValue instanceof ItemStack stack) || stack.isEmpty() || !(countValue instanceof Number)) {
                        incomplete("results[" + entry.getKey() + "]");
                        return;
                    }
                    JsonObject effect = effect("select_recycling_output", "results[" + entry.getKey() + "]");
                    effect.addProperty("selector_kind", "part_pattern");
                    effect.addProperty("selector_id", String.valueOf(entry.getKey()));
                    JsonObject output = itemEdge(stack, "results[" + entry.getKey() + "]");
                    effect.add("output", output.deepCopy());
                    item(stack, Direction.OUTPUT, "results[" + entry.getKey() + "]");
                    addNumber(effect, "maximum_count", countValue);
                });
            } else incomplete("results");
        }

        private void tconstructToolRecycle(Object recipe) {
            if (!collectSizedIngredient(readDeclaredField(recipe, "toolRequirement"), "toolRequirement")) incomplete("toolRequirement");
            collectDeclaredField(recipe, "pattern", Direction.INPUT);
            requirement("tool_must_have_no_modifiers", true, "matches()");
            requirement("quantity_basis", "tool_material_slots_and_durability", "assemble()+getLeftover()");
            JsonObject effect = effect("recover_selected_material_tool_part", "assemble()+getLeftover()");
            effect.addProperty("selector_kind", "part_pattern");
            effect.addProperty("material_source", "selected_tool_material_slot");
            Object parts = readDeclaredField(recipe, "parts");
            Set<String> allowedIds = new TreeSet<>();
            if (parts instanceof Collection<?> values) {
                for (Object value : values) {
                    if (value instanceof net.minecraft.world.item.Item item) {
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                        if (id != null) allowedIds.add(id.toString());
                    }
                }
            }
            if (allowedIds.isEmpty()) {
                effect.addProperty("allowed_parts", "tool_definition_material_parts");
            } else {
                JsonArray allowed = new JsonArray();
                allowedIds.forEach(allowed::add);
                effect.add("allowed_parts", allowed);
            }
        }

        private void tconstructToolDamage(Object recipe) {
            Object ingredient = readDeclaredField(recipe, "ingredient");
            if (ingredient instanceof Ingredient value) collect(value, Direction.INPUT, "ingredient", 0);
            else incomplete("ingredient");
            requirement("mutable_tool_input", true, "ITinkerStationContainer.getTinkerableStack()");
            Object damage = readDeclaredField(recipe, "damageAmount");
            if (!(damage instanceof Number)) incomplete("damageAmount");
            JsonObject effect = effect("add_tool_damage", "damageAmount");
            addNumber(effect, "damage", damage);
        }

        private void matterCannonAmmo(Object recipe) {
            Object ammo = invokeNoArg(recipe, "getAmmo");
            if (ammo instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getAmmo()", 0);
            else incomplete("getAmmo()");
            Object weight = invokeNoArg(recipe, "getWeight");
            if (weight instanceof Number number) requirement("ammo_weight", number, "getWeight()");
            else incomplete("getWeight()");
            requirement("consumer_machine", "ae2:matter_cannon", "MatterCannonAmmo.TYPE_ID");
            JsonObject effect = effect("define_projectile_damage_profile", "getWeight()");
            addNumber(effect, "weight", weight);
        }

        private void clientRecipeTracker(Object recipe) {
            Object namespaceValue = readDeclaredField(recipe, "namespace");
            Object recipesValue = readDeclaredField(recipe, "recipes");
            if (!(namespaceValue instanceof String namespace) || !(recipesValue instanceof Map<?, ?> links)) {
                incomplete("namespace+recipes");
                return;
            }
            requirement("gameplay_recipe", false, "ClientRecipeTracker.matches()=false");
            requirement("scope", "client_recipe_synchronization", "ClientRecipeTracker");
            JsonObject effect = effect("exclude_non_gameplay_recipe_tracker", "namespace+recipes");
            effect.addProperty("namespace", namespace);
            JsonArray exactLinks = new JsonArray();
            links.values().stream().sorted(Comparator.comparing(link -> String.valueOf(invokeNoArg(link, "id"))))
                    .forEach(link -> {
                        Object id = invokeNoArg(link, "id");
                        Object unified = invokeNoArg(link, "isUnified");
                        Object duplicate = invokeNoArg(link, "isDuplicate");
                        if (!(id instanceof ResourceLocation) || !(unified instanceof Boolean) || !(duplicate instanceof Boolean)) {
                            incomplete("recipes.ClientRecipeLink");
                            return;
                        }
                        JsonObject row = new JsonObject();
                        row.addProperty("recipe_id", id.toString());
                        row.addProperty("unified", (Boolean) unified);
                        row.addProperty("duplicate", (Boolean) duplicate);
                        exactLinks.add(row);
                    });
            effect.add("linked_recipes", exactLinks);
        }

        private void malumSpiritRepair(Object recipe) {
            Object eligibleValue = readDeclaredField(recipe, "inputs");
            JsonArray eligibleIds = new JsonArray();
            if (eligibleValue instanceof Collection<?> eligible) {
                addItemAlternatives(eligible, "inputs", eligibleIds);
            }
            if (eligibleIds.isEmpty()) incomplete("inputs");

            Object repairMaterial = readDeclaredField(recipe, "repairMaterial");
            Object repairIngredient = readDeclaredField(repairMaterial, "ingredient");
            Object repairCount = readDeclaredField(repairMaterial, "count");
            if (repairIngredient instanceof Ingredient ingredient && repairCount instanceof Number count) {
                ingredient(ingredient, Direction.INPUT, "repairMaterial.ingredient", count.intValue());
            } else incomplete("repairMaterial.ingredient+count");

            Object spiritValue = readDeclaredField(recipe, "spirits");
            if (spiritValue instanceof Collection<?> spirits && !spirits.isEmpty()) {
                int index = 0;
                for (Object spirit : spirits) {
                    Object stack = invokeNoArg(spirit, "getStack");
                    if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                        item(itemStack, Direction.INPUT, "spirits[" + index + "].getStack()");
                    } else incomplete("spirits[" + index + "].getStack()");
                    index++;
                }
            } else incomplete("spirits");

            Object durability = readDeclaredField(recipe, "durabilityPercentage");
            if (!(durability instanceof Number)) incomplete("durabilityPercentage");
            requirement("mutable_item_input", true, "SpiritRepairRecipe.getRepairRecipeOutput()");
            JsonObject effect = effect("repair_item_durability_fraction", "durabilityPercentage");
            addNumber(effect, "durability_fraction", durability);
            effect.add("eligible_item_ids", eligibleIds);
            effect.addProperty("output_source", "mutable_item_input");
        }

        private void pneumaticHeatProperties(Object recipe) {
            Object blockValue = invokeNoArg(recipe, "getBlock");
            Object capacity = invokeNoArg(recipe, "getHeatCapacity");
            Object temperature = invokeNoArg(recipe, "getTemperature");
            Object resistance = invokeNoArg(recipe, "getThermalResistance");
            if (!(blockValue instanceof Block block) || !(capacity instanceof Number)
                    || !(temperature instanceof Number) || !(resistance instanceof Number)) {
                incomplete("getBlock()+getHeatCapacity()+getTemperature()+getThermalResistance()");
                return;
            }
            resource("block", BuiltInRegistries.BLOCK.getKey(block), Direction.INPUT, "getBlock()");
            JsonObject effect = effect("define_block_heat_properties", "HeatPropertiesRecipe display API");
            effect.addProperty("block_id", String.valueOf(BuiltInRegistries.BLOCK.getKey(block)));
            addNumber(effect, "heat_capacity", capacity);
            addNumber(effect, "temperature", temperature);
            addNumber(effect, "thermal_resistance", resistance);
            addBlockState(effect, "input_state", invokeNoArg(recipe, "getBlockState"));
            addBlockState(effect, "hot_transform", invokeNoArg(recipe, "getTransformHot"));
            addBlockState(effect, "hot_flowing_transform", invokeNoArg(recipe, "getTransformHotFlowing"));
            addBlockState(effect, "cold_transform", invokeNoArg(recipe, "getTransformCold"));
            addBlockState(effect, "cold_flowing_transform", invokeNoArg(recipe, "getTransformColdFlowing"));
            Object predicateValue = invokeNoArg(recipe, "getBlockStatePredicates");
            if (predicateValue instanceof Map<?, ?> predicates) {
                JsonObject exactPredicates = new JsonObject();
                predicates.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                        .forEach(entry -> exactPredicates.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
                effect.add("state_predicates", exactPredicates);
            } else incomplete("getBlockStatePredicates()");
            Object description = invokeNoArg(recipe, "getDescriptionKey");
            if (description instanceof String value && !value.isBlank()) effect.addProperty("description_key", value);
        }

        private void pneumaticFuelQuality(Object recipe) {
            Object fuel = invokeNoArg(recipe, "getFuel");
            Object fluidsValue = invokeNoArg(fuel, "getFluidStacks");
            int fluids = 0;
            if (fluidsValue instanceof Collection<?> stacks) {
                for (Object value : stacks) {
                    if (value instanceof FluidStack stack && !stack.isEmpty()) {
                        fluid(stack, Direction.INPUT, "getFuel().getFluidStacks()");
                        fluids++;
                    }
                }
            }
            Object air = invokeNoArg(recipe, "getAirPerBucket");
            Object burnRate = invokeNoArg(recipe, "getBurnRate");
            if (fluids == 0 || !(air instanceof Number) || !(burnRate instanceof Number)) {
                incomplete("getFuel()+getAirPerBucket()+getBurnRate()");
            }
            JsonObject effect = effect("define_fluid_fuel_quality", "FuelQualityRecipe display API");
            addNumber(effect, "air_per_bucket", air);
            addNumber(effect, "burn_rate", burnRate);
            requirement("consumer_machine", "pneumaticcraft:liquid_compressor", "FuelQualityRecipe.TYPE");
        }

        private void arsScryRitual(Object recipe) {
            Object augment = invokeNoArg(recipe, "augment");
            Object highlight = invokeNoArg(recipe, "highlight");
            ResourceLocation augmentId = tagLocation(augment);
            ResourceLocation highlightId = tagLocation(highlight);
            if (augmentId == null || highlightId == null) incomplete("augment()+highlight()");
            if (augmentId != null) resource("item_tag", augmentId, Direction.INPUT, "augment()");
            JsonObject effect = effect("highlight_blocks_matching_tag", "highlight()");
            if (highlightId != null) effect.addProperty("block_tag", highlightId.toString());
            requirement("consumer_ritual", "ars_nouveau:scrying", "ScryRitualRecipe");
        }

        private void bloodMagicLivingDowngrade(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            Object downgrade = invokeNoArg(recipe, "getLivingArmourResource");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getInput()", 0);
            else incomplete("getInput()");
            if (!(downgrade instanceof ResourceLocation)) incomplete("getLivingArmourResource()");
            requirement("mutable_living_armor_input", true, "RecipeLivingDowngrade");
            JsonObject effect = effect("remove_living_armor_downgrade", "getLivingArmourResource()");
            if (downgrade instanceof ResourceLocation id) effect.addProperty("downgrade_id", id.toString());
            effect.addProperty("output_source", "mutable_living_armor_input");
        }

        private void goetyBrewing(Object recipe) {
            Object input = invokeNoArg(recipe, "getInput");
            if (input instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "getInput()", 0);
            else incomplete("getInput()");
            Object mobEffect = invokeNoArg(recipe, "getOutput");
            ResourceLocation effectId = mobEffect instanceof MobEffect effect ? BuiltInRegistries.MOB_EFFECT.getKey(effect) : null;
            Object entityType = invokeNoArg(recipe, "getEntityType");
            Object entityTag = invokeNoArg(recipe, "getEntityTypeTag");
            ResourceLocation entityId = entityType instanceof EntityType<?> type ? BuiltInRegistries.ENTITY_TYPE.getKey(type) : null;
            ResourceLocation entityTagId = tagLocation(entityTag);
            Object soulCost = invokeNoArg(recipe, "getSoulCost");
            Object capacity = invokeNoArg(recipe, "getCapacityExtra");
            Object duration = invokeNoArg(recipe, "getDuration");
            if (effectId == null || !(soulCost instanceof Number)
                    || !(capacity instanceof Number) || !(duration instanceof Number)) incomplete("Goety BrewingRecipe display API");
            JsonObject effect = effect("apply_mob_effect_to_entity_selector", "BrewingRecipe display API");
            if (effectId != null) effect.addProperty("mob_effect_id", effectId.toString());
            if (entityId != null) effect.addProperty("entity_type_id", entityId.toString());
            if (entityTagId != null) effect.addProperty("entity_type_tag", entityTagId.toString());
            if (entityId == null && entityTagId == null) effect.addProperty("entity_selector", "unrestricted");
            addNumber(effect, "duration_ticks", duration);
            addNumber(effect, "capacity_extra", capacity);
            if (soulCost instanceof Number number) requirement("soul_cost", number, "getSoulCost()");
        }

        private void goetySoulAbsorber(Object recipe) {
            Object ingredientValue = readDeclaredField(recipe, "ingredient");
            if (ingredientValue instanceof Ingredient ingredient) collect(ingredient, Direction.INPUT, "ingredient", 0);
            else incomplete("ingredient");
            Object souls = invokeNoArg(recipe, "getSoulIncrease");
            Object time = invokeNoArg(recipe, "getCookingTime");
            if (!(souls instanceof Number) || !(time instanceof Number)) incomplete("getSoulIncrease()+getCookingTime()");
            JsonObject effect = effect("generate_souls", "getSoulIncrease()");
            addNumber(effect, "amount", souls);
            if (time instanceof Number number) requirement("time", number, "getCookingTime()");
            requirement("consumer_machine", "goety:soul_absorber", "SoulAbsorberRecipes");
        }

        private void addItemAlternatives(Collection<?> values, String path, JsonArray ids) {
            JsonObject group = new JsonObject();
            group.addProperty("slot", inputGroups.size());
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            values.stream().filter(Item.class::isInstance).map(Item.class::cast)
                    .sorted(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))))
                    .forEach(item -> {
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                        if (id == null) return;
                        JsonObject edge = edge("item", id.toString(), 1, path);
                        alternatives.add(edge.deepCopy());
                        addEdge(Direction.INPUT, edge);
                        ids.add(id.toString());
                    });
            group.add("alternatives", alternatives);
            if (!alternatives.isEmpty()) inputGroups.add(group);
        }

        private static ResourceLocation tagLocation(Object value) {
            return value instanceof TagKey<?> tag ? tag.location() : null;
        }

        private static void addBlockState(JsonObject target, String key, Object value) {
            if (!(value instanceof BlockState state)) return;
            JsonObject row = new JsonObject();
            row.addProperty("block_id", String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
            JsonObject properties = new JsonObject();
            state.getValues().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> properties.addProperty(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue())));
            row.add("properties", properties);
            target.add(key, row);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static String propertyValueName(Property property, Comparable value) {
            return property.getName(value);
        }

        private void operation(String kind, String path) {
            if (operationKind != null) return;
            operationKind = kind;
            evidence("operation_kind", path);
        }

        private JsonObject effect(String kind, String path) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("semantic_path", path);
            effects.add(row);
            evidence("effect", path);
            return row;
        }

        private void incomplete(String path) {
            contextualComplete = false;
            evidence("unavailable_contextual_evidence", path);
        }

        private static void addNumber(JsonObject target, String key, Object value) {
            if (value instanceof Number number) target.addProperty(key, number);
        }

        private static void addMobEffectTarget(JsonObject target, Object value) {
            if (value instanceof MobEffect effect) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
                if (id != null) {
                    target.addProperty("target_kind", "mob_effect");
                    target.addProperty("target_id", id.toString());
                }
            }
        }

        private static JsonArray mobEffectIds(Object value) {
            JsonArray ids = new JsonArray();
            if (value instanceof Collection<?> effects) {
                effects.stream().filter(MobEffect.class::isInstance).map(MobEffect.class::cast)
                        .map(BuiltInRegistries.MOB_EFFECT::getKey).filter(java.util.Objects::nonNull)
                        .map(ResourceLocation::toString).sorted().forEach(ids::add);
            }
            return ids;
        }

        private int collectRegistryItemsBySuperclass(String className, String path) {
            List<net.minecraft.world.item.Item> matches = BuiltInRegistries.ITEM.stream()
                    .filter(item -> superclassNamed(item.getClass(), className))
                    .sorted(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))))
                    .toList();
            matches.forEach(item -> resource("item", BuiltInRegistries.ITEM.getKey(item), Direction.INPUT, path));
            return matches.size();
        }

        private static boolean superclassNamed(Class<?> type, String className) {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                if (cursor.getName().equals(className)) return true;
            }
            return false;
        }

        private boolean collectFluidOutput(Object output, Direction direction, String path) {
            Object value = invokeNoArg(output, "get");
            if (value instanceof FluidStack stack && !stack.isEmpty()) {
                fluid(stack, direction, path + ".get()");
                return true;
            }
            return false;
        }

        private boolean collectSizedIngredient(Object sized, String path) {
            Object ingredientValue = invokeNoArg(sized, "getIngredient");
            Object amountValue = invokeNoArg(sized, "getAmountNeeded");
            if (ingredientValue instanceof Ingredient ingredient) {
                ingredient(ingredient, Direction.INPUT, path + ".getIngredient()",
                        amountValue instanceof Number number ? number.intValue() : 1);
                return amountValue instanceof Number;
            }
            return false;
        }

        private static Object invokeNoArg(Object root, String name) {
            if (root == null) return null;
            try {
                Method method = root.getClass().getMethod(name);
                method.trySetAccessible();
                return method.invoke(root);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void collectAccessor(Object root, String name, Direction direction) {
            try {
                Method method = root.getClass().getMethod(name);
                collect(method.invoke(root), direction, name + "()", 0);
            } catch (Throwable ignored) {
                // The exact pinned accessor is optional evidence.
            }
        }

        private void collectField(Object root, String name, Direction direction) {
            try {
                Field field = root.getClass().getField(name);
                collect(field.get(root), direction, name, 0);
            } catch (Throwable ignored) {
                // The exact pinned public field is optional evidence.
            }
        }

        private void collectDeclaredField(Object root, String name, Direction direction) {
            Object value = readDeclaredField(root, name);
            if (value != null) collect(value, direction, name, 0);
        }

        private Object readDeclaredField(Object root, String name) {
            for (Class<?> cursor = root.getClass(); cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    if (field.trySetAccessible()) return field.get(root);
                } catch (Throwable ignored) {
                    // Try the next superclass; absence is not evidence.
                }
            }
            return null;
        }

        private void collectNumberAccessor(Object root, String accessor, String requirement) {
            try {
                Object value = root.getClass().getMethod(accessor).invoke(root);
                if (value instanceof Number number) requirement(requirement, number, accessor + "()");
            } catch (Throwable ignored) {
                // A missing optional accessor is not evidence.
            }
        }

        private void collectIntRange(Object range, String prefix) {
            if (range == null) return;
            collectNumberAccessor(range, "min", prefix + "_min");
            collectNumberAccessor(range, "max", prefix + "_max");
        }

        private void collectModifierSlots(Object slots, String path) {
            if (slots == null) return;
            try {
                Object type = slots.getClass().getMethod("type").invoke(slots);
                Object countValue = slots.getClass().getMethod("count").invoke(slots);
                Object name = type.getClass().getMethod("getName").invoke(type);
                if (name instanceof String id && countValue instanceof Number count) {
                    resource("modifier_slot", "tconstruct:" + id, count.intValue(), Direction.OUTPUT, path);
                }
            } catch (Throwable ignored) {
                // A missing optional slot API is not evidence.
            }
        }

        private static List<Field> publicFields(Class<?> type) {
            return safeMembers(type::getFields);
        }

        void collect(Object value, Direction direction, String path, int depth) {
            if (value == null || depth > MAX_DEPTH) return;
            String className = value.getClass().getName();
            if (className.equals("slimeknights.tconstruct.library.materials.definition.MaterialVariant")) {
                resource("material", materialVariantId(value.toString()), direction, path);
                return;
            }
            if (className.equals("slimeknights.tconstruct.library.modifiers.ModifierEntry")
                    || className.equals("slimeknights.tconstruct.library.modifiers.util.LazyModifier")) {
                collectIdAccessor(value, "modifier", direction, path);
                return;
            }
            if (value instanceof Ingredient ingredient) {
                ingredient(ingredient, direction, path);
                return;
            }
            if (value instanceof ItemStack stack) {
                item(stack, direction, path);
                return;
            }
            if (value instanceof Item item) {
                resource("item", BuiltInRegistries.ITEM.getKey(item), direction, path);
                return;
            }
            if (value instanceof FluidStack stack) {
                fluid(stack, direction, path);
                return;
            }
            if (value instanceof Block block) {
                resource("block", BuiltInRegistries.BLOCK.getKey(block), direction, path);
                return;
            }
            if (value instanceof Enchantment enchantment) {
                resource("enchantment", BuiltInRegistries.ENCHANTMENT.getKey(enchantment), direction, path);
                return;
            }
            if (value instanceof ResourceLocation id) {
                resource(resourceKind(value.getClass()), id, direction, path);
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
            ingredient(ingredient, direction, path, 1);
        }

        private void ingredient(Ingredient ingredient, Direction direction, String path, int count) {
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
                JsonObject edge = edge("tag", tagId, Math.max(count, RecipeGraphExporter.ingredientCount(definition, ingredient)), path);
                addEdge(direction, edge);
                group.addProperty("membership", "generated/runtime-dumps/tags.json#item_tags/" + tagId);
            } else {
                try {
                    for (ItemStack stack : ingredient.getItems()) {
                        if (count > 1) stack = stack.copyWithCount(count);
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

        private void resource(String kind, ResourceLocation id, Direction direction, String path) {
            if (id != null) resource(kind, id.toString(), direction, path);
        }

        private void resource(String kind, String id, Direction direction, String path) {
            resource(kind, id, 1, direction, path);
        }

        private void resource(String kind, String id, int count, Direction direction, String path) {
            if (id == null || id.isBlank() || direction == Direction.UNKNOWN) return;
            JsonObject edge = edge(kind, id, count, path);
            addEdge(direction, edge);
            if (direction == Direction.OUTPUT) addOutputGroup(edge, path);
            evidence("edge", path);
        }

        private void collectIdAccessor(Object value, String kind, Direction direction, String path) {
            try {
                Object id = value.getClass().getMethod("getId").invoke(value);
                if (id instanceof ResourceLocation resourceId) resource(kind, resourceId, direction, path + ".getId()");
            } catch (Throwable ignored) {
                // A missing optional accessor is not evidence.
            }
        }

        private static String resourceKind(Class<?> type) {
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (name.contains("material")) return "material";
            if (name.contains("modifier")) return "modifier";
            return "resource";
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

        private void addOutputGroup(JsonObject edge, String path) {
            JsonObject group = new JsonObject();
            group.addProperty("slot", outputGroups.size());
            group.addProperty("semantic_path", path);
            JsonArray alternatives = new JsonArray();
            alternatives.add(edge.deepCopy());
            group.add("alternatives", alternatives);
            outputGroups.add(group);
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
                fields.addAll(safeMembers(cursor::getDeclaredFields));
            }
            fields.sort(Comparator.comparing(Field::toGenericString));
            return fields;
        }
    }
}
