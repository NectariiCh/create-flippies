package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kFpuHost;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the 68040 core (spec 10.10: the FPU owner's private stand-in): 64 KiB of
 * big-endian memory, D0..D7 and A0..A7, the instruction stream, the effective address modes the
 * FPU tests use, and a log of the exceptions taken.
 */
final class FakeFpuHost implements M68kFpuHost {

    /** One exception the FPU asked the core to take. */
    record Taken(int vector, int format, int stackedPc, int address) {
    }

    final byte[] mem = new byte[0x10000];
    final int[] regs = new int[16];
    int pc;
    int instructionAddress;
    final List<Taken> exceptions = new ArrayList<>();

    @Override
    public int fetch16() {
        int v = read16(pc);
        pc += 2;
        return v;
    }

    @Override
    public int instructionAddress() {
        return instructionAddress;
    }

    @Override
    public int pc() {
        return pc;
    }

    @Override
    public int register(int n) {
        return regs[n];
    }

    @Override
    public void setRegister(int n, int value) {
        regs[n] = value;
    }

    @Override
    public int effectiveAddress(int mode, int reg, int bytes) {
        int a = 8 + reg;
        switch (mode) {
            case 2:
                return regs[a];
            case 3: {
                int addr = regs[a];
                regs[a] += bytes == 1 && reg == 7 ? 2 : bytes;
                return addr;
            }
            case 4:
                regs[a] -= bytes == 1 && reg == 7 ? 2 : bytes;
                return regs[a];
            case 5:
                return regs[a] + (short) fetch16();
            case 7:
                switch (reg) {
                    case 0:
                        return (short) fetch16();
                    case 1: {
                        int hi = fetch16();
                        return (hi << 16) | fetch16();
                    }
                    case 2: {
                        int base = pc;
                        return base + (short) fetch16();
                    }
                    case 4: {
                        int addr = pc;
                        pc += (bytes + 1) & ~1;
                        return bytes == 1 ? addr + 1 : addr;
                    }
                    default:
                        throw new IllegalStateException("fake host: mode 7 register " + reg);
                }
            default:
                throw new IllegalArgumentException("not a memory mode: " + mode);
        }
    }

    @Override
    public int read8(int address) {
        return mem[address & 0xFFFF] & 0xFF;
    }

    @Override
    public int read16(int address) {
        return (read8(address) << 8) | read8(address + 1);
    }

    @Override
    public int read32(int address) {
        return (read16(address) << 16) | read16(address + 2);
    }

    @Override
    public void write8(int address, int value) {
        mem[address & 0xFFFF] = (byte) value;
    }

    @Override
    public void write16(int address, int value) {
        write8(address, value >>> 8);
        write8(address + 1, value);
    }

    @Override
    public void write32(int address, int value) {
        write16(address, value >>> 16);
        write16(address + 2, value);
    }

    @Override
    public void exception(int vector, int format, int stackedPc, int address) {
        exceptions.add(new Taken(vector, format, stackedPc, address));
    }

    /** Places an FP instruction at {@code at} (opword plus extension words) and points the PC after the opword. */
    void place(int at, int... words) {
        for (int i = 0; i < words.length; i++)
            write16(at + 2 * i, words[i]);
        instructionAddress = at;
        pc = at + 2;
    }

    Taken lastException() {
        return exceptions.isEmpty() ? null : exceptions.get(exceptions.size() - 1);
    }
}
