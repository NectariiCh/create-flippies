package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.DEV;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import org.junit.jupiter.api.Test;

/**
 * Every RV32I and M instruction on hand-encoded words, with the edge cases spec 8.1 lists. Most
 * tests run one instruction with a1 and a2 as operands and a0 as the destination.
 */
class Rv32InstructionTest {

    /** Runs {@code inst} once with a1 = {@code a}, a2 = {@code b}; returns a0. */
    private static int op(int inst, int a, int b) {
        Rv32Rig rig = one(inst, a, b);
        return rig.x(A0);
    }

    private static Rv32Rig one(int inst, int a, int b) {
        Rv32Rig rig = new Rv32Rig().program(inst);
        rig.cpu.setReg(A1, a);
        rig.cpu.setReg(A2, b);
        rig.step();
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE), "no trap");
        assertEquals(RAM + 4, rig.cpu.pc(), "pc advanced");
        return rig;
    }

    // ---- register-register ----

    @Test
    void addAndSubWrap() {
        assertEquals(0x8000_0000, op(add(A0, A1, A2), 0x7FFF_FFFF, 1));
        assertEquals(0, op(add(A0, A1, A2), -1, 1));
        assertEquals(-1, op(sub(A0, A1, A2), 0, 1));
        assertEquals(0x7FFF_FFFF, op(sub(A0, A1, A2), 0x8000_0000, 1));
    }

    @Test
    void logicOps() {
        assertEquals(0xF0F0_0F0F ^ 0xFF00_FF00, op(xor(A0, A1, A2), 0xF0F0_0F0F, 0xFF00_FF00));
        assertEquals(0xFFF0_FF0F, op(or(A0, A1, A2), 0xF0F0_0F0F, 0xFF00_FF00));
        assertEquals(0xF000_0F00, op(and(A0, A1, A2), 0xF0F0_0F0F, 0xFF00_FF00));
    }

    @Test
    void registerShiftsUseOnlyTheLowFiveBitsOfRs2() {
        assertEquals(2, op(sll(A0, A1, A2), 1, 33));
        assertEquals(0x8000_0000, op(sll(A0, A1, A2), 1, 31));
        assertEquals(1, op(srl(A0, A1, A2), 0x8000_0000, 31));
        assertEquals(0x4000_0000, op(srl(A0, A1, A2), 0x8000_0000, 0xFFFF_FFE1));
        assertEquals(-1, op(sra(A0, A1, A2), 0x8000_0000, 31));
        assertEquals(-4, op(sra(A0, A1, A2), -8, 1));
        assertEquals(-8, op(sra(A0, A1, A2), -8, 32), "shift by 32 is shift by 0");
        assertEquals(0x0800_0000, op(sra(A0, A1, A2), 0x1000_0000, 1), "positive stays positive");
    }

    @Test
    void sltIsSignedAndSltuUnsigned() {
        assertEquals(1, op(slt(A0, A1, A2), 0x8000_0000, 1));
        assertEquals(0, op(sltu(A0, A1, A2), 0x8000_0000, 1));
        assertEquals(0, op(slt(A0, A1, A2), 1, 0x8000_0000));
        assertEquals(1, op(sltu(A0, A1, A2), 1, 0x8000_0000));
        assertEquals(0, op(slt(A0, A1, A2), 5, 5));
        assertEquals(1, op(sltu(A0, ZERO, A2), 0, 7), "snez");
    }

    // ---- register-immediate ----

    @Test
    void immediatesAreSignExtended() {
        assertEquals(-1, op(addi(A0, ZERO, -1), 0, 0));
        assertEquals(0x7FF + 10, op(addi(A0, A1, 0x7FF), 10, 0));
        assertEquals(10 - 0x800, op(addi(A0, A1, -0x800), 10, 0));
        assertEquals(~0x1234_5678, op(xori(A0, A1, -1), 0x1234_5678, 0), "not");
        assertEquals(0xFFFF_F800 | 0x12, op(ori(A0, A1, -0x800), 0x12, 0));
        assertEquals(0x1234_5000, op(andi(A0, A1, -0x800), 0x1234_5678, 0), "imm 0xFFFFF800");
        assertEquals(0x0000_0078, op(andi(A0, A1, 0xFF), 0x1234_5678, 0));
    }

    @Test
    void sltiSignedAndSltiuWithTheSignExtendedImmediate() {
        assertEquals(1, op(slti(A0, A1, 0), -5, 0));
        assertEquals(0, op(slti(A0, A1, -1), 0, 0));
        assertEquals(1, op(sltiu(A0, A1, -1), 5, 0), "imm -1 is 0xFFFFFFFF unsigned");
        assertEquals(1, op(sltiu(A0, A1, 1), 0, 0), "seqz of 0");
        assertEquals(0, op(sltiu(A0, A1, 1), 0x8000_0000, 0), "seqz of nonzero");
    }

    @Test
    void immediateShiftsBy31() {
        assertEquals(0x8000_0000, op(slli(A0, A1, 31), 1, 0));
        assertEquals(1, op(srli(A0, A1, 31), 0x8000_0000, 0));
        assertEquals(-1, op(srai(A0, A1, 31), 0x8000_0000, 0));
        assertEquals(0, op(srai(A0, A1, 31), 0x7FFF_FFFF, 0));
        assertEquals(0x1234_5678, op(slli(A0, A1, 0), 0x1234_5678, 0));
    }

    @Test
    void luiAndAuipc() {
        assertEquals(0x1234_5000, op(lui(A0, 0x12345), 0, 0));
        assertEquals(0x8000_0000, op(lui(A0, 0x80000), 0, 0));
        assertEquals(RAM + 0x1000, op(auipc(A0, 1), 0, 0));
        assertEquals(RAM - 0x1000, op(auipc(A0, 0xFFFFF), 0, 0), "negative offset");
    }

    @Test
    void writesToX0AreIgnored() {
        Rv32Rig rig = new Rv32Rig().program(addi(ZERO, ZERO, 5), lui(ZERO, 0x12345), lw(ZERO, SP, 0),
                jal(ZERO, 4), mul(ZERO, A1, A1), csrr(ZERO, Rv32Csr.MISA), add(A0, ZERO, ZERO));
        rig.cpu.setReg(SP, RAM + 0x100);
        rig.cpu.setReg(A1, 7);
        rig.poke32(RAM + 0x100, 0xDEAD_BEEF);
        rig.cpu.setReg(ZERO, 99);
        rig.steps(7);
        assertEquals(0, rig.x(ZERO));
        assertEquals(0, rig.x(A0));
        assertEquals(RAM + 28, rig.cpu.pc());
    }

    // ---- loads and stores ----

    private static Rv32Rig withData() {
        Rv32Rig rig = new Rv32Rig();
        rig.poke32(RAM + 0x100, 0x8001_FF80);
        rig.poke32(RAM + 0x104, 0x1122_3344);
        rig.cpu.setReg(A1, RAM + 0x100);
        return rig;
    }

    private static int load(int inst) {
        Rv32Rig rig = withData().program(inst);
        rig.step();
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE), "no trap");
        return rig.x(A0);
    }

    @Test
    void loadsSignOrZeroExtend() {
        assertEquals(0xFFFF_FF80, load(lb(A0, A1, 0)));
        assertEquals(0x80, load(lbu(A0, A1, 0)));
        assertEquals(0xFFFF_FFFF, load(lb(A0, A1, 1)));
        assertEquals(0xFFFF_FF80, load(lh(A0, A1, 0)));
        assertEquals(0xFF80, load(lhu(A0, A1, 0)));
        assertEquals(0xFFFF_8001, load(lh(A0, A1, 2)));
        assertEquals(0x8001, load(lhu(A0, A1, 2)));
        assertEquals(0x8001_FF80, load(lw(A0, A1, 0)));
        assertEquals(0x01, load(lbu(A0, A1, 2)));
        assertEquals(0x1122_3344, load(lw(A0, A1, 4)));
    }

    @Test
    void negativeLoadOffsets() {
        Rv32Rig rig = withData().program(lw(A0, A2, -4));
        rig.cpu.setReg(A2, RAM + 0x108);
        rig.step();
        assertEquals(0x1122_3344, rig.x(A0));
    }

    @Test
    void misalignedLoadsWorkAndCostOneMoreCycle() {
        Rv32Rig rig = withData().program(lw(A0, A1, 1), lh(A2, A1, 3), lhu(A3, A1, 5), lw(A4, A1, 4));
        rig.step();
        assertEquals(0x4480_01FF, rig.x(A0));
        assertEquals(3, rig.cpu.cycles(), "load 2 + misaligned 1");
        rig.step();
        assertEquals(0x4480, rig.x(A2));
        rig.step();
        assertEquals(0x2233, rig.x(A3));
        assertEquals(9, rig.cpu.cycles());
        rig.step();
        assertEquals(11, rig.cpu.cycles(), "aligned load costs 2");
    }

    @Test
    void storesWriteOnlyTheirBytes() {
        Rv32Rig rig = new Rv32Rig().program(sb(A2, A1, 1), sh(A2, A1, 6), sw(A2, A1, 8), sb(A2, A1, -1));
        rig.poke32(RAM + 0x100, 0xAABB_CCDD);
        rig.poke32(RAM + 0x104, 0xAABB_CCDD);
        rig.poke32(RAM + 0x0FC, 0xAABB_CCDD);
        rig.cpu.setReg(A1, RAM + 0x100);
        rig.cpu.setReg(A2, 0x1234_5678);
        rig.steps(4);
        assertEquals(0xAABB_78DD, rig.peek32(RAM + 0x100));
        assertEquals(0x5678_CCDD, rig.peek32(RAM + 0x104));
        assertEquals(0x1234_5678, rig.peek32(RAM + 0x108));
        assertEquals(0x78BB_CCDD, rig.peek32(RAM + 0x0FC));
        assertEquals(4, rig.cpu.cycles(), "aligned stores cost 1");
    }

    @Test
    void misalignedStoresWorkAndCostOneMoreCycle() {
        Rv32Rig rig = new Rv32Rig().program(sw(A2, A1, 3), sh(A2, A1, 9));
        rig.cpu.setReg(A1, RAM + 0x100);
        rig.cpu.setReg(A2, 0x1122_3344);
        rig.step();
        assertEquals(0x4400_0000, rig.peek32(RAM + 0x100));
        assertEquals(0x0011_2233, rig.peek32(RAM + 0x104));
        assertEquals(2, rig.cpu.cycles());
        rig.step();
        assertEquals(0x0033_4400, rig.peek32(RAM + 0x108));
        assertEquals(4, rig.cpu.cycles());
    }

    @Test
    void lastBytesOfRamAreReachable() {
        int end = RAM + RAM_SIZE;
        Rv32Rig rig = new Rv32Rig().program(sb(A2, A1, -1), lbu(A0, A1, -1), lhu(A3, A1, -2), lw(A4, A1, -4));
        rig.cpu.setReg(A1, end);
        rig.cpu.setReg(A2, 0xAB);
        rig.steps(4);
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(0xAB, rig.x(A0));
        assertEquals(0xAB00, rig.x(A3));
        assertEquals(0xAB00_0000, rig.x(A4));
        assertEquals(1 + 2 + 2 + 2, rig.cpu.cycles(), "RAM accesses never pay the device cost");
    }

    @Test
    void romIsReadable() {
        byte[] image = { 0x78, 0x56, 0x34, 0x12, (byte) 0x80 };
        Rv32Rig rig = new Rv32Rig(true, image).program(lw(A0, A1, 0), lb(A2, A1, 4));
        rig.cpu.setReg(A1, Rv32Map.ROM_BASE);
        rig.steps(2);
        assertEquals(0x1234_5678, rig.x(A0));
        assertEquals(-128, rig.x(A2));
    }

    @Test
    void vramStoresLandAndMarkTheirPageDirty() {
        Rv32Rig rig = new Rv32Rig().program(sw(A2, A1, 0), lw(A0, A1, 0), sh(A2, A3, 0));
        rig.cpu.setReg(A1, Rv32Map.VRAM_BASE + 0x5000);
        rig.cpu.setReg(A3, Rv32Map.VRAM_BASE + 0x5FFF);
        rig.cpu.setReg(A2, 0xCAFE_F00D);
        rig.steps(2);
        assertEquals(0xCAFE_F00D, rig.x(A0));
        assertEquals(1L << 5, rig.bus.vramDirtyPages()[0]);
        assertEquals((byte) 0x0D, rig.bus.vramArray()[0x5000]);
        rig.step();
        assertEquals((1L << 5) | (1L << 6), rig.bus.vramDirtyPages()[0], "a store straddling two pages dirties both");
    }

    @Test
    void deviceAccessesGoThroughTheBusAndCostTwoMore() {
        Rv32Rig rig = new Rv32Rig().program(sw(A2, A1, 8), lw(A0, A1, 8), lbu(A3, A1, 9), sb(A2, A1, 0));
        rig.cpu.setReg(A1, DEV);
        rig.cpu.setReg(A2, 0x1234_5678);
        rig.step();
        assertEquals(1 + Rv32Map.MMIO_EXTRA_CYCLES, rig.cpu.cycles());
        assertEquals(new TestDevice.Write(8, 4, 0x1234_5678), rig.device.writes.get(0), "one 32-bit access");
        rig.step();
        assertEquals(0x1234_5678, rig.x(A0));
        assertEquals(3 + 2 + Rv32Map.MMIO_EXTRA_CYCLES, rig.cpu.cycles());
        rig.step();
        assertEquals(0x56, rig.x(A3), "value-addressed byte 1 of the register");
        rig.step();
        assertEquals(new TestDevice.Write(0, 1, 0x78), rig.device.writes.get(1));
    }

    @Test
    void theClockIsPublishedAtTheStartOfADeviceAccess() {
        Rv32Rig rig = new Rv32Rig().program(NOP, NOP, sw(A2, A1, 0));
        rig.cpu.setReg(A1, DEV);
        long[] seen = new long[1];
        rig.device.onWrite = () -> seen[0] = rig.clock.now();
        rig.cpu.run(1000, Long.MAX_VALUE);
        assertEquals(2, seen[0]);
    }

    // ---- branches and jumps ----

    private static Rv32Rig branch(int inst, int a, int b) {
        Rv32Rig rig = new Rv32Rig().program(NOP, NOP, inst);
        rig.cpu.setPc(RAM + 8);
        rig.cpu.setReg(A1, a);
        rig.cpu.setReg(A2, b);
        rig.step();
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE), "no trap");
        return rig;
    }

    private static void assertTaken(int inst, int a, int b, boolean taken) {
        Rv32Rig rig = branch(inst, a, b);
        assertEquals(taken ? RAM + 8 + 16 : RAM + 12, rig.cpu.pc(), Integer.toHexString(inst));
        assertEquals(taken ? 3 : 1, rig.cpu.cycles(), "taken 3 cycles, not taken 1");
    }

    @Test
    void everyBranchTakenAndNotTaken() {
        assertTaken(beq(A1, A2, 16), 5, 5, true);
        assertTaken(beq(A1, A2, 16), 5, 6, false);
        assertTaken(bne(A1, A2, 16), 5, 6, true);
        assertTaken(bne(A1, A2, 16), 5, 5, false);
        assertTaken(blt(A1, A2, 16), 0x8000_0000, 1, true);
        assertTaken(blt(A1, A2, 16), 1, 0x8000_0000, false);
        assertTaken(blt(A1, A2, 16), 3, 3, false);
        assertTaken(bge(A1, A2, 16), 3, 3, true);
        assertTaken(bge(A1, A2, 16), 1, 0x8000_0000, true);
        assertTaken(bge(A1, A2, 16), 0x8000_0000, 1, false);
        assertTaken(bltu(A1, A2, 16), 1, 0x8000_0000, true);
        assertTaken(bltu(A1, A2, 16), 0x8000_0000, 1, false);
        assertTaken(bgeu(A1, A2, 16), 0x8000_0000, 1, true);
        assertTaken(bgeu(A1, A2, 16), 1, 0x8000_0000, false);
        assertTaken(bgeu(A1, A2, 16), 7, 7, true);
    }

    @Test
    void backwardBranchesAndExtremeOffsets() {
        Rv32Rig rig = branch(bne(A1, ZERO, -8), 1, 0);
        assertEquals(RAM, rig.cpu.pc());
        Rv32Rig far = new Rv32Rig().program(beq(ZERO, ZERO, 4092));
        far.step();
        assertEquals(RAM + 4092, far.cpu.pc(), "largest positive B offset");
        Rv32Rig back = new Rv32Rig().at(RAM + 0x2000, beq(ZERO, ZERO, -4096));
        back.cpu.setPc(RAM + 0x2000);
        back.step();
        assertEquals(RAM + 0x1000, back.cpu.pc(), "most negative B offset");
    }

    @Test
    void jalLinksPcPlus4() {
        Rv32Rig rig = new Rv32Rig().program(NOP, jal(RA, 0x800));
        rig.steps(2);
        assertEquals(RAM + 8, rig.x(RA));
        assertEquals(RAM + 4 + 0x800, rig.cpu.pc());
        assertEquals(4, rig.cpu.cycles());
        Rv32Rig back = new Rv32Rig().at(RAM + 0x100, jal(RA, -0x100));
        back.cpu.setPc(RAM + 0x100);
        back.step();
        assertEquals(RAM, back.cpu.pc());
    }

    @Test
    void jalrClearsBitZeroAndLinks() {
        Rv32Rig rig = new Rv32Rig().program(jalr(A0, A1, 1));
        rig.cpu.setReg(A1, RAM + 0x40);
        rig.step();
        assertEquals(RAM + 0x40, rig.cpu.pc(), "target 0x..41 with bit 0 cleared");
        assertEquals(RAM + 4, rig.x(A0));
        assertEquals(3, rig.cpu.cycles());
        Rv32Rig neg = new Rv32Rig().program(jalr(ZERO, A1, -3));
        neg.cpu.setReg(A1, RAM + 0x43);
        neg.step();
        assertEquals(RAM + 0x40, neg.cpu.pc());
    }

    @Test
    void jalrWithRdEqualRs1UsesTheOldValue() {
        Rv32Rig rig = new Rv32Rig().program(jalr(RA, RA, 0));
        rig.cpu.setReg(RA, RAM + 0x80);
        rig.step();
        assertEquals(RAM + 0x80, rig.cpu.pc());
        assertEquals(RAM + 4, rig.x(RA));
    }

    @Test
    void notTakenBranchToAMisalignedTargetDoesNotTrap() {
        Rv32Rig rig = branch(bne(ZERO, ZERO, 6), 0, 0);
        assertEquals(RAM + 12, rig.cpu.pc());
    }

    // ---- M ----

    @Test
    void multiplies() {
        assertEquals(42, op(mul(A0, A1, A2), 6, 7));
        assertEquals(0x1234_5678 * 0x9ABC_DEF0, op(mul(A0, A1, A2), 0x1234_5678, 0x9ABC_DEF0), "low bits");
        assertEquals(-6, op(mul(A0, A1, A2), -2, 3));
        assertEquals(0, op(mulh(A0, A1, A2), -1, -1));
        assertEquals(0x4000_0000, op(mulh(A0, A1, A2), 0x8000_0000, 0x8000_0000));
        assertEquals(-1, op(mulh(A0, A1, A2), -2, 3));
        assertEquals(0xFFFF_FFFE, op(mulhu(A0, A1, A2), 0xFFFF_FFFF, 0xFFFF_FFFF));
        assertEquals(0xFFFF_FFFF, op(mulhsu(A0, A1, A2), -1, 0xFFFF_FFFF));
        assertEquals(0x7FFF_FFFE, op(mulhsu(A0, A1, A2), 0x7FFF_FFFF, 0xFFFF_FFFF), "positive times large unsigned");
        assertEquals(0, op(mulhsu(A0, A1, A2), 1, 0xFFFF_FFFF));
        assertEquals(3, one(mul(A0, A1, A2), 2, 3).cpu.cycles(), "multiply costs 3");
    }

    @Test
    void dividesAndRemainders() {
        assertEquals(-3, op(div(A0, A1, A2), -7, 2), "rounds toward zero");
        assertEquals(-1, op(rem(A0, A1, A2), -7, 2), "sign of the dividend");
        assertEquals(1, op(rem(A0, A1, A2), 7, -2));
        assertEquals(0x7FFF_FFFF, op(divu(A0, A1, A2), 0xFFFF_FFFF, 2));
        assertEquals(1, op(remu(A0, A1, A2), 0xFFFF_FFFF, 2));
        assertEquals(33, one(div(A0, A1, A2), 9, 3).cpu.cycles(), "divide costs 33");
    }

    @Test
    void divisionByZeroAndOverflowFollowTheSpec() {
        assertEquals(-1, op(div(A0, A1, A2), 1234, 0));
        assertEquals(-1, op(div(A0, A1, A2), -1234, 0));
        assertEquals(0xFFFF_FFFF, op(divu(A0, A1, A2), 1234, 0));
        assertEquals(1234, op(rem(A0, A1, A2), 1234, 0));
        assertEquals(-1234, op(rem(A0, A1, A2), -1234, 0));
        assertEquals(0x8765_4321, op(remu(A0, A1, A2), 0x8765_4321, 0));
        assertEquals(0x8000_0000, op(div(A0, A1, A2), 0x8000_0000, -1), "overflow");
        assertEquals(0, op(rem(A0, A1, A2), 0x8000_0000, -1));
        assertEquals(0, op(divu(A0, A1, A2), 0x8000_0000, -1));
        assertEquals(0x8000_0000, op(remu(A0, A1, A2), 0x8000_0000, -1));
    }

    @Test
    void mEncodingsAreIllegalWithoutTheExtension() {
        int[] ops = { mul(A0, A1, A2), mulh(A0, A1, A2), mulhsu(A0, A1, A2), mulhu(A0, A1, A2),
                div(A0, A1, A2), divu(A0, A1, A2), rem(A0, A1, A2), remu(A0, A1, A2) };
        for (int inst : ops) {
            Rv32Rig rig = new Rv32Rig(false).program(inst);
            rig.mtvec(Rv32Rig.HANDLER);
            rig.cpu.setReg(A0, 77);
            rig.step();
            assertEquals(2, rig.csr(Rv32Csr.MCAUSE), Integer.toHexString(inst));
            assertEquals(inst, rig.csr(Rv32Csr.MTVAL));
            assertEquals(77, rig.x(A0), "rd untouched");
        }
        assertEquals(0x4000_0100, new Rv32Rig(false).csr(Rv32Csr.MISA));
    }
}
