package com.nectariich.create_flippies.content.computer.machine;

import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Clint;
import com.nectariich.create_flippies.content.computer.device.IntervalTimer;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The machine loop with a scripted fake CPU (spec 1.4, 1.5, 7.5, 8.3), at 10 MHz: 500 000 cycles per tick. */
class MachineTest {

    private static final long TICK_CYCLES = 500_000;
    private static final long BIG_BUDGET = 5_000_000_000L;

    private FakeCpu cpu;

    private static MachineConfig config() {
        return config(10_000_000, 64 * 1024, null);
    }

    private static MachineConfig config(int hz, int ram, Integer resetPc) {
        return new MachineConfig(CpuModel.RV32I, hz, ram, null, new byte[] { 0x13, 0, 0, 0 }, () -> 0L, resetPc);
    }

    private Machine machine(List<FakeCpu.Step> program) {
        return machine(config(), program);
    }

    private Machine machine(MachineConfig config, List<FakeCpu.Step> program) {
        Machine m = new Machine(config, host -> cpu = new FakeCpu(host, program, 1));
        m.powerOn();
        return m;
    }

    private static List<FakeCpu.Step> program(FakeCpu.Step... steps) {
        return new ArrayList<>(Arrays.asList(steps));
    }

    @Test
    void aNewMachineIsOffUntilPowerOn() {
        Machine m = new Machine(config(), host -> cpu = new FakeCpu(host, program(), 1));
        assertEquals(MachineState.OFF, m.state());
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(0, m.clock().now());
        assertTrue(cpu.runUntil.isEmpty());
        m.powerOn();
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(1, cpu.resets);
    }

    @Test
    void aTickIsWorthClockOverTwentyCycles() {
        Machine m = machine(program());
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(TICK_CYCLES, m.clock().now());
        assertEquals(TICK_CYCLES, cpu.instructionsRetired());
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(2 * TICK_CYCLES, m.clock().now());
        assertEquals(1.0, m.speed());
    }

    @Test
    void aWindowLongerThanTheProductRangeDoesNotOverflow() {
        Machine m = machine(config(100_000_000, 64 * 1024, null), program((c, bus) -> c.waitForInterrupt()));
        m.runTick(200_000_000_000L, BIG_BUDGET);
        assertEquals(20_000_000_000L, m.clock().now());
    }

    @Test
    void theCycleRemainderCarriesAcrossTicks() {
        Machine m = machine(program());
        for (int i = 0; i < 99; i++)
            m.runTick(1, BIG_BUDGET);
        assertEquals(0, m.clock().now(), "99 ns at 10 MHz is under one cycle");
        m.runTick(1, BIG_BUDGET);
        assertEquals(1, m.clock().now());
    }

    @Test
    void segmentsEndAtDeviceEvents() {
        Machine m = machine(program());
        m.bus().write32(TIMER_BASE + IntervalTimer.PERIOD, 100);
        m.bus().write32(TIMER_BASE + IntervalTimer.CTRL, IntervalTimer.CTRL_ENABLE | IntervalTimer.CTRL_IRQ_ENABLE);
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(1000L, cpu.runUntil.get(0), "the first segment ends at the timer's expiry");
        assertEquals(1000L, cpu.runReturnedAt.get(0));
        assertTrue((m.board().pic().read(0, 4) & 1 << LINE_TIMER) != 0, "line 7 raised at the segment end");
        assertEquals(TICK_CYCLES, m.clock().now());
    }

    @Test
    void segmentsEndWhereADeviceAsksDuringARun() {
        Machine m = machine(program(
                (c, bus) -> { },
                (c, bus) -> {
                    bus.write32(TIMER_BASE + IntervalTimer.PERIOD, 10);
                    bus.write32(TIMER_BASE + IntervalTimer.CTRL, IntervalTimer.CTRL_ENABLE | IntervalTimer.CTRL_IRQ_ENABLE);
                }));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(TICK_CYCLES, (long) cpu.runUntil.get(0), "no event known before the run");
        assertEquals(100L, cpu.runReturnedAt.get(0), "stopped at the expiry step 1 asked for: 10 us from microsecond 0");
        assertEquals(TICK_CYCLES, m.clock().now());
    }

