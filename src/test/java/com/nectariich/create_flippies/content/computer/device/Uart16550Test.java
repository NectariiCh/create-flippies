package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Uart16550.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The COM port (spec 3.5). At 10 MHz with 8N1 and divisor 1, one character is 10 bits of
 * 1 / 115200 s: floor(10 * 16 * 10_000_000 / 1_843_200) = 868 cycles.
 */
class Uart16550Test {

    private static final int CHAR = 868;

    private DeviceRig rig;
    private Uart16550 uart;
    private final DeviceRig.Line line = new DeviceRig.Line();

    private void setUp(int hz) {
        rig = new DeviceRig();
        uart = new Uart16550(rig.clock, hz, line);
        rig.map(uart, 0x1000);
    }

    private void setUp8N1() {
        setUp(10_000_000);
        rig.w8(LCR, 0x03);
    }

    /** Exact completion of n characters of 8N1 at divisor 1. */
    private static long exact(long n, int hz) {
        return BigInteger.valueOf(n).multiply(BigInteger.valueOf(10L * 16 * hz))
                .divide(BigInteger.valueOf(1_843_200)).longValueExact();
    }

    private byte[] drain() {
        byte[] buf = new byte[70_000];
        int n = uart.hostRead(buf, 0, buf.length);
        return Arrays.copyOf(buf, n);
    }

