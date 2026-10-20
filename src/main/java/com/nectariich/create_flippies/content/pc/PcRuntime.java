package com.nectariich.create_flippies.content.pc;

import java.util.ArrayDeque;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import net.minecraft.world.item.ItemStack;

/**
 * One powered PC on the server (spec 11.2.6): it owns the {@link Machine} and makes
 * every call into it, so a throw from the emulator lands in one place, the
 * scheduler's guard, and crashes this PC alone.
 */
public final class PcRuntime implements PcScheduler.Job {

    /** Key events queued between ticks, at most; more are dropped (a typist cannot outrun this). */
    private static final int MAX_QUEUED_KEYS = 256;

    private final PcCaseBlockEntity owner;
    private final Machine machine;
    private final int ramBytes;
    private final PagedDiskImage hdd;
    private final DiskStore disks;
    private PagedDiskImage floppy;
    private UUID floppyId;
    private final ArrayDeque<int[]> keys = new ArrayDeque<>();
    private final Ps2DeviceKind[] plugged = {Ps2DeviceKind.NONE, Ps2DeviceKind.NONE};
    private double busy;
    private boolean hddActive;
    private boolean floppyMotor;

    PcRuntime(PcCaseBlockEntity owner, Machine machine, int ramBytes, PagedDiskImage hdd, DiskStore disks) {
        this.owner = owner;
        this.machine = machine;
        this.ramBytes = ramBytes;
        this.hdd = hdd;
        this.disks = disks;
    }

    public Machine machine() {
        return machine;
    }

    public int ramBytes() {
        return ramBytes;
    }

    // Host input

    void queueKey(int port, Ps2Key key, boolean down) {
        if (keys.size() < MAX_QUEUED_KEYS)
            keys.add(new int[] {port, key.ordinal(), down ? 1 : 0});
    }

    /**
     * Puts the case's floppy into the drive, opening its image (a disk that was
     * never used gets its id now). Replaces whatever was in.
     */
    void insertFloppy(ItemStack stack) {
        ejectFloppy();
        if (stack.isEmpty())
            return;
        floppyId = PcDiskItems.assignId(stack);
        floppy = disks.acquire(floppyId, PagedDiskImage.FLOPPY_CAPACITY);
        machine.floppy().insert(floppy, FloppyDiskItem.writeProtected(stack));
    }

    void ejectFloppy() {
        if (floppy == null)
            return;
        machine.floppy().eject();
        disks.release(floppy);
        floppy = null;
        floppyId = null;
    }

    void reset() {
        machine.reset();
    }

    void flushDisks() {
        disks.flush(hdd);
        disks.flush(floppy);
    }

    /** Lets go of the disks: their writes are queued and the images closed if nobody else holds them. */
    void release() {
        disks.release(hdd);
        disks.release(floppy);
        floppy = null;
    }

    PcPower.Draw draw(PcCaseBlockEntity owner) {
        return owner.draw(busy, hddActive, floppyMotor);
    }

    // The scheduler's job

    @Override
    public void applyInput() {
        for (int port = 0; port < Machine.PS2_PORTS; port++) {
            Ps2DeviceKind wanted = owner.keyboardOn(port) ? Ps2DeviceKind.KEYBOARD : Ps2DeviceKind.NONE;
            if (wanted != plugged[port]) {
                machine.ps2().setDevice(port, wanted);
                plugged[port] = wanted;
            }
        }
        int[] event;
        while ((event = keys.poll()) != null) {
            int port = event[0];
            if (plugged[port] != Ps2DeviceKind.KEYBOARD)
                continue;
            Ps2Key key = Ps2Key.values()[event[1]];
            if (event[2] != 0)
                machine.keyboard(port).keyDown(key);
            else
                machine.keyboard(port).keyUp(key);
        }
    }

    @Override
    public void run(long budgetNanos) {
        machine.runTick(Machine.TICK_NANOS, budgetNanos);
    }

    @Override
    public void afterTick() {
        busy = machine.busyFraction();
        hddActive = machine.hdd().takeActivity();
        machine.floppy().takeActivity();
        floppyMotor = machine.floppy().motorOn();
        owner.afterMachineTick();
    }

    @Override
    public void crashed(Throwable error) {
        owner.crash(error);
    }
}
