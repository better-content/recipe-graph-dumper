package com.bettercontent.runtimedatadumper;

record CombatDumpResult(boolean success, int sampledEntities, int excludedBosses, int errors, String output, String message) {
    static CombatDumpResult failure(String output, String message) {
        return new CombatDumpResult(false, 0, 0, 1, output, message);
    }
}