    private void hostSend(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) bytes[i];
        assertEquals(b.length, uart.hostWrite(b, 0, b.length));
    }

    @Test
    void resetValues() {
        setUp(10_000_000);
        assertEquals(0x01, rig.r8(IIR));
        assertEquals(0x60, rig.r8(LSR));
        assertEquals(0, rig.r8(LCR));
        assertEquals(0, rig.r8(MCR));
        assertEquals(0, rig.r8(IER));
        assertEquals(0, rig.r8(MSR), "no host connected");
        assertEquals(0, rig.r8(RBR), "empty RX reads 0");
        assertFalse(line.level);
        assertEquals(0, rig.r32(4), "a 32-bit read at 4 is MCR only, zero-extended");
    }

    @Test
    void dlabSelectsTheDivisorLatch() {
        setUp(10_000_000);
        rig.w8(LCR, 0x83);
        rig.w8(DLL, 0x0C);
        rig.w8(DLM, 0x01);
        assertEquals(0x0C, rig.r8(DLL));
        assertEquals(0x01, rig.r8(DLM));
        rig.w8(LCR, 0x03);
        assertEquals(0, rig.r8(IER), "IER, not DLM");
        rig.w8(IER, 0x05);
        assertEquals(0x05, rig.r8(IER));
        rig.w8(LCR, 0x83);
        assertEquals(0x01, rig.r8(DLM));
        rig.w32(MCR, 0x1234_5603);
        assertEquals(0x03, rig.r8(MCR), "a wide write stores the low byte");
        assertEquals(0x03, rig.r32(MCR), "a wide read is the register zero-extended");
    }

    @Test
    void charCyclesFollowLineSettings() {
        setUp(10_000_000);
        assertEquals(607, uart.charCycles(1), "reset LCR: 5N1 = 7 bits");
        rig.w8(LCR, 0x03);
        assertEquals(CHAR, uart.charCycles(1), "8N1 = 10 bits");
        rig.w8(LCR, 0x0F);
        assertEquals(1041, uart.charCycles(1), "8 data, parity, 2 stop = 12 bits");
        rig.w8(LCR, 0x04);
        assertEquals(651, uart.charCycles(1), "5 data with 1.5 stop bits = 7.5 bits");
        rig.w8(LCR, 0x83);
        rig.w8(DLL, 12);
        rig.w8(LCR, 0x03);
        assertEquals(10416, uart.charCycles(1), "9600 baud");
        rig.w8(LCR, 0x83);
        rig.w8(DLL, 0);
        rig.w8(LCR, 0x03);
        assertEquals(CHAR, uart.charCycles(1), "divisor 0 counts as 1");
    }

    @Test
    void transmittedByteReachesTheHostAfterOneCharacterTime() {
        setUp8N1();
        rig.at(1000);
        rig.w8(THR, 'A');
        rig.at(1000 + CHAR - 1);
        assertEquals(0, uart.hostAvailable());
        assertArrayEquals(new byte[0], drain(), "not visible before the character time");
        rig.at(1000 + CHAR);
        assertArrayEquals(new byte[] { 'A' }, drain());
    }

    @Test
    void threAndTemtTiming() {
        setUp8N1();
        rig.w8(THR, 1);
        assertEquals(LSR_THRE, rig.r8(LSR), "first byte went to the shift register");
        rig.w8(THR, 2);
        assertEquals(0, rig.r8(LSR), "second waits in the holding register");
        rig.w8(THR, 3);
        rig.at(CHAR - 1);
        assertEquals(0, rig.r8(LSR));
        rig.at(CHAR);
        assertEquals(LSR_THRE, rig.r8(LSR));
        rig.at(2 * CHAR - 1);
        assertEquals(LSR_THRE, rig.r8(LSR));
        rig.at(exact(2, 10_000_000));
        assertEquals(LSR_THRE | LSR_TEMT, rig.r8(LSR));
        assertArrayEquals(new byte[] { 1, 2 }, drain(), "the third byte found the holding register full");
    }

    @Test
    void fifoDepthSixteenVersusOne() {
        setUp8N1();
        rig.w8(FCR, 0x01);
        for (int i = 0; i < 20; i++)
            rig.w8(THR, i);
        rig.at(100 * CHAR);
        assertEquals(17, drain().length, "16 in the FIFO plus one in the shift register");
        rig.w8(FCR, 0x00);
        for (int i = 0; i < 20; i++)
            rig.w8(THR, i);
        rig.at(200 * CHAR);
        assertEquals(2, drain().length, "holding register plus shift register");
    }

    @Test
    void rxTriggerLevelRaisesReceivedDataAvailable() {
        setUp8N1();
        rig.w8(FCR, 0x81);
        rig.w8(IER, IER_RDA);
        hostSend(1, 2, 3, 4, 5, 6, 7, 8);
        assertEquals(exact(1, 10_000_000), uart.nextEventCycle(), "the first arrival");
        rig.at(exact(7, 10_000_000));
        assertEquals(0xC1, rig.r8(IIR), "7 bytes, below the trigger of 8");
        assertFalse(line.level);
        rig.at(exact(8, 10_000_000));
        assertEquals(0xC4, rig.r8(IIR));
        assertTrue(line.level);
        assertEquals(LSR_DR | LSR_THRE | LSR_TEMT, rig.r8(LSR));
        assertEquals(1, rig.r8(RBR));
        assertEquals(0xC1, rig.r8(IIR), "dropped below the trigger");
        assertFalse(line.level);
    }

    @Test
    void triggerLevels() {
        int[] levels = { 1, 4, 8, 14 };
        for (int t = 0; t < 4; t++) {
            setUp8N1();
            rig.w8(FCR, t << 6 | 1);
            rig.w8(IER, IER_RDA);
            byte[] bytes = new byte[levels[t]];
            uart.hostWrite(bytes, 0, bytes.length);
            rig.at(exact(levels[t], 10_000_000) - 1);
            assertEquals(0xC1, rig.r8(IIR), "trigger " + levels[t]);
            rig.at(exact(levels[t], 10_000_000));
            assertEquals(0xC4, rig.r8(IIR), "trigger " + levels[t]);
        }
    }

    @Test
    void characterTimeoutAfterFourCharacterTimes() {
        setUp8N1();
        rig.w8(FCR, 0xC1);
        rig.w8(IER, IER_RDA);
        hostSend('x');
        rig.at(CHAR);
        assertEquals(0xC1, rig.r8(IIR), "one byte, far below the trigger of 14");
        long timeout = CHAR + exact(4, 10_000_000);
        assertEquals(timeout, uart.nextEventCycle());
        rig.at(timeout - 1);
        assertEquals(0xC1, rig.r8(IIR));
        rig.at(timeout);
        assertEquals(0xCC, rig.r8(IIR));
        assertTrue(line.level);
        assertEquals('x', rig.r8(RBR));
        assertEquals(0xC1, rig.r8(IIR), "cleared by reading RBR");
        assertFalse(line.level);
    }

    @Test
    void overrunDropsTheByteAndRaisesLineStatus() {
        setUp8N1();
        hostSend(0x11, 0x22);
        rig.w8(IER, IER_RLS);
        rig.at(exact(2, 10_000_000));
        assertEquals(0x06, rig.r8(IIR));
        assertTrue(line.level);
        assertEquals(LSR_DR | LSR_OE | LSR_THRE | LSR_TEMT, rig.r8(LSR));
        assertEquals(LSR_DR | LSR_THRE | LSR_TEMT, rig.r8(LSR), "OE clears on read");
        assertFalse(line.level);
        assertEquals(0x11, rig.r8(RBR), "the first byte kept, the second dropped");
        assertEquals(0, rig.r8(LSR) & LSR_DR);
    }

    @Test
    void loopbackWiresModemLinesFromMcr() {
        setUp8N1();
        rig.w8(MCR, MCR_LOOP | MCR_RTS);
        assertEquals(MSR_CTS | MSR_DCTS, rig.r8(MSR), "CTS = RTS");
        rig.w8(MCR, MCR_LOOP | MCR_DTR);
        assertEquals(MSR_DSR | MSR_DCTS | MSR_DDSR, rig.r8(MSR), "DSR = DTR");
        rig.w8(MCR, MCR_LOOP | MCR_OUT1);
        assertEquals(MSR_RI | MSR_DDSR, rig.r8(MSR), "RI = OUT1");
        rig.w8(MCR, MCR_LOOP | MCR_OUT2);
        assertEquals(MSR_DCD | MSR_TERI | MSR_DDCD, rig.r8(MSR), "DCD = OUT2; RI fell: TERI");
        assertEquals(MSR_DCD, rig.r8(MSR), "deltas clear on read");
        rig.w8(MCR, MCR_LOOP | MCR_OUT1);
        rig.r8(MSR);
        rig.w8(MCR, MCR_LOOP | MCR_OUT2);
        assertEquals(MSR_TERI, rig.r8(MSR) & MSR_TERI, "TERI only on 1 to 0");
        rig.w8(MCR, MCR_LOOP | MCR_OUT1 | MCR_OUT2);
        assertEquals(0, rig.r8(MSR) & MSR_TERI, "RI rising is not TERI");
    }

    @Test
    void loopbackSendsTxToRxAndNothingToTheHost() {
        setUp8N1();
        rig.w8(MCR, MCR_LOOP);
        rig.w8(THR, 0x55);
        rig.at(CHAR);
        assertEquals(LSR_DR | LSR_THRE | LSR_TEMT, rig.r8(LSR));
        assertEquals(0x55, rig.r8(RBR));
        assertEquals(0, drain().length);
        hostSend(0x66);
        rig.at(10 * CHAR);
        assertEquals(0, rig.r8(LSR) & LSR_DR, "host input does not arrive in loopback");
        rig.w8(MCR, 0);
        rig.at(11 * CHAR + CHAR);
        assertEquals(0x66, rig.r8(RBR), "and arrives once loopback is off");
    }

    @Test
    void enablingThreInterruptWithEmptyThrRaisesItAtOnce() {
        setUp8N1();
        rig.w8(IER, IER_THRE);
        assertTrue(line.level);
        assertEquals(0x02, rig.r8(IIR));
        assertFalse(line.level, "reading IIR when THRE is the source clears it");
        assertEquals(0x01, rig.r8(IIR));
    }

    @Test
    void thrWriteClearsTheThreInterruptUntilTheHoldingRegisterEmpties() {
        setUp8N1();
        rig.clock.setHorizon(Long.MAX_VALUE);
        rig.w8(IER, IER_THRE);
        rig.w8(THR, 'a');
        assertTrue(line.level, "the byte went straight to the shift register: THR empty again");
        rig.w8(THR, 'b');
        assertFalse(line.level, "writing THR clears it");
        assertEquals(CHAR, rig.clock.horizon(), "the write asked to stop at the completion");
        assertEquals(CHAR, uart.nextEventCycle());
        rig.at(CHAR);
        uart.advanceTo(CHAR);
        assertTrue(line.level, "'b' moved to the shift register");
    }

    @Test
    void iirPriorityAndClearing() {
        setUp8N1();
        hostSend(0x11, 0x22);
        rig.at(exact(2, 10_000_000));
        rig.w8(IER, IER_RDA | IER_THRE | IER_RLS | IER_MS);
        uart.setHostConnected(true);
        assertEquals(0x06, rig.r8(IIR), "line status first");
        rig.r8(LSR);
        assertEquals(0x04, rig.r8(IIR), "then received data");
        rig.r8(RBR);
        assertEquals(0x02, rig.r8(IIR), "then THR empty, cleared by this read");
        assertEquals(0x00, rig.r8(IIR), "then modem status");
        rig.r8(MSR);
        assertEquals(0x01, rig.r8(IIR));
        assertFalse(line.level);
    }

    @Test
    void msrDeltasOnHostConnect() {
        setUp8N1();
        uart.setHostConnected(true);
        assertEquals(MSR_CTS | MSR_DSR | MSR_DCD | MSR_DCTS | MSR_DDSR | MSR_DDCD, rig.r8(MSR));
        assertEquals(MSR_CTS | MSR_DSR | MSR_DCD, rig.r8(MSR));
        uart.setHostConnected(false);
        assertEquals(MSR_DCTS | MSR_DDSR | MSR_DDCD, rig.r8(MSR));
        assertEquals(0, rig.r8(MSR));
    }

    @Test
    void fcrClearsFifos() {
        setUp8N1();
        rig.w8(FCR, 0x01);
        hostSend(1, 2, 3);
        rig.at(exact(3, 10_000_000));
        assertEquals(LSR_DR, rig.r8(LSR) & LSR_DR);
        rig.w8(FCR, 0x03);
        assertEquals(0, rig.r8(LSR) & LSR_DR);
        rig.w8(THR, 1);
        rig.w8(THR, 2);
        rig.w8(THR, 3);
        rig.w8(FCR, 0x05);
        assertEquals(LSR_THRE, rig.r8(LSR), "waiting bytes cleared, the shift register keeps its byte");
        rig.at(100 * CHAR);
        assertArrayEquals(new byte[] { 1 }, drain());
    }

    @Test
    void thousandBytesBackToBackDoNotDrift() {
        int hz = 4_772_727;
        setUp(hz);
        rig.w8(LCR, 0x03);
        rig.w8(FCR, 0x01);
        long one = uart.charCycles(1);
        long thousand = exact(1000, hz);
        assertEquals(thousand, uart.charCycles(1000));
        assertNotEquals(1000 * one, thousand, "rounded per-byte durations would drift");

        for (int i = 0; i < 17; i++)
            rig.w8(THR, i);
        int sent = 17;
        for (int k = 1; sent < 1000; k++) {
            rig.at(exact(k, hz));
            rig.w8(THR, sent++);
        }
        rig.at(thousand - 1);
        assertEquals(999, uart.hostAvailable());
        rig.at(thousand);
        assertEquals(1000, uart.hostAvailable());
        byte[] got = drain();
        for (int i = 0; i < 1000; i++)
            assertEquals((byte) i, got[i]);
    }

    @Test
    void hostBuffersHaveTheirCapacities() {
        setUp8N1();
        byte[] big = new byte[5000];
        assertEquals(HOST_INPUT_CAPACITY, uart.hostWrite(big, 0, big.length));
        assertEquals(0, uart.hostWrite(big, 0, 1));
        assertEquals(HOST_INPUT_CAPACITY, uart.hostPending());

        setUp8N1();
        rig.w8(MCR, 0);
        int total = HOST_OUTPUT_CAPACITY + 100;
        for (int i = 0; i < total; i++) {
            rig.at(exact(i, 10_000_000));
            rig.w8(THR, i);
        }
        rig.at(exact(total + 1, 10_000_000));
        assertEquals(HOST_OUTPUT_CAPACITY, uart.hostAvailable());
        assertEquals(100, uart.hostDropped(), "the oldest bytes were dropped");
        byte[] got = drain();
        assertEquals((byte) 100, got[0]);
    }

    @Test
    void resetKeepsHostBuffers() {
        setUp8N1();
        rig.w8(THR, 'z');
        rig.at(CHAR);
        rig.w8(IER, 0x0F);
        uart.reset();
        assertEquals(0, rig.r8(IER));
        assertEquals(0x01, rig.r8(IIR));
        assertArrayEquals(new byte[] { 'z' }, drain());
    }
}
