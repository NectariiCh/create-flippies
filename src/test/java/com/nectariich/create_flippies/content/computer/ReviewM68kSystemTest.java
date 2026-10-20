package com.nectariich.create_flippies.content.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Bugs found by the adversarial review of the 68k assembler, the shared framework changes it
 * needed (data statements that may shrink, autoAlignment for labels) and the 68k machine
 * integration, against the programmer's reference manual and docs/PC_SPEC.md section 10. Each
 * test states the rule it checks and fails while the bug is present.
 */
class ReviewM68kSystemTest {

    private static AsmResult asm(String src, int base) {
        return M68kAssembler.assemble(src, AsmOptions.at(base), M68kTarget.M68000);
    }

    private static int symbol(AsmImage img, String name) {
        return (int) img.symbol(name).orElseThrow();
    }

    /**
     * Spec 10.7.3 and IsaBackend.relaxes: instructions only grow between relaxation passes, and
     * an instruction's size includes its auto-alignment pad byte. When an odd-sized change before
     * an instruction moves it from an odd to an even offset in a later pass (here a {@code ds.b}
     * whose count follows a branch that grew), the framework keeps the old, padded size, so the
     * second pass emits 2 bytes for a statement laid out as 3 and reports an internal assembler
     * error for a valid program.
     */
    @Test
    void relaxationKeepsAStalePadWhenAnInstructionMovesToAnEvenOffset() {
        String src = """
                a:      bra     far
                b:      ds.b    (b-a)/2
                        nop
                        ds.b    200
                far:    nop
                """;
        AsmResult r = asm(src, 0x1000);
        assertTrue(r.ok(), () -> "valid program must assemble: " + r.diagnostics());
    }

    /**
     * Spec 10.7.4 and IsaBackend.relaxes: pass 1 repeats until no size changes, at most 16
     * times. Forward labels are read from the previous pass's layout, so growth propagates
     * backwards through nested forward branches one branch per pass: 20 short branches, each
     * pushed over the .s limit only by the growth of the one after it, need 21 passes. The
     * valid program fails with "instruction sizes did not settle after 16 passes".
     */
    @Test
    void cascadeOfNestedForwardBranchesNeedsMoreThan16Passes() {
        int n = 20;
        StringBuilder src = new StringBuilder();
        for (int i = 1; i <= n; i++)
            src.append("b").append(i).append(": bra t").append(i).append('\n');
        // in the first layout every branch is .s; branch i's distance is 128 - 2 * (n - i)
        int pos = 2 * n;
        for (int i = 1; i <= n; i++) {
            int target = 2 * (i - 1) + 2 + 128 - 2 * (n - i);
            if (target > pos)
                src.append(" ds.b ").append(target - pos).append('\n');
            pos = target;
            src.append("t").append(i).append(":\n");
        }
        src.append(" nop\n");
        AsmResult r = asm(src.toString(), 0x1000);
        assertTrue(r.ok(), () -> "valid program must assemble: " + r.diagnostics());
    }

    /**
     * Spec 10.7.3: every instruction and word datum is aligned to an even address (padded with
     * one zero byte). The pad is decided from the section offset, assuming the section starts at
     * an even address; a text base that is odd (AsmOptions accepts any) puts every instruction
     * at an odd address with no pad and no diagnostic.
     */
    @Test
    void oddTextBaseLeavesInstructionsAtOddAddresses() {
        AsmResult r = asm("start:  nop\n", 0x1001);
        if (!r.ok())
            return;   // rejecting an odd base is also acceptable
        int start = symbol(r.image(), "start");
        assertEquals(0, start & 1, "the first instruction must be at an even address, got 0x" + Integer.toHexString(start));
    }

    /**
     * Spec 10.7.3: "A label just before a padded statement names the padded (even) address." A
     * backend directive such as {@code .cpu} between the label and the instruction makes the
     * framework drop the pending labels, so the label keeps the odd address and a branch or call
     * to it goes to the pad byte (an address error on the 68000).
     */
    @Test
    void labelBeforeCpuDirectiveKeepsTheOddAddress() {
        String src = """
                        dc.b    1
                func:
                        .cpu    68040
                        nop
                """;
        AsmResult r = asm(src, 0x1000);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        assertEquals(0x1002, symbol(r.image(), "func"), "func must name the padded nop at 0x1002");
    }

