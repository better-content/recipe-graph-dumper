package com.bettercontent.runtimedatadumper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CombatArmorCanonTest {
    @Test
    void nearestRankUsesTheDocumentedPercentiles() {
        List<Double> values = List.of(0.0, 0.0, 2.0, 4.0, 8.0, 12.0, 16.0, 20.0);
        assertEquals(4.0, CombatArmorCanon.nearestRank(values, 0.50));
        assertEquals(12.0, CombatArmorCanon.nearestRank(values, 0.75));
        assertEquals(20.0, CombatArmorCanon.nearestRank(values, 0.90));
    }

    @Test
    void representativesAdvanceAcrossDuplicatePercentilesAndExcludeBosses() {
        List<CombatArmorCanon.Sample> samples = List.of(
                sample("a", 0, 20), sample("b", 0, 20), sample("c", 0, 20),
                sample("d", 2, 20), sample("e", 6, 40), sample("boss", 20, 100)
        );
        var result = CombatArmorCanon.representatives(samples);
        assertEquals(0.0, result.trash());
        assertEquals(2.0, result.elite());
        assertEquals(6.0, result.boss());
        assertEquals(1.0, result.trashEliteBoundary());
        assertEquals(4.0, result.eliteBossBoundary());
    }

    private static CombatArmorCanon.Sample sample(String id, double armor, double health) {
        return new CombatArmorCanon.Sample(id, armor, 0, health, health >= 100);
    }
}
