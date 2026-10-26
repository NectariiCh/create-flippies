package com.nectariich.create_flippies.content.display.element;

/**
 * Anything that shows {@link ScreenElements}: both forms of the alphanumeric display
 * and the designed screen ({@code DesignedScreenControl}).
 *
 * What the menu, the links and the renderers need to ask of a display without caring
 * which of them it is: the elements themselves, the rectangle they are laid out in,
 * how to say they have changed, and the two feeds that decide whether they can be
 * seen. The block form saves and syncs through its block entity and a mounted one
 * through its host, which is the only reason {@link #elementsChanged} is not simply a
 * call every implementation could share.
 *
 * Common code: nothing here may reach a client class, because the server holds these
 * too and is the side that edits them.
 */
public interface ElementDisplay {

    /** The live collection, edited in place on the server and read in place on every sync. */
    ScreenElements elements();

    /** The rectangle every element's box is measured in and clamped to. */
    Viewport viewport();

    /** Saves and syncs after an edit to {@link #elements}; server side only. */
    void elementsChanged();

    /** Whether the device has its supply, without which it shows nothing at all. */
    boolean isPowered();

    /** Whether the backlight has its feed, which decides how what is shown is lit. */
    boolean isBacklit();
}
