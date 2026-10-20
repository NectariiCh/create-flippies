package com.nectariich.create_flippies.content.computer.asm;

import java.util.List;

/** What an assembly run produced: diagnostics always, an image only when there were no errors. */
public final class AsmResult {

    private final List<AsmDiagnostic> diagnostics;
    private final AsmImage image;

    AsmResult(List<AsmDiagnostic> diagnostics, AsmImage image) {
        this.diagnostics = List.copyOf(diagnostics);
        this.image = image;
    }

    /** No errors (warnings allowed). */
    public boolean ok() {
        return image != null;
    }

    /** Sorted by file (input order), then line, then column. */
    public List<AsmDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** The image, or null when {@link #ok()} is false. */
    public AsmImage image() {
        return image;
    }

    /** Errors only. */
    public List<AsmDiagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(ok() ? "ok" : "failed");
        for (AsmDiagnostic d : diagnostics)
            sb.append('\n').append(d);
        return sb.toString();
    }
}
