package com.nectariich.create_flippies.content.plc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.PlcCore;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenPlcPacket;
import com.nectariich.create_flippies.network.PlcUploadResultPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One small PLC clipped to a rail: an emulated STM32F103CB running an Arduino sketch,
 * on a board that takes 24 V in and puts 24 V out.
 *
 * The emulator is {@link Stm32f103} and the board's arithmetic is {@link PlcBoard};
 * this class is the glue between them and the world. Once a server tick it reads
 * the terminals, decides what the board does with them, runs the chip for the tick's
 * fifty milliseconds and drives the outputs with what the chip did. Power Grid cannot
 * run at 72 MHz and does not need to: everything the sketch does inside one tick
 * reaches the wires as one averaged state, and every input it reads holds still for
 * the whole tick, which is the exchange {@link com.nectariich.create_flippies.content.plc.mcu.PlcCore}
 * is built around.
 *
 * <h2>The circuit</h2>
 *
 * Built in a fixed order — the logic load, eight inputs, RESET, nine outputs —
 * because Power Grid streams switched-wire states to the client positionally, and a
 * control whose wires came and went would shift every later control's readings on
 * the same rail. Every wire exists for the life of the circuit and is switched or
 * re-valued instead:
 *
 * <ul>
 * <li>the <b>logic load</b>, VCC to GND, is re-valued every tick to draw what the
 * board's own electronics draw — see {@link PlcBoard.Power#logicDraw()};
 * <li>each <b>input</b> and <b>RESET</b> is 6.8 kilohm to GND, switched open when its
 * channel is latched;
 * <li>each <b>output</b> is a high-side switch from VCC to its pin, open when off,
 * its on-resistance when fully on, and in between whatever series resistance makes
 * the load it drives carry the duty's share of its on-state current — see
 * {@link PlcBoard#outputResistance(double, double)} for why that is not simply the
 * on-resistance divided by the duty.
 * </ul>
 *
 * <h2>What is saved</h2>
 *
 * The sketch's source, whether it is flashed, and the latched channels. SRAM is not
 * saved: the MCU cold-boots when the chunk loads, the way a PLC does when its cabinet
 * is switched back on, so {@code millis()} starts from zero and globals are
 * re-initialised. The image itself is not saved either. It is rebuilt from its
 * source on the first server tick after a load — off the server thread, so a chunk
 * full of PLCs does not stall the game — and if the toolchain no longer accepts it,
 * the PLC comes back blank and says why, rather than running something other than
 * what was uploaded.
 *
 * The host syncs one tag for both disk and the network, so whatever is saved also
 * goes to every client in range whenever anything on the rail changes. The source is
 * therefore stored deflated — deflated once per edit and the bytes reused, since a
 * sync can come every couple of seconds — and the rest of the synced state is the
 * little the goggles need. (Keeping the source out of the network copy altogether
 * needs the host to say which copy it is writing, which {@link HostedControl#write}
 * does not yet.) Nothing calls {@link #changed()} per tick: the goggle summary is
 * re-sent only when something worth seeing changes, and never more often than
 * {@link #SYNC_INTERVAL} allows. Everything else the menu shows is fetched on demand;
 * see {@link PlcStatus}.
 *
 * Clients run no emulation. They build the same circuit, so Power Grid can stream
 * node voltages and switch states into it, and they read the goggle summary.
 */
public class PlcSmallControl extends HostedControl {

    /** One game tick of emulated time. */
    public static final long WINDOW_NANOS = 50_000_000L;

    /**
     * Real time one PLC may spend emulating one tick: 2 ms, so twenty-five busy PLCs
     * cost the server a whole tick. A sketch that needs more than that falls behind
     * real time instead — {@link PlcCore#speed()} reports by how much — which is the
     * honest failure for a machine asked to do more than it can. There is no server
     * config to hang this on yet; it is a constant until there is.
     */
    public static final long HOST_BUDGET_NANOS = 2_000_000L;

    /** How far away a player may be, in blocks, to use the menu. */
    public static final double MENU_REACH = 16;

    /**
     * The fewest ticks between two goggle syncs for a change of state, and for a
     * drift in power. A PLC whose RESET is wired to a clock would otherwise sync the
     * whole rail on every edge.
     */
    private static final int SYNC_INTERVAL = 10;
    private static final int POWER_SYNC_INTERVAL = 40;

    /** A power reading that has moved by less than this fraction is not worth a sync. */
    private static final double POWER_SYNC_FRACTION = 0.1;

    /** Below this many watts, differences in power are not worth a sync either. */
    private static final double POWER_SYNC_FLOOR = 0.005;

    /** Relative change in a wire's resistance below which Power Grid is not asked to re-solve. */
    private static final double RESISTANCE_DEADBAND = 0.01;

    /**
     * Largest inflated source, in bytes, that is read from a save at all: the bound
     * on the work inflating it can do. A text that fits is still held to
     * {@link PlcToolchain#MAX_SOURCE_CHARS} in {@link #read}.
     */
    private static final int MAX_STORED_BYTES = PlcToolchain.MAX_SOURCE_BYTES;

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".plc.goggles.";
    private static final String STATE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".plc.state.";
    private static final String NOTICE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".plc.notice.";

    // ------------------------------------------------- saved and synced ---

    /** What the menu's editor holds, saved on every upload and when the menu closes. */
    private String source = "";

    /** The source of the program that should be in flash, or null for blank flash. */
    private String flashedSource;

    // What the goggles show, written by the server and read by everyone
    private McuState shownState = McuState.UNPOWERED;
    private String shownFault = "";
    private int shownFaultLine;
    private float shownPower;
    /**
     * Set when the PLC was left blank against its owner's wishes, with why: the saved
     * program no longer compiled at load (the compiler's first error), was longer
     * than an upload allows, or the emulator itself threw (see
     * {@link #emulatorFailed}). Cleared by the next good upload.
     */
    private boolean notRestored;
    private String restoreError = "";

    private final PlcBoard board = new PlcBoard();

    /** The two texts above as last deflated or inflated; see {@link #write}. */
    private final Packed packedSource = new Packed();
    private final Packed packedFlashed = new Packed();

    // ------------------------------------------------------ server only ---

    /** The chip, created on the first server tick. Null on clients. */
    private PlcCore core;

    /** Source of the image the chip actually holds, or null when it is blank. */
    private String coreSource;

    /** The image the chip holds, for the menu's memory figures. */
    private CompiledProgram coreProgram;

    /** A compile in flight, if any; see {@link CompileJob}. */
    private CompileJob job;

    private int ticksSinceSync = Integer.MAX_VALUE / 2;
    private boolean summaryDirty;

    // The last tick's measurements, for the menu
    private double supplyVolts;
    private PlcBoard.Power power = PlcBoard.Power.NONE;
    private final float[] channelAmps = new float[PlcBoard.CHANNELS];
    private final float[] channelWatts = new float[PlcBoard.CHANNELS];

    // ------------------------------------------------------------ circuit ---

    private IElectricNode vcc;
    private IElectricNode gnd;
    private final IElectricNode[] inputNodes = new IElectricNode[PlcBoard.INPUTS];
    private IElectricNode resetNode;
    private final IElectricNode[] outputNodes = new IElectricNode[PlcBoard.OUTPUTS];

    private ElectricWire logicLoad;
    private final SwitchedWire[] inputWires = new SwitchedWire[PlcBoard.INPUTS];
    private SwitchedWire resetWire;
    private final SwitchedWire[] outputWires = new SwitchedWire[PlcBoard.OUTPUTS];

    /** What each wire should be, kept so a rebuilt circuit starts where the old one was. */
    private double logicOhms = PlcBoard.STANDBY_OHMS;
    private final double[] duty = new double[PlcBoard.OUTPUTS];

    /**
     * The load each output was last seen driving, in ohms, or NaN before it has been
     * seen; what the averaged switch is sized against. Not saved: the first tick an
     * output conducts after a load measures it again.
     */
    private final double[] loadOhms = new double[PlcBoard.OUTPUTS];

    {
        Arrays.fill(loadOhms, Double.NaN);
    }

    public PlcSmallControl(PlcSmallControlType type) {
        super(type);
    }

    /** The PLC mounted at {@code mount} in the host {@code blockEntity}, if there is one. */
    public static PlcSmallControl at(BlockEntity blockEntity, ControlMount mount) {
        if (!(blockEntity instanceof ControlRackHolder host))
            return null;
        for (HostedControl control : host.controls())
            if (control instanceof PlcSmallControl plc && mount.equals(plc.mount()))
                return plc;
        return null;
    }

    /**
     * The PLC a serverbound menu packet names, if this player may use it: the block
     * loaded, the PLC still mounted there, the player alive, not a spectator and
     * within {@link #MENU_REACH}. {@code modify} additionally asks whether they may
     * change the world — an adventure-mode player may watch a PLC's status but not
     * reprogram or reset it.
     */
    public static PlcSmallControl reachable(ServerPlayer player, BlockPos pos, ControlMount mount, boolean modify) {
        if (player == null || pos == null || mount == null || !player.isAlive() || player.isSpectator())
            return null;
        if (modify && !player.mayBuild())
            return null;
        Level level = player.level();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MENU_REACH * MENU_REACH)
            return null;
        return at(level.getBlockEntity(pos), mount);
    }

    public String source() {
        return source;
    }

    /** Puts a particular chip in this PLC, for tests; the game always gets an {@link Stm32f103}. */
    void useCore(PlcCore core) {
        this.core = core;
    }

    /** The board's state, for tests. */
    PlcBoard board() {
        return board;
    }

    /** What each output stage was driven at on the last tick, for tests. */
    double duty(int output) {
        return duty[output];
    }

    /** The logic load's resistance as last set, for tests. */
    double logicOhms() {
        return logicOhms;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(PlcSmallGeometry.TERMINALS);
        vcc = builder.terminalNode(PlcSmallGeometry.TERMINAL_VCC);
        gnd = builder.terminalNode(PlcSmallGeometry.TERMINAL_GND);
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            inputNodes[i] = builder.terminalNode(PlcSmallGeometry.TERMINAL_DI0 + i);
        resetNode = builder.terminalNode(PlcSmallGeometry.TERMINAL_RESET);
        for (int i = 0; i < PlcBoard.OUTPUTS; i++)
            outputNodes[i] = builder.terminalNode(i < 8 ? PlcSmallGeometry.TERMINAL_DO0 + i
                    : PlcSmallGeometry.TERMINAL_STLED);

        // The order below is the order Power Grid streams these to the client in;
        // see the class comment before changing it
        logicLoad = builder.connect((float) logicOhms, vcc, gnd);
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            inputWires[i] = builder.connectSwitch((float) PlcBoard.INPUT_OHMS, inputNodes[i], gnd,
                    !board.faulted(PlcBoard.CH_DI0 + i));
        resetWire = builder.connectSwitch((float) PlcBoard.INPUT_OHMS, resetNode, gnd,
                !board.faulted(PlcBoard.CH_RESET));
        for (int i = 0; i < PlcBoard.OUTPUTS; i++) {
            boolean on = duty[i] > 0;
            double ohms = on ? PlcBoard.outputResistance(duty[i], loadOhms[i]) : PlcBoard.OUTPUT_ON_OHMS;
            outputWires[i] = builder.connectSwitch((float) ohms, vcc, outputNodes[i], on);
        }
    }

    private boolean built() {
        return logicLoad != null;
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /** VCC above GND; on a client, from the node voltages Power Grid syncs. */
    public double supplyVolts() {
        return built() ? voltage(vcc) - voltage(gnd) : 0;
    }

    // ---------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide)
            return;
        serverTick(level);
    }

    /**
     * One server tick of the board. Package-private, with a nullable level, so that
     * the board can be driven against a real Power Grid network in a test without a
     * world around it; the level is only asked who requested an upload.
     */
    void serverTick(Level level) {
        if (!built())
            return;
        if (core == null)
            core = new Stm32f103();

        finishCompile(level);
        if (job == null && !Objects.equals(flashedSource, coreSource))
            boot();

        double ground = voltage(gnd);
        supplyVolts = voltage(vcc) - ground;

        // What the solve that has just happened saw: every channel's current, each
        // output's pin, and the duty each output's wire was set to for it — the
        // duties are about to be replaced with this tick's
        double[] amps = channelCurrents();
        double[] solvedDuty = duty.clone();
        double[] pinVolts = new double[PlcBoard.OUTPUTS];
        for (int i = 0; i < PlcBoard.OUTPUTS; i++) {
            pinVolts[i] = voltage(outputNodes[i]) - ground;
            double seen = PlcBoard.loadOhms(pinVolts[i], amps[PlcBoard.CH_DO0 + i]);
            if (!Double.isNaN(seen) && solvedDuty[i] > 0)
                loadOhms[i] = seen;
        }

        // Faults first: a brown-out clears every latch, and only a powered board can
        // set one. An output is judged on what it carries while its switch is
        // closed, not on its average; see PlcBoard.checkOvercurrent
        boolean faultsChanged = board.updateSupply(supplyVolts);
        faultsChanged |= board.checkOvercurrent(limitCurrents(amps, solvedDuty, pinVolts));

        double[] inputVolts = new double[PlcBoard.INPUTS];
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            inputVolts[i] = voltage(inputNodes[i]) - ground;
        board.updateInputs(inputVolts);
        // Asserting RESET clears the latches; holding the MCU in reset is the chip's
        // own business through setResetHeld below
        faultsChanged |= board.updateReset(voltage(resetNode) - ground);
        applyInputSwitches();

        // Every one of these can run sketch code — powering up or letting go of reset
        // boots the image, which runs its constructors and setup() — so all of them
        // are inside the guard, not only runFor
        try {
            core.setPowered(board.supplyOn());
            core.setResetHeld(board.resetAsserted());
            for (int i = 0; i < PlcBoard.INPUTS; i++)
                core.setInput(ArduinoPins.FIRST_DI + i, board.mcuInput(i));
            core.runFor(WINDOW_NANOS, HOST_BUDGET_NANOS);
        } catch (RuntimeException | StackOverflowError e) {
            emulatorFailed(e);
        }

        McuState state = core.state();
        double dutySum = 0;
        for (int i = 0; i < PlcBoard.OUTPUTS; i++) {
            int pin = i < 8 ? ArduinoPins.FIRST_DO + i : ArduinoPins.STATUS;
            PinDrive drive = core.pin(pin);
            setDuty(i, board.outputDuty(PlcBoard.CH_DO0 + i, drive, state));
            dutySum += duty[i];
        }

        measurePower(amps, solvedDuty, pinVolts, inputVolts, ground, dutySum);
        setLogicLoad(PlcBoard.logicLoadResistance(supplyVolts, power.logicDraw()));

        updateSummary(state, faultsChanged);
    }

    /**
     * Every channel's current from the last solve, in {@link PlcBoard}'s order. An open
     * switch reads zero, which is what a latched channel carries.
     */
    private double[] channelCurrents() {
        double[] amps = new double[PlcBoard.CHANNELS];
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            amps[PlcBoard.CH_DI0 + i] = inputWires[i].current();
        for (int i = 0; i < PlcBoard.OUTPUTS; i++)
            amps[PlcBoard.CH_DO0 + i] = outputWires[i].current();
        amps[PlcBoard.CH_RESET] = resetWire.current();
        for (int i = 0; i < PlcBoard.CHANNELS; i++)
            channelAmps[i] = (float) amps[i];
        return amps;
    }

    /**
     * The currents the per-pin limit is checked against: every channel's as measured,
     * except that each output's is its on-state current, worked out from the duty
     * its wire was averaged over.
     */
    private double[] limitCurrents(double[] amps, double[] solvedDuty, double[] pinVolts) {
        double[] limit = amps.clone();
        for (int i = 0; i < PlcBoard.OUTPUTS; i++)
            limit[PlcBoard.CH_DO0 + i] = PlcBoard.onStateAmps(amps[PlcBoard.CH_DO0 + i], solvedDuty[i], supplyVolts,
                    pinVolts[i]);
        return limit;
    }

    /** Opens the front end of every latched input, and closes the rest. */
    private void applyInputSwitches() {
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            inputWires[i].setState(!board.faulted(PlcBoard.CH_DI0 + i));
        resetWire.setState(!board.faulted(PlcBoard.CH_RESET));
    }

    /**
     * Drives one output stage, sized against the load it was last seen driving.
     * Power Grid is asked to re-solve only when the switch changes state or its
     * resistance moves by more than {@link #RESISTANCE_DEADBAND}; a PWM duty
     * jittering in its last digit is not a change in anything a load would notice.
     */
    private void setDuty(int output, double newDuty) {
        SwitchedWire wire = outputWires[output];
        double old = duty[output];
        duty[output] = newDuty;
        if (!(newDuty > 0)) {
            wire.setState(false);
            return;
        }
        double ohms = PlcBoard.outputResistance(newDuty, loadOhms[output]);
        if (!(old > 0) || Math.abs(ohms - wire.getResistance()) > wire.getResistance() * RESISTANCE_DEADBAND)
            wire.setResistance(ohms);
        wire.setState(true);
    }

    private void setLogicLoad(double ohms) {
        logicOhms = ohms;
        if (Math.abs(ohms - logicLoad.getResistance()) > logicLoad.getResistance() * RESISTANCE_DEADBAND)
            logicLoad.setResistance(ohms);
    }

    /**
     * The board's budget for this tick, and every channel's share of it for the menu.
     * Everything is from the solve the currents came from, never from the wires as
     * they have just been switched: a switch closed this tick onto a pin still at
     * 0 V would otherwise read as the whole rail across 0.12 ohm.
     */
    private void measurePower(double[] amps, double[] solvedDuty, double[] pinVolts, double[] inputVolts,
            double ground, double dutySum) {
        double inputs = 0;
        int optos = 0;
        for (int i = 0; i < PlcBoard.INPUTS; i++) {
            double watts = Math.abs(inputVolts[i] * amps[PlcBoard.CH_DI0 + i]);
            channelWatts[PlcBoard.CH_DI0 + i] = (float) watts;
            inputs += watts;
            if (board.mcuInput(i))
                optos++;
        }
        double resetWatts = Math.abs((voltage(resetNode) - ground) * amps[PlcBoard.CH_RESET]);
        channelWatts[PlcBoard.CH_RESET] = (float) resetWatts;
        inputs += resetWatts;
        if (board.resetAsserted())
            optos++;

        // An output is worked out from its on-state current rather than from the
        // averaged wire: the wire stands in for the switch with whatever resistance
        // gets the mean current right, so the drop across it is not what the real
        // switch dissipates, and the load's share of the power is under-stated in
        // Power Grid. A switch on for duty d at I_on keeps d·I_on²·R_on and passes on
        // the rest of d·I_on·V
        double drivers = 0;
        double delivered = 0;
        for (int i = 0; i < PlcBoard.OUTPUTS; i++) {
            double d = solvedDuty[i];
            double on = PlcBoard.onStateAmps(amps[PlcBoard.CH_DO0 + i], d, supplyVolts, pinVolts[i]);
            double loss = PlcBoard.driverLoss(d, on);
            double load = Math.max(0, PlcBoard.delivered(d, on, supplyVolts));
            channelWatts[PlcBoard.CH_DO0 + i] = (float) (loss + load);
            drivers += loss;
            delivered += load;
        }

        double mcu = board.supplyOn() ? core.mcuPowerWatts() : 0;
        power = board.power(supplyVolts, mcu, optos, dutySum, inputs, drivers, delivered);
    }

    // ------------------------------------------------------ the goggle sync ---

    /**
     * Re-sends the goggle summary when it has changed enough to matter. A change of
     * state, fault or latch is sent within {@link #SYNC_INTERVAL} ticks; a drift in
     * power within {@link #POWER_SYNC_INTERVAL}, and only past
     * {@link #POWER_SYNC_FRACTION}.
     */
    private void updateSummary(McuState state, boolean faultsChanged) {
        ticksSinceSync++;
        HardFault fault = core.fault();
        String faultName = state == McuState.HARD_FAULT && fault != null ? fault.kind().name() : "";
        int faultLine = state == McuState.HARD_FAULT ? core.faultLine() : 0;
        if (state != shownState || !faultName.equals(shownFault) || faultLine != shownFaultLine || faultsChanged) {
            shownState = state;
            shownFault = faultName;
            shownFaultLine = faultLine;
            summaryDirty = true;
        }

        double total = power.total();
        boolean drifted = Math.abs(total - shownPower) > Math.max(POWER_SYNC_FLOOR, shownPower * POWER_SYNC_FRACTION);
        if (summaryDirty && ticksSinceSync >= SYNC_INTERVAL
                || drifted && ticksSinceSync >= POWER_SYNC_INTERVAL) {
            shownPower = (float) total;
            sync();
        }
    }

    private void sync() {
        summaryDirty = false;
        ticksSinceSync = 0;
        changed();
    }

    // ------------------------------------------------------------ compiles ---

    /**
     * One compile running off the server thread, on the toolchain's own threads; see
     * {@link PlcToolchain#compileAsync}. A PLC has at most one at a time. A compile
     * cannot be stopped once started, so replacing one with a newer one would only
     * have left the old one running, unseen, for as long as it liked, and let anyone
     * holding Upload start as many as they could click.
     *
     * @param boot      true for the cold boot after a load, false for an upload
     * @param requester who asked for an upload, to be told how it went; null for a boot
     */
    private record CompileJob(String source, boolean boot, UUID requester, CompletableFuture<CompileResult> result) {}

    /**
     * Rebuilds the saved program, or blanks the chip if there is none to rebuild. If
     * the toolchain's queue is full (a chunk of many PLCs loading at once) nothing is
     * started, and the next tick asks again.
     */
    private void boot() {
        if (flashedSource == null) {
            load(null, null);
            return;
        }
        CompletableFuture<CompileResult> result = PlcToolchain.compileAsync(flashedSource);
        if (result != null)
            job = new CompileJob(flashedSource, true, null, result);
    }

    /** Whether a compile is in flight for this PLC. */
    boolean compiling() {
        return job != null;
    }

    private void finishCompile(Level level) {
        if (job == null || !job.result().isDone())
            return;
        CompileJob done = job;
        job = null;
        CompileResult result;
        try {
            result = done.result().join();
        } catch (RuntimeException e) {
            result = new CompileResult(null, List.of(
                    new Diagnostic(Diagnostic.Severity.ERROR, 0, 0, "internal compiler error: " + e)));
        }

        if (done.boot()) {
            // The program to boot changed while this compiled — the chip threw and
            // was blanked, say — so this image is no longer wanted; the next tick
            // compares again and boots what is
            if (!done.source().equals(flashedSource))
                return;
            if (result.ok()) {
                load(result.program(), done.source());
                return;
            }
            // Comes back blank and says so, rather than leaving a chip that silently
            // runs nothing or retrying the same failure every tick
            Diagnostic error = PlcToolchain.firstError(result);
            notRestored = true;
            restoreError = error == null ? "" : PlcStatus.clip(PlcToolchain.format(error));
            flashedSource = null;
            load(null, null);
            summaryDirty = true;
            return;
        }

        // A program the chip throws on as it goes in has not been flashed: the PLC is
        // blank and says why, and the uploader hears the same
        boolean flashed = result.ok() && load(result.program(), done.source());
        if (flashed) {
            flashedSource = done.source();
            board.clearFaults();
            applyInputSwitches();
            notRestored = false;
            restoreError = "";
            sync();
        }
        ServerPlayer player = requester(level, done.requester());
        if (player != null) {
            List<String> lines = PlcToolchain.report(result);
            if (result.ok() && !flashed) {
                lines = new ArrayList<>(lines);
                lines.add(restoreError);
            }
            reply(player, flashed ? PlcUploadResultPacket.Outcome.FLASHED : PlcUploadResultPacket.Outcome.FAILED,
                    lines);
        }
    }

    /** Tells the player who uploaded how it went. */
    public void reply(ServerPlayer player, PlcUploadResultPacket.Outcome outcome, List<String> lines) {
        if (host() != null)
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PlcUploadResultPacket(host().pos(), mount(), outcome, lines));
    }

    /**
     * Puts an image in flash and remembers which source it came from. The source is
     * the one the job was started with rather than {@code program.source()}, so that
     * a toolchain normalising its copy (line endings, say) cannot make the comparison
     * in {@link #tick} see a difference and boot the same sketch forever.
     *
     * @return false if the chip threw instead; it has then been blanked, see
     *         {@link #emulatorFailed}
     */
    private boolean load(CompiledProgram program, String builtFrom) {
        try {
            core.flash(program);
        } catch (RuntimeException | StackOverflowError e) {
            emulatorFailed(e);
            return false;
        }
        coreProgram = program;
        coreSource = program == null ? null : builtFrom;
        return true;
    }

    /**
     * What happens when the emulator itself throws — not a HardFault, which is the
     * sketch's failure and the chip's to report, but a bug in the interpreter or a
     * builtin. Left alone, the exception would leave through the block entity's tick
     * and Minecraft would stop the server with "Ticking block entity". Worse, the
     * same sketch is saved as flashed and is booted again on the first tick after the
     * restart, so the world would crash every time it loaded until someone edited
     * its NBT by hand.
     *
     * So the PLC is blanked instead, with the reason where the menu and the goggles
     * show it: the flash erased, every output off, and nothing saved as flashed, so a
     * reload does not try the same thing again. The sketch's text stays in the editor.
     * The chip is not trusted after an exception that may have left it halfway
     * through anything; if even blanking it throws, it is replaced by a fresh one.
     */
    private void emulatorFailed(Throwable e) {
        CreateFlippies.LOGGER.error("A PLC at {} threw inside its emulator and was blanked",
                host() == null ? "an unknown position" : host().pos(), e);
        flashedSource = null;
        coreSource = null;
        coreProgram = null;
        notRestored = true;
        restoreError = PlcStatus.clip("internal emulator error: " + e);
        try {
            core.flash(null);
        } catch (RuntimeException | StackOverflowError again) {
            core = new Stm32f103();
        }
        if (built())
            for (int i = 0; i < PlcBoard.OUTPUTS; i++)
                setDuty(i, 0);
        summaryDirty = true;
    }

    private static ServerPlayer requester(Level level, UUID id) {
        MinecraftServer server = level == null ? null : level.getServer();
        return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
    }

    // ------------------------------------------------------------- the menu ---

    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        // read() already refuses a longer text, but a string the packet cannot carry
        // would throw out of the click on the server thread, every click, and the
        // menu would never open — so it is clipped here too rather than trusted
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenPlcPacket(host().pos(), mount(), clipSource(source), status()));
        return true;
    }

    /**
     * Takes a sketch from the menu: always keeps it as the editor's text, and if
     * {@code flash} is set, compiles it and — only if it builds — flashes it, resets
     * the chip and clears every latch. A sketch that does not build leaves the old
     * program running, which is what an Arduino does when an upload fails.
     *
     * A flash is refused, with an immediate answer, while this PLC is still
     * compiling (its own boot, or an earlier upload) or while the toolchain's queue
     * is full. The text is kept either way, so nothing typed is lost.
     */
    public void upload(String newSource, boolean flash, ServerPlayer player) {
        if (!upload(newSource, flash, player.getUUID()))
            reply(player, PlcUploadResultPacket.Outcome.BUSY, List.of());
    }

    /** @return false if a flash was asked for and not started */
    boolean upload(String newSource, boolean flash, UUID requester) {
        if (!newSource.equals(source)) {
            source = newSource;
            changed();
        }
        if (!flash)
            return true;
        if (job != null)
            return false;
        CompletableFuture<CompileResult> result = PlcToolchain.compileAsync(newSource);
        if (result == null)
            return false;
        job = new CompileJob(newSource, false, requester, result);
        return true;
    }

    /** The menu's Reset: an NRST pulse, which also clears every latch. */
    public void pressReset() {
        // A reset boots the image again, constructors and setup() included, so it is
        // guarded like every other call that can run the sketch
        if (core != null) {
            try {
                core.reset();
            } catch (RuntimeException | StackOverflowError e) {
                emulatorFailed(e);
            }
        }
        if (board.clearFaults()) {
            applyInputSwitches();
            summaryDirty = true;
        }
    }

    /** What the menu's live pane shows, as of the last tick. Server only. */
    public PlcStatus status() {
        boolean running = core != null;
        McuState state = running ? core.state() : McuState.UNPOWERED;
        HardFault fault = running ? core.fault() : null;
        CompiledProgram program = coreProgram;
        int inputsHigh = 0;
        for (int i = 0; i < PlcBoard.INPUTS; i++)
            if (board.inputHigh(i))
                inputsHigh |= 1 << i;
        float[] duties = new float[PlcBoard.OUTPUTS];
        for (int i = 0; i < PlcBoard.OUTPUTS; i++)
            duties[i] = (float) duty[i];
        return new PlcStatus(state,
                state == McuState.HARD_FAULT && fault != null ? PlcStatus.clip(fault.kind().name()) : "",
                state == McuState.HARD_FAULT && running ? core.faultLine() : 0,
                running ? core.uptimeMillis() : 0,
                running ? core.cpuLoad() : 0,
                running ? core.speed() : 0,
                supplyVolts, PlcBoard.condition(board.supplyOn(), supplyVolts),
                program != null, program == null ? 0 : program.flashBytes(),
                program == null ? 0 : program.staticRamBytes(),
                running ? core.stackPeak() : 0, running ? core.heapPeak() : 0,
                power, inputsHigh, board.resetAsserted(), board.faults(), duties, channelAmps.clone(),
                channelWatts.clone(), job != null, notRestored, restoreError);
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "state", stateName(shownState))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "supply",
                Component.literal(String.format("%.1f", supplyVolts())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "power",
                Component.literal(formatWatts(shownPower)).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        if (shownState == McuState.HARD_FAULT)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "fault",
                    faultName(shownFault), shownFaultLine).withStyle(ChatFormatting.RED)));
        if (board.faults() != 0)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "latched",
                    Component.literal(channelList(board.faults()))).withStyle(ChatFormatting.RED)));
        if (notRestored)
            tooltip.add(indent(notRestoredNotice(restoreError).withStyle(ChatFormatting.GOLD)));
        return true;
    }

    /**
     * Why the PLC came back blank. The sentence is translated; the reason is not,
     * since it is a tool's output (the compiler's error, the emulator's exception)
     * and is printed in the tool's words.
     */
    public static MutableComponent notRestoredNotice(String error) {
        if (error == null || error.isEmpty())
            return Component.translatable(NOTICE_PREFIX + "not_restored");
        return Component.translatable(NOTICE_PREFIX + "not_restored_because", error);
    }

    /** The state's name, coloured for how much attention it wants. */
    public static MutableComponent stateName(McuState state) {
        ChatFormatting colour = switch (state) {
            case RUNNING -> ChatFormatting.GREEN;
            case HARD_FAULT -> ChatFormatting.RED;
            case PROGRAMMING, IN_RESET -> ChatFormatting.YELLOW;
            default -> ChatFormatting.DARK_GRAY;
        };
        return Component.translatable(stateKey(state)).withStyle(colour);
    }

    /**
     * The lang key for a state, lowercased in the root locale as every key is: under
     * a Turkish default locale a plain toLowerCase() turns RUNNING into "runnıng",
     * with a dotless i, and the key is not found.
     */
    static String stateKey(McuState state) {
        return STATE_PREFIX + state.name().toLowerCase(Locale.ROOT);
    }

    /** The lang key for a HardFault kind, by its enum name; see {@link #stateKey}. */
    static String faultKey(String kind) {
        if (kind.isEmpty())
            return STATE_PREFIX + "fault.unknown";
        return STATE_PREFIX + "fault." + kind.toLowerCase(Locale.ROOT);
    }

    /** The menu's lang key for a supply condition; see {@link #stateKey} for the locale. */
    public static String supplyKey(PlcBoard.SupplyCondition condition) {
        return "gui." + CreateFlippies.MOD_ID + ".plc.menu.supply." + condition.name().toLowerCase(Locale.ROOT);
    }

    /** A HardFault kind by name, translated where the kind is one this build knows. */
    public static MutableComponent faultName(String kind) {
        return Component.translatable(faultKey(kind));
    }

    /** "DI4, DO2, RESET": the latched channels by their board names. */
    public static String channelList(int faults) {
        StringBuilder names = new StringBuilder();
        for (int channel = 0; channel < PlcBoard.CHANNELS; channel++) {
            if ((faults & (1 << channel)) == 0)
                continue;
            if (names.length() > 0)
                names.append(", ");
            names.append(channelName(channel));
        }
        return names.toString();
    }

    /** A channel's board name, as printed by the terminal it belongs to. */
    public static String channelName(int channel) {
        if (channel < PlcBoard.CH_DO0)
            return "DI" + (channel - PlcBoard.CH_DI0);
        if (channel < PlcBoard.CH_STLED)
            return "DO" + (channel - PlcBoard.CH_DO0);
        return channel == PlcBoard.CH_STLED ? "STLED" : "RESET";
    }

    /** Watts with a unit that keeps three significant figures readable. */
    public static String formatWatts(double watts) {
        double magnitude = Math.abs(watts);
        if (magnitude >= 1)
            return String.format("%.2f W", watts);
        if (magnitude >= 0.001)
            return String.format("%.1f mW", watts * 1e3);
        return String.format("%.0f µW", watts * 1e6);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }

    // ------------------------------------------------------------ save/load ---

    /**
     * Writes the saved state, which is also the synced state: the host has one tag
     * for both, so this runs for every goggle or power sync as well as for saves.
     * The sketch is therefore deflated once per change of text, not once per call —
     * a 64 K-character sketch takes milliseconds to deflate at the best level, and a
     * PLC switching a real load syncs every couple of seconds.
     */
    @Override
    public void write(CompoundTag tag) {
        if (!source.isEmpty())
            tag.putByteArray("Source", packedSource.pack(source));
        if (flashedSource != null) {
            if (flashedSource.equals(source))
                tag.putBoolean("Flashed", true);
            else
                tag.putByteArray("FlashedSource", packedFlashed.pack(flashedSource));
        }
        if (board.faults() != 0)
            tag.putInt("Faults", board.faults());
        tag.putByte("State", (byte) shownState.ordinal());
        if (!shownFault.isEmpty()) {
            tag.putString("Fault", shownFault);
            tag.putInt("FaultLine", shownFaultLine);
        }
        tag.putFloat("Power", shownPower);
        if (notRestored) {
            tag.putBoolean("NotRestored", true);
            if (!restoreError.isEmpty())
                tag.putString("RestoreError", restoreError);
        }
    }

    /**
     * Reads the saved or synced state back. On the server this is the load, and the
     * first tick after it compares {@link #flashedSource} with what the chip holds and
     * boots it; on a client it only refreshes what the goggles show.
     */
    @Override
    public void read(CompoundTag tag) {
        String savedSource = tag.contains("Source", Tag.TAG_BYTE_ARRAY)
                ? packedSource.unpack(tag.getByteArray("Source")) : "";
        String savedFlashed;
        if (tag.getBoolean("Flashed"))
            savedFlashed = savedSource;
        else if (tag.contains("FlashedSource", Tag.TAG_BYTE_ARRAY))
            savedFlashed = packedFlashed.unpack(tag.getByteArray("FlashedSource"));
        else
            savedFlashed = null;

        notRestored = tag.getBoolean("NotRestored");
        restoreError = notRestored ? PlcStatus.clip(tag.getString("RestoreError")) : "";

        // The byte cap in inflate bounds the work; the character limit is the one an
        // upload is held to. A save can carry more — from /data, a structure, a
        // schematic or a creative client's item NBT — and a longer editor text could
        // never be sent to the menu, nor should a longer program boot when it could
        // not have been uploaded. The editor keeps what fits; the program is dropped
        // and the PLC comes back blank and says why
        source = clipSource(savedSource);
        if (source.length() != savedSource.length())
            CreateFlippies.LOGGER.warn("A PLC's saved sketch is {} characters, over the {} an upload allows; "
                    + "the editor keeps the first {}", savedSource.length(), PlcToolchain.MAX_SOURCE_CHARS,
                    source.length());
        if (savedFlashed != null && savedFlashed.length() > PlcToolchain.MAX_SOURCE_CHARS) {
            CreateFlippies.LOGGER.warn("A PLC's saved program is {} characters, over the {} an upload allows, "
                    + "and was not restored", savedFlashed.length(), PlcToolchain.MAX_SOURCE_CHARS);
            savedFlashed = null;
            notRestored = true;
            restoreError = "saved sketch is longer than " + PlcToolchain.MAX_SOURCE_CHARS + " characters";
        }
        flashedSource = savedFlashed;

        board.restoreFaults(tag.getInt("Faults"));
        if (built())
            applyInputSwitches();
        McuState[] states = McuState.values();
        int state = tag.getByte("State");
        shownState = state >= 0 && state < states.length ? states[state] : McuState.UNPOWERED;
        shownFault = tag.getString("Fault");
        shownFaultLine = tag.getInt("FaultLine");
        shownPower = tag.getFloat("Power");
    }

    /**
     * The first {@link PlcToolchain#MAX_SOURCE_CHARS} characters of a text, never
     * ending on half a surrogate pair.
     */
    static String clipSource(String text) {
        if (text.length() <= PlcToolchain.MAX_SOURCE_CHARS)
            return text;
        int end = PlcToolchain.MAX_SOURCE_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }

    /** How many times this PLC has deflated a sketch, for tests. */
    int deflations() {
        return packedSource.deflations + packedFlashed.deflations;
    }

    /**
     * One text and its deflated form, remembered as a pair so that writing the same
     * text again costs a comparison instead of a deflate, and reading the same bytes
     * again (a client receiving every sync of the rail) costs a comparison instead of
     * an inflate.
     */
    private static final class Packed {
        private String text;
        private byte[] bytes;
        int deflations;

        /** The deflated form of {@code text}; a copy, so no tag shares the cache's array. */
        byte[] pack(String text) {
            if (bytes == null || !text.equals(this.text)) {
                this.bytes = deflate(text);
                this.text = text;
                deflations++;
            }
            return bytes.clone();
        }

        String unpack(byte[] packed) {
            if (bytes != null && Arrays.equals(packed, bytes))
                return text;
            String unpacked = inflate(packed);
            // Only text that round-trips is remembered against these bytes; an
            // unreadable save comes back empty, and empty does not deflate to it
            if (!unpacked.isEmpty()) {
                text = unpacked;
                bytes = packed.clone();
            }
            return unpacked;
        }
    }

    private static byte[] deflate(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 16);
            byte[] chunk = new byte[4096];
            while (!deflater.finished())
                out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Anything unreadable or implausibly large comes back empty rather than throwing out of a chunk load. */
    private static String inflate(byte[] packed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 3 + 16);
            byte[] chunk = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    break;
                out.write(chunk, 0, n);
                if (out.size() > MAX_STORED_BYTES)
                    return "";
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            CreateFlippies.LOGGER.error("A PLC's saved sketch is unreadable and was dropped", e);
            return "";
        } finally {
            inflater.end();
        }
    }
}
