package com.nectariich.create_flippies.content.display.link;

import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;

/**
 * Where on a target a display link writes, packed into the one {@code int} Create
 * already keeps for it: the link's {@code targetLine}.
 *
 * Create saves that int as {@code TargetLine}, syncs it, keeps it through
 * {@code writeSafe} and onto contraptions, and carries all 32 bits through its
 * configuration packet with no range check. So packing into it costs no new storage,
 * no new packet and no link-side migration:
 * <ul>
 * <li>bits 0 to 15: the row on the host, which is all the int used to mean;</li>
 * <li>bits 16 to 22: the element's slot ({@link ScreenElement#id});</li>
 * <li>bit 23: unused;</li>
 * <li>bits 24 to 30: the slot's generation ({@link ScreenElements#generation});</li>
 * <li>bit 31: always clear, so the packed value is never negative to anything that
 * still reads it as a plain line number.</li>
 * </ul>
 * A link saved before any of this has a small non-negative line {@code N}, which
 * reads as row {@code N}, element 0, generation 0, exactly the element a migrated
 * display's one line became.
 */
public final class LinkAddress {

    public static final int MAX_ROW = 0xFFFF;

    private static final int ELEMENT_SHIFT = 16;
    private static final int GENERATION_SHIFT = 24;
    private static final int SEVEN_BITS = 0x7F;

    private LinkAddress() {}

    /** The three packed; each is clamped to what its bits hold rather than spilling into the next. */
    public static int pack(int row, int element, int generation) {
        return clamp(row, MAX_ROW)
                | clamp(element, SEVEN_BITS) << ELEMENT_SHIFT
                | clamp(generation, SEVEN_BITS) << GENERATION_SHIFT;
    }

    public static int row(int line) {
        return line & MAX_ROW;
    }

    public static int element(int line) {
        return line >>> ELEMENT_SHIFT & SEVEN_BITS;
    }

    public static int generation(int line) {
        return line >>> GENERATION_SHIFT & SEVEN_BITS;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
