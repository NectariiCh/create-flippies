package com.nectariich.create_flippies.content.psu;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * One DIN-rail power supply clipped to a rail: 230 V in, 24 V out, and a DC OK
 * contact saying so.
 *
 * What the supply decides is {@link PsuRegulator}'s, which knows nothing of the
 * world; this class is the glue. Once a server tick it reads the terminals, hands
 * the regulator what the last solve measured, and puts what the regulator decided
 * back into the circuit for the next one — like the PLC, a tick behind the solve
 * it is reacting to.
 *
 * <h2>The circuit</h2>
 *
 * Built in a fixed order, because Power Grid streams internal nodes and
 * switched-wire states to the client positionally, and every element exists for
 * the life of the circuit and is re-valued or switched rather than added and
 * removed:
 *
 * <ol>
 * <li>an internal node, {@code source+}, the regulated rail inside the case;
 * <li>a {@link VoltageSourceCoupling} from V− to {@code source+} at the
 * regulator's {@linkplain PsuRegulator#setpoint() setpoint}, with the 0.1 mΩ
 * Power Grid's own creative source uses — the source is ideal and the supply's
 * real internal resistance is the output switch below;
 * <li>the <b>input load</b>, a resistor across VCC and GND re-valued every tick
 * to draw what the regulator says the input supplies;
 * <li>the <b>output switch</b>, {@code source+} to V+, closed while the output
 * conducts and re-valued every tick to the regulator's
 * {@linkplain PsuRegulator#outputOhms() series resistance}:
 * {@link PsuRegulator#OUTPUT_OHMS} while it regulates the voltage, more while it
 * limits the current or soft-starts. The current limit is this resistance, not
 * the source voltage — see the regulator's class comment for why that is what
 * makes paralleled supplies and battery loads stable;
 * <li>the <b>DC OK switch</b>, V+ to DC OK, closed at
 * {@link PsuRegulator#DC_OK_OHMS} while the output is in its window.
 * </ol>
 *
 * <b>Isolation.</b> Nothing joins an input node to an output node: the input side
 * is a resistor and the output side a source, and the regulator is the only thing
 * that passes between them. V− is the output's own reference. This is load-bearing,
 * not only faithful: Power Grid joins a terminal to a network by tracing the
 * internal wires and couplings that touch it ({@code ElectricBehaviour.tracedAdd}),
 * so the two sides go into networks of their own, each solved against its own
 * reference. Two unconnected islands in one network have no common reference, and
 * Power Grid's solver then gives every node in that network 0 V (checked against
 * the 0.6.0 solver). An element bridging the sides would change only which of
 * those two you get if the user also wired them together, but leave it out.
 *
 * <b>Open when off.</b> A supply that is not running must not look like a 0 V
 * source, or a second supply paralleled onto the same output would be shorted
 * into it. So the source is never what disconnects: the output switch is opened,
 * which in Power Grid leaves {@code SwitchedWire.OFF_CONDUCTANCE} (5 nS, 200 MΩ)
 * between the source and V+ — an open circuit by any measure a 24 V circuit
 * cares about. The source behind it is set to 0 V while it is off, which with the
 * switch open is invisible from outside.
 *
 * <h2>What is saved</h2>
 *
 * Nothing the supply needs to run: a supply loaded from a save starts from cold,
 * with a soft start, exactly as one does when the cabinet is switched back on,
 * and a hiccup's countdown starting over is harmless. What {@link #write} does
 * carry is the goggle summary — state, output current and power, DC OK — because
 * the host syncs one tag for disk and network both. It is re-sent only when the
 * state changes, and never more often than {@link #SYNC_INTERVAL}; a drift in the
 * current within {@link #CURRENT_SYNC_INTERVAL} and only past
 * {@link #CURRENT_SYNC_FRACTION}. Nothing calls {@link #changed()} per tick.
 *
 * Clients run no regulator. They build the same circuit, so Power Grid streams
 * node voltages and switch states into it, and read input and output voltage from
 * those.
 */
public class PsuControl extends HostedControl {

    /** The source's own series resistance, Power Grid's creative source's. */
    static final float SOURCE_OHMS = 1e-4f;

    /** Relative change in the input load below which Power Grid is not asked to re-solve. */
    private static final double RESISTANCE_DEADBAND = 0.01;

    /** The fewest ticks between two syncs for a change of state, and for a drift in current. */
    private static final int SYNC_INTERVAL = 10;
    private static final int CURRENT_SYNC_INTERVAL = 40;

    /** A current that has moved by less than this fraction, or {@link #CURRENT_SYNC_FLOOR}, is not worth a sync. */
    private static final double CURRENT_SYNC_FRACTION = 0.1;
    private static final double CURRENT_SYNC_FLOOR = 0.01;

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".psu.goggles.";
    private static final String STATE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".psu.state.";

    private final PsuRegulator regulator;

    // ------------------------------------------------------------ circuit ---
    private IElectricNode vcc;
    private IElectricNode gnd;
    private IElectricNode vPlus;
    private IElectricNode vMinus;
    private IElectricNode dcOkNode;
    private VoltageSourceCoupling source;
    private ElectricWire inputLoad;
    private SwitchedWire output;
    private SwitchedWire dcOkSwitch;

    // ------------------------------------------- what the goggles show ---
    private PsuRegulator.State shownState = PsuRegulator.State.OFF;
    private float shownAmps;
    private float shownWatts;
    private boolean shownDcOk;
    private boolean shownDcOkLatched;

    private int ticksSinceSync = Integer.MAX_VALUE / 2;
    private boolean summaryDirty;

    PsuControl(PsuType type) {
        super(type);
        this.regulator = type.regulator();
    }

    public PsuType psu() {
        return (PsuType) type();
    }

    /** The regulator, for tests. */
    PsuRegulator regulator() {
        return regulator;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(PsuGeometry.TERMINALS);
        vcc = builder.terminalNode(PsuGeometry.TERMINAL_VCC);
        gnd = builder.terminalNode(PsuGeometry.TERMINAL_GND);
        vPlus = builder.terminalNode(PsuGeometry.TERMINAL_V_PLUS);
        vMinus = builder.terminalNode(PsuGeometry.TERMINAL_V_MINUS);
        dcOkNode = builder.terminalNode(PsuGeometry.TERMINAL_DC_OK);

        // The order below is the order Power Grid streams these to the client in;
        // see the class comment before changing it
        IElectricNode rail = builder.addInternalNode();
        source = new VoltageSourceCoupling(rail, vMinus, SOURCE_OHMS);
        source.setVoltage(regulator.setpoint());
        builder.add(source);
        inputLoad = builder.connect((float) regulator.inputOhms(), vcc, gnd);
        output = builder.connectSwitch((float) PsuRegulator.OUTPUT_OHMS, rail, vPlus, regulator.outputOn());
        dcOkSwitch = builder.connectSwitch((float) PsuRegulator.DC_OK_OHMS, vPlus, dcOkNode, regulator.dcOk());
    }

    private boolean built() {
        return output != null;
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /**
     * VCC − GND, signed; on a client, from the node voltages Power Grid syncs.
     * Callers take the magnitude: the front end is a bridge rectifier, so a
     * reversed input runs the supply all the same (see {@link PsuRegulator}).
     */
    public double inputVolts() {
        return built() ? voltage(vcc) - voltage(gnd) : 0;
    }

    /** V+ − V−; on a client, from the node voltages Power Grid syncs. */
    public double outputVolts() {
        return built() ? voltage(vPlus) - voltage(vMinus) : 0;
    }

    // ---------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide)
            return;
        serverTick();
    }

    /**
     * One server tick. Package-private and free of the level so the supply can be
     * driven against a real Power Grid network in a test without a world.
     */
    void serverTick() {
        if (!built())
            return;
        regulator.tick(inputVolts(), outputVolts(), output.current(), dcOkSwitch.current());

        source.setVoltage(regulator.setpoint());
        output.setState(regulator.outputOn());
        // Exactly, not within a deadband: it is what sets the output voltage and the
        // limit. A steady supply hands back the same value, so it still asks for no
        // re-solve; an open switch keeps the value for when it closes
        double outputOhms = regulator.outputOhms();
        if (outputOhms != output.getResistance())
            output.setResistance(outputOhms);
        dcOkSwitch.setState(regulator.dcOk());
        double ohms = regulator.inputOhms();
        if (Math.abs(ohms - inputLoad.getResistance()) > inputLoad.getResistance() * RESISTANCE_DEADBAND)
            inputLoad.setResistance(ohms);

        updateSummary();
    }

    /** The current out of V+ on the last solve; zero on a client, which reads {@link #shownAmps}. */
    double outputAmps() {
        return built() ? output.current() : 0;
    }

    /** The DC OK switch as last set. */
    boolean dcOkClosed() {
        return built() && dcOkSwitch.getState();
    }

    /** The output switch as last set. */
    boolean outputClosed() {
        return built() && output.getState();
    }

    /** The input load as last set, in ohms. */
    double inputOhms() {
        return built() ? inputLoad.getResistance() : PsuRegulator.OPEN_OHMS;
    }

    // ------------------------------------------------------ the goggle sync ---

    private void updateSummary() {
        ticksSinceSync++;
        PsuRegulator.State state = regulator.state();
        boolean dcOk = regulator.dcOk();
        boolean latched = regulator.dcOkLatched();
        if (state != shownState || dcOk != shownDcOk || latched != shownDcOkLatched) {
            shownState = state;
            shownDcOk = dcOk;
            shownDcOkLatched = latched;
            summaryDirty = true;
        }
        double amps = regulator.outputOn() ? Math.max(0, output.current()) : 0;
        boolean drifted = Math.abs(amps - shownAmps) > Math.max(CURRENT_SYNC_FLOOR, shownAmps * CURRENT_SYNC_FRACTION);
        if (summaryDirty && ticksSinceSync >= SYNC_INTERVAL
                || drifted && ticksSinceSync >= CURRENT_SYNC_INTERVAL) {
            shownAmps = (float) amps;
            shownWatts = (float) regulator.outputWatts();
            summaryDirty = false;
            ticksSinceSync = 0;
            changed();
        }
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        PsuType psu = psu();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header", psu.inputVolts(), psu.outputVolts(),
                psu.watts()).withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "state", stateName(shownState))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "input", value(Math.abs(inputVolts()), "%.1f"))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "output", value(outputVolts(), "%.2f"),
                value(shownAmps, "%.2f")).withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "load",
                value(100 * shownWatts / psu.watts(), "%.0f")).withStyle(ChatFormatting.GRAY)));
        Component dcOk = Component.translatable(GOGGLE_PREFIX + (shownDcOk ? "dc_ok.on"
                : shownDcOkLatched ? "dc_ok.latched" : "dc_ok.off"))
                .withStyle(shownDcOk ? ChatFormatting.GREEN : shownDcOkLatched ? ChatFormatting.RED
                        : ChatFormatting.DARK_GRAY);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "dc_ok", dcOk).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    /** The state's name, coloured for how much attention it wants. */
    public static MutableComponent stateName(PsuRegulator.State state) {
        ChatFormatting colour = switch (state) {
            case RUNNING -> ChatFormatting.GREEN;
            case OVERLOAD, INPUT_LOW -> ChatFormatting.GOLD;
            case INPUT_HIGH, HICCUP -> ChatFormatting.RED;
            default -> ChatFormatting.DARK_GRAY;
        };
        return Component.translatable(stateKey(state)).withStyle(colour);
    }

    /** The lang key for a state, lowercased in the root locale; see {@code PlcSmallControl.stateKey}. */
    static String stateKey(PsuRegulator.State state) {
        return STATE_PREFIX + state.name().toLowerCase(Locale.ROOT);
    }

    private static Component value(double value, String format) {
        return Component.literal(String.format(Locale.ROOT, format, value)).withStyle(ChatFormatting.AQUA);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }

    // ------------------------------------------------------------ save/load ---

    /** The goggle summary only; see the class comment for why nothing else. */
    @Override
    public void write(CompoundTag tag) {
        tag.putByte("State", (byte) shownState.ordinal());
        tag.putFloat("Amps", shownAmps);
        tag.putFloat("Watts", shownWatts);
        if (shownDcOk)
            tag.putBoolean("DcOk", true);
        if (shownDcOkLatched)
            tag.putBoolean("DcOkLatched", true);
    }

    @Override
    public void read(CompoundTag tag) {
        PsuRegulator.State[] states = PsuRegulator.State.values();
        int state = tag.getByte("State");
        shownState = state >= 0 && state < states.length ? states[state] : PsuRegulator.State.OFF;
        shownAmps = tag.getFloat("Amps");
        shownWatts = tag.getFloat("Watts");
        shownDcOk = tag.getBoolean("DcOk");
        shownDcOkLatched = tag.getBoolean("DcOkLatched");
    }
}
