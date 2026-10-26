package com.nectariich.create_flippies.foundation.control;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;

import net.minecraft.network.chat.Component;

/**
 * A mounted control that a Create display link can write one line to.
 *
 * A link addresses a block position and a line number, and a host block holds many
 * controls, so the host's link-driven controls share one numbering: line 0 is the
 * first of them in reading order, whatever kind it happens to be. That is why this
 * is an interface on the control rather than a check for one particular kind — a
 * panel of legends and gauges is numbered as one list, exactly as a sign's four rows
 * are, and inserting a gauge between two labels renumbers what follows it the same
 * way inserting a label would.
 *
 * What a control does with its line is its own business: a label prints the text, a
 * procedural gauge pulls a number out of it and drives a needle. Both are handed the
 * same string, so nothing about the link has to know which is which.
 */
public interface LinkDrivenControl {

    /**
     * Takes one line from a link. Called on the server, and often repeatedly with
     * the same string — a link refreshing passively delivers its line over and over,
     * so an implementation that saves and syncs unconditionally would cost a packet
     * a tick for every panel in range of a redstone clock.
     */
    void acceptLine(String text);

    /**
     * How many characters this control can show, which is what the link's editor
     * offers as a width. A promise about a typical string rather than every string:
     * anything wider is trimmed by the control as it is drawn rather than refused.
     */
    int linkColumns();

    /**
     * What to call this control in the link's row selector.
     *
     * Without it the selector is a run of numbered rows with nothing to tell them
     * apart, which on a panel carrying both legends and gauges makes choosing the
     * right one guesswork — and picking wrong silently drives the wrong instrument.
     * Kept short, because it goes in a single narrow label in Create's screen.
     */
    Component linkRowName();

    /**
     * Takes one line from a link aimed at one element of this control.
     *
     * A control that shows {@link ElementDisplay elements} writes the element the link
     * names, and only when the slot's generation is the one the link was made against,
     * so a link whose element was deleted (and whose slot may since hold something
     * else) writes nothing rather than landing on a stranger. Anything else has one
     * line and nothing finer to address: it takes the line only when the link names
     * element 0 of generation 0, which is what every link made before elements
     * existed decodes to.
     */
    default void acceptLine(int element, int generation, String text) {
        if (this instanceof ElementDisplay display) {
            if (display.elements().acceptLinked(element, generation, text, HostElementBudget.textCap(display)))
                display.elementsChanged();
        } else if (element == 0 && generation == 0) {
            acceptLine(text);
        }
    }

    /**
     * How many characters that element can show; {@link #linkColumns()} for a control
     * with no elements. -1 when the control shows elements but has no lettering
     * element in that slot, which the caller turns into its own fallback.
     */
    default int linkColumns(int element) {
        if (!(this instanceof ElementDisplay display))
            return linkColumns();
        ScreenElement chosen = display.elements().get(element);
        return chosen != null && chosen.kind().linked() ? chosen.columns() : -1;
    }

    /**
     * Whether a link aimed at this control picks one of its elements as well as the
     * control: true exactly when it shows {@link ElementDisplay elements}.
     */
    default boolean isElementDisplay() {
        return this instanceof ElementDisplay;
    }
}
