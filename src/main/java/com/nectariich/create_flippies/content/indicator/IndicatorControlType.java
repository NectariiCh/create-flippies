package com.nectariich.create_flippies.content.indicator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The indicator as something that can be mounted inside a host block: the same cap,
 * barrel and rack of eight contacts as {@link IndicatorBlock}, described so that
 * many of them can share one cell.
 *
 * One kind per {@link IndicatorKind} — that is, per size and rating — and not per
 * lens colour, mirroring the way the voltmeters are one kind per range. Those two
 * are what the unit is: the rating is the resistance on the line and the size is the
 * footprint, and between them they decide the item a broken panel gives back. The
 * colour, the action and the legend are per-unit state a screwdriver can change, so
 * a kind carrying any of them would be writing the same fact in two places and
 * letting them disagree.
 *
 * The footprint is the whole unit, which for once is barely more than the drawn
 * model: nothing here moves except a quarter pixel of cap, so unlike the rotary
 * switch there is no swept volume to add. Its consequences are pleasant for a
 * change. The unit is one pixel across — one and a half on the large cap — and its
 * grown contacts one and three quarters on both, so units on one face need their
 * centres only that far apart, which in free placement allows eight rows of eight on
 * a single face and in constrained placement lets a ruled grid put them shoulder to
 * shoulder in a row of annunciators. The barrel spends four pixels of the depth
 * budget, so two units mounted back to back through a panel one block thick clear
 * each other by eight.
 */
public class IndicatorControlType extends ControlType {

    private static final Map<IndicatorKind, IndicatorControlType> BY_KIND = byKind();

    private final IndicatorKind kind;

    private IndicatorControlType(IndicatorKind kind) {
        super(kind.blockId());
        this.kind = kind;
    }

    /** Adds every kind to the catalogue of mountable controls. */
    public static void register() {
        BY_KIND.values().forEach(ControlTypes::register);
    }

    public static IndicatorControlType of(IndicatorKind kind) {
        return BY_KIND.get(kind);
    }

    public IndicatorKind kind() {
        return kind;
    }

    public IndicatorRating rating() {
        return kind.rating();
    }

    public IndicatorSize size() {
        return kind.size();
    }

    @Override
    public int terminalCount() {
        return IndicatorLamp.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return IndicatorLamp.footprint(kind.size());
    }

    @Override
    public HostedControl create() {
        return new IndicatorControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return IndicatorLamp.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.INDICATORS.get(kind).get());
    }

    private static Map<IndicatorKind, IndicatorControlType> byKind() {
        Map<IndicatorKind, IndicatorControlType> kinds = new EnumMap<>(IndicatorKind.class);
        for (IndicatorKind kind : IndicatorKind.values())
            kinds.put(kind, new IndicatorControlType(kind));
        return Collections.unmodifiableMap(kinds);
    }
}
