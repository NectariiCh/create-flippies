package com.nectariich.create_flippies.content.computer.asm;

/** One error or warning, at a 1-based line and column of a source file. */
public record AsmDiagnostic(Severity severity, String fileName, int line, int column, String message) {

    @Override
    public String toString() {
        return fileName + ":" + line + ":" + column + ": "
                + (severity == Severity.ERROR ? "error: " : "warning: ") + message;
    }
}
