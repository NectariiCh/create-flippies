package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.DiskImage;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The parts a PC is built from (spec 1.1, 7.5): CPU model and clock, RAM size, an optional disk,
 * the boot ROM image, and the RTC's source. The compact constructor checks only what holds for
 * every platform; {@link Platform#validate} checks the platform's own limits.
 *
 * @param disk            null for no HDD
 * @param bootRom         the ROM image, unpadded (the platform pads it to its ROM window)
 * @param rtcEpochSeconds null means host wall-clock time
 * @param resetPc         null means the platform's default reset PC
 * @param strictFpu       68040 only, chosen in game: the FPU traps the instructions and data types
 *                        the real chip leaves to its support package, as bare hardware does,
 *                        instead of emulating them (spec 10.4); false for every other CPU
 * @param macAddress      the network card's MAC, 48 bits (spec 11.1.7); the game assigns a
 *                        locally administered unicast address
 */
public record MachineConfig(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                            byte[] bootRom, LongSupplier rtcEpochSeconds, Integer resetPc,
                            boolean strictFpu, long macAddress) {

    /** The largest MAC address, 48 bits. */
    public static final long MAC_MAX = (1L << 48) - 1;

    public MachineConfig {
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(bootRom, "bootRom");
        if (clockHz <= 0)
            throw new IllegalArgumentException("clockHz must be positive: " + clockHz);
        if (ramBytes <= 0 || ramBytes % 4096 != 0)
            throw new IllegalArgumentException("RAM must be a positive multiple of 4 KiB: " + ramBytes);
        if (bootRom.length == 0)
            throw new IllegalArgumentException("boot ROM image is empty");
        if (strictFpu && cpu != CpuModel.CPU_68040)
            throw new IllegalArgumentException("strict FPU needs a 68040, not a " + cpu.displayName());
        if (macAddress < 0 || macAddress > MAC_MAX)
            throw new IllegalArgumentException("MAC address must fit 48 bits: " + Long.toHexString(macAddress));
    }

    /** A config with MAC address 0. */
    public MachineConfig(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                         byte[] bootRom, LongSupplier rtcEpochSeconds, Integer resetPc, boolean strictFpu) {
        this(cpu, clockHz, ramBytes, disk, bootRom, rtcEpochSeconds, resetPc, strictFpu, 0);
    }

    /** A config with the FPU in its default, emulating mode (every CPU but the 68040 has no FPU). */
    public MachineConfig(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                         byte[] bootRom, LongSupplier rtcEpochSeconds, Integer resetPc) {
        this(cpu, clockHz, ramBytes, disk, bootRom, rtcEpochSeconds, resetPc, false);
    }

    /** A PC with the model's standard boot ROM ({@link BootRoms}), the RTC on host time and the platform's reset PC. */
    public static MachineConfig of(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk) {
        return new MachineConfig(cpu, clockHz, ramBytes, disk, BootRoms.image(cpu), null, null);
    }

    /** What the game builds (spec 11.1.8): the model's standard boot ROM, host time, the strict FPU choice and the MAC. */
    public static MachineConfig of(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                                   boolean strictFpu, long macAddress) {
        return new MachineConfig(cpu, clockHz, ramBytes, disk, BootRoms.image(cpu), null, null, strictFpu, macAddress);
    }
}
