package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kFpOpcodes;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.cpu.m68k.FpuOpcodes;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * The 68040's FPU end to end (spec 10.12.4): a program computing sqrt(2) and sin(0.5) printed as
 * double bits over COM1, the strict FPU option trapping FSIN on vector 11 through the boot ROM's
 * handler, the strict flag in the saved-state config check, and the assembler's copy of the FPU
 * opcode tables against the CPU's.
 */
class M68kFpuE2eTest {

    private static final CpuModel M040 = CpuModel.CPU_68040;

    private static final String SQRT_SIN = """
            _start: fmove.l #2,fp0
                    fsqrt.x fp0
                    fmove.d fp0,result
                    move.l  result,d0
                    bsr     puthex
                    move.l  result+4,d0
                    bsr     puthex
                    moveq   #10,d0
                    bsr     putc
                    fmove.d #0.5,fp1
                    fsin.x  fp1
                    fmove.d fp1,result
                    move.l  result,d0
                    bsr     puthex
                    move.l  result+4,d0
                    bsr     puthex
                    moveq   #10,d0
                    bsr     putc
                    fmul.l  #1000,fp0           ; 1000 * sqrt(2), truncated
                    fintrz.x fp0
                    fmove.l fp0,d0
                    bsr     puthex
                    fmove.l fpsr,d0
                    move.l  d0,fpsr_bits
                    bra     exit
                    .bss
            result: ds.d    1
            fpsr_bits: ds.l 1
            """;

    private static String hex(double d) {
        return String.format("%016X", Double.doubleToRawLongBits(d));
    }

    @Test
    void sqrtAndSinPrintedAsDoubleBits() {
        AsmImage program = assemble(M040, SQRT_SIN);
        Machine m = boot(M040, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("3FF6A09E667F3BCD", hex(Math.sqrt(2)));
        // sin goes through StrictMath in double (spec 10.4), so the double result is its bits
        assertEquals(hex(Math.sqrt(2)) + "\n" + hex(StrictMath.sin(0.5)) + "\n" + "00000586", com1(m));
        assertTrue((ram32(m, program, "fpsr_bits") & 0x08) != 0, "accrued inexact");
    }

    @Test
    void strictFpuTrapsFsinOnVector11ThroughTheRomHandler() {
        String program = """
                _start: fmove.l #2,fp0
                        fsin.x  fp0
                        lea     done(pc),a0
                        bsr     puts
                        bra     exit
                done:   dc.b    "emulated",0
                """;
        Machine plain = boot(M040, assemble(M040, program), null, false);
        runUntilOff(plain, 5);
        assertCleanExit(plain);
        assertEquals("emulated", com1(plain));

        Machine strict = boot(M040, assemble(M040, program), null, true);
        runUntilOff(strict, 5);
        assertEquals(M68kBootRom.TRAP_EXIT_CODE, strict.exitCode());
        // format 2: PC = the instruction after the FSIN, address = its effective address (none: 0)
        assertEquals("TRAP vector=0B pc=0000100C sr=2700 addr=00000000\r\n", com1(strict));
    }

    @Test
    void stateSavedWithStrictFpuDoesNotLoadIntoAnEmulatingMachine() throws StateFormatException {
        Machine strict = boot(M040, assemble(M040, SQRT_SIN), null, true);
        runTicks(strict, 1);
        byte[] state = strict.saveState();
        Machine plain = new Machine(config(M040, null, false), StandardCpus.INSTANCE);
        plain.powerOn();
        byte[] before = plain.saveState();
        StateFormatException e = assertThrows(StateFormatException.class, () -> plain.loadState(state));
        assertTrue(e.getMessage().contains("strict FPU"), e.getMessage());
        assertArrayEquals(before, plain.saveState(), "the failed load changed nothing");
        Machine again = new Machine(config(M040, null, true), StandardCpus.INSTANCE);
        again.loadState(state);
        assertArrayEquals(state, again.saveState());
    }

    // ---- the assembler's copy of the FPU tables (spec 10.9, 10.12.4) ----

    private static Map<String, Integer> intConstants(Class<?> c) throws IllegalAccessException {
        Map<String, Integer> m = new TreeMap<>();
        for (Field f : c.getFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class)
                m.put(f.getName(), f.getInt(null));
        return m;
    }

    @Test
    void assemblerOpcodeTablesEqualTheCpus() throws IllegalAccessException {
        Map<String, Integer> cpu = intConstants(FpuOpcodes.class);
        assertTrue(cpu.size() > 60, "opmodes, formats and the signalling bit: " + cpu.size());
        assertEquals(cpu, intConstants(M68kFpOpcodes.class));
        assertArrayEquals(FpuOpcodes.PREDICATES, M68kFpOpcodes.PREDICATES);
        for (int format = 0; format < 8; format++)
            assertEquals(FpuOpcodes.formatBytes(format), M68kFpOpcodes.formatBytes(format), "format " + format);
    }

    private static int[] words(String source) {
        AsmResult r = M68kAssembler.assemble(source, AsmOptions.at(PROG), M68kTarget.M68040);
        assertTrue(r.ok(), source + ": " + r.diagnostics());
        byte[] b = r.image().flatten(PROG, 8);
        return new int[] {(b[0] & 0xFF) << 8 | b[1] & 0xFF, (b[2] & 0xFF) << 8 | b[3] & 0xFF};
    }

    /** Every opmode the CPU knows, assembled from its mnemonic, lands in the command word the CPU decodes. */
    @Test
    void everyMnemonicAssemblesToTheCpusOpmode() throws IllegalAccessException {
        int checked = 0;
        for (Map.Entry<String, Integer> e : intConstants(FpuOpcodes.class).entrySet()) {
            String name = e.getKey();
            if (name.startsWith("FORMAT_") || name.startsWith("PREDICATE"))
                continue;
            int opmode = e.getValue();
            String mnemonic = name.toLowerCase(Locale.ROOT);
            int[] w;
            if (opmode == FpuOpcodes.FSINCOS) {
                w = words(" fsincos.x fp1,fp3:fp2");
                assertEquals(0x30 | 3, w[1] & 0x7F, name);
                assertEquals(2, (w[1] >> 7) & 7, name + " sine register");
            } else if (opmode == FpuOpcodes.FTST) {
                w = words(" ftst.x fp1");
                assertEquals(opmode, w[1] & 0x7F, name);
            } else {
                w = words(" " + mnemonic + ".x fp1,fp2");
                assertEquals(opmode, w[1] & 0x7F, name);
                assertEquals(2, (w[1] >> 7) & 7, name + " destination");
            }
            assertEquals(0xF200, w[0], name);
            assertEquals(1, (w[1] >> 10) & 7, name + " source register");
            assertEquals(0, w[1] >> 13, name + " register to register");
            checked++;
        }
        assertEquals(54, checked, "opmodes");
        for (int code = 0; code < FpuOpcodes.PREDICATES.length; code++) {
            int[] w = words(" fs" + FpuOpcodes.PREDICATES[code] + " d0");
            assertEquals(0xF240, w[0], FpuOpcodes.PREDICATES[code]);
            assertEquals(code, w[1], FpuOpcodes.PREDICATES[code]);
        }
    }
}
