package com.nectariich.create_flippies.foundation.control;

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
}
