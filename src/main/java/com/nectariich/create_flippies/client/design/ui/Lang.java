package com.nectariich.create_flippies.client.design.ui;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The workbench's strings, all under {@code gui.create_flippies.design_workbench.}. */
public final class Lang {

    public static final String PREFIX = "gui." + CreateFlippies.MOD_ID + ".design_workbench.";

    private Lang() {}

    public static MutableComponent t(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }
}
