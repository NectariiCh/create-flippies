package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.device.Clint;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.InterruptController;
import com.nectariich.create_flippies.content.computer.device.IntervalTimer;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Rtc;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The hardware of one machine as a platform built it. */
public final class Board {

    private final SystemBus memory;
    private final SystemBus io;
    private final byte[] ram;
    private final SysCon sysCon;
    private final InterruptController pic;
    private final IntervalTimer timer;
    private final Rtc rtc;
    private final Uart16550[] com;
    private final Ps2Controller ps2;
    private final VgaDisplay[] vga;
    private final HddController hdd;
    private final FloppyController floppy;
    private final Nic nic;
    private final Clint clint;
    private final List<BusDevice> devices;

    /**
     * {@code io} and {@code clint} may be null; everything else is required: three COM ports and
     * three displays (spec 11.1.2, 11.1.3).
     */
    public Board(SystemBus memory, SystemBus io, byte[] ram, SysCon sysCon, InterruptController pic,
                 IntervalTimer timer, Rtc rtc, Uart16550[] com, Ps2Controller ps2, VgaDisplay[] vga,
                 HddController hdd, FloppyController floppy, Nic nic, Clint clint) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.io = io;
        this.ram = Objects.requireNonNull(ram, "ram");
        this.sysCon = Objects.requireNonNull(sysCon, "sysCon");
        this.pic = Objects.requireNonNull(pic, "pic");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.rtc = Objects.requireNonNull(rtc, "rtc");
        if (com.length != 3 || vga.length != 3)
            throw new IllegalArgumentException("a board has three COM ports and three displays");
        this.com = com.clone();
        this.ps2 = Objects.requireNonNull(ps2, "ps2");
        this.vga = vga.clone();
        this.hdd = Objects.requireNonNull(hdd, "hdd");
        this.floppy = Objects.requireNonNull(floppy, "floppy");
        this.nic = Objects.requireNonNull(nic, "nic");
        this.clint = clint;
        List<BusDevice> all = new ArrayList<>(List.of(sysCon, timer, rtc));
        for (Uart16550 u : this.com)
            all.add(Objects.requireNonNull(u, "com"));
        all.add(ps2);
        for (VgaDisplay v : this.vga)
            all.add(Objects.requireNonNull(v, "vga"));
        all.add(hdd);
        all.add(floppy);
        all.add(nic);
        if (clint != null)
            all.add(clint);
        all.add(pic);
        this.devices = List.copyOf(all);
    }

    public SystemBus memory() {
        return memory;
    }

    /** Null on platforms without port I/O. */
    public SystemBus io() {
        return io;
    }

    public byte[] ram() {
        return ram;
    }

    public SysCon sysCon() {
        return sysCon;
    }

    public InterruptController pic() {
        return pic;
    }

    public IntervalTimer timer() {
        return timer;
    }

    public Rtc rtc() {
        return rtc;
    }

    /** 0 = COM1, 1 = COM2, 2 = COM3. */
    public Uart16550 com(int index) {
        return com[index];
    }

    public Ps2Controller ps2() {
        return ps2;
    }

    /** Display 0. */
    public VgaDisplay vga() {
        return vga[0];
    }

    /** Display 0..2. */
    public VgaDisplay vga(int index) {
        return vga[index];
    }

    public HddController hdd() {
        return hdd;
    }

    public FloppyController floppy() {
        return floppy;
    }

    public Nic nic() {
        return nic;
    }

    /** Null on platforms without one. */
    public Clint clint() {
        return clint;
    }

    /** Every device, for reset and catch-up; the interrupt controller last. */
    public List<BusDevice> devices() {
        return devices;
    }
}
