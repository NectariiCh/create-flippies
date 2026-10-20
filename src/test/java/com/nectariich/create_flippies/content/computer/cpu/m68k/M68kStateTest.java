package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/** Saved state (spec 10.6): lockstep round trip, wait state and pins, model check. */
class M68kStateTest {

    private static final int[] LOOP = {
            0x5281,         // addq.l #1,d1
            0x20C1,         // move.l d1,(a0)+
            0xE389,         // lsl.l #1,d1
            0xD481,         // add.l d1,d2
            0x4E41,         // trap #1 (handler: rte)
            0x51C8, 0xFFF4, // dbf d0,loop
            0x4E72, 0x2700, // stop #$2700 (level 2 stays pending, masked)
            0x60FA,         // bra.s back to the stop
    };

    private static M68kRig rig(boolean is040) {
        M68kRig rig = new M68kRig(is040).program(LOOP);
        rig.at(handler(33), 0x4E73);
        rig.at(handler(27), 0x4E73);
        rig.cpu.setD(0, 40);
        rig.cpu.setA(0, DATA);
        return rig;
    }

    private static byte[] save(M68kCpu cpu) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        cpu.saveState(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void load(M68kCpu cpu, byte[] state) throws IOException {
        cpu.loadState(new DataInputStream(new ByteArrayInputStream(state)));
    }

    @Test
    void saveMidProgramLoadIntoAFreshCpuAndRunInLockstep() throws IOException {
        for (boolean is040 : new boolean[] {false, true}) {
            M68kRig a = rig(is040);
            a.runFor(777);
            a.cpu.setIrq(2, true);
            byte[] state = save(a.cpu);
            M68kRig b = new M68kRig(is040);
            System.arraycopy(a.ram, 0, b.ram, 0, a.ram.length);
            b.clock.advanceTo(a.clock.now());
            load(b.cpu, state);
            assertArrayEquals(state, save(b.cpu), "load then save is identical");
            for (int k = 0; k < 400; k++) {
                Cpu.Exit ea = a.runFor(13);
                Cpu.Exit eb = b.runFor(13);
                assertEquals(ea, eb);
                assertEquals(a.cpu.pc(), b.cpu.pc());
                assertEquals(a.cpu.cycles(), b.cpu.cycles());
                if (ea == Cpu.Exit.WAITING) {
                    a.clock.advanceTo(a.clock.now() + 13);
                    b.clock.advanceTo(b.clock.now() + 13);
                }
            }
            assertArrayEquals(save(a.cpu), save(b.cpu));
            assertArrayEquals(a.ram, b.ram);
            assertTrue(a.cpu.stopped(), "the loop ends in STOP");
        }
    }

    @Test
    void stoppedStateAndLevel7EdgeRoundTrip() throws IOException {
        M68kRig a = rig(false);
        a.cpu.setD(0, 0);
        a.runFor(100_000);
        assertTrue(a.cpu.stopped());
        a.cpu.setIrq(7, true);
        byte[] state = save(a.cpu);
        M68kRig b = new M68kRig(false);
        System.arraycopy(a.ram, 0, b.ram, 0, a.ram.length);
        load(b.cpu, state);
        assertTrue(b.cpu.stopped());
        b.runFor(1);
        assertEquals(handler(31), b.cpu.pc(), "the latched level 7 edge survives the round trip");
    }

    @Test
    void fpuStateIsInsideTheCpuPayload() throws IOException {
        M68kRig a = rig(true);
        a.fpu().stateMarker = 0x1234;
        byte[] state = save(a.cpu);
        M68kRig b = new M68kRig(true);
        load(b.cpu, state);
        assertEquals(0x1234, b.fpu().stateMarker);
    }

    @Test
    void a68000StateDoesNotLoadIntoA68040() throws IOException {
        byte[] state = save(rig(false).cpu);
        M68kRig other = new M68kRig(true);
        assertThrows(StateFormatException.class, () -> load(other.cpu, state));
        byte[] bad = save(rig(false).cpu);
        bad[3] = 99;
        assertThrows(StateFormatException.class, () -> load(rig(false).cpu, bad));
    }
}
