package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.AsmSource;
import com.nectariich.create_flippies.content.computer.asm.Assembler;

import java.util.List;

/**
 * Entry point of the 68000 and 68040 assembler (classic 68k syntax, spec 10.7). Thread-safe: each
 * call uses its own backend, because the {@code .cpu} directive switches the target within a run.
 */
public final class M68kAssembler {

    private M68kAssembler() {
    }

    /** Assembles one source for {@code target}; its file name in diagnostics is {@code <source>}. */
    public static AsmResult assemble(String source, AsmOptions options, M68kTarget target) {
        return assemble(List.of(new AsmSource("<source>", source)), options, target);
    }

    /** Assembles several files into one image (file-local and global symbols as in spec 5.1). */
    public static AsmResult assemble(List<AsmSource> sources, AsmOptions options, M68kTarget target) {
        return new Assembler(new M68kBackend(target), options).assemble(sources);
    }
}
