package com.nectariich.create_flippies.content.display;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The alphanumeric display as something that can be mounted inside a host block: the
 * same glass, barrel and rack of pins as {@link AlphanumDisplayBlock}, described so
 * that several of them can share one cell.
 *
 * One kind per {@link DisplayKind} — that is, per body and per rated supply — and not
 * per anything that is printed on it, mirroring the way the indicators are one kind
 * per size and rating. Those two are what the device is: the rating is the resistance
 * on the rail and the body is the footprint, and between them they decide the item a
 * broken panel gives back. The height of the lettering, its face and its colour are
 * per-unit state a screwdriver can change, so a kind carrying any of them would be
 * writing the same fact in two places and letting them disagree.
 *
 * The footprint is the whole device, which is barely more than the drawn model since
 * nothing about a display moves. Its consequences on the borderless 1.5 px form are
 * the connector's rather than the glass's: the rack behind the panel is wider than
 * the window in front of it, so what two displays on one face need between their
 * centres is set by the pins — see {@link DisplayPins}.
 */
public class AlphanumDisplayControlType extends ControlType {

    private static final Map<DisplayKind, AlphanumDisplayControlType> BY_KIND = byKind();

    private final DisplayKind kind;

    private AlphanumDisplayControlType(DisplayKind kind) {
        super(kind.blockId());
        this.kind = kind;
    }

    /** Adds every kind to the catalogue of mountable controls. */
    public static void register() {
        BY_KIND.values().forEach(ControlTypes::register);
    }

    public static AlphanumDisplayControlType of(DisplayKind kind) {
        return BY_KIND.get(kind);
    }

    public DisplayKind kind() {
        return kind;
    }

    public DisplayForm form() {
        return kind.form();
    }

    public SupplyVoltage supply() {
        return kind.supply();
    }

    @Override
    public int terminalCount() {
        return DisplayPins.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return DisplayPins.footprint(kind.form());
    }

    @Override
    public HostedControl create() {
        return new AlphanumDisplayControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return DisplayPins.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.ALPHANUM_DISPLAYS.get(kind).get());
    }

    private static Map<DisplayKind, AlphanumDisplayControlType> byKind() {
        Map<DisplayKind, AlphanumDisplayControlType> kinds = new EnumMap<>(DisplayKind.class);
        for (DisplayKind kind : DisplayKind.values())
            kinds.put(kind, new AlphanumDisplayControlType(kind));
        return Collections.unmodifiableMap(kinds);
    }
}
