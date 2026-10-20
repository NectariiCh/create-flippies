package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Clint;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.InterruptController;
import com.nectariich.create_flippies.content.computer.device.IntervalTimer;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Rtc;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import java.nio.ByteOrder;
import java.util.Arrays;

import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.*;

/**
 * The RV32I machine (spec 4): the memory map of {@link Rv32Map}, every device in a 4 KiB window
 * (the CLINT in 64 KiB), CLINT MSIP and MTIP on pins 3 and 7, interrupt controller output 0 on
 * pin 11.
 */
public final class Rv32Platform implements Platform {

    public static final Rv32Platform INSTANCE = new Rv32Platform();

    private Rv32Platform() {
    }

    @Override
    public CpuModel model() {
        return CpuModel.RV32I;
    }

    @Override
    public void validate(MachineConfig config) {
        if (config.cpu() != CpuModel.RV32I)
            throw new IllegalArgumentException("the RV32I platform cannot run a " + config.cpu().displayName());
        int hz = config.clockHz();
        if (hz < CLOCK_MIN_HZ || hz > CLOCK_MAX_HZ || hz % 1_000_000 != 0)
            throw new IllegalArgumentException("RV32I clock must be a whole number of MHz from 1 to 100 MHz: " + hz + " Hz");
        int ram = config.ramBytes();
        if (ram < RAM_MIN || ram > RAM_MAX)
            throw new IllegalArgumentException("RV32I RAM must be from 64 KiB to 128 MiB: " + ram + " bytes");
        if (config.bootRom().length > ROM_SIZE)
            throw new IllegalArgumentException("boot ROM of " + config.bootRom().length
                    + " bytes does not fit the " + ROM_SIZE + "-byte ROM window");
    }

    @Override
    public int maxRamBytes() {
        return RAM_MAX;
    }

    @Override
    public int defaultResetPc() {
        return RESET_PC;
    }

    @Override
    public Board build(MachineConfig config, CycleClock clock) {
        int hz = config.clockHz();
        byte[] ram = new byte[config.ramBytes()];
        byte[] rom = Arrays.copyOf(config.bootRom(), ROM_SIZE);
        byte[] vram = new byte[VRAM_SIZE];
        long[] dirtyPages = new long[VRAM_SIZE / (64 * 4096)];

        InterruptController pic = new InterruptController();
        int[] vgaLines = { LINE_VGA, LINE_VGA1, LINE_VGA2 };
        VgaDisplay[] vga = new VgaDisplay[3];
        for (int n = 0; n < 3; n++)
            vga[n] = new VgaDisplay(pic.line(vgaLines[n]), vram, vramDisplayOffset(n), dirtyPages);
        SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        bus.setRom(rom, ROM_BASE);
        bus.setRam(ram, RAM_BASE);
        bus.setVram(vram, VRAM_BASE, dirtyPages);

        SysCon sysCon = new SysCon(clock, RAM_BASE, ram.length, hz, vramDisplayBase(0), vramDisplayBase(1),
                vramDisplayBase(2), VRAM_DISPLAY_SIZE);
        Clint clint = new Clint(clock, hz);
        IntervalTimer timer = new IntervalTimer(clock, hz, pic.line(LINE_TIMER));
        Rtc rtc = new Rtc(config.rtcEpochSeconds());
        Uart16550[] com = {
                new Uart16550(clock, hz, pic.line(LINE_COM1)),
                new Uart16550(clock, hz, pic.line(LINE_COM2)),
                new Uart16550(clock, hz, pic.line(LINE_COM3)),
        };
        Ps2Controller ps2 = new Ps2Controller(clock, hz, pic.line(LINE_KEYBOARD), pic.line(LINE_MOUSE));
        HddController hdd = new HddController(clock, hz, bus, pic.line(LINE_HDD));
        hdd.setDisk(config.disk());
        FloppyController fdc = new FloppyController(clock, hz, bus, pic.line(LINE_FDC));
        Nic nic = new Nic(bus, pic.line(LINE_NIC), config.macAddress());
        sysCon.setPresentSource(Platform.presentBits(hdd, ps2, com, fdc, nic));

        bus.mapDevice(SYSCON_BASE, DEVICE_WINDOW, sysCon);
        bus.mapDevice(CLINT_BASE, CLINT_SIZE, clint);
        bus.mapDevice(PIC_BASE, DEVICE_WINDOW, pic);
        bus.mapDevice(COM1_BASE, DEVICE_WINDOW, com[0]);
        bus.mapDevice(COM2_BASE, DEVICE_WINDOW, com[1]);
        bus.mapDevice(PS2_BASE, DEVICE_WINDOW, ps2);
        bus.mapDevice(HDD_BASE, DEVICE_WINDOW, hdd);
        bus.mapDevice(VGA_BASE, DEVICE_WINDOW, vga[0]);
        bus.mapDevice(TIMER_BASE, DEVICE_WINDOW, timer);
        bus.mapDevice(RTC_BASE, DEVICE_WINDOW, rtc);
        bus.mapDevice(COM3_BASE, DEVICE_WINDOW, com[2]);
        bus.mapDevice(FDC_BASE, DEVICE_WINDOW, fdc);
        bus.mapDevice(NIC_BASE, DEVICE_WINDOW, nic);
        bus.mapDevice(VGA1_BASE, DEVICE_WINDOW, vga[1]);
        bus.mapDevice(VGA2_BASE, DEVICE_WINDOW, vga[2]);

        return new Board(bus, null, ram, sysCon, pic, timer, rtc, com, ps2, vga, hdd, fdc, nic, clint);
    }

    @Override
    public void wirePins(Board board, Cpu cpu) {
        board.clint().setOutputs(level -> cpu.setIrq(IRQ_PIN_MSIP, level),
                level -> cpu.setIrq(IRQ_PIN_MTIP, level));
        board.pic().setOutput(0, level -> cpu.setIrq(IRQ_PIN_MEIP, level));
    }

    @Override
    public boolean anyPinAsserted(Board board) {
        Clint clint = board.clint();
        return board.pic().output(0) || clint.msip() || clint.mtip();
    }
}
