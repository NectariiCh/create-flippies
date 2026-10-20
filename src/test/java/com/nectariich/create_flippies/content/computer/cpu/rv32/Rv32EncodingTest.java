package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the test encoder to instruction words checked by hand against the unprivileged spec's
 * format diagrams (bit fields written out, then grouped into hex), so a mistake in
 * {@link Rv32Enc} cannot hide a matching mistake in the CPU.
 */
class Rv32EncodingTest {

    @Test
    void integerRegisterImmediateAndUpperForms() {
        assertEquals(0x0000_0013, addi(ZERO, ZERO, 0), "nop");
        assertEquals(0x0015_0513, addi(A0, A0, 1));
        assertEquals(0xFFF0_0513, addi(A0, ZERO, -1));
        assertEquals(0x4015_D513, srai(A0, A1, 1));
        assertEquals(0x1234_52B7, lui(T0, 0x12345));
        assertEquals(0x0000_0517, auipc(A0, 0));
    }

    @Test
    void registerRegisterAndM() {
        assertEquals(0x00B5_0533, add(A0, A0, A1));
        assertEquals(0x40B5_0533, sub(A0, A0, A1));
        assertEquals(0x02B5_0533, mul(A0, A0, A1));
        assertEquals(0x02B5_4533, div(A0, A0, A1));
    }

    @Test
    void loadsStoresAndControlTransfers() {
        assertEquals(0x0001_2503, lw(A0, SP, 0));
        assertEquals(0x00A1_2023, sw(A0, SP, 0));
        assertEquals(0x0000_8067, jalr(ZERO, RA, 0), "ret");
        assertEquals(0x0000_006F, jal(ZERO, 0), "j .");
        assertEquals(0xFFDF_F06F, jal(ZERO, -4), "j -4");
        assertEquals(0xFE00_0EE3, beq(ZERO, ZERO, -4));
    }

    @Test
    void systemFencesAndCsrs() {
        assertEquals(0x3410_2573, csrr(A0, Rv32Csr.MEPC));
        assertEquals(UNIMP, csrrw(ZERO, Rv32Csr.CYCLE, ZERO));
        assertEquals(0x0000_100F, fenceI());
        assertEquals(0x0FF0_000F, fence(0xF, 0xF));
    }

    @Test
    void liSplitsWithSignCompensation() {
        assertArrayEquals(new int[] { lui(A0, 0x12345), addi(A0, A0, 0x678) }, li(A0, 0x1234_5678));
        assertArrayEquals(new int[] { lui(A0, 0x12346), addi(A0, A0, -0x800) }, li(A0, 0x1234_5800));
        assertArrayEquals(new int[] { lui(A0, 0), addi(A0, A0, -1) }, li(A0, -1));
    }
}