    @Test
    void aWaitingCpuFastForwardsWithoutHostTime() {
        Machine m = machine(program((c, bus) -> c.waitForInterrupt()));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(TICK_CYCLES, m.clock().now());
        assertEquals(1, cpu.instructionsRetired());
        m.runTick(Machine.TICK_NANOS, 0);
        assertEquals(2 * TICK_CYCLES, m.clock().now(), "a sleeping PC keeps full speed on no budget");
        assertEquals(1.0, m.speed());
    }

    @Test
    void wfiWakesAtTheDeviceEventItWaitsFor() {
        Machine m = machine(program(
                (c, bus) -> {
                    bus.write32(CLINT_BASE + Clint.MTIMECMP_LO, 100);
                    bus.write32(CLINT_BASE + Clint.MTIMECMP_HI, 0);
                },
                (c, bus) -> c.waitForInterrupt(),
                (c, bus) -> { }));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(List.of("7+@1000"), cpu.pinLog, "MTIP at exactly 100 us");
        assertEquals(1000L, cpu.executedAt.get(2), "the CPU runs again at the wake-up, not at the tick end");
    }

    @Test
    void sysConWaitParksTheCpuUntilAPinRises() {
        Machine m = machine(program(
                (c, bus) -> {
                    bus.write32(CLINT_BASE + Clint.MTIMECMP_LO, 100);
                    bus.write32(CLINT_BASE + Clint.MTIMECMP_HI, 0);
                },
                (c, bus) -> bus.write32(SYSCON_BASE + SysCon.WAIT, 1),
                (c, bus) -> { }));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(List.of(0, 1, 2), cpu.executed.subList(0, 3));
        assertEquals(1000L, cpu.executedAt.get(2), "step 2 runs only once MTIP rose at 1000");
        assertFalse(m.waitingForInterrupt());
    }

    @Test
    void sysConWaitWithNoInterruptSleepsThroughTheTick() {
        Machine m = machine(program((c, bus) -> bus.write32(SYSCON_BASE + SysCon.WAIT, 1)));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(1, cpu.instructionsRetired());
        assertTrue(m.waitingForInterrupt());
        assertEquals(TICK_CYCLES, m.clock().now());
        assertEquals(1, cpu.runUntil.size(), "no CPU run while parked");
    }

    @Test
    void aPastDeadlineDilatesTime() {
        Machine m = machine(program());
        m.runTick(Machine.TICK_NANOS, 0);
        long ran = m.clock().now();
        assertTrue(ran > 0, "progress every tick");
        assertTrue(ran < TICK_CYCLES, "the rest of the tick was dropped");
        assertTrue(m.speed() < 1.0);
        m.runTick(Machine.TICK_NANOS, 0);
        assertTrue(m.clock().now() > ran);
    }

    @Test
    void guestPowerOffStopsAfterTheWritingInstruction() {
        Machine m = machine(program(
                (c, bus) -> bus.write32(SYSCON_BASE + SysCon.POWER, 0x3333 | 5 << 16),
                (c, bus) -> { throw new AssertionError("ran after power off"); }));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(MachineState.OFF_BY_GUEST, m.state());
        assertEquals(5, m.exitCode());
        assertEquals(1, cpu.instructionsRetired());
        long now = m.clock().now();
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(now, m.clock().now(), "an off machine does not run");
    }

    @Test
    void guestResetTakesEffectAfterTheWritingInstructionAndKeepsRam() {
        boolean[] didReset = { false };
        Machine m = machine(program(
                (c, bus) -> bus.write32(RAM_BASE, 0xCAFE),
                (c, bus) -> {
                    if (!didReset[0]) {
                        didReset[0] = true;
                        bus.write32(SYSCON_BASE + SysCon.POWER, 0x7777);
                    }
                },
                (c, bus) -> { }));
        m.bus().write32(TIMER_BASE + IntervalTimer.PERIOD, 999);
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(List.of(0, 1, 0, 1, 2), cpu.executed.subList(0, 5), "nothing ran between the reset write and the reset");
        assertEquals(2, cpu.resets);
        assertEquals(0xCAFE, m.bus().read32(RAM_BASE));
        assertEquals(0, m.bus().read32(TIMER_BASE + IntervalTimer.PERIOD), "devices were reset");
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(TICK_CYCLES, m.clock().now(), "the clock keeps running through a reset");
    }

