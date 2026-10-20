package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
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

import static com.nectariich.create_flippies.content.computer.machine.M68kMap.*;

/**
 * The 68000 and 68040 machines (spec 10.8): the memory map of {@link M68kMap}, no CLINT,
 * interrupt controller output k on IPL pin k and output 0 also on pin 4, and the boot ROM
 * mirrored at address 0 after reset and power-on until the guest writes ROM_OVERLAY_OFF.
 *
 * One class, two instances. The bus is mapped at the CPU's own view of each address: the 68000
 * drives 24 address lines, so its machine maps every region at {@code address & 0xFF_FFFF}, and
 * guest code written with the 32-bit constants runs on both (the 68000 masks them on the way
 * out). SysCon reports the 32-bit VRAM bases on both. The three displays share one 1.5 MiB VRAM
 * range below display 0's old base (spec 11.1.1).
 */
public final class M68kPlatform implements Platform {

    public static final M68kPlatform M68000 = new M68kPlatform(CpuModel.CPU_68000, ADDRESS_MASK_68000,
            CLOCK_68000_MIN_HZ, CLOCK_68000_MAX_HZ, RAM_MAX_68000);
    public static final M68kPlatform M68040 = new M68kPlatform(CpuModel.CPU_68040, -1,
            CLOCK_68040_MIN_HZ, CLOCK_68040_MAX_HZ, RAM_MAX_68040);

    private final CpuModel model;
    /** What the CPU drives onto the bus: 24 bits on the 68000, all 32 on the 68040. */
    private final int addressMask;
    private final int clockMinHz;
    private final int clockMaxHz;
    private final int ramMax;

    private M68kPlatform(CpuModel model, int addressMask, int clockMinHz, int clockMaxHz, int ramMax) {
        this.model = model;
        this.addressMask = addressMask;
        this.clockMinHz = clockMinHz;
        this.clockMaxHz = clockMaxHz;
        this.ramMax = ramMax;
    }

    @Override
    public CpuModel model() {
        return model;
    }

    @Override
    public void validate(MachineConfig config) {
        String name = model.displayName();
        if (config.cpu() != model)
            throw new IllegalArgumentException("the " + name + " platform cannot run a " + config.cpu().displayName());
        int hz = config.clockHz();
        if (hz < clockMinHz || hz > clockMaxHz)
            throw new IllegalArgumentException(name + " clock must be from " + clockMinHz + " to " + clockMaxHz
                    + " Hz: " + hz + " Hz");
        int ram = config.ramBytes();
        if (ram < RAM_MIN || ram > ramMax)
            throw new IllegalArgumentException(name + " RAM must be from 64 KiB to " + (ramMax >> 10) + " KiB: "
                    + ram + " bytes");
        if (config.bootRom().length > ROM_SIZE)
            throw new IllegalArgumentException("boot ROM of " + config.bootRom().length
                    + " bytes does not fit the " + ROM_SIZE + "-byte ROM window");
    }

    @Override
    public int maxRamBytes() {
        return ramMax;
    }

    /** Unused: the CPU reads its reset PC from the vector at address 4 (the ROM overlay). */
    @Override
    public int defaultResetPc() {
        return 0;
    }

    /** Reset and power-on mirror the ROM at 0, so the CPU reads its reset vectors from ROM (spec 1.5). */
    @Override
    public boolean romOverlayAtReset() {
        return true;
    }

    @Override
    public Board build(MachineConfig config, CycleClock clock) {
        int hz = config.clockHz();
        byte[] ram = new byte[config.ramBytes()];
        byte[] rom = Arrays.copyOf(config.bootRom(), ROM_SIZE);
        byte[] vram = new byte[VRAM_REGION_SIZE];
        long[] dirtyPages = new long[VRAM_REGION_SIZE / (64 * 4096)];

        InterruptController pic = new InterruptController();
        int[] vgaLines = { LINE_VGA, LINE_VGA1, LINE_VGA2 };
        VgaDisplay[] vga = new VgaDisplay[3];
        for (int n = 0; n < 3; n++)
            vga[n] = new VgaDisplay(pic.line(vgaLines[n]), vram, vramDisplayOffset(n), dirtyPages);
        SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
        bus.setRom(rom, ROM_BASE & addressMask);
        bus.setRam(ram, RAM_BASE);
        bus.setVram(vram, VRAM_REGION_BASE & addressMask, dirtyPages);
        bus.setRomOverlay(true);
        // device DMA addresses go through the same 24 address lines on the 68000 (spec 10.8.1)
        bus.setDmaAddressMask(addressMask);

        SysCon sysCon = new SysCon(clock, RAM_BASE, ram.length, hz, vramDisplayBase(0), vramDisplayBase(1),
                vramDisplayBase(2), VRAM_DISPLAY_SIZE);
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

        bus.mapDevice(SYSCON_BASE & addressMask, DEVICE_WINDOW, sysCon);
        bus.mapDevice(PIC_BASE & addressMask, DEVICE_WINDOW, pic);
        bus.mapDevice(COM1_BASE & addressMask, DEVICE_WINDOW, com[0]);
        bus.mapDevice(COM2_BASE & addressMask, DEVICE_WINDOW, com[1]);
        bus.mapDevice(PS2_BASE & addressMask, DEVICE_WINDOW, ps2);
        bus.mapDevice(HDD_BASE & addressMask, DEVICE_WINDOW, hdd);
        bus.mapDevice(VGA_BASE & addressMask, DEVICE_WINDOW, vga[0]);
        bus.mapDevice(TIMER_BASE & addressMask, DEVICE_WINDOW, timer);
        bus.mapDevice(RTC_BASE & addressMask, DEVICE_WINDOW, rtc);
        bus.mapDevice(COM3_BASE & addressMask, DEVICE_WINDOW, com[2]);
        bus.mapDevice(FDC_BASE & addressMask, DEVICE_WINDOW, fdc);
        bus.mapDevice(NIC_BASE & addressMask, DEVICE_WINDOW, nic);
        bus.mapDevice(VGA1_BASE & addressMask, DEVICE_WINDOW, vga[1]);
        bus.mapDevice(VGA2_BASE & addressMask, DEVICE_WINDOW, vga[2]);

        return new Board(bus, null, ram, sysCon, pic, timer, rtc, com, ps2, vga, hdd, fdc, nic, null);
    }

    /**
     * Output k (1..7) drives IPL pin k; pin 4 is output 0 OR output 4 (spec 10.8.2), so both
     * outputs re-drive it from the controller's current levels.
     */
    @Override
    public void wirePins(Board board, Cpu cpu) {
        InterruptController pic = board.pic();
        for (int k = 1; k < InterruptController.OUTPUTS; k++) {
            if (k == IRQ_PIN_OUTPUT_0)
                continue;
            int pin = k;
            pic.setOutput(k, level -> cpu.setIrq(pin, level));
        }
        Runnable pin4 = () -> cpu.setIrq(IRQ_PIN_OUTPUT_0, pic.output(0) || pic.output(IRQ_PIN_OUTPUT_0));
        pic.setOutput(0, level -> pin4.run());
        pic.setOutput(IRQ_PIN_OUTPUT_0, level -> pin4.run());
    }

    @Override
    public boolean anyPinAsserted(Board board) {
        InterruptController pic = board.pic();
        for (int k = 0; k < InterruptController.OUTPUTS; k++)
            if (pic.output(k))
                return true;
        return false;
    }
}
