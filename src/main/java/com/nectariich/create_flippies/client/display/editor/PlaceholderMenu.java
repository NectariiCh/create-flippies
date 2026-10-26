package com.nectariich.create_flippies.client.display.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The third sidebar menu, empty for now: it holds a place in the layout (so players get
 * used to stacking and moving menus) until a real menu replaces it. Its content is one dim
 * "coming soon" line, wrapped to the menu's width.
 */
public final class PlaceholderMenu implements EditorMenu {

    private static final int PAD = 4;
    private static final Component TITLE = Component.translatable("gui.create_flippies.display_editor.placeholder");
    private static final Component SOON = Component.translatable(
            "gui.create_flippies.display_editor.placeholder.coming_soon");

    private final Font font;
    private int x, y, width, height;

    public PlaceholderMenu(Font font) {
        this.font = font;
    }

    @Override
    public String id() {
        return "placeholder";
    }

    @Override
    public Component title() {
        return TITLE;
    }

    @Override
    public void layout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (width <= 2 * PAD || height <= PAD)
            return;
        graphics.drawWordWrap(font, SOON, x + PAD, y + PAD, width - 2 * PAD, 0x6A7480);
    }
}
