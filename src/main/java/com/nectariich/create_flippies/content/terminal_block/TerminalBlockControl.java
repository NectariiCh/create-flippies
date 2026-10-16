package com.nectariich.create_flippies.content.terminal_block;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One DIN terminal block on a rail.
 *
 * <h2>The circuit</h2>
 *
 * Each group is a bus bar: an internal node, and every pin of the group joined to
 * it at {@link #CONTACT_OHMS}, a clamp's contact resistance. Built in a fixed
 * order — the group nodes in group order, then the pin wires in terminal order —
 * because Power Grid streams internal node values to the client positionally.
 * Nothing joins one group to another, so each is its own network once wired, as
 * the PSU's input and output are.
 *
 * A bus bar node rather than pins wired to each other so that each pin's current
 * can be read off its own wire, by client and server alike, from the node voltages
 * Power Grid already syncs.
 *
 * <h2>Ground blocks</h2>
 *
 * A ground block's one bus bar is bonded to the rail. The rail does the bonding —
 * it alone knows which blocks share it — and tells each one how many others it is
 * bonded to; see {@code DinRailBlockEntity#buildCircuit}.
 *
 * <h2>Ratings</h2>
 *
 * {@link TerminalBlockType#RATED_AMPS} per contact and
 * {@link TerminalBlockType#RATED_VOLTS} between groups. Neither is enforced; the
 * goggles say when one is exceeded. Nothing is saved: the block has no state.
 */
public class TerminalBlockControl extends HostedControl {

    /** One clamp's contact resistance: a milliohm. */
    public static final double CONTACT_OHMS = 1e-3;

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".terminal_block.goggles.";

    private final TerminalBlockType type;

    private FloatingNode[] buses;
    private IElectricNode[] pins;
    private int bonded;

    TerminalBlockControl(TerminalBlockType type) {
        super(type);
        this.type = type;
    }

    public TerminalBlockType terminalBlock() {
        return type;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        List<TerminalBlockType.Pin> layout = type.pins();
        builder.setTerminalCount(layout.size());

        // The order below is the order Power Grid streams these to the client in;
        // see the class comment before changing it
        FloatingNode[] buses = new FloatingNode[type.groups()];
        for (int group = 0; group < buses.length; group++)
            buses[group] = builder.addInternalNode();
        IElectricNode[] pins = new IElectricNode[layout.size()];
        for (int i = 0; i < pins.length; i++) {
            pins[i] = builder.terminalNode(i);
            builder.connect((float) CONTACT_OHMS, pins[i], buses[layout.get(i).group()]);
        }
        this.buses = buses;
        this.pins = pins;
        this.bonded = 0;
    }

    private boolean built() {
        return buses != null;
    }

    /**
     * The node a ground block bonds to the rail with, or null if this is not a
     * ground block or its circuit is not built.
     */
    public IElectricNode groundBus() {
        return type.ground() && built() ? buses[0] : null;
    }

    /** Called by the rail as it bonds: how many other ground blocks share it. */
    public void setBonded(int others) {
        this.bonded = others;
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /** One pin's current, from the drop across its clamp. */
    public double pinAmps(int terminal) {
        if (!built())
            return 0;
        int group = type.pins().get(terminal).group();
        return Math.abs(voltage(pins[terminal]) - voltage(buses[group])) / CONTACT_OHMS;
    }

    /** The most any pin of a group carries, which is what the rating is against. */
    public double groupAmps(int group) {
        double most = 0;
        for (int i = 0; i < type.pins().size(); i++)
            if (type.pins().get(i).group() == group)
                most = Math.max(most, pinAmps(i));
        return most;
    }

    /** Group {@code a} less group {@code b}; on a client, from the node voltages Power Grid syncs. */
    public double volts(int a, int b) {
        return built() ? voltage(buses[a]) - voltage(buses[b]) : 0;
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable("item." + CreateFlippies.MOD_ID + "." + type.id())
                .withStyle(ChatFormatting.WHITE)));

        boolean overloaded = false;
        for (int group = 0; group < type.groups(); group++) {
            double amps = groupAmps(group);
            overloaded |= amps > TerminalBlockType.RATED_AMPS;
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "group", "G" + (group + 1),
                    value(amps, "%.2f"), value(TerminalBlockType.RATED_AMPS, "%.0f")).withStyle(ChatFormatting.GRAY)));
        }

        boolean overvolted = false;
        for (int a = 0; a < type.groups(); a++)
            for (int b = a + 1; b < type.groups(); b++) {
                double volts = Math.abs(volts(a, b));
                overvolted |= volts > TerminalBlockType.RATED_VOLTS;
                tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "between", "G" + (a + 1), "G" + (b + 1),
                        value(volts, "%.1f")).withStyle(ChatFormatting.GRAY)));
            }

        if (type.ground())
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "bonded", value(bonded, "%.0f"))
                    .withStyle(ChatFormatting.GRAY)));
        if (overloaded)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "overloaded").withStyle(ChatFormatting.RED)));
        if (overvolted)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "overvolted").withStyle(ChatFormatting.RED)));
        return true;
    }

    private static Component value(double value, String format) {
        return Component.literal(String.format(Locale.ROOT, format, value)).withStyle(ChatFormatting.AQUA);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
