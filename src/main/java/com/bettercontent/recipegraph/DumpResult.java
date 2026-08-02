package com.bettercontent.recipegraph;

public record DumpResult(
        boolean success,
        String snapshotId,
        int recipeCount,
        int partialCount,
        int errorCount,
        String outputDirectory,
        String message
) {
    static DumpResult failure(String message, String outputDirectory) {
        return new DumpResult(false, "UNKNOWN", 0, 0, 1, outputDirectory, message);
    }
}
