package com.nectariich.create_flippies.content.computer.cpu.rv32;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * One RV32I CPU on a {@link TestBus} with 64 KiB of RAM, a {@link TestDevice} at COM1's address
 * and a 10 MHz clock, starting at RAM_BASE. Programs are hand-encoded words written into RAM.
 */
final class Rv32Rig {

    static final int RAM = Rv32Map.RAM_BASE;
    static final int RAM_SIZE = 64 * 1024;
    static final int DEV = Rv32Map.COM1_BASE;
    static final int HZ = 10_000_000;
    /** Where {@link #setup} runs its throwaway instructions: the top 1 KiB of RAM. */
    static final int SCRATCH = RAM + RAM_SIZE - 0x400;
    /** A default trap handler location, far from programs. */
    static final int HANDLER = RAM + 0x8000;

    private static final VarHandle LE32 = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    final TestDevice device = new TestDevice();
    final TestBus bus;
    final CycleClock clock = new CycleClock();
    final Rv32Cpu cpu;

    Rv32Rig() {
        this(true, new byte[0]);
    }

    Rv32Rig(boolean mExtension) {
        this(mExtension, new byte[0]);
    }

    Rv32Rig(boolean mExtension, byte[] rom) {
        bus = TestBus.rv32(RAM_SIZE, rom, device);
        cpu = new Rv32Cpu(CpuHost.of(CpuModel.RV32I, HZ, bus, clock, RAM), mExtension);
    }

    /** Writes {@code words} into RAM from {@code address}. */
    Rv32Rig at(int address, int... words) {
        for (int k = 0; k < words.length; k++)
            poke32(address + 4 * k, words[k]);
        return this;
    }

    /** Writes the program at RAM_BASE, where the CPU starts. */
    Rv32Rig program(int... words) {
        return at(RAM, words);
    }

    static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts)
            n += p.length;
        int[] out = new int[n];
        int k = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, k, p.length);
            k += p.length;
        }
        return out;
    }

    void poke32(int address, int value) {
        LE32.set(bus.ramArray(), address - RAM, value);
    }

    int peek32(int address) {
        return (int) LE32.get(bus.ramArray(), address - RAM);
    }

    /** Executes exactly one instruction (or takes one pending interrupt) with run(now + 1). */
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

    /**
     * Runs {@code words} from the scratch area, one step each, then puts pc back. For setting
     * CSRs before a test; the cycles and minstret it spends are real.
     */
    void setup(int... words) {
        int pc = cpu.pc();
        at(SCRATCH, words);
        cpu.setPc(SCRATCH);
        steps(words.length);
        cpu.setPc(pc);
    }

    /** mtvec = {@code address} through a real CSR write. */
    void mtvec(int address) {
        setup(concat(Rv32Enc.li(Rv32Enc.T6, address), new int[] { Rv32Enc.csrw(Rv32Csr.MTVEC, Rv32Enc.T6) }));
    }

    int x(int r) {
        return cpu.reg(r);
    }

    int csr(int address) {
        return cpu.csr(address);
    }
}
