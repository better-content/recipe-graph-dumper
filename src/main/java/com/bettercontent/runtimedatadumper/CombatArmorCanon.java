package com.bettercontent.runtimedatadumper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CombatArmorCanon {
    private static final double ARMOR_CAP = 20.0;

    private CombatArmorCanon() {}

    record Sample(String entityId, double armor, double armorToughness, double maxHealth, boolean excludedBoss) {}

    record Representatives(double trash, double elite, double boss) {
        double trashEliteBoundary() {
            return (trash + elite) / 2.0;
        }

        double eliteBossBoundary() {
            return (elite + boss) / 2.0;
        }
    }

    static Representatives representatives(List<Sample> samples) {
        List<Double> armor = samples.stream()
                .filter(sample -> !sample.excludedBoss())
                .map(Sample::armor)
                .sorted()
                .toList();
        if (armor.isEmpty()) {
            throw new IllegalArgumentException("No non-boss hostile armor samples were available");
        }

        List<Double> distinct = new ArrayList<>(armor.stream().distinct().toList());
        double trash = nearestRank(armor, 0.50);
        double elite = nextDistinctOrStep(distinct, nearestRank(armor, 0.75), trash);
        double boss = nextDistinctOrStep(distinct, nearestRank(armor, 0.90), elite);
        return new Representatives(trash, elite, boss);
    }

    static double nearestRank(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            throw new IllegalArgumentException("Cannot select a percentile from an empty sample");
        }
        int rank = Math.max(1, (int)Math.ceil(percentile * sortedValues.size()));
        return sortedValues.get(Math.min(sortedValues.size(), rank) - 1);
    }

    private static double nextDistinctOrStep(List<Double> distinct, double candidate, double previous) {
        if (candidate > previous) {
            return candidate;
        }
        return distinct.stream()
                .filter(value -> value > previous)
                .min(Comparator.naturalOrder())
                .orElse(Math.min(ARMOR_CAP, previous + 2.0));
    }
}
