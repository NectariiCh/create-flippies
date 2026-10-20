package com.nectariich.create_flippies.content.computer.asm;

/**
 * An error in the statement being processed, at a column of its line. Thrown inside the
 * assembler and turned into a diagnostic by the driver; carries no stack trace.
 */
public final class AsmError extends RuntimeException {

    public final int column;

    public AsmError(int column, String message) {
        super(message, null, false, false);
        this.column = column;
    }
}
