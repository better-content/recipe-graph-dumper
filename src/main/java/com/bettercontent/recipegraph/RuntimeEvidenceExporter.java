package com.bettercontent.recipegraph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootDataId;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeEvidenceExporter {
    private static final int TRADE_SAMPLE_COUNT = 16;

    private RuntimeEvidenceExporter() {}

    static JsonObject loot(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonObject tables = new JsonObject();
        JsonArray issues = new JsonArray();
        LootDataManager manager = server.getLootData();
        List<ResourceLocation> keys = new ArrayList<>(manager.getKeys(LootDataType.TABLE));
        keys.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation key : keys) {
            try {
                LootTable table = manager.getElement(new LootDataId<>(LootDataType.TABLE, key));
                tables.add(key.toString(), LootDataType.TABLE.parser().toJsonTree(table));
            } catch (Exception error) {
                issues.add(issue(key.toString(), error));
            }
        }
        out.addProperty("table_count", tables.size());
        out.addProperty("error_count", issues.size());
        out.add("tables", tables);
        out.add("issues", issues);
        return out;
    }

    static JsonObject trades(MinecraftServer server) {
        JsonObject out = new JsonObject();
        JsonArray villagers = new JsonArray();
        JsonArray wanderer = new JsonArray();
        JsonArray issues = new JsonArray();

        Villager entity = EntityType.VILLAGER.create(server.overworld());
        if (entity == null) {
            issues.add("could not construct villager sample entity");
        } else {
            VillagerTrades.TRADES.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> registryId(BuiltInRegistries.VILLAGER_PROFESSION, entry.getKey())))
                    .forEach(entry -> exportProfession(entry.getKey(), entry.getValue(), entity, villagers, issues));
            entity.discard();
        }

        WanderingTrader wanderingEntity = EntityType.WANDERING_TRADER.create(server.overworld());
        if (wanderingEntity == null) {
            issues.add("could not construct wandering trader sample entity");
        } else {
            VillagerTrades.WANDERING_TRADER_TRADES.int2ObjectEntrySet().stream()
                    .sorted(Comparator.comparingInt(it -> it.getIntKey()))
                    .forEach(entry -> exportListings(
                            "minecraft:wandering_trader", "minecraft:wandering_trader", entry.getIntKey(),
                            entry.getValue(), wanderingEntity, wanderer, issues));
            wanderingEntity.discard();
        }

        out.addProperty("sampling", "deterministic_16_seeds_per_listing");
        out.addProperty("villager_offer_count", villagers.size());
        out.addProperty("wandering_offer_count", wanderer.size());
        out.addProperty("error_count", issues.size());
        out.add("villager_offers", villagers);
        out.add("wandering_offers", wanderer);
        out.add("issues", issues);
        return out;
    }

    private static void exportProfession(
            VillagerProfession profession,
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<VillagerTrades.ItemListing[]> levels,
            Villager entity,
            JsonArray output,
            JsonArray issues
    ) {
        String professionId = registryId(BuiltInRegistries.VILLAGER_PROFESSION, profession);
        List<VillagerType> types = BuiltInRegistries.VILLAGER_TYPE.stream()
                .sorted(Comparator.comparing(type -> registryId(BuiltInRegistries.VILLAGER_TYPE, type)))
                .toList();
        levels.int2ObjectEntrySet().stream().sorted(Comparator.comparingInt(it -> it.getIntKey())).forEach(level -> {
            for (VillagerType type : types) {
                String typeId = registryId(BuiltInRegistries.VILLAGER_TYPE, type);
                entity.setVillagerData(new VillagerData(type, profession, level.getIntKey()));
                exportListings(professionId, typeId, level.getIntKey(), level.getValue(), entity, output, issues);
            }
        });
    }

    private static void exportListings(
            String profession,
            String villagerType,
            int level,
            VillagerTrades.ItemListing[] listings,
            Entity entity,
            JsonArray output,
            JsonArray issues
    ) {
        for (int listingIndex = 0; listingIndex < listings.length; listingIndex++) {
            VillagerTrades.ItemListing listing = listings[listingIndex];
            Map<String, JsonObject> distinct = new LinkedHashMap<>();
            for (int sample = 0; sample < TRADE_SAMPLE_COUNT; sample++) {
                try {
                    long seed = tradeSeed(profession, villagerType, level, listingIndex, sample);
                    MerchantOffer offer = listing.getOffer(entity, RandomSource.create(seed));
                    if (offer == null) continue;
                    JsonObject row = offerJson(profession, villagerType, level, listingIndex, listing, offer, sample);
                    distinct.putIfAbsent(offer.createTag().toString(), row);
                } catch (Exception error) {
                    issues.add(issue(profession + "/" + villagerType + "/" + level + "/" + listingIndex, error));
                    break;
                }
            }
            distinct.values().forEach(output::add);
        }
    }

    private static JsonObject offerJson(
            String profession,
            String villagerType,
            int level,
            int listingIndex,
            VillagerTrades.ItemListing listing,
            MerchantOffer offer,
            int sample
    ) {
        JsonObject row = new JsonObject();
        row.addProperty("profession", profession);
        row.addProperty("villager_type", villagerType);
        row.addProperty("level", level);
        row.addProperty("listing_index", listingIndex);
        row.addProperty("listing_class", listing.getClass().getName());
        row.addProperty("representative_sample", sample);
        row.add("cost_a", stack(offer.getBaseCostA()));
        row.add("cost_b", stack(offer.getCostB()));
        row.add("result", stack(offer.getResult()));
        row.addProperty("max_uses", offer.getMaxUses());
        row.addProperty("villager_xp", offer.getXp());
        row.addProperty("price_multiplier", offer.getPriceMultiplier());
        row.addProperty("reward_exp", offer.shouldRewardExp());
        row.addProperty("offer_nbt", offer.createTag().toString());
        return row;
    }

    static JsonObject worldgen(RegistryAccess access) {
        JsonObject out = new JsonObject();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        JsonArray issues = new JsonArray();
        out.add("configured_features", encodeRegistry(access, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC, ops, issues));
        out.add("placed_features", encodeRegistry(access, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC, ops, issues));
        out.add("biomes", encodeRegistry(access, Registries.BIOME, Biome.DIRECT_CODEC, ops, issues));
        out.add("structures", encodeRegistry(access, Registries.STRUCTURE, Structure.DIRECT_CODEC, ops, issues));
        out.add("biome_modifiers", encodeRegistry(access, ForgeRegistries.Keys.BIOME_MODIFIERS, BiomeModifier.DIRECT_CODEC, ops, issues));
        out.addProperty("error_count", issues.size());
        out.add("issues", issues);
        return out;
    }

    private static <T> JsonObject encodeRegistry(
            RegistryAccess access,
            ResourceKey<? extends Registry<T>> key,
            Codec<T> codec,
            RegistryOps<JsonElement> ops,
            JsonArray issues
    ) {
        JsonObject rows = new JsonObject();
        Registry<T> registry;
        try {
            registry = access.registryOrThrow(key);
        } catch (Exception error) {
            issues.add(issue(key.location().toString(), error));
            return rows;
        }
        registry.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().location().toString())).forEach(entry -> {
            String id = entry.getKey().location().toString();
            try {
                JsonObject row = new JsonObject();
                row.addProperty("java_class", entry.getValue().getClass().getName());
                JsonElement encoded = codec.encodeStart(ops, entry.getValue())
                        .getOrThrow(false, message -> { throw new IllegalStateException(message); });
                row.add("value", encoded);
                rows.add(id, row);
            } catch (Exception error) {
                issues.add(issue(key.location() + "/" + id, error));
            }
        });
        return rows;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", "item");
        out.addProperty("id", registryId(ForgeRegistries.ITEMS, stack.getItem()));
        out.addProperty("count", stack.getCount());
        if (stack.hasTag()) out.addProperty("nbt", stack.getTag().toString());
        return out;
    }

    static long tradeSeed(String profession, String type, int level, int index, int sample) {
        long seed = 1125899906842597L;
        seed = seed * 31 + profession.hashCode();
        seed = seed * 31 + type.hashCode();
        seed = seed * 31 + level;
        seed = seed * 31 + index;
        return seed * 31 + sample;
    }

    private static JsonObject issue(String id, Exception error) {
        JsonObject issue = new JsonObject();
        issue.addProperty("id", id);
        issue.addProperty("error", error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        return issue;
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key == null ? "UNKNOWN" : key.toString();
    }

    private static <T> String registryId(net.minecraftforge.registries.IForgeRegistry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key == null ? "UNKNOWN" : key.toString();
    }
}
