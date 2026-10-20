package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.cpu.m68k.FpuOpcodes;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kOptions;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kVectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared 68k types written before the core, FPU and assembler (spec 10.9). */
class M68kFoundationTest {

    @Test
    void strictFpuIsAConfigOptionOnlyTheMachineWithA68040Takes() {
        MachineConfig plain = new MachineConfig(CpuModel.CPU_68040, 25_000_000, 64 * 1024, null, new byte[4], null, null);
        assertFalse(plain.strictFpu());
        assertTrue(new MachineConfig(CpuModel.CPU_68040, 25_000_000, 64 * 1024, null, new byte[4], null, null, true).strictFpu());
        assertThrows(IllegalArgumentException.class,
                () -> new MachineConfig(CpuModel.CPU_68000, 8_000_000, 64 * 1024, null, new byte[4], null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineConfig(CpuModel.RV32I, 10_000_000, 64 * 1024, null, new byte[4], null, null, true));
    }

    @Test
    void optionsAcceptOnly68kModels() {
        assertFalse(M68kOptions.m68000().hasFpu());
        assertTrue(M68kOptions.m68040(true).strictFpu());
        assertThrows(IllegalArgumentException.class, () -> new M68kOptions(CpuModel.CPU_68000, true));
        assertThrows(IllegalArgumentException.class, () -> new M68kOptions(CpuModel.RV32I, false));
    }

    @Test
    void sharedTables() {
        assertEquals(32, FpuOpcodes.PREDICATES.length);
        assertEquals("sf", FpuOpcodes.PREDICATES[FpuOpcodes.PREDICATE_SIGNALING]);
        assertEquals(12, FpuOpcodes.formatBytes(FpuOpcodes.FORMAT_P_DYNAMIC));
        assertEquals(60, M68kVectors.frameBytes(M68kVectors.FORMAT_7));
        assertEquals(-1, M68kVectors.frameBytes(5));
        assertEquals(0xF0_0000, M68kMap.DEVICE_BASE & M68kMap.ADDRESS_MASK_68000);
        assertEquals(0xFC_0000, M68kMap.ROM_BASE & M68kMap.ADDRESS_MASK_68000);
        assertEquals(0xE0_0000, M68kMap.VRAM_BASE & M68kMap.ADDRESS_MASK_68000);
        assertEquals(M68kMap.RAM_MAX_68000, M68kMap.VRAM_REGION_BASE & M68kMap.ADDRESS_MASK_68000);
    }
}
