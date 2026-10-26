package com.nectariich.create_flippies.content.display.link;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;

import net.minecraft.network.chat.Component;

/**
 * A display target whose link picks a display and then one of its lettering elements,
 * rather than a single line number: what lets Create's link screen offer a second
 * picker under its own (see {@code DisplayLinkScreenMixin}).
 *
 * Both choices travel in Create's own {@code targetLine}, packed by
 * {@link LinkAddress}. A target implementing this must decode it that way, and must
 * keep treating a small plain number as row N, element 0, generation 0, which is what
 * every link saved before this existed holds.
 *
 * Common code, because the targets are; the screen is the only caller and runs on the
 * client against the client's copy of the target block.
 */
public interface ElementAddressedTarget {

    String ELEMENT_KEY = "gui." + CreateFlippies.MOD_ID + ".display_link.element";
    String ELEMENT_NAMED_KEY = "gui." + CreateFlippies.MOD_ID + ".display_link.element_named";

    /** How much of an element's text its name in the picker previews. */
    int PREVIEW = 12;

    /** One entry of the element picker: the slot, its generation, and what to call it. */
    record LinkElementChoice(int id, int generation, Component name) {}

    /**
     * What to call each display (row) the link could be aimed at, in row order. Never
     * empty: a target with nothing writable still offers its one row, so the link has
     * something to point at.
     */
    List<Component> displayNames(DisplayLinkContext context);

    /**
     * The lettering elements and bars of that display, in id order, which is the
     * order a multi-line source fills them. Null when the display does not show
     * elements at all (a label, a gauge), which means no element picker; empty when
     * it does but has nothing a link can write to just now.
     */
    List<LinkElementChoice> elements(DisplayLinkContext context, int display);

    /** Every lettering element and bar of {@code display}, named by slot and its name or a glimpse of its text. */
    static List<LinkElementChoice> choices(ElementDisplay display) {
        ScreenElements elements = display.elements();
        List<LinkElementChoice> choices = new ArrayList<>();
        for (ScreenElement text : elements.linkTargets())
            choices.add(new LinkElementChoice(text.id(), elements.generation(text.id()), name(text)));
        return choices;
    }

    /**
     * "#3 Boiler" for an element its author named, else "#3" for an empty one and
     * "#3 Pressure" for one with text, cut to {@link #PREVIEW}.
     */
    static Component name(ScreenElement element) {
        if (!element.name().isEmpty())
            return Component.translatable(ELEMENT_NAMED_KEY, element.id(), element.name());
        String text = element.text().strip();
        if (text.isEmpty())
            return Component.translatable(ELEMENT_KEY, element.id());
        String preview = text.length() > PREVIEW ? ScreenElement.cut(text, PREVIEW) + "…" : text;
        return Component.translatable(ELEMENT_NAMED_KEY, element.id(), preview);
    }
}
