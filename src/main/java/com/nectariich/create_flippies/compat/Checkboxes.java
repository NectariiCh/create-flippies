package com.nectariich.create_flippies.compat;

import java.util.function.Consumer;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
//? if neoforge {
/*import net.minecraft.client.Minecraft;
*///?}

/**
 * A checkbox that reports each change, on both versions. 1.21.1 made the
 * {@link Checkbox} constructor package-private, so it can no longer be subclassed to
 * hear a press; it is built with a change listener instead.
 *
 * On 1.21.1 the box is sized from the font and {@code width} only caps its label;
 * {@code height} is ignored.
 */
public final class Checkboxes {

    private Checkboxes() {}

    /** {@code onChange} gets the new state after every press. */
    public static Checkbox create(int x, int y, int width, int height, Component label, boolean selected,
            Consumer<Boolean> onChange) {
        //? if forge {
        return new Checkbox(x, y, width, height, label, selected) {
            @Override
            public void onPress() {
                super.onPress();
                onChange.accept(selected());
            }
        };
        //?} else {
        /*return Checkbox.builder(label, Minecraft.getInstance().font)
                .pos(x, y)
                .maxWidth(width)
                .selected(selected)
                .onValueChange((box, value) -> onChange.accept(value))
                .build();
        *///?}
    }
}
