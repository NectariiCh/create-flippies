package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;

import java.util.function.IntSupplier;

/** What differs per CPU family: limits, memory map, device placement and pin wiring. */
public interface Platform {

    CpuModel model();

    /** IllegalArgumentException naming the problem if this platform cannot run the config. */
    void validate(MachineConfig config);

    int defaultResetPc();

    /** The most RAM this platform accepts (spec 11.1.10), so the game asks instead of copying limits. */
    int maxRamBytes();

    /** Creates RAM, ROM (padded), VRAM, devices and buses, maps them, wires device lines into the controller. */
    Board build(MachineConfig config, CycleClock clock);

    /**
     * Connects controller outputs and CPU-specific sources (CLINT) to the CPU's pins, driving each
     * pin with its source's current level. Safe to call again (the machine does after every CPU
     * reset, so the CPU sees the levels whatever its reset does to its pin fields).
     */
    void wirePins(Board board, Cpu cpu);

    /** Whether any CPU interrupt pin is asserted, masked or not; the wake rule of SysCon WAIT (spec 1.4). */
    boolean anyPinAsserted(Board board);

    /**
     * Whether reset and power-on mirror the boot ROM at address 0 (spec 1.5), until the guest
     * writes SysCon ROM_OVERLAY_OFF: the 68000 and 68040 read their reset vectors there.
     */
    default boolean romOverlayAtReset() {
        return false;
    }

    /** SysCon PRESENT from the board's devices (spec 3.1, 11.1.1), shared by every platform. */
    static IntSupplier presentBits(HddController hdd, Ps2Controller ps2, Uart16550[] com, FloppyController fdc, Nic nic) {
        return () -> (hdd.mediaPresent() ? SysCon.PRESENT_HDD : 0)
                | (ps2.device(0) != Ps2DeviceKind.NONE ? SysCon.PRESENT_KEYBOARD : 0)
                | (ps2.device(1) != Ps2DeviceKind.NONE ? SysCon.PRESENT_MOUSE : 0)
                | (com[0].hostConnected() ? SysCon.PRESENT_COM1 : 0)
                | (com[1].hostConnected() ? SysCon.PRESENT_COM2 : 0)
                | (fdc.mediaPresent() ? SysCon.PRESENT_FLOPPY : 0)
                | (com[2].hostConnected() ? SysCon.PRESENT_COM3 : 0)
                | (nic.link() ? SysCon.PRESENT_NIC_LINK : 0);
    }

    /** The platform for a model; UnsupportedOperationException if none exists yet (RV32I, 68000, 68040 now). */
    static Platform forModel(CpuModel model) {
        return switch (model) {
            case RV32I -> Rv32Platform.INSTANCE;
            case CPU_68000 -> M68kPlatform.M68000;
            case CPU_68040 -> M68kPlatform.M68040;
            default -> throw new UnsupportedOperationException(model.displayName() + " has no machine platform yet");
        };
    }
}
