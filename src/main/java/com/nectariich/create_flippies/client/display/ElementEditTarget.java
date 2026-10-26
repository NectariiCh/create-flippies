package com.nectariich.create_flippies.client.display;

import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

/**
 * What the display editor ({@link com.nectariich.create_flippies.client.display.editor.DisplayEditor})
 * edits: a set of {@link ScreenElements} and the way an edit reaches them.
 *
 * Two of these exist, which is why the editor speaks to an interface rather than to a
 * packet. The screwdriver menu's ({@link ScreenEditorScreen}) reads the display the
 * client has and sends each edit to the server, which owns it and syncs the result
 * back. The workbench's (a {@link LocalElementTarget}) edits a layout the client owns
 * outright, the one a designed screen is minted with. The editor is the same widget
 * either way, so a screen's layout is authored with exactly the controls that later
 * adjust it on the panel.
 *
 * Every edit names its element by id, never by place in the list, and a set carries a
 * field mask: see {@link ScreenElement.Fields} for why the values are raw.
 */
public interface ElementEditTarget {

    /** The live elements, read every tick; null once the display has gone, which closes the editor. */
    ScreenElements elements();

    /** The rectangle every box is measured in. */
    Viewport viewport();

    void remove(int id);

    /** Sets the fields of that element named in {@code mask} from {@code values}. */
    void set(int id, int mask, ScreenElement.Fields values);

    /** To that place in the drawing order, 0 at the back. */
    void zTo(int id, int index);

    /**
     * Puts an element whole into its own (free) slot at that place in the drawing
     * order, with the slot's generation set to {@code generation}; see
     * {@link ScreenElements#put}. How the display editor places what it draws or
     * pastes, and how its undo brings a deleted element back.
     */
    void put(ScreenElement element, int index, int generation);

    /**
     * Makes sure whoever owns the elements can draw a font file before an edit names
     * it. A display on a server takes a TTF only once the world's design store holds
     * it, so the screwdriver menu uploads it first; a layout the client owns needs
     * nothing until it is saved.
     */
    default void provideFont(String hash) {}

    /**
     * Makes sure whoever owns the elements holds a picture (an SVG element's art, by
     * hash) before an edit names it, as {@link #provideFont} does for a font: a display on
     * a server refuses an element whose picture the world's store lacks.
     */
    default void provideArt(String hash) {}
}
