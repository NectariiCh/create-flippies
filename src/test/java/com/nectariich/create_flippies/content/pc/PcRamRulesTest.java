package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Platform;

import org.junit.jupiter.api.Test;

class PcRamRulesTest {

    private static final long MIB = 1024 * 1024;

    private static int usable(CpuModel cpu, PcRamType a, PcRamType b) {
        return PcRamRules.usableBytes(PcRamRules.installedBytes(a, b), Platform.forModel(cpu).maxRamBytes());
    }

    @Test
    void usableRamPerCpu() {
        assertEquals(17 * MIB, usable(CpuModel.RV32I, PcRamType.RAM_16M, PcRamType.RAM_1M));
        assertEquals(512 * 1024, usable(CpuModel.CPU_68000, PcRamType.RAM_512K, null));
        assertEquals(0, usable(CpuModel.RV32I, null, null));
    }

    @Test
    void the68000IsCappedByItsBus() {
        // 13.5 MiB below the VRAM range of a 24-bit bus
        assertEquals(13.5 * MIB, usable(CpuModel.CPU_68000, PcRamType.RAM_16M, null), 0);
        assertEquals(13.5 * MIB, usable(CpuModel.CPU_68000, PcRamType.RAM_64M, PcRamType.RAM_64M), 0);
    }

    @Test
    void twoBigSticksAreFullyUsableOnRv32iAndThe68040() {
        assertEquals(128 * MIB, usable(CpuModel.RV32I, PcRamType.RAM_64M, PcRamType.RAM_64M));
        assertEquals(128 * MIB, usable(CpuModel.CPU_68040, PcRamType.RAM_64M, PcRamType.RAM_64M));
    }

    @Test
    void usableIsWholePages() {
        assertEquals(0, PcRamRules.usableBytes(10 * MIB + 100, Integer.MAX_VALUE) % PcRamRules.PAGE);
    }

    @Test
    void totalRamCapRefusesAPowerOn() {
        assertTrue(PcRamRules.fitsTotal(1920 * MIB, 128 * MIB, 2048));
        assertFalse(PcRamRules.fitsTotal(1921 * MIB, 128 * MIB, 2048));
        assertFalse(PcRamRules.fitsTotal(0, 128 * MIB, 64));
    }

    @Test
    void installedAndUsableReport() {
        assertEquals("16 MiB", PcRamRules.report(16 * MIB, (int) (16 * MIB)));
        assertEquals("installed 16 MiB, usable 13.5 MiB", PcRamRules.report(16 * MIB, (int) (13.5 * MIB)));
        assertEquals("512 KiB", PcRamRules.format(512 * 1024));
    }
}
