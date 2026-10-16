package com.nectariich.create_flippies.content.plc.mcu.lang;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * newlib-nano's malloc (nano-mallocr.c), the allocator STM32duino links, running on
 * the simulated SRAM.
 *
 * Everything lives where it lives on the chip: each chunk carries its 4-byte size
 * header in SRAM, free chunks are linked through their own bodies in address
 * order, and libc's two statics ({@code __malloc_free_list} and {@code _sbrk}'s
 * {@code heap_end}) are words of SRAM too. So fragmentation, a String growing by
 * reallocation, a double free and a stack that grows down into the heap all behave
 * as they do on the board: there is no Java-side bookkeeping a sketch could get out
 * of step with.
 *
 * Chunk sizes are the request rounded to 4, plus 4 bytes of padding and the 4-byte
 * header, at least 12; payloads are 8-aligned. The heap grows from the end of .bss
 * through {@code _sbrk}, which refuses to move the break past the current stack
 * pointer, or into the {@code _Min_Stack_Size} (1 KB) the linker script reserves
 * below {@code _estack} (STM32duino's syscalls.c checks both), at which point malloc
 * returns NULL.
 *
 * <h2>A corrupted free list</h2>
 * The free list's links are ordinary SRAM words, so one write through a freed
 * pointer can make a chunk point back at itself. newlib then walks the cycle
 * forever. Here the walk is bounded instead: a walk that has not ended after more
 * steps than there are readable addresses must have revisited a node, so it would
 * never end on the board either, and the sketch stops with an ABORT fault naming
 * the heap. This is the one place the emulator deviates on purpose: the board would
 * hang inside malloc with interrupts still running, but an endless Java loop on the
 * server thread is not an option, and a fault tells the player where it happened.
 *
 * Costs are counted as the routine runs: a word read or written in SRAM at 2
 * cycles, plus the fixed work of each routine (call, argument checks, alignment
 * arithmetic) measured from the -Os disassembly at roughly 20-30 cycles.
 */
final class Heap {

    interface Cost {
        void charge(CycleClass cls, long cycles);
    }

    private static final int CHUNK_OFFSET = 4;
    private static final int MALLOC_PADDING = 4;
    private static final int MALLOC_MINCHUNK = 12;
    /**
     * More list steps than this means a cycle: every node is a distinct readable
     * address (SRAM or flash; anything else faults on the read) on an acyclic walk.
     */
    private static final int MAX_WALK = Stm32f103cb.SRAM_SIZE + Stm32f103cb.FLASH_SIZE + 1;
    /** {@code &_estack - &_Min_Stack_Size}: the break may not reach it. */
    private static final long STACK_RESERVE_START = Stm32f103cb.SRAM_BASE + Stm32f103cb.SRAM_SIZE - Stm32f103cb.MIN_STACK;

    private final SimMemory m;
    private final long freeListAddr;
    private final long heapEndAddr;
    /** {@code &_end}: the first byte after .bss, 8-aligned by the linker script. */
    final long end;
    long inUse;
    long peak;
    private int loads;
    private int stores;

    Heap(SimMemory m, long freeListAddr, long heapEndAddr, long end) {
        this.m = m;
        this.freeListAddr = freeListAddr;
        this.heapEndAddr = heapEndAddr;
        this.end = end;
    }

    void reset() {
        inUse = 0;
        peak = 0;
    }

    private long rd(long a) {
        loads++;
        return m.read32(a) & 0xFFFFFFFFL;
    }

    private int rdSigned(long a) {
        loads++;
        return m.read32(a);
    }

    private void wr(long a, long v) {
        stores++;
        m.write32(a, (int) v);
    }

    private void settle(Cost c, int fixed) {
        c.charge(CycleClass.ALU, fixed);
        c.charge(CycleClass.LOAD, 2L * loads);
        c.charge(CycleClass.STORE, 2L * stores);
        c.charge(CycleClass.BRANCH, 6);
        loads = 0;
        stores = 0;
    }

    /** {@code _sbrk}: moves the break, or returns -1 if it would pass the stack pointer. */
    private long sbrk(long incr, long stackPointer) {
        long heapEnd = rd(heapEndAddr);
        if (heapEnd == 0)
            heapEnd = end;
        if (heapEnd + incr > stackPointer)
            return -1; // heap and stack collision
        if (heapEnd + incr >= STACK_RESERVE_START)
            return -1; // keep the minimum stack size the linker script defines
        wr(heapEndAddr, heapEnd + incr);
        return heapEnd;
    }

    private long sbrkAligned(long s, long sp) {
        long p = sbrk(s, sp);
        if (p == -1)
            return -1;
        long aligned = (p + 3) & ~3L;
        if (aligned != p) {
            long q = sbrk(aligned - p, sp);
            if (q == -1)
                return -1;
        }
        return aligned;
    }

    long malloc(long s, long stackPointer, Cost c) {
        long r = mallocImpl(s & 0xFFFFFFFFL, stackPointer);
        settle(c, 24);
        return r;
    }

    private long mallocImpl(long s, long sp) {
        long allocSize = ((s + 3) & ~3L) + MALLOC_PADDING + CHUNK_OFFSET;
        allocSize = Math.max(allocSize, MALLOC_MINCHUNK);
        if (allocSize >= 0x80000000L || allocSize < s)
            return 0;
        long p = rd(freeListAddr);
        long r = p;
        int steps = 0;
        while (r != 0) {
            if (++steps > MAX_WALK)
                throw corrupted(r, "malloc");
            long rem = rdSigned(r) - allocSize;
            if (rem >= 0) {
                if (rem >= MALLOC_MINCHUNK) {
                    // Split: shrink this chunk and hand out its tail.
                    wr(r, rem);
                    r = r + rem;
                    wr(r, allocSize);
                } else if (p == r) {
                    wr(freeListAddr, rd(r + 4));
                } else {
                    wr(p + 4, rd(r + 4));
                }
                break;
            }
            p = r;
            r = rd(r + 4);
        }
        if (r == 0) {
            r = sbrkAligned(allocSize, sp);
            if (r == -1)
                return 0;
            wr(r, allocSize);
        }
        long ptr = r + CHUNK_OFFSET;
        long aligned = (ptr + 7) & ~7L;
        long offset = aligned - ptr;
        if (offset != 0)
            wr(r + offset, -offset);
        inUse += rdSigned(r);
        peak = Math.max(peak, inUse);
        return aligned;
    }

    private HardFault corrupted(long node, String where) {
        loads = 0;
        stores = 0;
        return new HardFault(HardFault.Kind.ABORT, node & 0xFFFFFFFFL, "heap corrupted: " + where
                + "() found a loop in the free list at 0x" + Long.toHexString(node & 0xFFFFFFFFL)
                + " and would never return (a write through a freed pointer?)");
    }

    private long chunkOf(long ptr) {
        long c = ptr - CHUNK_OFFSET;
        int size = rdSigned(c);
        if (size < 0)
            c += size;
        return c;
    }

    void free(long ptr, Cost c) {
        freeImpl(ptr & 0xFFFFFFFFL);
        settle(c, 20);
    }

    private void freeImpl(long ptr) {
        if (ptr == 0)
            return;
        long pf = chunkOf(ptr);
        int pfSize = rdSigned(pf);
        long freeList = rd(freeListAddr);
        long released = pfSize;
        if (freeList == 0) {
            wr(pf + 4, 0);
            wr(freeListAddr, pf);
            inUse -= released;
            return;
        }
        if (pf < freeList) {
            if (pf + pfSize == freeList) {
                wr(pf, pfSize + rdSigned(freeList));
                wr(pf + 4, rd(freeList + 4));
            } else {
                wr(pf + 4, freeList);
            }
            wr(freeListAddr, pf);
            inUse -= released;
            return;
        }
        long q = freeList;
        long p;
        int steps = 0;
        do {
            if (++steps > MAX_WALK)
                throw corrupted(q, "free");
            p = q;
            q = rd(q + 4);
        } while (q != 0 && q <= pf);
        int pSize = rdSigned(p);
        if (p + pSize == pf) {
            pSize += pfSize;
            wr(p, pSize);
            if (p + pSize == q) {
                wr(p, pSize + rdSigned(q));
                wr(p + 4, rd(q + 4));
            }
        } else if (p + pSize > pf) {
            // Double free: newlib sets errno and returns.
            return;
        } else if (pf + pfSize == q) {
            wr(pf, pfSize + rdSigned(q));
            wr(pf + 4, rd(q + 4));
            wr(p + 4, pf);
        } else {
            wr(pf + 4, q);
            wr(p + 4, pf);
        }
        inUse -= released;
    }

    /** malloc_usable_size: the payload bytes a chunk really has. */
    private long usable(long ptr) {
        long c = chunkOf(ptr);
        int sizeOrOffset = rdSigned(ptr - CHUNK_OFFSET);
        int size = rdSigned(c);
        if (sizeOrOffset < 0)
            return size - CHUNK_OFFSET + sizeOrOffset;
        return size - CHUNK_OFFSET;
    }

    long realloc(long ptr, long size, long sp, Cost c) {
        ptr &= 0xFFFFFFFFL;
        size &= 0xFFFFFFFFL;
        if (ptr == 0)
            return malloc(size, sp, c);
        if (size == 0) {
            free(ptr, c);
            return 0;
        }
        long old = usable(ptr) & 0xFFFFFFFFL; // malloc_size_t
        if (size <= old && (old >> 1) < size) {
            settle(c, 16);
            return ptr;
        }
        long mem = mallocImpl(size, sp);
        if (mem != 0) {
            long n = Math.min(size, old);
            m.move(mem, ptr, n);
            c.charge(CycleClass.LOAD, n);
            c.charge(CycleClass.STORE, n);
            freeImpl(ptr);
        }
        settle(c, 40);
        return mem;
    }

    long calloc(long n, long size, long sp, Cost c) {
        long total = (n & 0xFFFFFFFFL) * (size & 0xFFFFFFFFL);
        if (total > 0xFFFFFFFFL)
            return 0;
        long p = malloc(total, sp, c);
        if (p != 0) {
            m.fill(p, 0, total);
            c.charge(CycleClass.STORE, (total + 3) / 4 + 8);
        }
        return p;
    }
}
