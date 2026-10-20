package com.nectariich.create_flippies.mixin;

import java.util.Map;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the font manager's map of font sets by id. Every {@code Font} the client
 * makes looks a style's font up in this map at draw time, so a set put here is
 * drawable through {@code Style.withFont} like any font a resource pack defines.
 * The manager empties the map on every resource reload and closes what was in it,
 * which is why {@link com.nectariich.create_flippies.client.design.DesignFonts}
 * checks its own entries are still there before each use.
 */
@Mixin(FontManager.class)
public interface FontManagerAccessor {

    @Accessor("fontSets")
    Map<ResourceLocation, FontSet> create_flippies$fontSets();
}
