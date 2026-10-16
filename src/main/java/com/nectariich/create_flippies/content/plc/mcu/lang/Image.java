package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoLibrary;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * A linked program: the bytecode, the function and call-site tables, and the
 * flash image with .rodata and the .data load image at the addresses the sketch's
 * pointers hold. Immutable; every CPU made from it gets its own SRAM.
 *
 * <h2>Layout, as STM32duino's linker script lays it out</h2>
 * Flash: the core (vector table, HAL, startup, newlib bits it always pulls in),
 * then the code the sketch pulls in from the core (each builtin's own size, as
 * the library reports it), the libgcc/newlib support its operations need, the
 * sketch's .text, .rodata, and finally the .data initialisers. SRAM: the core's
 * .data/.bss, the sketch's .data then .bss, then the heap from {@code _end}
 * (8-aligned) up; the stack comes down from the top.
 *
 * Like {@code --gc-sections}, the linker leaves out globals nothing refers to and
 * functions nothing calls, and a {@code const} scalar whose address is never taken
 * occupies nothing, since every use was folded to its value.
 *
 * <h2>Support code sizes</h2>
 * Measured from arm-none-eabi-gcc 12 / newlib-nano -Os builds for a Cortex-M3:
 * single-precision soft-float (add/sub/mul/div/compare/conversions) ~1.5 KB; double
 * ~3 KB; 64-bit division (__aeabi_ldivmod, __udivmoddi4) ~0.6 KB; nano malloc+free
 * ~0.4 KB; String's core members ~0.3 KB plus ~80 bytes per member used; the core's
 * itoa family ~0.2 KB; dtostrf with integer-only nano printf ~1.9 KB; strtod ~2.4 KB.
 */
final class Image implements CompiledProgram {

    final String source;
    int flashBytes;
    int staticRamBytes;
    int[] code;
    int[] lines;
    int[] fnEntry;
    int[] fnFrame;
    int[] fnSaved;
    int[][] fnParamOff;
    int[][] fnParamSize;
    boolean[] fnReturnsValue;
    String[] fnName;
    long[] fnAddr;
    CodeGen.CallDesc[] descs;
    Sym.Builtin[] builtins;
    long[] builtinAddr;
    byte[] flashImage;
    int dataLoad;
    int dataStart;
    int dataSize;
    /** The .noinit range (SRAM offsets): what {@link Vm#reset()} leaves as it found it. */
    int noinitStart;
    int noinitSize;
    int heapStart;
    int freeListOff;
    int heapEndOff;
    int dummyCharOff;
    long emptyStrAddr;
    int loopCount;
    int startFn;
    int textBytes;
    int rodataBytes;
    int supportBytes;
    private final Map<Long, Integer> fnByAddr = new HashMap<>();
    private final Map<Long, Integer> builtinByAddr = new HashMap<>();

    private Image(String source) {
        this.source = source;
    }

    @Override
    public int flashBytes() {
        return flashBytes;
    }

    @Override
    public int staticRamBytes() {
        return staticRamBytes;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public CpuCore createCpu(McuEnvironment environment, CycleLedger ledger) {
        return new Vm(this, environment, ledger);
    }

    /** The function a pointer value designates (Thumb bit set), or -1. */
    int functionAt(long pointer) {
        Integer i = fnByAddr.get(pointer & 0xFFFFFFFFL);
        return i == null ? -1 : i;
    }

    int builtinAt(long pointer) {
        Integer i = builtinByAddr.get(pointer & 0xFFFFFFFFL);
        return i == null ? -1 : i;
    }

    // ================= the linker =================

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }

    private static long align(long v, int a) {
        return (v + a - 1) / a * a;
    }

