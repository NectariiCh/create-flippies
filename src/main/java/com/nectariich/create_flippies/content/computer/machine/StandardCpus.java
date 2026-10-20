package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuFactory;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kCpu;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kOptions;
import com.nectariich.create_flippies.content.computer.cpu.m68k.fpu.M68kFpu;
import com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Cpu;

/**
 * The CPU factory the game uses (spec 7.1): the real emulator for every model that has one.
 * RV32I (always with the M extension), the 68000, and the 68040 with its FPU in the host's
 * strict or emulating mode (spec 10.8.3); the other models throw until their emulators are
 * written.
 */
public final class StandardCpus implements CpuFactory {

    public static final StandardCpus INSTANCE = new StandardCpus();

    private StandardCpus() {
    }

    @Override
    public Cpu create(CpuHost host) {
        return switch (host.model()) {
            case RV32I -> new Rv32Cpu(host, true);
            case CPU_68000 -> new M68kCpu(host, M68kOptions.m68000(), null);
            case CPU_68040 -> new M68kCpu(host, M68kOptions.m68040(host.strictFpu()), M68kFpu::new);
            default -> throw new UnsupportedOperationException(host.model().displayName() + " is not emulated yet");
        };
    }
}
