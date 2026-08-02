package com.bettercontent.recipegraph;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(RecipeGraphMod.MOD_ID)
public final class RecipeGraphMod {
    public static final String MOD_ID = "bcrecipegraph";

    public RecipeGraphMod() {
        MinecraftForge.EVENT_BUS.register(RecipeGraphCommands.class);
    }
}
