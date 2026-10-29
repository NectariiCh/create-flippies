package com.nectariich.create_flippies.content.pc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.ItemNbt;
import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.Platform;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortAddress;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.keyboard.KeyboardEntity;
import com.nectariich.create_flippies.foundation.ServerConfig;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.nectariich.create_flippies.registry.ModSounds;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;

/**
 * A PC case in the world (spec 11.2.2): the parts it holds, its power supply on
 * Power Grid's cord, the machine while it runs, its data ports and its saved state.
 *
 * The machine exists only while the PC is on and its chunk is loaded (spec
 * 11.1.10): an off or unloaded PC holds no RAM. Every call into it goes through
 * {@link PcRuntime}, which the server-wide {@link PcScheduler} runs once per tick
 * after every block entity has ticked.
 *
 * Order inside a tick (spec 11.2.4): this block entity reads the supply voltage
 * and may power the machine off, and sets the load from the previous tick's draw;
 * the scheduler then runs the machine and records this tick's draw.
 */
public class PcCaseBlockEntity extends ElectricBlockEntity implements DataPortHost, IHaveGoggleInformation {

    /** Ticks a refused power-on keeps the error light on: 3 s. */
    private static final int REFUSAL_TICKS = 60;
    /** Ticks between the {@code setChanged} that let autosaves include a running PC: 30 s. */
    private static final int SAVE_INTERVAL = 600;
    /** Ticks a PC that was on waits after loading for Power Grid to solve its network before deciding. */
    private static final int RESTORE_GRACE = 40;
    /** Ticks a PC whose saved state would pass the RAM cap waits before trying again (spec 11.2.6). */
    private static final int RAM_CAP_RETRY = 100;
    /** Ticks between goggle and screen figure syncs while they change. */
    private static final int FIGURES_SYNC = 20;
    /** Ticks a pressed button stays in: 4 (spec 11.2.1). */
    public static final int PRESS_TICKS = 4;

    // Saved

    private UUID pcId;
    private final ItemStack[] parts = emptyParts();
    private boolean supplyOn;
    private boolean crashed;
    private String crashMessage = "";
    private long stateGen;
    private final DataLink[] links = new DataLink[PcCaseGeometry.PORTS];
    /** Held still by a command (spec 12.19): the machine is kept, out of the schedule. */
    private boolean frozen;

    // Server only

    /**
     * Deliberately not initialized where it is declared: Power Grid builds the
     * circuit from the superclass constructor, before any initializer here runs,
     * and an initializer would then overwrite what it built.
     */
    private PcPower.Circuit circuit;
    private PcRuntime runtime;
    private double volts;
    private double railWatts;
    private boolean overVoltage;
    private boolean exitError;
    private long refusalUntil;
    private int sinceLoad = -1;
    private boolean restorePending;
    private int restoreRetryAt;
    private int ticks;
    private String haltReason;
    /** A snapshot of this PC is being written; it stays frozen until that is done. */
    private boolean snapshotBusy;

    // Synced to clients

    private int partsMask = 1 << PcSlot.FRONT_PANEL.ordinal();
    private boolean powerLit;
    private boolean errorLit;
    private long powerPressTick = Long.MIN_VALUE;
    private long resetPressTick = Long.MIN_VALUE;
    private boolean socketWired;
    private String stateKey = "off";
    private String errorText = "";
    private float shownVolts;
    private float shownWatts;
    private float shownSpeed;
    private String cpuText = "";
    private String ramText = "";

