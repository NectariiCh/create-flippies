package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The STM32F103CB's bus as a program sees it: 20 KB of SRAM at 0x2000_0000 and the
 * 128 KB flash at 0x0800_0000, little-endian, with nothing else mapped.
 *
 * Flash reads past the end of the programmed image return 0xFF, as erased flash
 * does. A store to flash is a MemManage fault (on the chip, the flash interface
 * ignores it and the write is a bus error with the MPU off; either way the sketch
 * stops). Any address outside both regions — including NULL, which the real part
 * aliases to flash but which here is treated as the null pointer it almost always
 * is — is a BusFault.
 *
 * {@link #changes} counts stores that changed at least one byte. The VM's idle-loop
 * detector compares it across a loop iteration: a store of the value already there
 * does not count, so a loop that keeps writing the same thing is still idle.
 */
final class SimMemory implements Memory {

    static final VarHandle I16 = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle I32 = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle I64 = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static final int SRAM_BASE = (int) Stm32f103cb.SRAM_BASE;
    static final int SRAM_SIZE = Stm32f103cb.SRAM_SIZE;
    static final int FLASH_BASE = (int) Stm32f103cb.FLASH_BASE;
    static final int FLASH_SIZE = Stm32f103cb.FLASH_SIZE;

    final byte[] sram = new byte[SRAM_SIZE];
    final byte[] flash;
    /** Stores that changed SRAM. */
    long changes;
    /** Data reads that went to flash, for wait-state accounting by the caller. */
    long flashReads;

    SimMemory(byte[] flashImage) {
        this.flash = flashImage;
    }

    // ---- region checks ----

    /** SRAM offset of an access of {@code n} bytes, or -1. */
    static int sramOffset(long address, int n) {
        long off = (address & 0xFFFFFFFFL) - Stm32f103cb.SRAM_BASE;
        return off >= 0 && off <= SRAM_SIZE - n ? (int) off : -1;
    }

    private static long flashOffset(long address, int n) {
        long off = (address & 0xFFFFFFFFL) - Stm32f103cb.FLASH_BASE;
        return off >= 0 && off <= FLASH_SIZE - n ? off : -1;
    }

    static HardFault busFault(long address, int n, boolean write) {
        long a = address & 0xFFFFFFFFL;
        String what = a == 0 ? "null pointer " + (write ? "write" : "read")
                : (write ? "write" : "read") + " of " + n + " byte" + (n == 1 ? "" : "s") + " at unmapped address 0x" + Long.toHexString(a);
        return new HardFault(HardFault.Kind.BUS_FAULT, a, what);
    }

    private int flashByte(long off) {
        return off < flash.length ? flash[(int) off] & 0xFF : 0xFF;
    }

    private long flashRead(long address, int n) {
        long off = flashOffset(address, n);
        if (off < 0)
            throw busFault(address, n, false);
        flashReads++;
        long v = 0;
        for (int i = 0; i < n; i++)
            v |= (long) flashByte(off + i) << (8 * i);
        return v;
    }

    // ---- Memory ----

    @Override
    public int read8(long address) {
        int o = sramOffset(address, 1);
        if (o >= 0)
            return sram[o] & 0xFF;
        return (int) flashRead(address, 1);
    }

    @Override
    public int read16(long address) {
        int o = sramOffset(address, 2);
        if (o >= 0)
            return (short) I16.get(sram, o) & 0xFFFF;
        return (int) flashRead(address, 2);
    }

    @Override
    public int read32(long address) {
        int o = sramOffset(address, 4);
        if (o >= 0)
            return (int) I32.get(sram, o);
        return (int) flashRead(address, 4);
    }

    @Override
    public long read64(long address) {
        int o = sramOffset(address, 8);
        if (o >= 0)
            return (long) I64.get(sram, o);
        return flashRead(address, 8);
    }

    private void storeCheck(long address, int n) {
        if (flashOffset(address, n) >= 0)
            throw new HardFault(HardFault.Kind.MEM_MANAGE, address & 0xFFFFFFFFL,
                    "write to flash at 0x" + Long.toHexString(address & 0xFFFFFFFFL) + " (a const object or a string literal)");
        throw busFault(address, n, true);
    }

    @Override
    public void write8(long address, int value) {
        int o = sramOffset(address, 1);
        if (o < 0) {
            storeCheck(address, 1);
            return;
        }
        byte b = (byte) value;
        if (sram[o] != b) {
            sram[o] = b;
            changes++;
        }
    }

    @Override
    public void write16(long address, int value) {
        int o = sramOffset(address, 2);
        if (o < 0) {
            storeCheck(address, 2);
            return;
        }
        short v = (short) value;
        if ((short) I16.get(sram, o) != v) {
            I16.set(sram, o, v);
            changes++;
        }
    }

    @Override
    public void write32(long address, int value) {
        int o = sramOffset(address, 4);
        if (o < 0) {
            storeCheck(address, 4);
            return;
        }
        if ((int) I32.get(sram, o) != value) {
            I32.set(sram, o, value);
            changes++;
        }
    }

    @Override
    public void write64(long address, long value) {
        int o = sramOffset(address, 8);
        if (o < 0) {
            storeCheck(address, 8);
            return;
        }
        if ((long) I64.get(sram, o) != value) {
            I64.set(sram, o, value);
            changes++;
        }
    }

    @Override
    public String readCString(long address, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int c = read8(address + i);
            if (c == 0)
                break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    /**
     * Copies within the address space, as memmove does, faulting like the byte
     * accesses would. {@code n} is a size_t: anything up to 4 GB is a legal request,
     * and one that runs off the end of a region faults at the first unmapped byte, so
     * no Java buffer is ever sized from it.
     */
    void move(long dst, long src, long n) {
        n &= 0xFFFFFFFFL;
        if (n == 0)
            return;
        int so = n <= SRAM_SIZE ? sramOffset(src, (int) n) : -1, dOff = n <= SRAM_SIZE ? sramOffset(dst, (int) n) : -1;
        if (so >= 0 && dOff >= 0) {
            int len = (int) n;
            boolean differs = false;
            for (int i = 0; i < len; i++) {
                if (sram[dOff + i] != sram[so + i]) {
                    differs = true;
                    break;
                }
            }
            if (differs) {
                System.arraycopy(sram, so, sram, dOff, len);
                changes++;
            }
            return;
        }
        // Byte by byte in memmove's direction. The destination is at most 20 KB of SRAM
        // before it leaves the map (a write to flash faults at once), so this ends quickly.
        long d = dst & 0xFFFFFFFFL, s = src & 0xFFFFFFFFL;
        if (d <= s || d >= s + n) {
            for (long i = 0; i < n; i++)
                write8(dst + i, read8(src + i));
        } else {
            for (long i = n - 1; i >= 0; i--)
                write8(dst + i, read8(src + i));
        }
    }

    /** memset over {@code n} bytes (a size_t), faulting at the first unmapped byte. */
    void fill(long dst, int value, long n) {
        n &= 0xFFFFFFFFL;
        if (n == 0)
            return;
        int o = n <= SRAM_SIZE ? sramOffset(dst, (int) n) : -1;
        if (o >= 0) {
            int len = (int) n;
            byte b = (byte) value;
            for (int i = 0; i < len; i++) {
                if (sram[o + i] != b) {
                    java.util.Arrays.fill(sram, o + i, o + len, b);
                    changes++;
                    break;
                }
            }
            return;
        }
        for (long i = 0; i < n; i++)
            write8(dst + i, value);
    }
}
