package com.nectariich.create_flippies.content.computer.asm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Symbols of one assembly run. Each file has its own table of the names it defines; names a
 * file declares {@code .globl} or {@code .weak} are also entered in the shared global table after
 * the first pass. A reference looks in its own file first, then in the global table, so two files
 * may each have a local {@code loop} or {@code .L1}. Numeric local labels live in per-file lists
 * ordered by statement, for {@code 1b} and {@code 1f}.
 */
final class SymbolTable {

    enum Kind { LABEL, EQUATE, PREDEFINED }

    static final class Symbol {
        final String name;
        final int file;
        final Kind kind;
        final int line;
        final int seq;
        /** Labels: section and offset within it. */
        Section section;
        long offset;
        /** Equates and predefined symbols: the current value. */
        Val value;
        boolean known;
        /** The value is section-relative (computed during the first layout pass). */
        boolean relative;
        /** An equate whose value was not known in the first pass: evaluated after layout. */
        Expr deferred;
        Statement definedBy;
        boolean redefinable;
        boolean resolving;

        Symbol(String name, int file, Kind kind, int line, int seq) {
            this.name = name;
            this.file = file;
            this.kind = kind;
            this.line = line;
            this.seq = seq;
        }
    }

    private final List<Map<String, Symbol>> files = new ArrayList<>();
    private final List<Set<String>> globalNames = new ArrayList<>();
    private final List<Set<String>> weakNames = new ArrayList<>();
    private final List<Map<Integer, List<Symbol>>> numeric = new ArrayList<>();
    private final Map<String, Symbol> globals = new HashMap<>();

    SymbolTable(int fileCount) {
        for (int i = 0; i < fileCount; i++) {
            files.add(new LinkedHashMap<>());
            globalNames.add(new HashSet<>());
            weakNames.add(new HashSet<>());
            numeric.add(new HashMap<>());
        }
    }

    Symbol defined(int file, String name) {
        return files.get(file).get(name);
    }

    void define(Symbol s) {
        files.get(s.file).put(s.name, s);
    }

    void declareGlobal(int file, String name) {
        globalNames.get(file).add(name);
    }

    void declareWeak(int file, String name) {
        weakNames.get(file).add(name);
    }

    void declareLocal(int file, String name) {
        globalNames.get(file).remove(name);
        weakNames.get(file).remove(name);
    }

    boolean isWeak(int file, String name) {
        return weakNames.get(file).contains(name);
    }

    boolean isGlobal(int file, String name) {
        return globalNames.get(file).contains(name) || weakNames.get(file).contains(name);
    }

    /**
     * Own file first, then the global table; null if neither has it. A name the own file
     * declares global resolves through the global table once it is merged, so a strong
     * definition elsewhere beats this file's weak one.
     */
    Symbol lookup(int file, String name) {
        Symbol s = files.get(file).get(name);
        if (s != null) {
            if (isGlobal(file, name)) {
                Symbol g = globals.get(name);
                if (g != null)
                    return g;
            }
            return s;
        }
        return globals.get(name);
    }

    Symbol global(String name) {
        return globals.get(name);
    }

    void putGlobal(Symbol s) {
        globals.put(s.name, s);
    }

    /**
     * Enters every file's global and weak definitions in the global table. A name defined
     * globally in two files is reported through {@code dup} (a weak definition yields to a
     * strong one without an error).
     */
    void mergeGlobals(DuplicateSink dup) {
        for (int f = 0; f < files.size(); f++) {
            for (Symbol s : files.get(f).values()) {
                if (!isGlobal(f, s.name) || s.name.startsWith(".L"))
                    continue;
                Symbol old = globals.get(s.name);
                if (old == null) {
                    globals.put(s.name, s);
                } else if (old.kind == Kind.PREDEFINED) {
                    continue;
                } else if (isWeak(old.file, old.name) && !isWeak(f, s.name)) {
                    globals.put(s.name, s);
                } else if (!isWeak(f, s.name)) {
                    dup.duplicate(s, old);
                }
            }
        }
    }

    @FunctionalInterface
    interface DuplicateSink {
        void duplicate(Symbol second, Symbol first);
    }

    void addNumeric(int file, int number, Symbol s) {
        numeric.get(file).computeIfAbsent(number, k -> new ArrayList<>()).add(s);
    }

    /** The definition of local label {@code number} nearest before (or after) statement {@code seq}. */
    Symbol numeric(int file, int number, boolean forward, int seq) {
        List<Symbol> list = numeric.get(file).get(number);
        if (list == null)
            return null;
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).seq < seq)
                lo = mid + 1;
            else
                hi = mid;
        }
        // lo is the first definition at or after seq.
        if (forward) {
            while (lo < list.size() && list.get(lo).seq <= seq)
                lo++;
            return lo < list.size() ? list.get(lo) : null;
        }
        return lo > 0 ? list.get(lo - 1) : null;
    }

    Map<String, Symbol> fileSymbols(int file) {
        return files.get(file);
    }

    Iterable<Symbol> predefined() {
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : globals.values())
            if (s.kind == Kind.PREDEFINED)
                out.add(s);
        return out;
    }
}
