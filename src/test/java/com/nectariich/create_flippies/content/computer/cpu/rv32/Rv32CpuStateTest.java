package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.HANDLER;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Saved state of the RV32I CPU (spec 1.5): a CPU loaded into a fresh rig at the same cycle, with
 * the same RAM, runs on in lockstep with the original, through an interrupt, a WFI sleep and a
 * halt. A CPU without M and an unknown version are rejected.
 */
class Rv32CpuStateTest {

    /** Main loop with M arithmetic, stores and a CSR write; a timer handler that counts and masks MTIE. */
    private static Rv32Rig busyRig() {
        Rv32Rig rig = new Rv32Rig();
        rig.program(Rv32Rig.concat(
                li(T6, HANDLER),
                new int[] { csrw(Rv32Csr.MTVEC, T6), addi(T0, ZERO, 0x80), csrw(Rv32Csr.MIE, T0),
                        csrrsi(ZERO, Rv32Csr.MSTATUS, 8), addi(A0, ZERO, 7) },
                li(S0, RAM + 0x2000),
                new int[] {
                        addi(A1, A1, 1),                 // loop
                        mul(A2, A1, A0),
                        divu(A3, A2, A0),
                        sw(A2, S0, 0),
                        csrw(Rv32Csr.MSCRATCH, A1),
                        jal(ZERO, -20),
                }));
        rig.at(HANDLER, addi(S1, S1, 1), addi(T1, ZERO, 0x80), csrrc(ZERO, Rv32Csr.MIE, T1), MRET);
        return rig;
    }

    private static byte[] save(Rv32Cpu cpu) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        cpu.saveState(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void load(Rv32Cpu cpu, byte[] state) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        cpu.loadState(in);
        assertEquals(0, in.available());
    }

    /** A fresh rig with a's RAM and clock and a's CPU state loaded. */
    private static Rv32Rig restore(Rv32Rig a) throws IOException {
        Rv32Rig b = new Rv32Rig(true);
        System.arraycopy(a.bus.ramArray(), 0, b.bus.ramArray(), 0, a.bus.ramArray().length);
        b.clock.advanceTo(a.clock.now());
        load(b.cpu, save(a.cpu));
        return b;
    }

    private static void assertSame(Rv32Rig a, Rv32Rig b) throws IOException {
        assertArrayEquals(save(a.cpu), save(b.cpu));
        for (int r = 0; r < 32; r++)
            assertEquals(a.x(r), b.x(r), "x" + r);
        assertEquals(a.cpu.pc(), b.cpu.pc());
        assertEquals(a.cpu.cycles(), b.cpu.cycles());
        assertEquals(a.cpu.instructionsRetired(), b.cpu.instructionsRetired());
        for (int csr : new int[] { Rv32Csr.MSTATUS, Rv32Csr.MIE, Rv32Csr.MIP, Rv32Csr.MEPC, Rv32Csr.MCAUSE,
                Rv32Csr.MSCRATCH, Rv32Csr.MCYCLE, Rv32Csr.MINSTRET, Rv32Csr.TIME })
            assertEquals(a.csr(csr), b.csr(csr), "CSR " + Integer.toHexString(csr));
        assertArrayEquals(a.bus.ramArray(), b.bus.ramArray());
        assertEquals(a.clock.now(), b.clock.now());
    }

    @Test
    void aRestoredCpuRunsInLockstepThroughAnInterrupt() throws IOException {
        Rv32Rig a = busyRig();
        a.runFor(5_003);
        Rv32Rig b = restore(a);
        assertSame(a, b);
        for (Rv32Rig r : new Rv32Rig[] { a, b }) {
            r.runFor(3_001);
            r.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
            r.runFor(4_000);
        }
        assertSame(a, b);
        assertEquals(1, a.x(S1), "the handler ran once");
        assertTrue(a.x(A1) > 100, "the loop ran");
    }

    @Test
    void pendingPinsWaitingAndCountersRoundTrip() throws IOException {
        Rv32Rig a = new Rv32Rig();
        a.program(Rv32Rig.concat(li(T6, HANDLER), new int[] { csrw(Rv32Csr.MTVEC, T6), addi(T0, ZERO, 0x800),
                csrw(Rv32Csr.MIE, T0), csrw(Rv32Csr.MCYCLE, T0), WFI, addi(A0, ZERO, 5), jal(ZERO, 0) }));
        a.at(HANDLER, addi(S1, S1, 1), MRET);
        a.cpu.setIrq(Rv32Map.IRQ_PIN_MSIP, true);        // pending but not enabled in mie
        assertEquals(Cpu.Exit.WAITING, a.runFor(1_000));
        Rv32Rig b = restore(a);
        assertSame(a, b);
        for (Rv32Rig r : new Rv32Rig[] { a, b }) {
            assertEquals(Cpu.Exit.WAITING, r.runFor(1_000), "still asleep after the load");
            r.cpu.setIrq(Rv32Map.IRQ_PIN_MEIP, true);    // wakes WFI; mstatus.MIE is off, so no trap
            r.runFor(20);
        }
        assertSame(a, b);
        assertEquals(5, a.x(A0));
        assertEquals(0, a.x(S1));
    }

    @Test
    void aHaltedCpuStaysHaltedWithItsReason() throws IOException {
        Rv32Rig a = new Rv32Rig();
        a.program(Rv32Rig.concat(li(T6, 0x0000_0100), new int[] { csrw(Rv32Csr.MTVEC, T6), ECALL }));
        assertEquals(Cpu.Exit.HALTED, a.runFor(100));
        Rv32Rig b = restore(a);
        assertTrue(b.cpu.halted());
        assertEquals(a.cpu.haltReason(), b.cpu.haltReason());
        assertEquals(Cpu.Exit.HALTED, b.runFor(100));
    }

    @Test
    void aCpuWithoutMRejectsAStateWithMAndUnknownVersionsAreRejected() throws IOException {
        byte[] state = save(busyRig().cpu);
        Rv32Rig noM = new Rv32Rig(false);
        StateFormatException e = assertThrows(StateFormatException.class, () -> load(noM.cpu, state));
        assertTrue(e.getMessage().contains("M extension"), e.getMessage());
        byte[] future = state.clone();
        future[3] = 2;
        e = assertThrows(StateFormatException.class, () -> load(new Rv32Rig().cpu, future));
        assertTrue(e.getMessage().contains("version 2"), e.getMessage());
    }
}
