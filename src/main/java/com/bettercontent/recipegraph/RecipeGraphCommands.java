package com.bettercontent.recipegraph;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class RecipeGraphCommands {
    private RecipeGraphCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bcgraph")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dump").executes(context -> {
                    var result = RecipeGraphExporter.dump(context.getSource().getServer());
                    if (result.success()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Recipe graph " + result.snapshotId() + ": " + result.recipeCount()
                                        + " recipes, " + result.partialCount() + " partial, "
                                        + result.errorCount() + " errors -> " + result.outputDirectory()), true);
                        return result.errorCount() == 0 ? 1 : 0;
                    }
                    context.getSource().sendFailure(Component.literal("Recipe graph dump failed: " + result.message()));
                    return 0;
                })));
    }
}