    private static int clampInt(long v) {
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    static Image link(String source, Sema sema, CodeGen cg, ArduinoLibrary lib, Sym.Global emptyStr, Diagnostics d) {
        Image img = new Image(source);
        // ---- which globals survive --gc-sections ----
        Set<Object> referenced = new HashSet<>();
        for (CodeGen.CodeReloc r : cg.relocs)
            referenced.add(r.target());
        List<Sym.Global> all = new ArrayList<>(sema.globals);
        all.addAll(cg.extraGlobals);
        boolean grew = true;
        Set<Sym.Global> kept = new HashSet<>();
        while (grew) {
            grew = false;
            for (Sym.Global g : all) {
                if (kept.contains(g))
                    continue;
                boolean keep;
                Sym.Var v = g.owner;
                if (v == null) {
                    keep = true;
                } else if (v.constValue != null && !v.addressTaken && !g.dynamic) {
                    keep = false;
                } else {
                    keep = g.dynamic || v.used || v.addressTaken || referenced.contains(v) || referenced.contains(g);
                }
                if (keep) {
                    kept.add(g);
                    for (Sym.Reloc r : g.relocs)
                        if (referenced.add(r.target()))
                            grew = true;
                }
            }
        }

        // ---- RAM ----
        // Sums are in long: one object can be up to 2 GB, and a few of them would wrap an
        // int back under the region size and slip past the check below.
        int coreRam = Math.max(0, lib.coreRamBytes());
        long ram = coreRam;
        boolean runtimeInCore = coreRam >= 16;
        int runtimeVars;
        if (runtimeInCore) {
            runtimeVars = 0;
        } else {
            ram = align(ram, 4);
            runtimeVars = (int) ram;
            ram += 16;
        }
        img.freeListOff = runtimeVars;
        img.heapEndOff = runtimeVars + 4;
        img.dummyCharOff = runtimeVars + 8;
        ram = align(ram, 8);
        img.dataStart = (int) ram;
        for (Sym.Global g : all) {
            if (kept.contains(g) && g.section == Sym.Global.Section.DATA) {
                ram = align(ram, g.align);
                g.address = Stm32f103cb.SRAM_BASE + ram;
                ram += g.size;
            }
        }
        long dataSize = ram - img.dataStart;
        ram = align(ram, 4);
        for (Sym.Global g : all) {
            if (kept.contains(g) && g.section == Sym.Global.Section.BSS) {
                ram = align(ram, g.align);
                g.address = Stm32f103cb.SRAM_BASE + ram;
                ram += g.size;
            }
        }
        // .noinit after .bss: outside the range startup zeroes, so it survives a warm reset.
        ram = align(ram, 4);
        long noinitStart = ram;
        for (Sym.Global g : all) {
            if (kept.contains(g) && g.section == Sym.Global.Section.NOINIT) {
                ram = align(ram, g.align);
                g.address = Stm32f103cb.SRAM_BASE + ram;
                ram += g.size;
            }
        }
        img.noinitStart = clampInt(noinitStart);
        img.noinitSize = clampInt(ram - noinitStart);
        long staticEnd = align(ram, 8);
        img.staticRamBytes = clampInt(ram);

        // ---- flash ----
        long flash = Math.max(0, lib.coreFlashBytes());
        // Builtins the sketch references: each gets its code's size and an address inside it.
        img.builtins = sema.builtins.toArray(new Sym.Builtin[0]);
        img.builtinAddr = new long[img.builtins.length];
        Set<String> counted = new HashSet<>();
        int builtinCode = 0;
        for (int i = 0; i < img.builtins.length; i++) {
            Sym.Builtin b = img.builtins[i];
            b.index = i;
            if (!b.used || b.op != null)
                continue;
            int sz = counted.add(b.name) ? Math.max(0, lib.flashBytesFor(b.name)) : 0;
            img.builtinAddr[i] = Stm32f103cb.FLASH_BASE + flash + builtinCode + 1;
            builtinCode += Math.max(4, align(sz, 2));
        }
        flash += builtinCode;
        int support = supportBytes(cg, sema);
        img.supportBytes = support;
        flash += support;
        flash = align(flash, 4);
        long textStart = flash;
        int nf = cg.funcs.size();
        img.fnAddr = new long[nf];
        long text = 0;
        for (int i = 0; i < nf; i++) {
            CodeGen.FuncInfo fi = cg.funcs.get(i);
            text = align(text, 2);
            img.fnAddr[i] = Stm32f103cb.FLASH_BASE + textStart + text + 1;
            boolean live = fi.sym == null || fi.sym.used;
            if (live)
                text += Math.max(2, fi.thumbBytes);
            else
                text += 2;
        }
        img.textBytes = clampInt(text);
        flash = align(textStart + text, 4);
        long rodataStart = flash;
        for (Sym.Global g : all) {
            if (kept.contains(g) && g.section == Sym.Global.Section.RODATA) {
                flash = align(flash, g.align);
                g.address = Stm32f103cb.FLASH_BASE + flash;
                flash += g.size;
            }
        }
        img.rodataBytes = clampInt(flash - rodataStart);
        flash = align(flash, 4);
        long dataLoad = flash;
        flash += dataSize;
        img.flashBytes = clampInt(flash);
        img.emptyStrAddr = emptyStr.address;

        // ---- the two regions ----
        boolean overflow = false;
        if (flash > Stm32f103cb.FLASH_SIZE) {
            d.linkError("sketch.elf section `.text' will not fit in region `FLASH'");
            d.linkError("region `FLASH' overflowed by " + (flash - Stm32f103cb.FLASH_SIZE) + " bytes");
            overflow = true;
        }
        long ramNeed = staticEnd + Stm32f103cb.MIN_HEAP + Stm32f103cb.MIN_STACK;
        if (ramNeed > Stm32f103cb.SRAM_SIZE) {
            d.linkError("sketch.elf section `._user_heap_stack' will not fit in region `RAM'");
            d.linkError("region `RAM' overflowed by " + (ramNeed - Stm32f103cb.SRAM_SIZE) + " bytes");
            overflow = true;
        }
        if (overflow)
            return img; // nothing below is used: the image is never run
        img.dataSize = (int) dataSize;
        img.heapStart = (int) staticEnd;
        img.dataLoad = (int) dataLoad;

        // ---- resolve addresses ----
        Map<Object, Long> fnAddrBySym = new HashMap<>();
        for (int i = 0; i < nf; i++) {
            CodeGen.FuncInfo fi = cg.funcs.get(i);
            if (fi.sym != null)
                fnAddrBySym.put(fi.sym, img.fnAddr[i]);
            img.fnByAddr.put(img.fnAddr[i], i);
        }
        for (int i = 0; i < img.builtins.length; i++)
            if (img.builtinAddr[i] != 0)
                img.builtinByAddr.put(img.builtinAddr[i], i);

        img.flashImage = new byte[(int) Math.min(Math.max(flash, 16), Stm32f103cb.FLASH_SIZE)];
        for (Sym.Global g : all) {
            if (!kept.contains(g) || g.data == null)
                continue;
            for (Sym.Reloc r : g.relocs) {
                long a = resolve(r.target(), fnAddrBySym, img) + r.addend();
                for (int i = 0; i < 4; i++)
                    g.data[r.offset() + i] = (byte) (a >>> (8 * i));
            }
            int at;
            if (g.section == Sym.Global.Section.RODATA)
                at = (int) (g.address - Stm32f103cb.FLASH_BASE);
            else if (g.section == Sym.Global.Section.DATA)
                at = img.dataLoad + (int) (g.address - Stm32f103cb.SRAM_BASE) - img.dataStart;
            else
                continue;
            if (at >= 0 && at + g.data.length <= img.flashImage.length)
                System.arraycopy(g.data, 0, img.flashImage, at, g.data.length);
        }
        img.code = cg.code;
        img.lines = cg.lines;
        for (CodeGen.CodeReloc r : cg.relocs)
            img.code[r.pc()] = (int) (resolve(r.target(), fnAddrBySym, img) + r.addend());

        // ---- tables ----
        img.fnEntry = new int[nf];
        img.fnFrame = new int[nf];
        img.fnSaved = new int[nf];
        img.fnParamOff = new int[nf][];
        img.fnParamSize = new int[nf][];
        img.fnReturnsValue = new boolean[nf];
        img.fnName = new String[nf];
        for (int i = 0; i < nf; i++) {
            CodeGen.FuncInfo fi = cg.funcs.get(i);
            img.fnEntry[i] = fi.entry;
            img.fnFrame[i] = fi.frameSize;
            img.fnSaved[i] = fi.savedRegs;
            img.fnParamOff[i] = fi.paramOff;
            img.fnParamSize[i] = fi.paramSize;
            img.fnReturnsValue[i] = fi.returnsValue;
            img.fnName[i] = fi.sym != null ? fi.sym.name + "()" : fi.name;
        }
        img.descs = cg.descs.toArray(new CodeGen.CallDesc[0]);
        img.loopCount = cg.loopCount;
        img.startFn = cg.startIndex;
        return img;
    }

    private static long resolve(Object target, Map<Object, Long> fns, Image img) {
        if (target instanceof Sym.Global g)
            return g.address;
        if (target instanceof Sym.Var v)
            return v.global == null ? 0 : v.global.address;
        if (target instanceof Sym.Func f) {
            Long a = fns.get(f);
            return a == null ? 0 : a;
        }
        if (target instanceof Sym.Builtin b)
            return b.index >= 0 ? img.builtinAddr[b.index] : 0;
        return 0;
    }

    /** Bytes of libgcc, newlib and WString the sketch's operations pull in. */
    private static int supportBytes(CodeGen cg, Sema sema) {
        int n = 0;
        if (cg.usesFloat)
            n += 1500;
        if (cg.usesDouble)
            n += 3000;
        if (cg.usesLongDiv)
            n += 600;
        boolean string = false, heap = false, search = false, numbers = false;
        int members = 0;
        for (Intrinsics.Op op : Intrinsics.Op.values()) {
            if (!cg.intrinsicUsed[op.ordinal()])
                continue;
            switch (op) {
                case MALLOC, CALLOC, REALLOC, FREE, NEW, DELETE, MEMSET0 -> heap = true;
                // CLZ/RBIT are inline; popcount pulls in libgcc's __popcountsi2 / __popcountdi2.
                case BUILTIN_CLZ, BUILTIN_CLZL, BUILTIN_CLZLL, BUILTIN_CTZ, BUILTIN_CTZL, BUILTIN_CTZLL -> { }
                case BUILTIN_POPCOUNT, BUILTIN_POPCOUNTL -> n += 40;
                case BUILTIN_POPCOUNTLL -> n += 64;
                default -> {
                    string = true;
                    members++;
                }
            }
            switch (op) {
                case STR_INDEX_OF_STR, STR_INDEX_OF_STR_FROM, STR_LAST_INDEX_OF_STR, STR_LAST_INDEX_OF_STR_FROM,
                        STR_REPLACE_STR, STR_INDEX_OF_CHAR, STR_INDEX_OF_CHAR_FROM, STR_LAST_INDEX_OF_CHAR,
                        STR_LAST_INDEX_OF_CHAR_FROM -> search = true;
                case STR_CTOR_UCHAR, STR_CTOR_INT, STR_CTOR_UINT, STR_CTOR_LONG, STR_CTOR_ULONG, STR_CONCAT_UCHAR,
                        STR_CONCAT_INT, STR_CONCAT_UINT, STR_CONCAT_LONG, STR_CONCAT_ULONG, STR_SUM_UCHAR, STR_SUM_INT,
                        STR_SUM_UINT, STR_SUM_LONG, STR_SUM_ULONG -> numbers = true;
                case CALLOC -> n += 60;
                case REALLOC -> n += 140;
                default -> { }
            }
        }
        if (heap || string)
            n += 400;
        if (string)
            n += 300 + 80 * members;
        if (search)
            n += 160;
        if (numbers)
            n += 200;
        if (cg.usesStringFloat)
            n += 1900;
        if (cg.intrinsicUsed[Intrinsics.Op.STR_TO_INT.ordinal()])
            n += 220;
        if (cg.intrinsicUsed[Intrinsics.Op.STR_TO_FLOAT.ordinal()] || cg.intrinsicUsed[Intrinsics.Op.STR_TO_DOUBLE.ordinal()])
            n += 2400;
        if (!sema.dynamicInits.isEmpty())
            n += 40;
        return n;
    }
}
