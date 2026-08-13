package com.bettercontent.runtimedatadumper;

public record DumpResult(
        boolean success,
        boolean complete,
        String snapshotId,
        int recipeCount,
        int partialCount,
        int errorCount,
        String outputDirectory,
        String message
) {
    static DumpResult failure(String message, String outputDirectory) {
        return new DumpResult(false, false, "UNKNOWN", 0, 0, 1, outputDirectory, message);
    }
}