    // ---- saved state ----

    private static Machine running(CpuModel model, int hz) {
        Machine m = new Machine(MachineConfig.of(model, hz, 1 << 20, null), StandardCpus.INSTANCE);
        m.powerOn();
        for (int i = 0; i < 3; i++)
            m.runTick(Machine.TICK_NANOS, 10_000_000_000L);
        return m;
    }

    /** {@code state} with the bytes at {@code drop} removed, version {@code version} and a fresh CRC. */
    private static byte[] downgrade(byte[] state, int version, int... drop) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < state.length - 4; i++) {
            boolean skip = false;
            for (int d : drop)
                skip |= d == i;
            if (!skip)
                out.write(state[i]);
        }
        byte[] body = out.toByteArray();
        body[4] = 0;
        body[5] = 0;
        body[6] = 0;
        body[7] = (byte) version;
        CRC32 crc = new CRC32();
        crc.update(body);
        int c = (int) crc.getValue();
        byte[] all = new byte[body.length + 4];
        System.arraycopy(body, 0, all, 0, body.length);
        all[body.length] = (byte) (c >>> 24);
        all[body.length + 1] = (byte) (c >>> 16);
        all[body.length + 2] = (byte) (c >>> 8);
        all[body.length + 3] = (byte) c;
        return all;
    }

    /**
     * Spec 1.5 and 11.1.8: envelope version 2 added the strict FPU flag and 3 the pending device
     * reset; a version 1 state was once misparsed as corrupt instead of loaded or refused. Version
     * 4 is the in-game board, whose memory map changed under every older state, so versions 1 to
     * 3 are now refused as such and the game cold-boots the PC.
     */
    @Test
    void statesFromBeforeTheInGameBoardAreRefused() {
        Machine source = running(CpuModel.RV32I, 10_000_000);
        byte[] v4 = source.saveState();
        Machine target = new Machine(MachineConfig.of(CpuModel.RV32I, 10_000_000, 1 << 20, null), StandardCpus.INSTANCE);
        for (int v = 1; v <= 3; v++) {
            byte[] old = downgrade(v4, v);
            StateFormatException e = assertThrows(StateFormatException.class, () -> target.loadState(old));
            assertTrue(e.getMessage().contains("before the in-game board"), "version " + v + ": " + e.getMessage());
        }
        assertEquals(MachineState.OFF, target.state());
    }

    // ---- 68000 machine ----

    /**
     * Spec 10.8.1: "Guest code written with the 32-bit constants runs on both", and SysCon
     * reports VRAM_BASE as 0xFFE0_0000 on both machines ("the 68000 masks it"). The CPU masks its
     * own accesses, but the HDD controller's DMA_ADDR is a bus address the 68000 machine maps at
     * 24 bits: a READ into VRAM at the advertised 0xFFE0_0000 fails with ERROR_CODE 3 (DMA range)
     * on the 68000 and works on the 68040.
     */
    @Test
    void hddDmaToTheAdvertisedVramBaseFailsOnThe68000() {
        for (CpuModel model : new CpuModel[] {CpuModel.CPU_68040, CpuModel.CPU_68000}) {
            int hz = model == CpuModel.CPU_68040 ? 25_000_000 : 8_000_000;
            Machine m = new Machine(MachineConfig.of(model, hz, 1 << 20, new SparseDiskImage(1 << 20)),
                    StandardCpus.INSTANCE);
            m.powerOn();
            HddController hdd = m.board().hdd();
            int vramBase = m.board().sysCon().read(SysCon.VRAM_BASE, 4);
            assertEquals(0xFFE0_0000, vramBase, "precondition: SysCon advertises the 32-bit VRAM base");
            hdd.write(HddController.LBA, 4, 0);
            hdd.write(HddController.COUNT, 4, 1);
            hdd.write(HddController.DMA_ADDR, 4, vramBase);
            hdd.write(HddController.COMMAND, 4, HddController.CMD_READ);
            assertEquals(HddController.ERR_NONE, hdd.read(HddController.ERROR_CODE, 4),
                    "the " + model.displayName() + " guest's READ to VRAM at 0xFFE00000 must be accepted");
        }
    }
}
