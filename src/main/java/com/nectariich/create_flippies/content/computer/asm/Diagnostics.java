package com.nectariich.create_flippies.content.computer.asm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collects diagnostics, caps errors at {@link #MAX_ERRORS} and sorts them for the result. */
final class Diagnostics {

    static final int MAX_ERRORS = 100;

    private record Entry(int file, int order, AsmDiagnostic diagnostic) { }

    private final List<String> fileNames;
    private final List<Entry> entries = new ArrayList<>();
    private int errors;
    private boolean full;

    Diagnostics(List<String> fileNames) {
        this.fileNames = fileNames;
    }

    void error(int file, int line, int column, String message) {
        if (full)
            return;
        if (errors == MAX_ERRORS) {
            full = true;
            add(file, Severity.ERROR, line, column, "too many errors, stopping after " + MAX_ERRORS);
            return;
        }
        errors++;
        add(file, Severity.ERROR, line, column, message);
    }

    void warning(int file, int line, int column, String message) {
        if (!full)
            add(file, Severity.WARNING, line, column, message);
    }

    private void add(int file, Severity sev, int line, int column, String message) {
        entries.add(new Entry(file, entries.size(),
                new AsmDiagnostic(sev, fileNames.get(file), line, Math.max(column, 1), message)));
    }

    boolean hasErrors() {
        return errors > 0;
    }

    /** True once the error cap is reached: the driver stops early. */
    boolean full() {
        return full;
    }

    List<AsmDiagnostic> sorted() {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingInt(Entry::file)
                .thenComparingInt(e -> e.diagnostic().line())
                .thenComparingInt(e -> e.diagnostic().column())
                .thenComparingInt(Entry::order));
        List<AsmDiagnostic> out = new ArrayList<>(copy.size());
        for (Entry e : copy)
            out.add(e.diagnostic());
        return out;
    }
}
