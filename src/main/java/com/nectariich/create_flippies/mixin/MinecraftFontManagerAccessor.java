package com.nectariich.create_flippies.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the client's font manager, which {@link Minecraft} keeps private. The
 * Design Workbench registers the font files players print on their controls with it,
 * see {@link com.nectariich.create_flippies.client.design.DesignFonts}.
 */
@Mixin(Minecraft.class)
public interface MinecraftFontManagerAccessor {

    @Accessor("fontManager")
    FontManager create_flippies$fontManager();
}
