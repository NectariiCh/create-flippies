package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;
import com.nectariich.create_flippies.content.computer.asm.SymbolTable.Symbol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shared two-pass assembler driver. A backend ({@link IsaBackend}) supplies the instruction
 * set; this class does everything else: lexing, statement parsing, labels and equates,
 * directives, sections and layout, diagnostics and the image.
 *
 * Pass 1 walks every statement in order, gives each a section, an offset and a size, and defines
 * labels. Layout then places the sections: text at the text base, then rodata, init_array,
 * fini_array, data and bss, each aligned to the larger of 16 and its largest {@code .align}.
 * Pass 2 walks the statements again with every symbol known and emits exactly the sizes pass 1
 * gave. A backend that {@link IsaBackend#relaxes() relaxes} gets more pass-1 iterations first.
 *
 * Guest source is untrusted: nothing here throws on any input, no allocation happens past
 * {@link AsmOptions#maxImageBytes()}, and expression depth is capped.
 */
public final class Assembler {

    /** Relaxation passes allowed beyond one per instruction statement (see {@link IsaBackend#relaxes}). */
    private static final int MIN_RELAX_ITERATIONS = 16;
    private static final int MAX_ALIGN_LOG2 = 16;

    private static final Map<String, Integer> DATA_SIZES = Map.ofEntries(
            Map.entry(".byte", 1), Map.entry(".half", 2), Map.entry(".short", 2), Map.entry(".2byte", 2),
            Map.entry(".word", 4), Map.entry(".long", 4), Map.entry(".4byte", 4),
            Map.entry(".dword", 8), Map.entry(".quad", 8), Map.entry(".8byte", 8));

    /** Accepted for compiler compatibility and otherwise ignored. */
    private static final Set<String> IGNORED = Set.of(
            ".file", ".loc", ".ident", ".type", ".size", ".hidden", ".attribute");

    private static final List<String> PREDEFINED = List.of(
            "__text_start", "__text_end", "__rodata_start", "__init_array_start", "__init_array_end",
            "__fini_array_start", "__fini_array_end", "__data_load_start", "__data_start", "__data_end",
            "__bss_start", "__bss_end", "_end");

    private final IsaBackend backend;
    private final AsmOptions options;

    public Assembler(IsaBackend backend, AsmOptions options) {
        this.backend = backend;
        this.options = options;
    }

    public AsmResult assemble(String source) {
        return assemble(List.of(new AsmSource("<source>", source)));
    }

    public AsmResult assemble(List<AsmSource> sources) {
        Run run = new Run(sources);
        try {
            return run.run();
        } catch (RuntimeException | StackOverflowError e) {
            // A bug, never the guest's fault; still never throw at the caller.
            run.diags.error(0, 1, 1, "internal assembler error: " + e);
            return new AsmResult(run.diags.sorted(), null);
        }
    }

    /** State of one assembly run. */
    private final class Run {
        final List<AsmSource> sources;
        final List<String> fileNames = new ArrayList<>();
        final Diagnostics diags;
        final SymbolTable symbols;
        final AsmContext ctx;
        final List<Statement> statements = new ArrayList<>();
        final Section[] sections = Section.values();
        final long[] loc = new long[sections.length];
        final long[] maxAlign = new long[sections.length];
        final boolean[] limitReported = new boolean[sections.length];
        final Emitter emitter = new Emitter(backend.byteOrder());
        final long textBase;
        long dataLoad;
        Section current;
        byte[] run1;
        byte[] run2;

        Run(List<AsmSource> sources) {
            this.sources = sources.isEmpty() ? List.of(new AsmSource("<source>", "")) : sources;
            for (AsmSource s : this.sources)
                fileNames.add(s.fileName());
            diags = new Diagnostics(fileNames);
            symbols = new SymbolTable(this.sources.size());
            ctx = new AsmContext(backend, symbols, diags);
            textBase = Integer.toUnsignedLong(options.textBase());
        }

        AsmResult run() {
            parseAll();
            if (!basesAligned())
                return new AsmResult(diags.sorted(), null);
            ctx.pass = 1;
            ctx.iteration = 1;
            pass1();
            for (String name : PREDEFINED) {
                Symbol s = new Symbol(name, 0, SymbolTable.Kind.PREDEFINED, 0, -1);
                symbols.putGlobal(s);
            }
            symbols.mergeGlobals((second, first) -> diags.error(second.file,
                    second.line, 1, "global symbol '" + second.name + "' already defined in "
                            + fileNames.get(first.file) + " line " + first.line));
            boolean laidOut = layout();
            if (backend.relaxes() && laidOut && !diags.full()) {
                ctx.quiet = true;
                boolean changed = true;
                // a forward-label cascade settles one more instruction per pass, so allow one pass per
                // instruction statement on top of the fixed minimum; the cap still ends a run that
                // never settles
                int maxIterations = MIN_RELAX_ITERATIONS + instructionCount();
                for (int it = 2; it <= maxIterations && changed; it++) {
                    ctx.iteration = it;
                    changed = pass1();
                    laidOut = layout();
                    if (!laidOut)
                        break;
                }
                ctx.quiet = false;
                if (changed)
                    diags.error(0, 1, 1, "instruction sizes did not settle after "
                            + maxIterations + " passes");
            }
            if (laidOut)
                allocate();
            ctx.pass = 2;
            pass2();
            if (diags.hasErrors())
                return new AsmResult(diags.sorted(), null);
            return new AsmResult(diags.sorted(), buildImage());
        }

        /** Rejects a text or data base the backend's auto-alignment cannot work from (68k: odd). */
        boolean basesAligned() {
            int align = backend.baseAlignment();
            boolean ok = true;
            if ((options.textBase() & (align - 1)) != 0) {
                diags.error(0, 1, 1, String.format("text base 0x%08x is not a multiple of %d (instructions "
                        + "would sit at misaligned addresses)", options.textBase(), align));
                ok = false;
            }
            if (options.dataBase() != null && (options.dataBase() & (align - 1)) != 0) {
                diags.error(0, 1, 1, String.format("data base 0x%08x is not a multiple of %d (word data "
                        + "would sit at misaligned addresses)", options.dataBase(), align));
                ok = false;
            }
            return ok;
        }

        int instructionCount() {
            int n = 0;
            for (Statement st : statements)
                if (st.kind == Statement.Kind.INSTRUCTION)
                    n++;
            return n;
        }

        // ---- parsing ----

        void parseAll() {
            int seq = 0;
            for (int f = 0; f < sources.size(); f++) {
                final int file = f;
                AsmLexer lexer = new AsmLexer(backend.syntax(), sources.get(f).text(),
                        (line, column, message) -> diags.error(file, line, column, message));
                List<AsmToken> tokens = lexer.tokenize();
                int start = 0;
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).kind() == Kind.NEWLINE) {
                        if (i > start)
                            seq = parseLine(f, tokens.subList(start, i), seq);
                        start = i + 1;
                    }
                }
            }
        }

        int parseLine(int file, List<AsmToken> t, int seq) {
            String fileName = fileNames.get(file);
            int i = 0, n = t.size();
            while (i + 1 < n && t.get(i + 1).isPunct(":")) {
                AsmToken a = t.get(i);
                if (a.kind() == Kind.IDENT) {
                    statements.add(new Statement(seq++, file, fileName, a.line(), a.column(),
                            Statement.Kind.LABEL, a.text(), List.of()));
                } else if (a.kind() == Kind.INT && !a.text().startsWith("'")) {
                    if (a.value() < 0 || a.value() > 99) {
                        diags.error(file, a.line(), a.column(), "local label number must be 0..99");
                    } else {
                        statements.add(new Statement(seq++, file, fileName, a.line(), a.column(),
                                Statement.Kind.LOCAL_LABEL, Long.toString(a.value()), List.of()));
                    }
                } else {
                    break;
                }
                i += 2;
            }
            if (i >= n)
                return seq;
            AsmToken head = t.get(i);
            if (head.kind() != Kind.IDENT) {
                diags.error(file, head.line(), head.column(),
                        "expected a label, instruction or directive, got '" + head.display() + "'");
                return seq;
            }
            if (i + 1 < n && t.get(i + 1).isPunct("=")) {
                List<AsmToken> rest = t.subList(i + 2, n);
                Operand value = new Operand(rest, rest.isEmpty() ? t.get(i + 1).column() + 1 : rest.get(0).column());
                statements.add(new Statement(seq++, file, fileName, head.line(), head.column(),
                        Statement.Kind.ASSIGN, head.text(), List.of(value)));
                return seq;
            }
            if (backend.syntax().equKeyword() && i + 1 < n && t.get(i + 1).kind() == Kind.IDENT
                    && (t.get(i + 1).text().equalsIgnoreCase("equ") || t.get(i + 1).text().equalsIgnoreCase("set"))) {
                // classic 68k "name equ expr" is ".equ name, expr" ("set": ".set")
                AsmToken keyword = t.get(i + 1);
                List<AsmToken> rest = t.subList(i + 2, n);
                Operand target = new Operand(t.subList(i, i + 1), head.column());
                Operand value = new Operand(rest, rest.isEmpty() ? keyword.column() + keyword.text().length() : rest.get(0).column());
                String directive = keyword.text().equalsIgnoreCase("equ") ? ".equ" : ".set";
                statements.add(new Statement(seq++, file, fileName, head.line(), head.column(),
                        Statement.Kind.DIRECTIVE, directive, List.of(target, value)));
                return seq;
            }
            String name = head.text().toLowerCase(Locale.ROOT);
            Statement.Kind kind = name.startsWith(".") ? Statement.Kind.DIRECTIVE : Statement.Kind.INSTRUCTION;
            statements.add(new Statement(seq++, file, fileName, head.line(), head.column(), kind, name,
                    splitOperands(t, i + 1, head)));
            return seq;
        }

        List<Operand> splitOperands(List<AsmToken> t, int from, AsmToken head) {
            List<Operand> out = new ArrayList<>();
            if (from >= t.size())
                return out;
            int depth = 0, start = from;
            for (int i = from; i <= t.size(); i++) {
                AsmToken tok = i < t.size() ? t.get(i) : null;
                if (tok != null && tok.isPunct("("))
                    depth++;
                else if (tok != null && tok.isPunct(")"))
                    depth = Math.max(0, depth - 1);
                if (tok == null || (depth == 0 && tok.isPunct(","))) {
                    int column = start < t.size() && start < i ? t.get(start).column()
                            : tok != null ? tok.column() : t.get(t.size() - 1).column() + 1;
                    out.add(new Operand(t.subList(start, i), column));
                    start = i + 1;
                }
            }
            return out;
        }

        // ---- pass 1 ----

        /** One first-pass walk; returns whether any instruction size changed (relaxation). */
        boolean pass1() {
            Arrays.fill(loc, 0);
            Arrays.fill(maxAlign, 1);
            current = Section.TEXT;
            boolean changed = false;
            List<Statement> pendingLabels = new ArrayList<>();
            for (Statement st : statements) {
                if (diags.full())
                    break;
                ctx.current = st;
                boolean isLabel = st.kind == Statement.Kind.LABEL || st.kind == Statement.Kind.LOCAL_LABEL;
                if (isLabel)
                    pendingLabels.add(st);
                if (current == null && !isSectionDirective(st)) {
                    st.section = null;
                    st.size = 0;
                    if (ctx.iteration == 1)
                        parseOnly(st);
                    pendingLabels.clear();
                    continue;
                }
                if (st.failed && ctx.iteration > 1) {
                    st.section = current;
                    st.offset = current == null ? 0 : loc[current.ordinal()];
                    pendingLabels.clear();
                    continue;
                }
                long previousOffset = st.offset;
                st.section = current;
                st.offset = current == null ? 0 : loc[current.ordinal()];
                long size;
                try {
                    size = sizeOf(st);
                } catch (AsmError e) {
                    fail(st, e.column, e.getMessage());
                    size = 0;
                } catch (AsmContext.Unknown u) {
                    fail(st, st.column, "value must be a constant known at this point");
                    size = 0;
                } catch (RuntimeException | StackOverflowError e) {
                    fail(st, st.column, "internal assembler error: " + e);
                    size = 0;
                }
                if (st.section == null || current == null) {
                    st.size = 0;
                    if (!isLabel)
                        pendingLabels.clear();
                    continue;
                }
                Section sec = st.section;
                if (size > 0 && loc[sec.ordinal()] + size > options.maxImageBytes()) {
                    if (!limitReported[sec.ordinal()]) {
                        limitReported[sec.ordinal()] = true;
                        fail(st, st.column, sec.displayName() + " grows past the image limit of "
                                + options.maxImageBytes() + " bytes");
                    } else {
                        st.failed = true;
                    }
                    size = 0;
                }
                if (st.kind == Statement.Kind.INSTRUCTION && ctx.iteration > 1) {
                    // instructions only grow, apart from the auto-alignment pad, which follows the
                    // current offset; a data statement (classic org, align) follows the code before it
                    if (!backend.isDataStatement(st)) {
                        long padMask = backend.autoAlignment(st) - 1;
                        long pad = -st.offset & padMask;
                        long previousPad = -previousOffset & padMask;
                        if (size - pad < st.size - previousPad)
                            size = st.size - previousPad + pad;
                    }
                    if (size != st.size)
                        changed = true;
                }
                st.size = (int) size;
                if (!isLabel) {
                    // labels carry over statements that emit nothing (a .cpu directive, an assignment)
                    // to the next statement that does
                    if (st.kind == Statement.Kind.INSTRUCTION && !st.failed)
                        moveLabelsPastPad(pendingLabels, st, size);
                    if (size != 0 || st.failed)
                        pendingLabels.clear();
                }
                loc[sec.ordinal()] += size;
            }
            return changed;
        }

        /**
         * Labels just before a statement that pads itself to its backend's
         * {@link IsaBackend#autoAlignment} name the address after the pad, where the statement's
         * content starts (a 68k instruction after an odd-length string).
         */
        void moveLabelsPastPad(List<Statement> labels, Statement st, long size) {
            if (labels.isEmpty())
                return;
            long pad = -st.offset & (backend.autoAlignment(st) - 1);
            if (pad == 0 || pad > size)
                return;
            for (Statement label : labels)
                if (label.symbol != null && label.section == st.section && label.offset == st.offset)
                    label.symbol.offset = st.offset + pad;
        }

        /**
         * A statement in a dropped section is not evaluated, but the expressions of data
         * directives and assignments are still parsed so syntax errors count.
         */
        void parseOnly(Statement st) {
            boolean exprs = st.kind == Statement.Kind.ASSIGN
                    || (st.kind == Statement.Kind.DIRECTIVE && (DATA_SIZES.containsKey(st.name)
                    || st.name.equals(".space") || st.name.equals(".skip") || st.name.equals(".zero")
                    || st.name.equals(".fill") || st.name.equals(".org") || st.name.equals(".align")
                    || st.name.equals(".p2align") || st.name.equals(".balign")));
            if (!exprs)
                return;
            for (Operand op : st.operands) {
                try {
                    op.expr();
                } catch (AsmError e) {
                    fail(st, e.column, e.getMessage());
                    return;
                }
            }
        }

        /** Statements that failed once are skipped by later iterations, so each error is reported once. */
        void fail(Statement st, int column, String message) {
            st.failed = true;
            diags.error(st.file, st.line, column, message);
        }

        boolean isSectionDirective(Statement st) {
            return st.kind == Statement.Kind.DIRECTIVE && switch (st.name) {
                case ".text", ".data", ".bss", ".rodata", ".section" -> true;
                default -> false;
            };
        }

        long sizeOf(Statement st) {
            return switch (st.kind) {
                case LABEL -> {
                    defineLabel(st);
                    yield 0;
                }
                case LOCAL_LABEL -> {
                    if (ctx.iteration == 1) {
                        Symbol s = new Symbol(st.name, st.file, SymbolTable.Kind.LABEL, st.line, st.seq);
                        st.symbol = s;
                        symbols.addNumeric(st.file, Integer.parseInt(st.name), s);
                    }
                    st.symbol.section = current;
                    st.symbol.offset = st.offset;
                    yield 0;
                }
                case ASSIGN -> {
                    assign(st, st.name, st.column, st.operand(0), true);
                    yield 0;
                }
                case DIRECTIVE -> directiveSize(st);
                case INSTRUCTION -> {
                    if (current == Section.BSS && !backend.isDataStatement(st))
                        throw new AsmError(st.column, "instructions are not allowed in .bss");
                    yield backend.size(st, ctx);
                }
            };
        }

        void checkNotPredefined(String name, int column) {
            if (PREDEFINED.contains(name))
                throw new AsmError(column, "symbol '" + name + "' is predefined by the assembler");
        }

        void defineLabel(Statement st) {
            if (ctx.iteration == 1) {
                checkNotPredefined(st.name, st.column);
                Symbol old = symbols.defined(st.file, st.name);
                if (old != null)
                    throw new AsmError(st.column, "symbol '" + st.name + "' already defined on line " + old.line);
                Symbol s = new Symbol(st.name, st.file, SymbolTable.Kind.LABEL, st.line, st.seq);
                symbols.define(s);
                st.symbol = s;
            }
            st.symbol.section = current;
            st.symbol.offset = st.offset;
        }

        /** {@code .set}, {@code .equ} and {@code name = expr}, in either pass. */
        void assign(Statement st, String name, int column, Operand valueOperand, boolean redefinable) {
            Expr expr = valueOperand.expr();
            Symbol s = st.symbol;
            if (ctx.pass == 1 && ctx.iteration == 1) {
                checkNotPredefined(name, column);
                if (name.equals("."))
                    throw new AsmError(column, "assigning to '.' is not supported; use .org");
                Symbol old = symbols.defined(st.file, name);
                if (old != null) {
                    if (old.kind != SymbolTable.Kind.EQUATE || !old.redefinable || !redefinable)
                        throw new AsmError(column, "symbol '" + name + "' already defined on line " + old.line);
                    s = old;
                } else {
                    s = new Symbol(name, st.file, SymbolTable.Kind.EQUATE, st.line, st.seq);
                    s.redefinable = redefinable;
                    symbols.define(s);
                }
                st.symbol = s;
            }
            if (s == null)
                return;
            s.definedBy = st;
            try {
                Val v = ctx.eval(expr);
                s.value = v;
                s.known = true;
                s.relative = ctx.relative() && v.section() >= 0;
                s.deferred = null;
            } catch (AsmContext.Unknown u) {
                s.known = false;
                s.relative = false;
                s.deferred = expr;
            }
        }

        long directiveSize(Statement st) {
            String d = st.name;
            Integer width = dataWidth(d);
            if (width != null)
                return (long) width * st.operands.size();
            switch (d) {
                case ".text" -> { current = Section.TEXT; return 0; }
                case ".data" -> { current = Section.DATA; return 0; }
                case ".bss" -> { current = Section.BSS; return 0; }
                case ".rodata" -> { current = Section.RODATA; return 0; }
                case ".section" -> { section(st); return 0; }
                case ".org" -> { return org(st); }
                case ".ascii", ".asciz", ".string" -> {
                    long n = 0;
                    for (Operand op : st.operands) {
                        String s = op.string();
                        if (s == null)
                            throw new AsmError(op.column(), "expected a string");
                        n += s.length() + (d.equals(".ascii") ? 0 : 1);
                    }
                    return n;
                }
                case ".space", ".skip", ".zero" -> {
                    if (st.operands.isEmpty() || st.operands.size() > 2)
                        throw new AsmError(st.column, "'" + d + "' expects a size and an optional fill byte");
                    long n = ctx.evalNow(st.operand(0).expr(), "'" + d + "' size");
                    if (n < 0)
                        throw new AsmError(st.operand(0).column(), "negative size " + n);
                    return n;
                }
                case ".fill" -> {
                    if (st.operands.isEmpty() || st.operands.size() > 3)
                        throw new AsmError(st.column, "'.fill' expects count[, size[, value]]");
                    long count = ctx.evalNow(st.operand(0).expr(), "'.fill' count");
                    long size = st.operands.size() > 1 ? ctx.evalNow(st.operand(1).expr(), "'.fill' size") : 1;
                    if (count < 0)
                        throw new AsmError(st.operand(0).column(), "negative count " + count);
                    if (size < 0 || size > 8)
                        throw new AsmError(st.operand(1).column(), "'.fill' size " + size + " out of range (0..8)");
                    return count > (1L << 40) ? Long.MAX_VALUE / 2 : count * size;
                }
                case ".align", ".p2align", ".balign" -> { return align(st); }
                case ".equ", ".set" -> {
                    st.expectOperands(2);
                    String name = st.operand(0).identifier();
                    if (name == null)
                        throw new AsmError(st.operand(0).column(), "expected a symbol name");
                    assign(st, name, st.operand(0).column(), st.operand(1), d.equals(".set"));
                    return 0;
                }
                case ".uleb128", ".sleb128" -> {
                    long n = 0;
                    for (Operand op : st.operands)
                        n += leb(ctx.evalNow(op.expr(), "'" + d + "' value"), d.equals(".sleb128")).length;
                    return n;
                }
                case ".globl", ".global", ".weak", ".local" -> {
                    for (Operand op : st.operands) {
                        String name = op.identifier();
                        if (name == null)
                            throw new AsmError(op.column(), "expected a symbol name");
                        if (ctx.iteration == 1) {
                            switch (d) {
                                case ".weak" -> symbols.declareWeak(st.file, name);
                                case ".local" -> symbols.declareLocal(st.file, name);
                                default -> symbols.declareGlobal(st.file, name);
                            }
                        }
                    }
                    return 0;
                }
                case ".comm", ".lcomm" -> { common(st); return 0; }
                default -> { }
            }
            if (IGNORED.contains(d) || d.startsWith(".cfi_") || d.startsWith(".addrsig"))
                return 0;
            if (backend.directive(st, ctx))
                return 0;
            throw new AsmError(st.column, "unknown directive '" + d + "'");
        }

        void section(Statement st) {
            if (st.operands.isEmpty())
                throw new AsmError(st.column, "expected a section name");
            Operand nameOp = st.operand(0);
            String name = nameOp.identifier();
            if (name == null)
                name = nameOp.string();
            if (name == null)
                name = joinedName(nameOp);
            if (name == null)
                throw new AsmError(nameOp.column(), "expected a section name");
            String flags = st.operands.size() > 1 ? st.operand(1).string() : null;
            if (name.startsWith(".note") || name.startsWith(".comment") || name.startsWith(".debug")) {
                current = null;
                st.section = null;
                return;
            }
            Section sec = Section.forName(name);
            if (sec != null) {
                current = sec;
                st.section = sec;
                return;
            }
            current = null;
            st.section = null;
            if (flags != null && flags.indexOf('a') >= 0)
                throw new AsmError(nameOp.column(), "unsupported section '" + name + "'");
        }

        /** A section name the lexer split at {@code -} (such as {@code .note.GNU-stack}), joined back. */
        String joinedName(Operand op) {
            StringBuilder sb = new StringBuilder();
            for (AsmToken t : op.tokens()) {
                if (t.kind() == Kind.IDENT || t.kind() == Kind.INT || t.isPunct("-"))
                    sb.append(t.text());
                else
                    return null;
            }
            return sb.isEmpty() || sb.charAt(0) != '.' ? null : sb.toString();
        }

        long org(Statement st) {
            st.expectOperands(1);
            Val v;
            try {
                v = ctx.eval(st.operand(0).expr());
            } catch (AsmContext.Unknown u) {
                throw new AsmError(st.operand(0).column(), "'.org' target must be a constant known at this point");
            }
            long target;
            if (v.isAbs())
                target = v.value();
            else if (v.section() == current.ordinal())
                target = ctx.relative() ? v.value() : v.value() - ctx.base[current.ordinal()];
            else
                throw new AsmError(st.operand(0).column(), "'.org' target is not in the current section");
            long here = loc[current.ordinal()];
            if (target < here)
                throw new AsmError(st.operand(0).column(), String.format(
                        "'.org' moves the location counter backwards (from 0x%x to 0x%x)", here, target));
            return target - here;
        }

        long align(Statement st) {
            if (st.operands.isEmpty() || st.operands.size() > 3)
                throw new AsmError(st.column, "'" + st.name + "' expects alignment[, fill[, max]]");
            long n = ctx.evalNow(st.operand(0).expr(), "alignment");
            boolean pow2 = st.name.equals(".p2align") || (st.name.equals(".align") && backend.alignIsPowerOfTwo());
            long alignment;
            if (pow2) {
                if (n < 0 || n > MAX_ALIGN_LOG2)
                    throw new AsmError(st.operand(0).column(), "alignment 2^" + n + " out of range (2^0..2^"
                            + MAX_ALIGN_LOG2 + ")");
                alignment = 1L << n;
            } else {
                if (n < 1 || n > (1L << MAX_ALIGN_LOG2) || Long.bitCount(n) != 1)
                    throw new AsmError(st.operand(0).column(), "alignment " + n
                            + " is not a power of two up to " + (1L << MAX_ALIGN_LOG2));
                alignment = n;
            }
            long here = loc[current.ordinal()];
            long pad = (alignment - (here & (alignment - 1))) & (alignment - 1);
            if (st.operands.size() > 2 && !st.operand(2).isEmpty()) {
                long max = ctx.evalNow(st.operand(2).expr(), "maximum skip");
                if (pad > max)
                    return 0;
            }
            maxAlign[current.ordinal()] = Math.max(maxAlign[current.ordinal()], alignment);
            return pad;
        }

        void common(Statement st) {
            if (st.operands.size() < 2 || st.operands.size() > 3)
                throw new AsmError(st.column, "'" + st.name + "' expects name, size[, align]");
            String name = st.operand(0).identifier();
            if (name == null)
                throw new AsmError(st.operand(0).column(), "expected a symbol name");
            long size = ctx.evalNow(st.operand(1).expr(), "size");
            if (size < 0)
                throw new AsmError(st.operand(1).column(), "negative size " + size);
            long alignment;
            if (st.operands.size() > 2) {
                alignment = ctx.evalNow(st.operand(2).expr(), "alignment");
                if (alignment < 1 || alignment > (1L << MAX_ALIGN_LOG2) || Long.bitCount(alignment) != 1)
                    throw new AsmError(st.operand(2).column(), "alignment " + alignment + " is not a power of two");
            } else {
                alignment = 1;
                while (alignment * 2 <= size && alignment < 16)
                    alignment *= 2;
            }
            if (ctx.iteration == 1) {
                checkNotPredefined(name, st.operand(0).column());
                Symbol old = symbols.defined(st.file, name);
                if (old != null)
                    throw new AsmError(st.operand(0).column(), "symbol '" + name + "' already defined on line " + old.line);
                Symbol s = new Symbol(name, st.file, SymbolTable.Kind.LABEL, st.line, st.seq);
                symbols.define(s);
                st.symbol = s;
                if (st.name.equals(".comm"))
                    symbols.declareGlobal(st.file, name);
            }
            int b = Section.BSS.ordinal();
            long offset = (loc[b] + alignment - 1) & -alignment;
            if (offset + size > options.maxImageBytes())
                throw new AsmError(st.operand(1).column(), ".bss grows past the image limit of "
                        + options.maxImageBytes() + " bytes");
            st.symbol.section = Section.BSS;
            st.symbol.offset = offset;
            loc[b] = offset + size;
            maxAlign[b] = Math.max(maxAlign[b], alignment);
        }

        // ---- layout ----

        long sectionAlign(Section s) {
            return Math.max(16, maxAlign[s.ordinal()]);
        }

        static long alignUp(long x, long a) {
            return (x + a - 1) & -a;
        }

        long end(Section s) {
            return ctx.base[s.ordinal()] + loc[s.ordinal()];
        }

        /** Places the sections and sets the predefined symbols; false (with an error) if they do not fit. */
        boolean layout() {
            long[] base = ctx.base;
            base[Section.TEXT.ordinal()] = textBase;
            Section prev = Section.TEXT;
            for (Section s : new Section[] {Section.RODATA, Section.INIT_ARRAY, Section.FINI_ARRAY}) {
                base[s.ordinal()] = alignUp(end(prev), sectionAlign(s));
                prev = s;
            }
            long afterFini = end(Section.FINI_ARRAY);
            int d = Section.DATA.ordinal();
            if (options.dataBase() == null) {
                base[d] = alignUp(afterFini, sectionAlign(Section.DATA));
                dataLoad = base[d];
            } else {
                base[d] = Integer.toUnsignedLong(options.dataBase());
                dataLoad = alignUp(afterFini, sectionAlign(Section.DATA));
            }
            base[Section.BSS.ordinal()] = alignUp(end(Section.DATA), sectionAlign(Section.BSS));
            setPredefined("__text_start", base[Section.TEXT.ordinal()]);
            setPredefined("__text_end", end(Section.TEXT));
            setPredefined("__rodata_start", base[Section.RODATA.ordinal()]);
            setPredefined("__init_array_start", base[Section.INIT_ARRAY.ordinal()]);
            setPredefined("__init_array_end", end(Section.INIT_ARRAY));
            setPredefined("__fini_array_start", base[Section.FINI_ARRAY.ordinal()]);
            setPredefined("__fini_array_end", end(Section.FINI_ARRAY));
            setPredefined("__data_load_start", dataLoad);
            setPredefined("__data_start", base[d]);
            setPredefined("__data_end", end(Section.DATA));
            setPredefined("__bss_start", base[Section.BSS.ordinal()]);
            setPredefined("__bss_end", end(Section.BSS));
            setPredefined("_end", end(Section.BSS));
            long top = Math.max(end(Section.BSS), dataLoad + loc[d]);
            if (top > 0x1_0000_0000L) {
                if (!ctx.quiet)
                    diags.error(0, 1, 1, String.format("program does not fit in the 32-bit address space "
                            + "(ends at 0x%x)", top));
                return false;
            }
            if (options.dataBase() != null && loc[d] + loc[Section.BSS.ordinal()] > 0) {
                long runStart = base[d], runEnd = end(Section.BSS);
                if (runStart < dataLoad + loc[d] && runEnd > textBase) {
                    if (!ctx.quiet)
                        diags.error(0, 1, 1, String.format("data at dataBase 0x%08x overlaps the text image "
                                + "0x%08x..0x%08x", runStart, textBase, dataLoad + loc[d]));
                    return false;
                }
            }
            return true;
        }

        void setPredefined(String name, long value) {
            Symbol s = symbols.global(name);
            s.value = new Val(value, Val.ADDR);
            s.known = true;
        }

        long loadAddress(Section s) {
            return s == Section.DATA ? dataLoad : ctx.base[s.ordinal()];
        }

        void allocate() {
            // The load image runs from the text base to the end of the last non-empty section.
            long run1End = textBase;
            for (Section sec : sections)
                if (sec != Section.BSS && loc[sec.ordinal()] > 0)
                    run1End = Math.max(run1End, loadAddress(sec) + loc[sec.ordinal()]);
            long run1Len = run1End - textBase;
            long run2Len = options.dataBase() != null ? loc[Section.DATA.ordinal()] : 0;
            if (run1Len + run2Len > options.maxImageBytes()) {
                diags.error(0, 1, 1, "image of " + (run1Len + run2Len) + " bytes exceeds the limit of "
                        + options.maxImageBytes() + " bytes");
                return;
            }
            run1 = new byte[(int) run1Len];
            run2 = new byte[(int) run2Len];
        }

        // ---- pass 2 ----

        void pass2() {
            for (Statement st : statements) {
                if (diags.full())
                    break;
                if (st.failed || st.section == null)
                    continue;
                ctx.current = st;
                Section sec = st.section;
                if (sec == Section.BSS || run1 == null)
                    emitter.start(null, 0);
                else
                    emitter.start(run1, (int) (loadAddress(sec) - textBase + st.offset));
                try {
                    emit(st);
                    if (emitter.count() != st.size)
                        diags.error(st.file, st.line, st.column, "internal assembler error: emitted "
                                + emitter.count() + " bytes, sized " + st.size + " in the first pass");
                    else if (sec == Section.BSS && emitter.nonZero())
                        diags.error(st.file, st.line, st.column, "nonzero data in .bss");
                } catch (AsmError e) {
                    diags.error(st.file, st.line, e.column, e.getMessage());
                } catch (AsmContext.Unknown u) {
                    diags.error(st.file, st.line, st.column, "internal assembler error: value unknown in pass 2");
                } catch (RuntimeException | StackOverflowError e) {
                    diags.error(st.file, st.line, st.column, "internal assembler error: " + e);
                }
            }
            if (run2 != null && run2.length > 0 && run1 != null)
                System.arraycopy(run1, (int) (dataLoad - textBase), run2, 0, run2.length);
        }

        void emit(Statement st) {
            switch (st.kind) {
                case LABEL, LOCAL_LABEL -> { }
                case ASSIGN -> assign(st, st.name, st.column, st.operand(0), true);
                case INSTRUCTION -> backend.encode(st, ctx, emitter);
                case DIRECTIVE -> emitDirective(st);
            }
        }

        void emitDirective(Statement st) {
            String d = st.name;
            Integer width = dataWidth(d);
            if (width != null) {
                for (Operand op : st.operands) {
                    long v = ctx.eval(op.expr()).value();
                    checkFits(v, width, op.column());
                    emitter.value(v, width);
                }
                return;
            }
            switch (d) {
                case ".ascii", ".asciz", ".string" -> {
                    for (Operand op : st.operands) {
                        emitter.bytes(op.string());
                        if (!d.equals(".ascii"))
                            emitter.u8(0);
                    }
                }
                case ".space", ".skip", ".zero" -> emitter.fill(st.size, fillByte(st, 1));
                case ".fill" -> {
                    long count = ctx.evalAbs(st.operand(0).expr());
                    int size = st.operands.size() > 1 ? (int) ctx.evalAbs(st.operand(1).expr()) : 1;
                    long value = st.operands.size() > 2 ? ctx.evalAbs(st.operand(2).expr()) : 0;
                    if (value == 0 || size == 0)
                        emitter.fill(count * size, 0);
                    else
                        for (long i = 0; i < count; i++)
                            emitter.value(value, size);
                }
                case ".org" -> emitter.fill(st.size, 0);
                case ".align", ".p2align", ".balign" -> {
                    if (st.operands.size() > 1 && !st.operand(1).isEmpty()) {
                        emitter.fill(st.size, fillByte(st, 1));
                    } else if (st.section == Section.TEXT && st.size % backend.instructionAlignment() == 0) {
                        byte[] nop = backend.nopPadding();
                        for (int i = 0; i < st.size; i += nop.length)
                            emitter.bytes(nop);
                    } else {
                        emitter.fill(st.size, 0);
                    }
                }
                case ".uleb128", ".sleb128" -> {
                    for (Operand op : st.operands) {
                        long v = ctx.evalAbs(op.expr());
                        emitter.bytes(leb(v, d.equals(".sleb128")));
                    }
                    if (emitter.count() != st.size)
                        throw new AsmError(st.column, "'" + d + "' value changed between passes");
                }
                case ".equ", ".set" -> assign(st, st.operand(0).identifier(), st.operand(0).column(),
                        st.operand(1), d.equals(".set"));
                default -> {
                    if (!DATA_SIZES.containsKey(d) && !isSectionDirective(st) && !IGNORED.contains(d)
                            && !d.startsWith(".cfi_") && !d.startsWith(".addrsig")
                            && !d.equals(".globl") && !d.equals(".global") && !d.equals(".weak")
                            && !d.equals(".local") && !d.equals(".comm") && !d.equals(".lcomm"))
                        backend.directive(st, ctx);
                }
            }
        }

        int fillByte(Statement st, int index) {
            if (st.operands.size() <= index || st.operand(index).isEmpty())
                return 0;
            long v = ctx.evalAbs(st.operand(index).expr());
            if (v < -128 || v > 255)
                throw new AsmError(st.operand(index).column(), "fill value " + v + " does not fit in a byte");
            return (int) v;
        }

        /** Bytes per value of a data directive, or null if {@code d} is not one; {@code .word} is the backend's. */
        Integer dataWidth(String d) {
            return d.equals(".word") ? Integer.valueOf(backend.wordBytes()) : DATA_SIZES.get(d);
        }

        void checkFits(long v, int width, int column) {
            if (width >= 8)
                return;
            long lo = -(1L << (8 * width - 1)), hi = (1L << (8 * width)) - 1;
            if (v < lo || v > hi)
                throw new AsmError(column, "value " + v + " does not fit in " + width
                        + (width == 1 ? " byte" : " bytes"));
        }

        // ---- image ----

        AsmImage buildImage() {
            List<AsmSegment> segments = new ArrayList<>();
            if (run1 != null && run1.length > 0)
                segments.add(new AsmSegment((int) textBase, run1));
            if (run2 != null && run2.length > 0)
                segments.add(new AsmSegment((int) ctx.base[Section.DATA.ordinal()], run2));

            boolean multi = sources.size() > 1;
            Map<String, Long> table = new LinkedHashMap<>();
            for (int f = 0; f < sources.size(); f++) {
                for (Symbol s : symbols.fileSymbols(f).values()) {
                    if (s.name.startsWith(".L"))
                        continue;
                    boolean global = symbols.isGlobal(f, s.name);
                    Symbol winner = global && symbols.global(s.name) != null ? symbols.global(s.name) : s;
                    Long v = valueOf(winner);
                    if (v == null)
                        continue;
                    String key = global || !multi ? s.name : fileNames.get(f) + ":" + s.name;
                    table.putIfAbsent(key, v);
                }
            }
            for (Symbol s : symbols.predefined())
                table.put(s.name, s.value.value() & 0xFFFF_FFFFL);

            long entry = textBase;
            Symbol start = symbols.global("_start");
            if (start == null) {
                for (int f = 0; f < sources.size() && start == null; f++)
                    start = symbols.defined(f, "_start");
            }
            if (start != null) {
                Long v = valueOf(start);
                if (v != null)
                    entry = v;
            }

            List<Statement> insns = new ArrayList<>();
            for (Statement st : statements)
                if (st.kind == Statement.Kind.INSTRUCTION && !st.failed && st.section != null && st.size > 0
                        && st.section != Section.BSS)
                    insns.add(st);
            insns.sort((a, b) -> Long.compare(addressOf(a), addressOf(b)));
            int[] starts = new int[insns.size()];
            int[] sizes = new int[insns.size()];
            AsmLine[] lines = new AsmLine[insns.size()];
            for (int i = 0; i < insns.size(); i++) {
                Statement st = insns.get(i);
                starts[i] = (int) addressOf(st);
                sizes[i] = st.size;
                lines[i] = new AsmLine(st.fileName, st.line);
            }
            return new AsmImage(segments, (int) entry, (int) ctx.base[Section.BSS.ordinal()],
                    (int) end(Section.BSS), table, starts, sizes, lines);
        }

        long addressOf(Statement st) {
            return ctx.base[st.section.ordinal()] + st.offset;
        }

        Long valueOf(Symbol s) {
            try {
                if (s.kind == SymbolTable.Kind.LABEL && s.section == null)
                    return null;
                if (s.definedBy != null)
                    ctx.current = s.definedBy;
                return ctx.value(s, 1).value() & 0xFFFF_FFFFL;
            } catch (AsmError | AsmContext.Unknown e) {
                return null;
            }
        }
    }

    /** LEB128 bytes of {@code v}, unsigned or signed. */
    static byte[] leb(long v, boolean signed) {
        byte[] buf = new byte[10];
        int n = 0;
        while (true) {
            int b = (int) (v & 0x7F);
            v = signed ? v >> 7 : v >>> 7;
            boolean done = signed ? ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) : v == 0;
            if (!done)
                b |= 0x80;
            buf[n++] = (byte) b;
            if (done)
                return Arrays.copyOf(buf, n);
        }
    }
}
