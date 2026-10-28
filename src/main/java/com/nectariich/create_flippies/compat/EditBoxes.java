package com.nectariich.create_flippies.compat;

import net.minecraft.client.gui.components.EditBox;

/**
 * {@code EditBox} calls that changed. 1.21.1's cursor moves take a flag to extend the
 * selection; these never do, as 1.20.1's did not with shift up.
 */
public final class EditBoxes {

    private EditBoxes() {}

    /** {@code box.moveCursorToStart()}. */
    public static void cursorToStart(EditBox box) {
        //? if forge {
        box.moveCursorToStart();
        //?} else {
        /*box.moveCursorToStart(false);
        *///?}
    }

    /** {@code box.moveCursorToEnd()}. */
    public static void cursorToEnd(EditBox box) {
        //? if forge {
        box.moveCursorToEnd();
        //?} else {
        /*box.moveCursorToEnd(false);
        *///?}
    }

    /** {@code box.tick()}. 1.21.1 blinks the cursor by the clock and has no tick. */
    public static void tick(EditBox box) {
        //? if forge {
        box.tick();
        //?}
    }
}
