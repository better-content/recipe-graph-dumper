package com.bettercontent.recipegraph.mixin;

import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iron's Spellbooks 3.15.6 was compiled against a newer Gson than Minecraft
 * 1.20.1 supplies. Preserve the serializer's intended emptiness check without
 * linking the unavailable JsonObject.isEmpty() method.
 */
@Mixin(targets = "io.redspace.ironsspellbooks.loot.SpellFilter", remap = false)
abstract class SpellFilterMixin {
    @Redirect(
            method = "serialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/gson/JsonObject;isEmpty()Z",
                    remap = false
            ),
            require = 1,
            remap = false
    )
    private boolean bcrecipegraph$gsonCompatibleIsEmpty(JsonObject object) {
        return object.entrySet().isEmpty();
    }
}
