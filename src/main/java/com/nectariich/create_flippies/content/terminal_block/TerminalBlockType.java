package com.nectariich.create_flippies.content.terminal_block;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.din_rail.DinRailMountable;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A DIN terminal block — the rail-mounted kind, not a Minecraft block — as
 * something that clips onto a rail: one kind per row of {@link #TABLE}.
 *
 * Every one is the same stepped half-module body with a pin on each side of each
 * step, one to three steps tall, and its pins joined into groups. A pin is
 * labelled {@code G<group><contact>} — {@code G2C} is contact C of group 2 — and
 * every pin of a group is the same conductor. Groups never touch each other.
 *
 * A {@linkplain #ground() ground} block is a one-group block that also bonds to
 * the rail it is clipped to, and through it to every other ground block on that
 * rail; see {@code DinRailBlockEntity#buildCircuit}.
 *
 * All are rated 660 V and 85 A. Neither is enforced; the goggles say when one is
 * exceeded, as they do for a relay.
 *
 * Like the relay mount it has no block form; its item is a plain item and what
 * makes it mountable is that this kind names it from {@link #asItem()}. Adding a
 * variant is a row below, a copy of the export (tools/copy_terminal_block_variants.py
 * makes them), a lang name, and {@code python tools/gen_terminal_blocks.py}.
 */
public class TerminalBlockType extends ControlType implements DinRailMountable {

    public static final double RATED_VOLTS = 660;
    public static final double RATED_AMPS = 85;

    /**
     * Every terminal block, in creative tab order. Each pin is (label, step, side):
     * step 0 the lowest, side −1 the −z face and +1 the +z face. The order of the
     * pins is the order of the terminals and is frozen: Power Grid numbers a
     * control's terminals positionally and saved wires depend on it.
     */
    private static final Map<String, TerminalBlockType> TABLE = table(
            new TerminalBlockType("6p3g", false,
                    pin("G1A", 0, -1), pin("G1B", 0, +1),
                    pin("G2A", 1, -1), pin("G2B", 1, +1),
                    pin("G3A", 2, -1), pin("G3B", 2, +1)),
            new TerminalBlockType("6p2g", false,
                    pin("G1A", 0, -1), pin("G1B", 1, -1), pin("G1C", 2, -1),
                    pin("G2A", 0, +1), pin("G2B", 1, +1), pin("G2C", 2, +1)),
            new TerminalBlockType("6p1g", false,
                    pin("G1A", 0, -1), pin("G1B", 0, +1), pin("G1C", 1, -1),
                    pin("G1D", 1, +1), pin("G1E", 2, -1), pin("G1F", 2, +1)),
            new TerminalBlockType("6p1g_ground", true,
                    pin("G1A", 0, -1), pin("G1B", 0, +1), pin("G1C", 1, -1),
                    pin("G1D", 1, +1), pin("G1E", 2, -1), pin("G1F", 2, +1)),
            new TerminalBlockType("4p2g", false,
                    pin("G1A", 0, -1), pin("G1B", 0, +1),
                    pin("G2A", 1, -1), pin("G2B", 1, +1)),
            new TerminalBlockType("4p1g", false,
                    pin("G1A", 0, -1), pin("G1B", 0, +1), pin("G1C", 1, -1), pin("G1D", 1, +1)),
            new TerminalBlockType("4p1g_ground", true,
                    pin("G1A", 0, -1), pin("G1B", 0, +1), pin("G1C", 1, -1), pin("G1D", 1, +1)),
            new TerminalBlockType("2p1g", false,
                    pin("G1A", 0, -1), pin("G1B", 0, +1)),
            new TerminalBlockType("2p1g_ground", true,
                    pin("G1A", 0, -1), pin("G1B", 0, +1)));

    /** One pin: its label, the step it stands on, and which face of it. */
    public record Pin(String label, int group, int level, int side) {}

    private final boolean ground;
    private final List<Pin> pins;
    private final int groups;
    private final int levels;
    private final ControlFootprint footprint;

    private TerminalBlockType(String variant, boolean ground, Pin... pins) {
        super("din_terminal_block_" + variant);
        this.ground = ground;
        this.pins = List.of(pins);
        this.groups = this.pins.stream().mapToInt(Pin::group).max().orElse(0) + 1;
        this.levels = this.pins.stream().mapToInt(Pin::level).max().orElse(0) + 1;
        if (ground && groups != 1)
            throw new IllegalStateException(id() + " is a ground block of " + groups + " groups");
        this.footprint = TerminalBlockGeometry.footprint(this);
    }

    /** {@code G2C} is contact C of group 2; the group is what the circuit needs of it. */
    private static Pin pin(String label, int level, int side) {
        if (label.length() != 3 || label.charAt(0) != 'G')
            throw new IllegalArgumentException("Not a terminal label: " + label);
        return new Pin(label, label.charAt(1) - '1', level, side);
    }

    /** Every terminal block in the table, in table order. */
    public static List<TerminalBlockType> all() {
        return List.copyOf(TABLE.values());
    }

    /** The terminal block with this id, or null if there is none. */
    public static TerminalBlockType of(String id) {
        return TABLE.get(id);
    }

    /** Adds every terminal block to the catalogue of mountable controls. */
    public static void register() {
        TABLE.values().forEach(ControlTypes::register);
    }

    /** Whether this block bonds its one group to the rail. */
    public boolean ground() {
        return ground;
    }

    /** The pins, in terminal order. */
    public List<Pin> pins() {
        return pins;
    }

    public int groups() {
        return groups;
    }

    /** How many steps tall the body is: one per pair of pins. */
    public int levels() {
        return levels;
    }

    /** Half a module of rail, as the relay mount is. */
    @Override
    public int modules() {
        return 1;
    }

    @Override
    public double width() {
        return TerminalBlockGeometry.WIDTH;
    }

    /** The partial the renderer draws, which gen_terminal_blocks.py writes. */
    public String model() {
        return "block/" + id() + "_body";
    }

    @Override
    public int terminalCount() {
        return pins.size();
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new TerminalBlockControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return TerminalBlockGeometry.terminals(this, mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.TERMINAL_BLOCKS.get(id()).get());
    }

    private static Map<String, TerminalBlockType> table(TerminalBlockType... rows) {
        Map<String, TerminalBlockType> kinds = new LinkedHashMap<>();
        for (TerminalBlockType row : rows)
            if (kinds.putIfAbsent(row.id(), row) != null)
                throw new IllegalStateException("Two terminal block rows are both " + row.id());
        return Collections.unmodifiableMap(kinds);
    }
}
