package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.MutableComponent;

/**
 * Lets a Create display link write onto an alphanumeric display placed as a block of
 * its own.
 *
 * One line, because that is what the device has: one row of glass showing one string.
 * A source offering more lines than that has the rest ignored rather than wrapped,
 * for the reason {@link DisplayPrint} gives about cutting — there is nowhere on a
 * borderless display for a second line to go.
 *
 * A display mounted in a panel goes through
 * {@link com.nectariich.create_flippies.content.host.ControlHostDisplayTarget}
 * instead, which numbers the host's link-driven controls as one list; the string that
 * arrives is the same either way, which is the point of
 * {@link com.nectariich.create_flippies.foundation.control.LinkDrivenControl}.
 *
 * The width reported is what the glass will really show at the height the player set,
 * so the link's editor trims to roughly what can be read rather than to a figure
 * nothing enforces. Anything longer that arrives anyway is cut as it is drawn.
 */
public class AlphanumDisplayTarget extends DisplayTarget {

    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        if (!(context.getTargetBlockEntity() instanceof AlphanumDisplayBlockEntity display))
            return;
        if (line != 0 || text.isEmpty())
            return;
        display.acceptLine(text.get(0).getString());
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        int columns = context.getTargetBlockEntity() instanceof AlphanumDisplayBlockEntity display
                ? display.print().columns(display.getForm())
                : 1;
        return new DisplayTargetStats(1, columns, this);
    }

    /**
     * Refuses a line carrying a click event, exactly as the sign target does. A
     * display stores flat text and draws it through the font, so a component that
     * expects to be clicked is being sent somewhere that can never honour it.
     */
    @Override
    public boolean requiresComponentSanitization() {
        return true;
    }
}