    @Test
    void hostResetKeepsRamAndPowerOnClearsIt() {
        Machine m = machine(program());
        m.loadRam(RAM_BASE + 16, new byte[] { 1, 2, 3 });
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        m.reset();
        assertEquals(TICK_CYCLES, m.clock().now());
        assertEquals(0x030201, m.bus().read32(RAM_BASE + 16));
        m.powerOn();
        assertEquals(0, m.clock().now());
        assertEquals(0, m.bus().read32(RAM_BASE + 16));
        m.powerOff();
        assertEquals(MachineState.OFF, m.state());
    }

    @Test
    void endFrameOncePerTick() {
        Machine m = machine(program());
        for (int i = 0; i < 3; i++)
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(3, m.vga().frame());
        assertTrue(m.vga().generation() >= 1);
    }

    @Test
    void aHaltedCpuLeavesIdleTimeAndDevicesStillAdvance() {
        Machine m = machine(program(
                (c, bus) -> {
                    bus.write8(COM1_BASE + Uart16550.LCR, 3);
                    bus.write8(COM1_BASE + Uart16550.THR, 'k');
                },
                (c, bus) -> c.halt()));
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(MachineState.HALTED, m.state());
        assertEquals(TICK_CYCLES, m.clock().now());
        byte[] out = new byte[4];
        assertEquals(1, m.com(0).hostRead(out, 0, 4), "the UART drained while the CPU was halted");
        assertEquals('k', out[0]);
        m.reset();
        assertEquals(MachineState.RUNNING, m.state());
    }

    @Test
    void interruptControllerOutputDrivesPin11() {
        Machine m = machine(program());
        m.bus().write32(PIC_BASE + 4, 1 << LINE_TIMER);
        m.bus().write32(TIMER_BASE + IntervalTimer.PERIOD, 50);
        m.bus().write32(TIMER_BASE + IntervalTimer.CTRL, IntervalTimer.CTRL_ENABLE | IntervalTimer.CTRL_IRQ_ENABLE);
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertTrue(cpu.pins[IRQ_PIN_MEIP]);
        assertEquals("11+@500", cpu.pinLog.get(0));
    }

    @Test
    void resetPcIsHonoured() {
        machine(program());
        assertEquals(RESET_PC, cpu.host.resetPc());
        assertEquals(CpuModel.RV32I, cpu.host.model());
        assertEquals(10_000_000, cpu.host.clockHz());
        machine(config(10_000_000, 64 * 1024, RAM_BASE), program());
        assertEquals(RAM_BASE, cpu.host.resetPc());
    }

    @Test
    void forModelThrowsForEveryCpuWithoutAPlatform() {
        for (CpuModel model : CpuModel.values()) {
            if (model == CpuModel.RV32I) {
                assertTrue(Platform.forModel(model) instanceof Rv32Platform);
                continue;
            }
            if (model == CpuModel.CPU_68000 || model == CpuModel.CPU_68040) {
                assertTrue(Platform.forModel(model) instanceof M68kPlatform);
                assertEquals(model, Platform.forModel(model).model());
                continue;
            }
            assertThrows(UnsupportedOperationException.class, () -> Platform.forModel(model), model.name());
            MachineConfig c = new MachineConfig(model, 8_000_000, 64 * 1024, null, new byte[4], null, null);
            assertThrows(UnsupportedOperationException.class, () -> new Machine(c, host -> null), model.name());
        }
    }

    @Test
    void rv32ValidateRejectsWhatThePlatformCannotRun() {
        Rv32Platform p = Rv32Platform.INSTANCE;
        p.validate(config());
        p.validate(config(100_000_000, 128 * 1024 * 1024, null));
        assertThrows(IllegalArgumentException.class, () -> p.validate(config(4_772_727, 64 * 1024, null)));
        assertThrows(IllegalArgumentException.class, () -> p.validate(config(200_000_000, 64 * 1024, null)));
        assertThrows(IllegalArgumentException.class, () -> p.validate(config(10_000_000, 128 * 1024 * 1024 + 4096, null)));
        assertThrows(IllegalArgumentException.class, () -> p.validate(config(10_000_000, 60 * 1024, null)));
        assertThrows(IllegalArgumentException.class, () -> p.validate(new MachineConfig(CpuModel.RV32I,
                10_000_000, 64 * 1024, null, new byte[ROM_SIZE + 1], null, null)));
        assertThrows(IllegalArgumentException.class, () -> new Machine(config(4_772_727, 64 * 1024, null), host -> null));
    }

