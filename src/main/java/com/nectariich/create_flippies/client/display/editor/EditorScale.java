package com.nectariich.create_flippies.client.display.editor;

import com.mojang.blaze3d.platform.Window;
import com.nectariich.create_flippies.foundation.ClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The display editor's own GUI scale. Its sidebars need far more room than a menu, so
 * on a high density screen, where the game's automatic scale runs to 8 or more, the
 * editor would have none: while it is open the game's GUI scale is set to the editor's
 * instead, and put back when it closes.
 *
 * Setting the window's scale rather than shrinking the editor's drawing keeps
 * everything the game positions on its own (the mouse, tooltips, scissor clipping) in
 * step with what is drawn, for nothing. Nothing else is drawn meanwhile: the HUD is
 * hidden while the editor is open.
 *
 * The host calls {@link #apply} at the top of its {@code init}, which every resize
 * runs, and {@link #restore} once it no longer shows the editor (before another screen
 * is opened, since that one takes its size from the window).
 */
public final class EditorScale {

    /** The scaled room the editor needs for its sidebars, top bar and tool strip. */
    private static final int MIN_WIDTH = 860, MIN_HEIGHT = 520;

    private EditorScale() {}

    /** Sets the editor's scale and gives {@code screen} the size that goes with it. */
    public static void apply(Screen screen) {
        Window window = Minecraft.getInstance().getWindow();
        int scale = editorScale(window);
        if (window.getGuiScale() != scale)
            window.setGuiScale(scale);
        screen.width = window.getGuiScaledWidth();
        screen.height = window.getGuiScaledHeight();
    }

    /** Puts the game's own scale back, giving {@code screen} (if any) the size that goes with it. */
    public static void restore(Screen screen) {
        Window window = Minecraft.getInstance().getWindow();
        int scale = gameScale(window);
        if (window.getGuiScale() != scale)
            window.setGuiScale(scale);
        if (screen != null) {
            screen.width = window.getGuiScaledWidth();
            screen.height = window.getGuiScaledHeight();
        }
    }

    private static int gameScale(Window window) {
        Minecraft minecraft = Minecraft.getInstance();
        return window.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode());
    }

    /** The configured scale, or the largest no larger than the game's that leaves the editor its room. */
    private static int editorScale(Window window) {
        int chosen = ClientConfig.displayEditorGuiScale();
        if (chosen > 0)
            return chosen;
        int scale = gameScale(window);
        while (scale > 1 && (window.getWidth() / scale < MIN_WIDTH || window.getHeight() / scale < MIN_HEIGHT))
            scale--;
        return scale;
    }
}
