package com.nectariich.create_flippies.content.plc.mcu;

import java.util.List;

/** What the toolchain hands back: diagnostics always, a program only if there was no error. */
public record CompileResult(CompiledProgram program, List<Diagnostic> diagnostics) {

    public boolean ok() {
        return program != null;
    }
}
