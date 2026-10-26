package com.nectariich.create_flippies.client.display.editor;

import com.nectariich.create_flippies.client.display.ElementEditTarget;

import net.minecraft.network.chat.Component;

/**
 * Where a {@link DisplayEditor} runs: the screwdriver menu over a display in the world,
 * or the Design Workbench over the layout a designed screen is minted with. The editor
 * is the same either way; this is everything that differs.
 */
public interface EditorHost {

    /** What the edits go to. */
    ElementEditTarget target();

    /** The camera and its mapping. */
    GlassView view();

    /**
     * What the renderer names the edited display by
     * ({@link com.nectariich.create_flippies.client.display.ElementPreview}): the live
     * {@code ScreenElements} in the world, which a sync may replace (so asked again every
     * frame), or the workbench preview's key.
     */
    Object owner();

    /**
     * Names the display for the clipboard, so a paste can tell it is going back onto the
     * display it was copied from.
     */
    String sourceKey();

    /** Whether the top bar offers Rotate: a display that is a block of its own. */
    boolean canRotate();

    void rotate();

    /** Done or Esc: the host closes the editor (flushing it first). */
    void close();

    Component title();

    /** False once the display has gone or lost power, which closes the editor. */
    default boolean alive() {
        return true;
    }
}
