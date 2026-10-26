package com.nectariich.create_flippies.client.display.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One menu of the display editor's sidebars: the hierarchy, the inspector, or the
 * placeholder. The layout ({@code EditorLayout}) owns where it is, its title bar and
 * collapsing; the menu draws and handles only its content area, which it is told with
 * {@link #layout} before every frame it is drawn in. The layout clips drawing to the
 * content area with a scissor, and passes on only input whose point is inside it
 * (except drags and releases, which go to the menu a press started in).
 *
 * Coordinates are the screen's GUI coordinates throughout.
 */
public interface EditorMenu {

    /** Stable, for the saved layout: "hierarchy", "inspector", "placeholder". */
    String id();

    Component title();

    /** Where the content area is this frame. */
    void layout(int x, int y, int width, int height);

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks);

    default void tick() {}

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return false;
    }

    default boolean keyPressed(int key, int scanCode, int modifiers) {
        return false;
    }

    default boolean charTyped(char character, int modifiers) {
        return false;
    }

    /** Whether a text field of this menu has the keyboard, so editor shortcuts must not take keys. */
    default boolean hasTextFocus() {
        return false;
    }

    /** Lets go of the keyboard (a click elsewhere). */
    default void clearFocus() {}
}