    public PcCaseBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.PC_CASE.get(), pos, state);
    }

    protected PcCaseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        parts[PcSlot.FRONT_PANEL.ordinal()] = new ItemStack(com.nectariich.create_flippies.registry.ModItems.PC_FRONT_PANEL.get());
    }

    private static ItemStack[] emptyParts() {
        ItemStack[] stacks = new ItemStack[PcSlot.values().length];
        java.util.Arrays.fill(stacks, ItemStack.EMPTY);
        return stacks;
    }

    // Power Grid

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        circuit = PcPower.buildCircuit(builder);
    }

    /** The supply voltage last read, volts. */
    public double volts() {
        return volts;
    }

    // Parts

    public Direction facing() {
        return getBlockState().getValue(PcCaseBlock.FACING);
    }

    public ItemStack part(PcSlot slot) {
        return parts[slot.ordinal()];
    }

    /** The filled slots: from the stacks on the server, from the synced mask on a client. */
    public Set<PcSlot> filled() {
        EnumSet<PcSlot> set = EnumSet.noneOf(PcSlot.class);
        for (PcSlot slot : PcSlot.values())
            if ((partsMask & slot.bit()) != 0)
                set.add(slot);
        return set;
    }

    public int partsMask() {
        return partsMask;
    }

    private void setPart(PcSlot slot, ItemStack stack) {
        parts[slot.ordinal()] = stack;
        recomputeMask();
        notifyUpdate();
    }

    private void recomputeMask() {
        int mask = 0;
        for (PcSlot slot : PcSlot.values())
            if (!parts[slot.ordinal()].isEmpty())
                mask |= slot.bit();
        partsMask = mask;
    }

    /** Whether a machine exists: on the server the runtime, on a client the synced power light. */
    public boolean running() {
        return level != null && level.isClientSide ? powerLit : runtime != null;
    }

    public Machine machine() {
        return runtime == null ? null : runtime.machine();
    }

    PcRuntime runtime() {
        return runtime;
    }

    public UUID pcId() {
        return pcId;
    }

    /**
     * Puts a part in. The caller has checked {@link PcSlotRules}; this only does it.
     * A floppy goes into a running machine at once (spec 11.1.5 handles the swap).
     */
    void install(PcSlot slot, ItemStack stack) {
        // Identities are given on first install, so they travel with the item from then on
        if (stack.getItem() instanceof PcMotherboardItem)
            PcMotherboardItem.macOf(stack, level.random);
        if (stack.getItem() instanceof FloppyDiskItem && PcServer.get() != null)
            FloppyDiskItem.fillFromPreset(stack, PcServer.get().disks());
        if (stack.getItem() instanceof PcHddItem || stack.getItem() instanceof FloppyDiskItem)
            PcDiskItems.assignId(stack);
        setPart(slot, stack);
        if (slot == PcSlot.FLOPPY && runtime != null)
            runtime.insertFloppy(stack);
        setChanged();
    }

    /** Takes a part out and hands it back. A floppy leaves a running machine at once. */
    ItemStack remove(PcSlot slot) {
        ItemStack stack = parts[slot.ordinal()];
        if (slot == PcSlot.FLOPPY && runtime != null)
            runtime.ejectFloppy();
        setPart(slot, ItemStack.EMPTY);
        setChanged();
        return stack;
    }

    // Buttons

    /** The power button (spec 11.2.9). Server only. */
    void pressPower(Player player) {
        powerPressTick = level.getGameTime();
        click();
        if (runtime != null) {
            stopMachine(true);
        } else {
            Component refusal = tryPowerOn();
            if (refusal != null) {
                refusalUntil = level.getGameTime() + REFUSAL_TICKS;
                player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.RED), true);
            }
        }
        updateLights();
        notifyUpdate();
    }

    /** The reset button: a reset when a machine exists, else nothing. Server only. */
    void pressReset() {
        resetPressTick = level.getGameTime();
        click();
        if (runtime != null)
            runtime.reset();
        notifyUpdate();
    }

    private void click() {
        level.playSound(null, worldPosition, ModSounds.RELAY_CLICK.get(), SoundSource.BLOCKS, 0.4f, 1.6f);
    }

    public long powerPressTick() {
        return powerPressTick;
    }

    public long resetPressTick() {
        return resetPressTick;
    }

    public boolean powerLit() {
        return powerLit;
    }

    public boolean errorLit() {
        return errorLit;
    }

    public boolean socketWired() {
        return socketWired;
    }

    // Starting and stopping the machine

    /**
     * Builds the machine and powers it on, or says why not: a motherboard, CPU and
     * RAM are needed, the supply must be in its start window, and the server's
     * running and RAM caps must allow one more (spec 11.2.6, 11.2.9).
     */
    private Component tryPowerOn() {
        crashed = false;
        crashMessage = "";
        exitError = false;
        overVoltage = false;
        haltReason = null;
        if (part(PcSlot.MOTHERBOARD).isEmpty())
            return Component.translatable("gui.create_flippies.pc.power.no_motherboard");
        if (!(part(PcSlot.CPU).getItem() instanceof PcCpuItem))
            return Component.translatable("gui.create_flippies.pc.power.no_cpu");
        if (part(PcSlot.RAM_A).isEmpty() && part(PcSlot.RAM_B).isEmpty())
            return Component.translatable("gui.create_flippies.pc.power.no_ram");
        if (!PcPower.canStart(volts))
            return Component.translatable("gui.create_flippies.pc.power.no_supply",
                    String.format(Locale.ROOT, "%.0f", volts));
        PcServer server = PcServer.get();
        if (server == null)
            return Component.translatable("gui.create_flippies.pc.power.no_server");
        if (server.scheduler().size() >= ServerConfig.maxRunningPcs())
            return Component.translatable("gui.create_flippies.pc.power.running_cap", ServerConfig.maxRunningPcs());
        if (!PcRamRules.fitsTotal(server.heldRamBytes(), usableRam(), ServerConfig.maxTotalRamMiB()))
            return Component.translatable("gui.create_flippies.pc.power.ram_cap", ServerConfig.maxTotalRamMiB());
        try {
            startMachine(server).machine().powerOn();
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            crash(e);
            return Component.translatable("gui.create_flippies.pc.power.crashed", e.toString());
        }
        supplyOn = true;
        setChanged();
        return null;
    }

    private PcCpuType cpuType() {
        return part(PcSlot.CPU).getItem() instanceof PcCpuItem cpu ? cpu.type() : null;
    }

    private long installedRam() {
        return PcRamRules.installedBytes(PcRamItem.typeOf(part(PcSlot.RAM_A)), PcRamItem.typeOf(part(PcSlot.RAM_B)));
    }

    /** What the CPU can use of the installed RAM; 0 without a CPU. */
    int usableRam() {
        PcCpuType cpu = cpuType();
        return cpu == null ? 0 : PcRamRules.usableBytes(installedRam(), Platform.forModel(cpu.model()).maxRamBytes());
    }

    /** Builds the machine from the parts and schedules it, powered off. */
    private PcRuntime startMachine(PcServer server) {
        PcCpuType cpu = cpuType();
        ItemStack hddStack = part(PcSlot.HDD);
        PagedDiskImage hdd = null;
        if (hddStack.getItem() instanceof PcHddItem hddItem)
            hdd = server.disks().acquire(PcDiskItems.assignId(hddStack), hddItem.type().bytes());
        long mac = PcMotherboardItem.macOf(part(PcSlot.MOTHERBOARD), level.random);
        MachineConfig config = MachineConfig.of(cpu.model(), cpu.clockHz(), usableRam(), hdd,
                PcCpuItem.strictFpu(part(PcSlot.CPU)), mac);
        Machine machine;
        try {
            machine = new Machine(config, StandardCpus.INSTANCE);
        } catch (RuntimeException | Error e) {
            server.disks().release(hdd);
            throw e;
        }
        runtime = new PcRuntime(this, machine, usableRam(), hdd, server.disks());
        if (!part(PcSlot.FLOPPY).isEmpty())
            runtime.insertFloppy(part(PcSlot.FLOPPY));
        if (!frozen)
            server.scheduler().add(runtime);
        return runtime;
    }

    /**
     * Drops the machine: powered off the hard way, its disks flushed and let go,
     * and out of the schedule. {@code forget} also deletes the state file, which is
     * what a PC that is off does (spec 11.2.8); an unloading PC keeps it.
     */
    private void stopMachine(boolean forget) {
        if (runtime == null)
            return;
        PcRuntime old = runtime;
        runtime = null;
        PcServer server = PcServer.get();
        if (server != null)
            server.scheduler().remove(old);
        try {
            old.machine().powerOff();
        } catch (RuntimeException e) {
            // Off is off; a machine that cannot even do that is dropped all the same
        }
        old.release();
        if (forget) {
            supplyOn = false;
            frozen = false;
            deleteStateFile();
            setChanged();
        }
    }

    /** The emulator threw: this PC alone stops, logged once, with its error light on (spec 11.2.6). */
    void crash(Throwable error) {
        if (!crashed)
            CreateFlippies.LOGGER.error("PC at {} crashed", worldPosition, error);
        crashed = true;
        String message = error.getMessage();
        crashMessage = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        if (crashMessage.length() > 200)
            crashMessage = crashMessage.substring(0, 200);
        stopMachine(true);
        updateLights();
        notifyUpdate();
    }

    /** After the scheduler ran the machine: follow its state, draw and lights (spec 11.2.6 step 4). */
    void afterMachineTick() {
        Machine machine = runtime.machine();
        MachineState state = machine.state();
        if (state == MachineState.OFF_BY_GUEST) {
            exitError = machine.exitCode() != 0;
            stopMachine(true);
            railWatts = 0;
        } else {
            haltReason = state == MachineState.HALTED ? machine.haltReason() : null;
            railWatts = PcPower.railWatts(runtime.draw(this), peerModem() != null);
        }
        updateLights();
    }

    PcPower.Draw draw(double busy, boolean hddActive, boolean floppyMotor) {
        return new PcPower.Draw(!part(PcSlot.MOTHERBOARD).isEmpty(), cpuType(), busy,
                PcRamItem.typeOf(part(PcSlot.RAM_A)), PcRamItem.typeOf(part(PcSlot.RAM_B)),
                !part(PcSlot.HDD).isEmpty(), hddActive, floppyMotor);
    }

    private void updateLights() {
        Machine machine = machine();
        MachineState state = machine == null ? MachineState.OFF : machine.state();
        boolean power = state == MachineState.RUNNING || state == MachineState.HALTED;
        boolean guestLed = false;
        if (machine != null) {
            try {
                guestLed = machine.guestErrorLed();
            } catch (RuntimeException e) {
                guestLed = false;
            }
        }
        boolean error = crashed || state == MachineState.HALTED || exitError || overVoltage || guestLed
                || (level != null && level.getGameTime() < refusalUntil);
        String key = crashed ? "crashed" : frozen && machine != null ? "frozen"
                : state == MachineState.HALTED ? "halted" : power ? "running" : "off";
        String text = crashed ? crashMessage : haltReason != null ? haltReason : overVoltage ? "over-voltage" : "";
        if (power != powerLit || error != errorLit || !key.equals(stateKey) || !text.equals(errorText)) {
            powerLit = power;
            errorLit = error;
            stateKey = key;
            errorText = text;
            sendData();
        }
    }

    // Ticking

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        ticks++;
        if (sinceLoad >= 0)
            sinceLoad++;
        if (circuit != null)
            volts = circuit.volts();

        boolean settling = sinceLoad >= 0 && sinceLoad < RESTORE_GRACE;
        if (restorePending && (PcPower.canStart(volts) || !settling) && ticks >= restoreRetryAt && !importing())
            restore();

        // A frozen PC is outside time: no brown-out, no draw
        if (runtime != null && !settling && !frozen) {
            PcPower.Event event = PcPower.check(volts);
            if (event != PcPower.Event.NONE) {
                // Brown-out or over-voltage: off at once, RAM lost, as a real PC
                stopMachine(true);
                overVoltage = event == PcPower.Event.OVER_VOLTAGE;
                railWatts = 0;
                updateLights();
            }
        }

        if (circuit != null) {
            double ohms = PcPower.loadOhms(volts, PcPower.inputWatts(active(), railWatts));
            if (PcPower.outsideDeadband(circuit.load().getResistance(), ohms))
                circuit.load().setResistance(ohms);
        }

        if (refusalUntil != 0 && level.getGameTime() >= refusalUntil) {
            refusalUntil = 0;
            updateLights();
        }

        boolean wired = socketHasCord();
        if (wired != socketWired) {
            socketWired = wired;
            sendData();
        }

        if (runtime != null && ticks % SAVE_INTERVAL == 0)
            setChanged();
        if (ticks % FIGURES_SYNC == 0)
            syncFigures();
        if (ticks % 20 == 7)
            DataCords.validateLinks(this);
    }

    private boolean socketHasCord() {
        var connections = getElectricBehaviour().getConnections();
        return hasWire(connections.get(new BlockWireEndpoint(worldPosition, PcPower.TERMINAL_A)))
                && hasWire(connections.get(new BlockWireEndpoint(worldPosition, PcPower.TERMINAL_B)));
    }

    private static boolean hasWire(Set<BaseWireEntity> wires) {
        return wires != null && !wires.isEmpty();
    }

    private void syncFigures() {
        Machine machine = machine();
        float v = (float) Math.round(volts);
        float w = (float) (Math.round(PcPower.inputWatts(active(), railWatts) * 10) / 10.0);
        float speed = machine == null ? 0 : (float) (Math.round(safeSpeed(machine) * 100));
        PcCpuType cpu = cpuType();
        String cpuLine = cpu == null ? "" : cpu.displayName() + (PcCpuItem.strictFpu(part(PcSlot.CPU)) ? ", strict FPU" : "");
        String ramLine = installedRam() == 0 ? "" : PcRamRules.report(installedRam(), usableRam());
        if (v != shownVolts || w != shownWatts || speed != shownSpeed || !cpuLine.equals(cpuText)
                || !ramLine.equals(ramText)) {
            shownVolts = v;
            shownWatts = w;
            shownSpeed = speed;
            cpuText = cpuLine;
            ramText = ramLine;
            sendData();
        }
    }

    private static double safeSpeed(Machine machine) {
        try {
            return machine.speed();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // Loading, saving and the chunk-unload freeze (spec 11.2.8)

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide)
            return;
        if (pcId == null) {
            pcId = UUID.randomUUID();
            setChanged();
        }
        PcServer server = PcServer.get();
        if (server != null)
            server.loaded(this);
        sinceLoad = 0;
        restorePending = supplyOn;
    }

    /**
     * The first chance after loading with power settled (or the grace run out):
     * build the machine and load the side file if its generation matches, else cold
     * boot or stay off. A state that would pass the RAM cap waits and tries again,
     * so loading never throws a running PC away.
     */
    private void restore() {
        PcServer server = PcServer.get();
        if (server == null)
            return;
        if (part(PcSlot.MOTHERBOARD).isEmpty() || cpuType() == null || usableRam() == 0) {
            restorePending = false;
            stopMachine(true);
            supplyOn = false;
            return;
        }
        if (!PcRamRules.fitsTotal(server.heldRamBytes(), usableRam(), ServerConfig.maxTotalRamMiB())
                || !frozen && server.scheduler().size() >= ServerConfig.maxRunningPcs()) {
            restoreRetryAt = ticks + RAM_CAP_RETRY;
            return;
        }
        restorePending = false;
        boolean power = PcPower.canStart(volts);
        PcStateStore.Saved saved = null;
        try {
            saved = PcStateStore.decode(server.writer().read(statePath(server)));
        } catch (java.io.IOException e) {
            CreateFlippies.LOGGER.warn("PC at {}: could not read its saved state", worldPosition, e);
        }
        PcStateStore.Restore decision = PcStateStore.decide(stateGen, saved, supplyOn, power);
        // A frozen PC comes back as it was or not at all: never a cold boot behind a freeze
        if (frozen && decision == PcStateStore.Restore.COLD_BOOT)
            decision = PcStateStore.Restore.STAY_OFF;
        if (decision == PcStateStore.Restore.LOAD) {
            try {
                // The floppy is already in the drive (startMachine), which is what lets the
                // controller see a disk swapped while the PC was unloaded (spec 11.1.5)
                startMachine(server).machine().loadState(saved.state());
                updateLights();
                return;
            } catch (StateFormatException | RuntimeException e) {
                CreateFlippies.LOGGER.warn("PC at {}: saved state not loaded ({}); {}", worldPosition, e.getMessage(),
                        power ? "cold boot" : "staying off");
                stopMachine(false);
                decision = frozen ? PcStateStore.Restore.STAY_OFF : PcStateStore.afterFailure(power);
            }
        } else if (supplyOn) {
            CreateFlippies.LOGGER.info("PC at {}: saved state {}; {}", worldPosition,
                    saved == null ? "missing" : "from another generation", power ? "cold boot" : "staying off");
        }
        if (decision == PcStateStore.Restore.COLD_BOOT) {
            try {
                startMachine(server).machine().powerOn();
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                crash(e);
            }
        } else {
            supplyOn = false;
            frozen = false;
            deleteStateFile();
            setChanged();
        }
        updateLights();
    }

    private java.nio.file.Path statePath(PcServer server) {
        return PcStateStore.path(server.worldDir(), pcId);
    }

    private void deleteStateFile() {
        PcServer server = PcServer.get();
        if (server != null && pcId != null)
            server.writer().delete(statePath(server));
    }

    /**
     * Freeze: the chunk was saved just before this (which wrote the side file), so
     * all that is left is to flush the disks and drop the machine. Emulated time
     * stands still until the chunk loads again.
     */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level == null || level.isClientSide)
            return;
        PcServer server = PcServer.get();
        if (server != null)
            server.unloaded(this);
        stopMachine(false);
    }

    /** Broken: every part drops (the disks keep their images), links clear, the state file goes. Server only. */
    void dropContents() {
        stopMachine(true);
        for (PcSlot slot : PcSlot.values()) {
            ItemStack stack = parts[slot.ordinal()];
            if (!stack.isEmpty())
                Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5, stack);
            parts[slot.ordinal()] = ItemStack.EMPTY;
        }
        DataCords.breakAll(this);
        PcServer server = PcServer.get();
        if (server != null)
            server.unloaded(this);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (clientPacket) {
            tag.putInt("PartsMask", partsMask);
            tag.putBoolean("PowerLit", powerLit);
            tag.putBoolean("ErrorLit", errorLit);
            tag.putLong("PowerPress", powerPressTick);
            tag.putLong("ResetPress", resetPressTick);
            tag.putBoolean("Socket", socketWired);
            tag.putString("State", stateKey);
            tag.putString("Error", errorText);
            tag.putFloat("Volts", shownVolts);
            tag.putFloat("Watts", shownWatts);
            tag.putFloat("Speed", shownSpeed);
            tag.putString("Cpu", cpuText);
            tag.putString("Ram", ramText);
            tag.putBoolean("Frozen", frozen);
            return;
        }
        if (pcId != null)
            tag.putUUID("PcId", pcId);
        ListTag list = new ListTag();
        for (PcSlot slot : PcSlot.values()) {
            ItemStack stack = parts[slot.ordinal()];
            if (stack.isEmpty())
                continue;
            //? if forge {
            CompoundTag entry = stack.save(new CompoundTag());
            //?} else {
            /*CompoundTag entry = ItemNbt.save(stack, registries);
            *///?}
            entry.putString("Slot", slot.name());
            list.add(entry);
        }
        tag.put("Parts", list);
        tag.putBoolean("Supply", supplyOn);
        tag.putBoolean("Frozen", frozen);
        tag.putBoolean("Crashed", crashed);
        tag.putString("CrashMessage", crashMessage);
        saveMachineState();
        tag.putLong("StateGen", stateGen);
        ListTag linkList = new ListTag();
        for (int port = 0; port < links.length; port++) {
            if (links[port] == null)
                continue;
            CompoundTag entry = links[port].write();
            entry.putInt("Own", port);
            linkList.add(entry);
        }
        tag.put("Links", linkList);
    }

    /**
     * Saving a running PC writes its machine state to the side file under a new
     * generation: taken now on the machine's thread, written in the background.
     */
    private void saveMachineState() {
        if (runtime == null || level == null || level.isClientSide)
            return;
        PcServer server = PcServer.get();
        if (server == null || pcId == null)
            return;
        byte[] state;
        try {
            state = runtime.machine().saveState();
        } catch (RuntimeException e) {
            CreateFlippies.LOGGER.error("PC at {}: could not save its machine state", worldPosition, e);
            return;
        }
        stateGen++;
        server.writer().write(statePath(server), PcStateStore.encode(stateGen, state));
        runtime.flushDisks();
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        if (clientPacket) {
            partsMask = tag.getInt("PartsMask");
            powerLit = tag.getBoolean("PowerLit");
            errorLit = tag.getBoolean("ErrorLit");
            powerPressTick = tag.getLong("PowerPress");
            resetPressTick = tag.getLong("ResetPress");
            socketWired = tag.getBoolean("Socket");
            stateKey = tag.getString("State");
            errorText = tag.getString("Error");
            shownVolts = tag.getFloat("Volts");
            shownWatts = tag.getFloat("Watts");
            shownSpeed = tag.getFloat("Speed");
            cpuText = tag.getString("Cpu");
            ramText = tag.getString("Ram");
            frozen = tag.getBoolean("Frozen");
            return;
        }
        if (tag.hasUUID("PcId"))
            pcId = tag.getUUID("PcId");
        java.util.Arrays.fill(parts, ItemStack.EMPTY);
        for (Tag t : tag.getList("Parts", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            try {
                PcSlot slot = PcSlot.valueOf(entry.getString("Slot"));
                //? if forge {
                parts[slot.ordinal()] = ItemStack.of(entry);
                //?} else {
                /*parts[slot.ordinal()] = ItemNbt.load(entry, registries);
                *///?}
            } catch (IllegalArgumentException e) {
                // A slot this build does not know; the part is dropped from the case
            }
        }
        recomputeMask();
        supplyOn = tag.getBoolean("Supply");
        frozen = tag.getBoolean("Frozen");
        crashed = tag.getBoolean("Crashed");
        crashMessage = tag.getString("CrashMessage");
        stateGen = tag.getLong("StateGen");
        java.util.Arrays.fill(links, null);
        for (Tag t : tag.getList("Links", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            int port = entry.getInt("Own");
            if (port >= 0 && port < links.length)
                links[port] = DataLink.read(entry);
        }
    }

    // Freezing and snapshots (spec 12.19)

    public boolean frozen() {
        return frozen;
    }

    /** Whether the machine runs: it exists and is not frozen. */
    private boolean active() {
        return runtime != null && !frozen;
    }

    /**
     * Holds the machine still: out of the schedule, its RAM and screens kept, no draw, no input.
     * Emulated time stands still, as in an unloaded chunk. Null when done, else why not. Server only.
     */
    public Component freeze() {
        if (frozen)
            return Component.translatable("gui.create_flippies.pc.freeze.already");
        if (runtime == null)
            return Component.translatable("gui.create_flippies.pc.freeze.off");
        frozen = true;
        PcServer server = PcServer.get();
        if (server != null)
            server.scheduler().remove(runtime);
        railWatts = 0;
        setChanged();
        updateLights();
        sendData();
        return null;
    }

    /** Lets a frozen machine run on, if the supply and the running cap allow it. Null when done, else why not. */
    public Component unfreeze() {
        if (!frozen)
            return Component.translatable("gui.create_flippies.pc.unfreeze.not_frozen");
        if (snapshotBusy || importing() || runtime == null)
            return Component.translatable("gui.create_flippies.pc.unfreeze.busy");
        PcServer server = PcServer.get();
        if (server == null)
            return Component.translatable("gui.create_flippies.pc.power.no_server");
        if (!PcPower.canStart(volts))
            return Component.translatable("gui.create_flippies.pc.power.no_supply",
                    String.format(Locale.ROOT, "%.0f", volts));
        if (server.scheduler().size() >= ServerConfig.maxRunningPcs())
            return Component.translatable("gui.create_flippies.pc.power.running_cap", ServerConfig.maxRunningPcs());
        resume(server);
        return null;
    }

    private void resume(PcServer server) {
        frozen = false;
        server.scheduler().add(runtime);
        setChanged();
        updateLights();
        sendData();
    }

    /** A placed snapshot's disks and state are still being copied in. */
    private boolean importing() {
        PcServer server = PcServer.get();
        return server != null && pcId != null && server.snapshots().importing(pcId);
    }

    /**
     * What a snapshot takes from this PC at one instant: the parts, the machine state (null when
     * off) and the disks it must copy.
     */
    record Capture(CompoundTag caseTag, byte[] state, UUID hdd, UUID floppy, String cpu, long ramBytes,
            boolean wasFrozen) {}

    /**
     * Starts a snapshot. A running PC is frozen (if it was not) so its disks hold still while they
     * are copied; its state is taken now and its disks' writes queued. Null while it cannot be
     * taken (a snapshot already running, or the PC still loading). Pair with {@link #endSnapshot}.
     */
    Capture beginSnapshot() {
        if (snapshotBusy || importing() || restorePending)
            return null;
        boolean wasFrozen = frozen;
        byte[] state = null;
        if (runtime != null) {
            if (!frozen)
                freeze();
            try {
                state = runtime.machine().saveState();
            } catch (RuntimeException e) {
                if (!wasFrozen)
                    resume(PcServer.get());
                throw e;
            }
            runtime.flushDisks();
        }
        snapshotBusy = true;
        PcCpuType cpu = cpuType();
        CompoundTag caseTag = new CompoundTag();
        caseTag.put("Parts", partsTag());
        return new Capture(caseTag, state, PcDiskItems.idOf(part(PcSlot.HDD)), PcDiskItems.idOf(part(PcSlot.FLOPPY)),
                cpu == null ? "" : cpu.displayName(), usableRam(), wasFrozen);
    }

    /** The snapshot is written (or failed): a PC that was running before it runs on. */
    void endSnapshot(boolean wasFrozen) {
        snapshotBusy = false;
        PcServer server = PcServer.get();
        if (!wasFrozen && frozen && runtime != null && server != null && !isRemoved())
            resume(server);
    }

    private ListTag partsTag() {
        ListTag list = new ListTag();
        for (PcSlot slot : PcSlot.values()) {
            ItemStack stack = parts[slot.ordinal()];
            if (stack.isEmpty())
                continue;
            CompoundTag entry = ItemNbt.save(stack, level.registryAccess());
            entry.putString("Slot", slot.name());
            list.add(entry);
        }
        return list;
    }

    /** The parts a snapshot's case tag lists, by slot; unknown slots and unreadable items are left out. */
    static ItemStack[] readParts(CompoundTag caseTag, net.minecraft.core.HolderLookup.Provider registries) {
        ItemStack[] stacks = emptyParts();
        for (Tag t : caseTag.getList("Parts", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            try {
                stacks[PcSlot.valueOf(entry.getString("Slot")).ordinal()] = ItemNbt.load(entry, registries);
            } catch (IllegalArgumentException e) {
                // A slot this build does not know
            }
        }
        return stacks;
    }

    /**
     * Fills a just-placed case from a snapshot: its parts (their disk ids already the new
     * copies'), and for a snapshot of a running PC, frozen, at generation 1, which is the state
     * file the import writes. It loads once the import is done. Server only.
     */
    void applySnapshot(ItemStack[] snapshotParts, boolean hasState) {
        System.arraycopy(snapshotParts, 0, parts, 0, parts.length);
        recomputeMask();
        if (pcId == null)
            pcId = UUID.randomUUID();
        supplyOn = hasState;
        frozen = hasState;
        stateGen = hasState ? 1 : 0;
        crashed = false;
        crashMessage = "";
        restorePending = hasState;
        sinceLoad = 0;
        setChanged();
        notifyUpdate();
    }

    // Data ports (spec 11.2.5)

    @Override
    public PortAddress portAddress() {
        return PortAddress.block(worldPosition);
    }

    @Override
    public Level hostLevel() {
        return level;
    }

    @Override
    public int dataPortCount() {
        return PcCaseGeometry.PORTS;
    }

    @Override
    public PortType portType(int port) {
        return PcCaseGeometry.portType(port);
    }

    @Override
    public PortRole portRole(int port) {
        return PcCaseGeometry.portRole(port);
    }

    @Override
    public Vec3 portOrigin(int port) {
        return Vec3.atLowerCornerOf(worldPosition)
                .add(PcCaseGeometry.toBlock(PcCaseGeometry.portFaceCentre(port), facing()));
    }

    @Override
    public AABB portBox(int port) {
        return PcCaseGeometry.toBlock(PcCaseGeometry.portBox(port), facing()).move(worldPosition);
    }

    @Override
    public Vec3 portNormal(int port) {
        return Vec3.atLowerCornerOf(PcCaseGeometry.back(facing()).getNormal());
    }

    @Override
    public DataLink link(int port) {
        return port >= 0 && port < links.length ? links[port] : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port < 0 || port >= links.length)
            return;
        links[port] = link;
        setChanged();
        sendData();
    }

    /** The case on the other end of a port's cord, if it is loaded and its link pairs with ours. */
    public PcCaseBlockEntity peerCase(int port) {
        return DataCords.peer(this, port, PcCaseBlockEntity.class);
    }

    /** The network modem on the other end of the ethernet cord, if it is loaded and its link pairs with ours. */
    public com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity peerModem() {
        return DataCords.peer(this, PcCaseGeometry.ETHERNET,
                com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity.class);
    }

    /**
     * What is linked to this PS/2 port and loaded (spec 11.2.10): a keyboard, or the
     * keyboard or mouse half of a keyboard and mouse (11.2.15), by the port its cord is on.
     */
    com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind ps2DeviceOn(int port) {
        KeyboardEntity device = DataCords.peer(this, port, KeyboardEntity.class);
        return device == null ? com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind.NONE
                : device.deviceKind(link(port).peerPort());
    }

    /** Queues a key for the machine, if it runs; a PC that is off drops it (spec 11.2.12). */
    public void queueKey(int ps2Port, com.nectariich.create_flippies.content.computer.device.Ps2Key key,
            boolean down) {
        if (runtime != null && !frozen)
            runtime.queueKey(ps2Port, key, down);
    }

    /** Queues mouse movement (dy up) and the buttons held, if the machine runs (spec 11.2.15). */
    public void queueMouse(int ps2Port, int dx, int dy, int buttons) {
        if (runtime != null && !frozen)
            runtime.queueMouse(ps2Port, dx, dy, buttons);
    }

    /**
     * Display {@code index}'s view for a monitor (spec 11.2.10, 11.2.14), or null
     * while the machine is not running. Only the server thread calls it, between
     * scheduler ticks.
     */
    public PcDisplayView display(int index) {
        Machine machine = machine();
        return machine == null ? null : new PcDisplayView(machine.vga(index));
    }

    // Goggles and the case screen (spec 11.2.11)

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.create_flippies.pc_case")));
        tooltip.add(line("gui.create_flippies.pc.goggles.state",
                Component.translatable("gui.create_flippies.pc.state." + stateKey)));
        tooltip.add(line("gui.create_flippies.pc.goggles.supply", String.format(Locale.ROOT, "%.0f", shownVolts),
                String.format(Locale.ROOT, "%.1f", shownWatts)));
        if (powerLit)
            tooltip.add(line("gui.create_flippies.pc.goggles.speed", String.format(Locale.ROOT, "%.0f", shownSpeed)));
        if (!cpuText.isEmpty())
            tooltip.add(line("gui.create_flippies.pc.goggles.cpu", cpuText));
        if (!ramText.isEmpty())
            tooltip.add(line("gui.create_flippies.pc.goggles.ram", ramText));
        if (!errorText.isEmpty())
            tooltip.add(Component.literal("    ").append(Component.literal(errorText).withStyle(ChatFormatting.RED)));
        return true;
    }

    private static MutableComponent line(String key, Object... args) {
        return Component.literal("    ").append(Component.translatable(key, args).withStyle(ChatFormatting.GRAY));
    }

    /** Everything the case screen lists, as the server sees it now. */
    public PcStatus status() {
        List<Component> lines = new ArrayList<>();
        PcCpuType cpu = cpuType();
        lines.add(Component.translatable("gui.create_flippies.pc.screen.cpu", cpu == null ? "-"
                : cpu.displayName() + (PcCpuItem.strictFpu(part(PcSlot.CPU)) ? ", strict FPU" : "")));
        lines.add(Component.translatable("gui.create_flippies.pc.screen.ram",
                installedRam() == 0 ? "-" : PcRamRules.report(installedRam(), usableRam())));
        ItemStack hdd = part(PcSlot.HDD);
        lines.add(Component.translatable("gui.create_flippies.pc.screen.hdd", hdd.getItem() instanceof PcHddItem h
                ? PcRamRules.format(h.type().bytes()) + " (" + PcDiskItems.shortId(PcDiskItems.idOf(hdd)) + ")" : "-"));
        ItemStack floppy = part(PcSlot.FLOPPY);
        String label = floppy.isEmpty() ? "-" : (FloppyDiskItem.label(floppy) == null
                ? PcDiskItems.shortId(PcDiskItems.idOf(floppy)) : FloppyDiskItem.label(floppy))
                + (FloppyDiskItem.writeProtected(floppy) ? ", write protected" : "");
        lines.add(Component.translatable("gui.create_flippies.pc.screen.floppy", label));
        ItemStack board = part(PcSlot.MOTHERBOARD);
        CompoundTag boardTag = board.isEmpty() ? null : StackData.peek(board);
        lines.add(Component.translatable("gui.create_flippies.pc.screen.mac", boardTag != null
                && boardTag.contains(PcMotherboardItem.TAG_MAC)
                ? PcMotherboardItem.formatMac(boardTag.getLong(PcMotherboardItem.TAG_MAC)) : "-"));
        lines.add(Component.translatable("gui.create_flippies.pc.screen.state",
                Component.translatable("gui.create_flippies.pc.state." + stateKey)));
        if (!errorText.isEmpty())
            lines.add(Component.literal(errorText).withStyle(ChatFormatting.RED));
        if (exitError)
            lines.add(Component.translatable("gui.create_flippies.pc.screen.exit_error"));
        Machine machine = machine();
        if (machine != null)
            lines.add(Component.translatable("gui.create_flippies.pc.screen.speed",
                    String.format(Locale.ROOT, "%.0f", safeSpeed(machine) * 100)));
        lines.add(Component.translatable("gui.create_flippies.pc.screen.supply", String.format(Locale.ROOT, "%.0f", volts),
                String.format(Locale.ROOT, "%.1f", PcPower.inputWatts(active(), railWatts))));
        for (int port = 0; port < PcCaseGeometry.PORTS; port++) {
            DataLink link = links[port];
            Component peer = link == null ? Component.literal("-")
                    : Component.literal(link.peer() + " #" + link.peerPort());
            lines.add(Component.translatable("gui.create_flippies.pc.screen.port",
                    Component.translatable("gui.create_flippies.pc.port." + port), peer));
        }
        return new PcStatus(lines);
    }

    /** Whether {@code player} may use this case's screen: within reach (16 blocks). */
    public boolean reachable(ServerPlayer player) {
        return player != null && !isRemoved() && player.level() == level
                && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 16 * 16;
    }

    ServerLevel serverLevel() {
        return (ServerLevel) level;
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(0.25);
    }
}
