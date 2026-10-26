package com.nectariich.create_flippies.foundation.control;

import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElements;

/**
 * How many screen elements, and how many characters of their text, all the displays
 * mounted in one host block may hold between them.
 *
 * <h2>Why a host needs a cap of its own</h2>
 *
 * Each display is capped on its own ({@link ScreenElements#MAX} elements,
 * {@link ScreenElements#MAX_TOTAL_TEXT} characters), but every control in a host goes
 * into the host's one block entity tag, and that tag reaches clients through
 * {@code FriendlyByteBuf.readNbt}, both in the chunk packet and in every host sync.
 * That read is held to 2 MiB by its {@code NbtAccounter}, and a tag past it does not
 * fail quietly: the client is disconnected with "tag too big", and since the same tag
 * comes back in the chunk packet, again every time it rejoins in sight of the block.
 * Measured against the accounter, one display full of elements counts for about
 * 72 KB empty and 91 KB with its whole text budget spent, so 23 to 29 full displays
 * in one cell break it, and the 1.5 px forms fit about a hundred to a face.
 *
 * {@link #MAX_ELEMENTS} is eight full displays, about 580 KB of elements at the
 * accounter's ~563 bytes apiece (a TTF font's name adds ~140 more), and
 * {@link #MAX_TEXT} is eight full text budgets, another ~66 KB: together well under
 * half the limit, leaving the rest for every other control in the cell, however many
 * displays the elements are spread over. Nothing is trimmed on read, so a host saved
 * over the cap before it existed keeps what it has and only stops growing.
 *
 * <h2>Where it is held</h2>
 *
 * Every way a host's displays grow goes through here: an editor's add and duplicate
 * ({@link #roomForElement}), text from the editor or a link (cut to {@link #textCap},
 * the way a display's own budget cuts it), and mounting another display, whose
 * starting elements must fit beside the rest ({@link #fits}, refused with
 * {@link PlacementRefusal#TOO_MANY_ELEMENTS}). A display that is not a hosted control
 * (the block form) is the only display in its block entity, so its own caps are
 * already the block's.
 */
public final class HostElementBudget {

    /** Elements summed over every display in one host block. */
    public static final int MAX_ELEMENTS = 8 * ScreenElements.MAX;

    /** Characters of text summed over every display in one host block. */
    public static final int MAX_TEXT = 8 * ScreenElements.MAX_TOTAL_TEXT;

    private HostElementBudget() {}

    /** Whether that display may take one more element without its host going over. */
    public static boolean roomForElement(ElementDisplay display) {
        List<HostedControl> controls = hostmates(display);
        return controls == null || elementsUsed(controls, null) < MAX_ELEMENTS;
    }

    /**
     * How many characters that display's text may come to in all: its own budget, or
     * less when the other displays in its host leave less than that.
     */
    public static int textCap(ElementDisplay display) {
        List<HostedControl> controls = hostmates(display);
        if (controls == null)
            return ScreenElements.MAX_TOTAL_TEXT;
        return Math.max(0, Math.min(ScreenElements.MAX_TOTAL_TEXT, MAX_TEXT - textUsed(controls, display)));
    }

    /**
     * Whether a control about to be mounted among {@code controls} leaves the host
     * within the cap: always, for one that shows no elements.
     */
    static boolean fits(List<HostedControl> controls, HostedControl incoming) {
        if (!(incoming instanceof ElementDisplay display))
            return true;
        return elementsUsed(controls, null) + display.elements().size() <= MAX_ELEMENTS
                && textUsed(controls, null) + text(display) <= MAX_TEXT;
    }

    /** The controls of the host that display is mounted in, or null when it is not a mounted control. */
    private static List<HostedControl> hostmates(ElementDisplay display) {
        return display instanceof HostedControl control && control.host() instanceof ControlRack rack
                ? rack.controls() : null;
    }

    private static int elementsUsed(List<HostedControl> controls, ElementDisplay except) {
        int used = 0;
        for (HostedControl control : controls)
            if (control instanceof ElementDisplay display && display != except)
                used += display.elements().size();
        return used;
    }

    private static int textUsed(List<HostedControl> controls, ElementDisplay except) {
        int used = 0;
        for (HostedControl control : controls)
            if (control instanceof ElementDisplay display && display != except)
                used += text(display);
        return used;
    }

    private static int text(ElementDisplay display) {
        return display.elements().textLength();
    }
}
