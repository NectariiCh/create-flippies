package com.nectariich.create_flippies.content.computer.cpu.m68k;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * One 68000 or 68040 on a big-endian {@link SystemBus} with 1 MiB of RAM at 0, the ROM and VRAM
 * windows of {@link M68kMap} and a {@link Dev} at COM1's window (the 68000 sees all of it at the
 * 24-bit addresses). Every exception vector N points at {@link #handler}(N); the supervisor
 * stack starts at {@link #SSP} and the CPU at {@link #PROG}. The constructor runs the reset
 * vector fetch, so a test starts with the CPU at PROG in supervisor mode with mask 7.
 */
final class M68kRig {

    static final int RAM_SIZE = 1 << 20;
    static final int PROG = 0x1000;
    static final int SSP = 0x8_0000;
    static final int USP = 0x7_0000;
    static final int DATA = 0x2_0000;
    static final int HANDLERS = 0x4_0000;

    /** Where the rig points vector {@code n}. */
    static int handler(int n) {
        return HANDLERS + n * 16;
    }

    /** A device of 64 value-addressed 32-bit registers that read back what was written, with a write hook. */
    static final class Dev implements BusDevice {
        final int[] regs = new int[64];
        final List<int[]> writes = new ArrayList<>();
        Runnable onWrite = () -> { };

        @Override
        public int size() {
            return 256;
        }

        @Override
        public int read(int offset, int width) {
            int v = regs[offset >>> 2] >>> (8 * (offset & 3));
            return width == 4 ? v : v & ((1 << (8 * width)) - 1);
        }

        @Override
        public void write(int offset, int width, int value) {
            writes.add(new int[] {offset, width, value});
            int shift = 8 * (offset & 3);
            int mask = (width == 4 ? -1 : (1 << (8 * width)) - 1) << shift;
            regs[offset >>> 2] = (regs[offset >>> 2] & ~mask) | ((value << shift) & mask);
            onWrite.run();
        }

        @Override
        public void reset() {
        }

        @Override
        public void saveState(DataOutput out) {
        }

        @Override
        public void loadState(DataInput in) {
        }
    }

    final boolean is040;
    final int mask;
    final SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
    final CycleClock clock = new CycleClock();
    final Dev dev = new Dev();
    final byte[] ram = new byte[RAM_SIZE];
    final byte[] rom = new byte[M68kMap.ROM_SIZE];
    final StubFpu[] fpu = new StubFpu[1];
    int deviceResets;
    final M68kCpu cpu;

    static M68kRig m68000() {
        return new M68kRig(false);
    }

    static M68kRig m68040() {
        return new M68kRig(true);
    }

    M68kRig(boolean is040) {
        this.is040 = is040;
        this.mask = is040 ? -1 : M68kMap.ADDRESS_MASK_68000;
        bus.setRam(ram, 0);
        bus.setRom(rom, M68kMap.ROM_BASE & mask);
        bus.setVram(new byte[M68kMap.VRAM_SIZE], M68kMap.VRAM_BASE & mask, new long[2]);
        bus.mapDevice(M68kMap.COM1_BASE & mask, M68kMap.DEVICE_WINDOW, dev);
        CpuModel model = is040 ? CpuModel.CPU_68040 : CpuModel.CPU_68000;
        CpuHost host = new CpuHost(model, is040 ? 25_000_000 : 8_000_000, bus, null, clock, null,
                () -> deviceResets++, 0);
        cpu = new M68kCpu(host, is040 ? M68kOptions.m68040(false) : M68kOptions.m68000(),
                is040 ? (h, strict) -> fpu[0] = new StubFpu(h) : null);
        poke32(0, SSP);
        poke32(4, PROG);
        for (int n = 2; n < 256; n++)
            poke32(4 * n, handler(n));
        cpu.run(clock.now() + 1, Long.MAX_VALUE);
    }

    StubFpu fpu() {
        return fpu[0];
    }

    // ---- memory, big-endian ----

    void poke8(int a, int v) {
        ram[a] = (byte) v;
    }

    void poke16(int a, int v) {
        ram[a] = (byte) (v >> 8);
        ram[a + 1] = (byte) v;
    }

    void poke32(int a, int v) {
        poke16(a, v >>> 16);
        poke16(a + 2, v);
    }

    int peek8(int a) {
        return ram[a] & 0xFF;
    }

    int peek16(int a) {
        return ((ram[a] & 0xFF) << 8) | (ram[a + 1] & 0xFF);
    }

    int peek32(int a) {
        return (peek16(a) << 16) | peek16(a + 2);
    }

    /** Writes 16-bit words from {@code address}. */
    M68kRig at(int address, int... words) {
        for (int k = 0; k < words.length; k++)
            poke16(address + 2 * k, words[k]);
        return this;
    }

    /** Writes the program at PROG and points the CPU there. */
    M68kRig program(int... words) {
        at(PROG, words);
        cpu.setPc(PROG);
        return this;
    }

    // ---- running ----

    /** One instruction (or one interrupt entry). */
    Cpu.Exit step() {
        return cpu.run(clock.now() + 1, Long.MAX_VALUE);
    }

    void steps(int n) {
        for (int k = 0; k < n; k++)
            step();
    }

    Cpu.Exit runFor(long cycles) {
        return cpu.run(clock.now() + cycles, Long.MAX_VALUE);
    }

    /** Cycles one instruction costs. */
    long cost() {
        long c0 = cpu.cycles();
        step();
        return cpu.cycles() - c0;
    }

    int d(int n) {
        return cpu.d(n);
    }

    int a(int n) {
        return cpu.a(n);
    }

    int ccr() {
        return cpu.sr() & 0x1F;
    }

    void ccr(int v) {
        cpu.setSrHost((cpu.sr() & 0xFF00) | v);
    }

    /** Enters user mode with USP = {@link #USP} and mask 0. */
    void userMode() {
        cpu.setSrHost(0x0000);
        cpu.setA(7, USP);
    }

    /** Device window base as the CPU addresses it. */
    static int dev() {
        return M68kMap.COM1_BASE;
    }
}
