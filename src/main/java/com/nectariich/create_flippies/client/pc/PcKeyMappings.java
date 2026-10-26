package com.nectariich.create_flippies.client.pc;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * The PC's keys in Minecraft's Controls screen (spec 11.2.15): the toggle that
 * hands a keyboard and mouse's mouse between the PC and looking around. Right
 * Ctrl by default, right Command on macOS, which has no right Ctrl key.
 */
public final class PcKeyMappings {

    public static final String CATEGORY = "key.categories.create_flippies";

    public static final KeyMapping PC_MOUSE_TOGGLE = new KeyMapping("key.create_flippies.pc_mouse_toggle",
            KeyConflictContext.GUI, InputConstants.Type.KEYSYM,
            Minecraft.ON_OSX ? GLFW.GLFW_KEY_RIGHT_SUPER : GLFW.GLFW_KEY_RIGHT_CONTROL, CATEGORY);

    private PcKeyMappings() {}
}
