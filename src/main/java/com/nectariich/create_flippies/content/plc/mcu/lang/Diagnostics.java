package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;

/**
 * Collects what the toolchain has to say. Positions inside the sketch are reported
 * as they are; a problem inside the core's prelude (which the player cannot see or
 * edit) is reported at line 0 with the prelude's own position folded into the text,
 * so it is still traceable but never points at an unrelated sketch line.
 */
final class Diagnostics {

    /** Thrown to stop compiling once enough has gone wrong; GCC gives up eventually too. */
    static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    private static final int MAX_ERRORS = 40;

    final List<Diagnostic> list = new ArrayList<>();
    private int errors;

    boolean hasErrors() {
        return errors > 0;
    }

    void error(int file, int line, int col, String msg) {
        add(Diagnostic.Severity.ERROR, file, line, col, msg);
        if (++errors >= MAX_ERRORS) {
            add(Diagnostic.Severity.NOTE, Token.SKETCH, 0, 0, "too many errors, stopping");
            throw new Abort();
        }
    }

    void error(Token t, String msg) {
        error(t.file, t.line, t.col, msg);
    }

    void warning(int file, int line, int col, String msg) {
        add(Diagnostic.Severity.WARNING, file, line, col, msg);
    }

    void note(int file, int line, int col, String msg) {
        add(Diagnostic.Severity.NOTE, file, line, col, msg);
    }

    /** A link-time error: no source position, as ld reports it. */
    void linkError(String msg) {
        list.add(new Diagnostic(Diagnostic.Severity.ERROR, 0, 0, msg));
        errors++;
    }

    private void add(Diagnostic.Severity sev, int file, int line, int col, String msg) {
        if (file == Token.SKETCH) {
            list.add(new Diagnostic(sev, line, col, msg));
        } else {
            String where = file == Token.PRELUDE ? "Arduino.h:" + line + ":" + col + ": " : "<built-in>:" + line + ": ";
            list.add(new Diagnostic(sev, 0, 0, where + msg));
        }
    }
}
