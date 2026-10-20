package com.nectariich.create_flippies.content.computer.asm.rv32;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.AsmSource;
import com.nectariich.create_flippies.content.computer.asm.Assembler;

import java.util.List;

/** Entry point of the RV32I assembler (RV32IM + Zicsr + Zifencei, GNU as syntax). Thread-safe. */
public final class Rv32Assembler {

    private Rv32Assembler() {
    }

    /** Assembles one source; its file name in diagnostics is {@code <source>}. */
    public static AsmResult assemble(String source, AsmOptions options) {
        return assemble(List.of(new AsmSource("<source>", source)), options);
    }

    /** Assembles several files into one image (file-local and global symbols as in spec 5.1). */
    public static AsmResult assemble(List<AsmSource> sources, AsmOptions options) {
        return new Assembler(Rv32Backend.INSTANCE, options).assemble(sources);
    }
}