    @Test
    void machineConfigChecksTheBasics() {
        assertThrows(NullPointerException.class, () -> new MachineConfig(null, 1, 4096, null, new byte[1], null, null));
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig(CpuModel.RV32I, 0, 4096, null, new byte[1], null, null));
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig(CpuModel.RV32I, 1, 4095, null, new byte[1], null, null));
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig(CpuModel.RV32I, 1, 4096, null, new byte[0], null, null));
    }

    @Test
    void loadRamChecksTheRange() {
        Machine m = machine(program());
        m.loadRam(RAM_BASE + 64 * 1024 - 4, new byte[] { 9, 9, 9, 9 });
        assertEquals(0x0909_0909, m.bus().read32(RAM_BASE + 64 * 1024 - 4));
        assertThrows(IllegalArgumentException.class, () -> m.loadRam(RAM_BASE + 64 * 1024 - 2, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> m.loadRam(0x1000, new byte[4]));
    }

    @Test
    void theRv32MemoryMap() {
        MachineConfig c = new MachineConfig(CpuModel.RV32I, 10_000_000, 128 * 1024,
                new SparseDiskImage(4 * 1024 * 1024), new byte[] { 0x37, 0x05, 0, 0 }, () -> 7L, null);
        Machine m = machine(c, program());
        var bus = m.bus();
        assertEquals(0x0537, bus.read16(ROM_BASE));
        assertEquals(0, bus.read32(ROM_BASE + ROM_SIZE - 4), "ROM padded to its window");
        assertEquals(ROM_SIZE, bus.romSize());
        assertEquals(RAM_BASE, bus.ramBase());
        assertEquals(128 * 1024, bus.ramSize());
        assertEquals(VRAM_BASE, bus.vramBase());
        assertEquals(VRAM_SIZE, bus.vramSize());
        assertEquals(SysCon.MACHINE_ID_VALUE, bus.read32(SYSCON_BASE + SysCon.MACHINE_ID));
        assertEquals(128 * 1024, bus.read32(SYSCON_BASE + SysCon.RAM_SIZE));
        assertEquals(10_000_000, bus.read32(SYSCON_BASE + SysCon.CPU_HZ));
        assertEquals(0b1, bus.read32(SYSCON_BASE + SysCon.PRESENT), "disk; nothing in the PS/2 ports, no COM host");
        m.ps2().setDevice(0, Ps2DeviceKind.KEYBOARD);
        m.ps2().setDevice(1, Ps2DeviceKind.MOUSE);
        assertEquals(0b111, bus.read32(SYSCON_BASE + SysCon.PRESENT), "disk, keyboard, mouse; no COM host");
        m.com(1).setHostConnected(true);
        assertEquals(0b10111, bus.read32(SYSCON_BASE + SysCon.PRESENT));
        assertEquals(0xFFFF_FFFF, bus.read32(CLINT_BASE + Clint.MTIMECMP_LO));
        assertEquals(8, bus.read32(PIC_BASE + 0x0C));
        assertEquals(0x60, bus.read8(COM1_BASE + Uart16550.LSR));
        assertEquals(0x60, bus.read8(COM2_BASE + Uart16550.LSR));
        assertEquals(0xAA, bus.read8(PS2_BASE), "keyboard power-on byte");
        assertEquals(0xAA, bus.read8(PS2_BASE + 0x10), "mouse power-on byte");
        assertEquals(8192, bus.read32(HDD_BASE + 0x14), "4 MiB in sectors");
        assertEquals(0xD, bus.read32(VGA_BASE + 4));
        assertEquals(0, bus.read32(TIMER_BASE + 8));
        assertEquals(7, bus.read32(RTC_BASE));
        assertThrows(com.nectariich.create_flippies.content.computer.bus.BusFault.class, () -> bus.read32(0));
        assertThrows(com.nectariich.create_flippies.content.computer.bus.BusFault.class, () -> bus.read32(0x4000_0000));
        assertArrayEquals(m.board().ram(), bus.ramArray());
        assertEquals(m.vga().dirtyPages(), bus.vramDirtyPages());
        assertTrue(m.hdd().mediaPresent());
    }
}
