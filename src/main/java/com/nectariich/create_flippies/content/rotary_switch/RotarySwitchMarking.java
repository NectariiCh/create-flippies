package com.nectariich.create_flippies.content.rotary_switch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What is printed on a rotary switch's front, and therefore what its detents are
 * called.
 *
 * A marking belongs to one {@link RotarySwitchKind} and cannot be moved between
 * them: the front printed on a switch has to have as many legends as the handle
 * has places to stop, and it has to be printed on a plate that size. Within a kind
 * it is free — I/O and ON/OFF are the same two-position switch with different
 * paint, as are II/O/I and H/O/A with three — which is why this is per-switch
 * state a wrench can change rather than a different block. A kind with only one
 * front authored, as the large switch has, simply has nothing to cycle to.
 *
 * The id is also the marking's half of the body model and texture names, so it is
 * what a save holds and must stay as it is for as long as worlds do.
 */
public enum RotarySwitchMarking {

    IO("io", RotarySwitchKind.TWO_POSITION),
    ONOFF("onoff", RotarySwitchKind.TWO_POSITION),
    IIOI("iioi", RotarySwitchKind.THREE_POSITION),
    HOA("hoa", RotarySwitchKind.THREE_POSITION),
    MNP("mnp", RotarySwitchKind.LARGE_THREE_POSITION);

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".rotary_switch.";

    /**
     * Grouped once here rather than filtered on every call, and after the constants
     * so that the constructors have already run. Nothing in
     * {@code RotarySwitchKind} reaches back into this class while it is being
     * built, so the two enums can name each other without their initialisers
     * racing: whichever is touched first finishes the other on its way.
     */
    private static final Map<RotarySwitchKind, List<RotarySwitchMarking>> BY_KIND = byKind();

    private final String id;
    private final RotarySwitchKind kind;

    RotarySwitchMarking(String id, RotarySwitchKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public String id() {
        return id;
    }

    public RotarySwitchKind kind() {
        return kind;
    }

    /** Every marking that fits the given switch, in menu order. */
    public static List<RotarySwitchMarking> of(RotarySwitchKind kind) {
        return BY_KIND.get(kind);
    }

    /**
     * The marking saved under this id, or {@code fallback} if it is unknown or
     * belongs to a switch with a different number of detents.
     */
    public static RotarySwitchMarking of(String id, RotarySwitchMarking fallback) {
        for (RotarySwitchMarking marking : values())
            if (marking.id.equals(id) && marking.kind == fallback.kind)
                return marking;
        return fallback;
    }

    /** The next marking this switch can wear, wrapping. */
    public RotarySwitchMarking next() {
        List<RotarySwitchMarking> siblings = of(kind);
        return siblings.get((siblings.indexOf(this) + 1) % siblings.size());
    }

    /** The whole front as one label, e.g. {@code I / O} — the wrench menu's button. */
    public Component label() {
        return Component.translatable(LANG_PREFIX + "marking." + id);
    }

    /** The marking printed against one detent, e.g. {@code I} or {@code O}. */
    public Component positionLabel(int position) {
        return Component.translatable(LANG_PREFIX + "position." + id + "."
                + Math.floorMod(position, kind.positions()));
    }

    private static Map<RotarySwitchKind, List<RotarySwitchMarking>> byKind() {
        Map<RotarySwitchKind, List<RotarySwitchMarking>> grouped = new EnumMap<>(RotarySwitchKind.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            grouped.put(kind, new ArrayList<>());
        for (RotarySwitchMarking marking : values())
            grouped.get(marking.kind).add(marking);
        grouped.replaceAll((kind, markings) -> List.copyOf(markings));
        return Collections.unmodifiableMap(grouped);
    }
}
