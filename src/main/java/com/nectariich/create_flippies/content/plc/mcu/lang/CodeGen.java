package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.lang.Ast.*;

/**
 * Lowers Sema's typed tree to VM bytecode, assigning every instruction the cost of
 * the Thumb-2 code arm-none-eabi-gcc -Os would emit for it, and estimating that
 * code's size for the linker.
 *
 * <h2>The cost model</h2>
 * Costs are Cortex-M3 cycles from the Technical Reference Manual (ARM DDI 0337),
 * section 18.2: 1 cycle for data processing and MUL, 2 to 12 for UDIV/SDIV, 2 for a
 * single LDR or STR, 1+N for LDM/STM and PUSH/POP of N registers, 1+P (P = 1..3,
 * taken as 2) for a taken branch plus the two flash wait states every refill pays at
 * 72 MHz (see {@link #TAKEN}), and a not-taken conditional branch at 1. Locals
 * whose address is never taken are assumed to live in registers, as GCC keeps them
 * (the first eight scalar ones, by declaration order; the rest spill, and
 * {@code volatile} ones never get a register), so reading one costs nothing and
 * writing one is at most a MOV. Their bytes are still reserved in the frame in
 * SRAM, so stack depth is as deep as it would be.
 * Software floating point is charged at the cost of the libgcc routines a
 * soft-float M3 calls, as measured for the ARM ieee754 assembly implementations:
 * fadd ~50, fmul ~45, fdiv ~110, fcmp ~25, int/float ~30 cycles; the double
 * versions about twice that.
 *
 * <h2>Code size</h2>
 * Each instruction contributes an estimate of the bytes of Thumb-2 it stands for,
 * from 0 (a read of a register-resident local) through 2 (most 16-bit encodings) to
 * 6-8 (a 32-bit constant with its literal-pool slot, a soft-float call with its
 * argument moves). Over typical sketches this lands within ~15% of what GCC
 * produces, which is what the "Sketch uses N bytes" line needs.
 */
final class CodeGen {

    static final int ALU = CycleClass.ALU.ordinal(), MUL = CycleClass.MUL.ordinal(), DIV = CycleClass.DIV.ordinal(),
            LOAD = CycleClass.LOAD.ordinal(), STORE = CycleClass.STORE.ordinal(), BRANCH = CycleClass.BRANCH.ordinal(),
            SOFT = CycleClass.SOFT_FLOAT.ordinal();

    /**
     * A taken branch: the M3's 1 + P with a pipeline refill of 2, plus the flash wait
     * states. At 72 MHz FLASH_ACR.LATENCY is 2 (RM0008 section 3.3.3) and the prefetch
     * buffer only hides them for sequential fetches, so every refill from a branch
     * target pays them (DS5319's 1.25 DMIPS/MHz is quoted at zero wait states). Calls
     * and returns pay the same in {@link Vm}.
     */
    static final int TAKEN = 3 + Stm32f103cb.FLASH_WAIT_STATES;
    /** A BL to a libgcc helper and its BX LR back: two refills. */
    static final int CALL_REFILL = 2 * TAKEN;

    /** Register-resident locals per function before the rest spill to the stack. */
    private static final int REGISTER_LOCALS = 8;
    /**
     * The saved LR (the VM's return token) and the frame link at the top of every frame.
     * Callee-saved registers are not added on top: the register-resident locals' own
     * slots stand for them.
     */
    private static final int SAVE_AREA = 8;

    // ---- value kinds ----
    static final int K_VOID = 0, K_I32 = 1, K_I64 = 2, K_F32 = 3, K_F64 = 4, K_ADDR = 5;

    static int kind(CType t) {
        if (t == null || t.isVoid())
            return K_VOID;
        if (t.isString() || t.isStruct() || t.isArray())
            return K_ADDR;
        if (t.kind == CType.Kind.FLOAT)
            return K_F32;
        if (t.isDoubleLike())
            return K_F64;
        if (t.is64())
            return K_I64;
        return K_I32;
    }

    /** One function as the VM runs it. */
    static final class FuncInfo {
        final String name;
        int entry;
        int frameSize;
        int[] paramOff;
        int[] paramSize;
        /** Words the prologue pushes (callee-saved registers plus LR). */
        int savedRegs;
        int thumbBytes;
        Sym.Func sym;
        /** Whether it returns a value on the operand stack. */
        boolean returnsValue;

        FuncInfo(String name) {
            this.name = name;
        }
    }

    /** A builtin or indirect call site: what the arguments are and what comes back. */
    static final class CallDesc {
        Sym.Builtin builtin;
        /** The type each argument was converted to: what the VM normalises it by. */
        CType[] argTypes;
        CType ret;
        boolean returnsValue;
        /** Indirect calls: the parameter count the site passes. */
        int argc;
    }

    record CodeReloc(int pc, Object target, long addend) {}

    // ---- output ----
    int[] code = new int[4096];
    int[] lines = new int[4096];
    int pc;
    final List<FuncInfo> funcs = new ArrayList<>();
    final Map<Sym.Func, Integer> funcIndex = new IdentityHashMap<>();
    final List<CallDesc> descs = new ArrayList<>();
    final List<CodeReloc> relocs = new ArrayList<>();
    final List<Sym.Global> extraGlobals = new ArrayList<>();
    int loopCount;
    boolean usesFloat, usesDouble, usesLongDiv, usesLongShift, usesStringFloat;
    final boolean[] intrinsicUsed = new boolean[Intrinsics.Op.values().length];
    int startIndex;

    private final Sema sema;
    private final Diagnostics d;

    // ---- per-function state ----
    private FuncInfo fi;
    private Sym.Func fn;
    private int alloc;
    private int allocMax;
    private int curLine;
    private int bytes;
    private final Map<Sym.Var, Boolean> regVars = new IdentityHashMap<>();
    private final ArrayDeque<ScopeInfo> scopes = new ArrayDeque<>();
    private final Map<String, Label> labelMap = new HashMap<>();
    private final Map<String, List<Object>> labelPaths = new HashMap<>();
    private int lastOp = -1;
    private int chars;

    CodeGen(Sema sema, Diagnostics d) {
        this.sema = sema;
        this.d = d;
    }

    // ================= emission =================

    private static final class Label {
        int pos = -1;
        final List<Integer> fixups = new ArrayList<>();
    }

    private void ensure(int n) {
        if (pc + n >= code.length) {
            int sz = Math.max(code.length * 2, pc + n + 16);
            code = Arrays.copyOf(code, sz);
            lines = Arrays.copyOf(lines, sz);
        }
    }

    private void emit(int op, int cls, int cycles, int thumb, int... operands) {
        ensure(1 + operands.length);
        lines[pc] = curLine;
        code[pc++] = Op.header(op, cls, cycles);
        for (int o : operands) {
            lines[pc] = curLine;
            code[pc++] = o;
        }
        bytes += thumb;
        lastOp = op;
    }

    private void jump(int op, Label l, int cls, int cycles, int thumb) {
        emit(op, cls, cycles, thumb, 0);
        if (l.pos >= 0)
            code[pc - 1] = l.pos;
        else
            l.fixups.add(pc - 1);
    }

    private void place(Label l) {
        l.pos = pc;
        for (int f : l.fixups)
            code[f] = pc;
        l.fixups.clear();
        lastOp = -1;
    }

    private void iconst(long v) {
        int iv = (int) v;
        int cyc, th;
        if (iv >= -255 && iv <= 255) {
            cyc = 0;
            th = 2;
        } else if ((iv & 0xFFFF0000) == 0 || (~iv & 0xFFFF0000) == 0) {
            cyc = 1;
            th = 4;
        } else {
            cyc = 2;
            th = 6;
        }
        emit(Op.ICONST, ALU, cyc, th, iv);
    }

    private void lconst(long v) {
        emit(Op.LCONST, ALU, v == 0 ? 2 : 4, v == 0 ? 4 : 8, (int) v, (int) (v >>> 32));
    }

    /** The address of a linker object, patched in by the linker. */
    private void addrConst(Object target, long addend) {
        emit(Op.ICONST, LOAD, 2, 6, 0);
        relocs.add(new CodeReloc(pc - 1, target, addend));
    }

    private void setLine(int file, int line) {
        curLine = file == Token.SKETCH ? line : 0;
    }

    private void setLine(Node n) {
        if (n != null && n.file == Token.SKETCH && n.line > 0)
            curLine = n.line;
        else if (n != null && n.file != Token.SKETCH)
            curLine = 0;
    }

    // ================= frame allocation =================

    /**
     * A frame slot. The running total is kept below 1 GB: a local array can be close
     * to 2 GB on its own, and a frame that size can never be entered anyway (the call
     * faults with STACK_OVERFLOW, as it would on the chip), so past that the offsets
     * no longer matter, only that they do not wrap. {@link #frameBytes} keeps the true
     * size for the fault message.
     */
    private int allocate(int size, int align) {
        align = Math.max(1, Math.min(align, 8));
        alloc = (alloc + align - 1) / align * align;
        int off = alloc;
        frameBytes = Math.max(frameBytes, (long) alloc + size);
        alloc = (int) Math.min((long) alloc + size, FRAME_CAP);
        allocMax = Math.max(allocMax, alloc);
        return off;
    }

    private static final int FRAME_CAP = 1 << 30;
    /** The largest frame extent asked for in the current function, uncapped. */
    private long frameBytes;

    private final class ScopeInfo {
        final Object node;
        final int mark;
        /** Destructible objects in this scope: frame offsets with their type (or a var whose slot holds the object). */
        final List<Object[]> dtors = new ArrayList<>();
        Label breakL;
        Label contL;
        int loopId = -1;
        boolean isSwitch;

        ScopeInfo(Object node) {
            this.node = node;
            this.mark = alloc;
        }
    }

    private void pushScope(Object node) {
        scopes.push(new ScopeInfo(node));
    }

    private void popScope() {
        ScopeInfo s = scopes.pop();
        destroyScope(s);
        alloc = s.mark;
    }

    private void destroyScope(ScopeInfo s) {
        for (int i = s.dtors.size() - 1; i >= 0; i--) {
            Object[] o = s.dtors.get(i);
            if (o[1] == null) {
                // A variable-length array's storage: SP back to where it was before it.
                emit(Op.LLD32, LOAD, 2, 2, (Integer) o[0]);
                emit(Op.SETSP, ALU, 1, 2);
                continue;
            }
            destroyAt(new FrameBase((Integer) o[0]), (CType) o[1]);
        }
    }

    // ================= bases: where an object being initialised lives =================

    private interface Base {
        /** Pushes the base address plus {@code off}. */
        void push(int off);
    }

    private final class FrameBase implements Base {
        final int off;

        FrameBase(int off) {
            this.off = off;
        }

        @Override
        public void push(int o) {
            emit(Op.LADDR, ALU, 1, 2, off + o);
        }
    }

    private final class GlobalBase implements Base {
        final Object sym;

        GlobalBase(Object sym) {
            this.sym = sym;
        }

        @Override
        public void push(int o) {
            addrConst(sym, o);
        }
    }

    /** A pointer held in a frame slot (a heap object, an sret slot, a by-address parameter). */
    private final class SlotBase implements Base {
        final int slot;

        SlotBase(int slot) {
            this.slot = slot;
        }

        @Override
        public void push(int o) {
            emit(Op.LLD32, LOAD, 2, 2, slot);
            if (o != 0)
                emit(Op.ADDI, ALU, 0, 0, o);
        }
    }

    // ================= program =================

    void run() {
        Sym.Func init = null;
        // Index every function first so calls can be emitted before their callee.
        FuncInfo start = new FuncInfo("_start");
        startIndex = 0;
        funcs.add(start);
        FuncInfo staticInit = null;
        if (!sema.dynamicInits.isEmpty()) {
            staticInit = new FuncInfo("_GLOBAL__sub_I_sketch");
            funcs.add(staticInit);
        }
        for (Sym.Func f : sema.functions) {
            FuncInfo i = new FuncInfo(f.name);
            i.sym = f;
            funcIndex.put(f, funcs.size());
            funcs.add(i);
        }
        genStart(start, staticInit);
        if (staticInit != null)
            genStaticInit(staticInit);
        for (Sym.Func f : sema.functions)
            genFunction(funcs.get(funcIndex.get(f)), f);
        code = Arrays.copyOf(code, pc);
        lines = Arrays.copyOf(lines, pc);
    }

    private void beginFunction(FuncInfo info, Sym.Func f) {
        fi = info;
        fn = f;
        alloc = 0;
        allocMax = 0;
        frameBytes = 0;
        bytes = 0;
        regVars.clear();
        scopes.clear();
        labelMap.clear();
        gotoLoopIds.clear();
        labelPaths.clear();
        info.entry = pc;
        lastOp = -1;
    }

    private void endFunction(int savedRegs) {
        // The frame: every local's slot (a register-resident one's slot stands for the
        // callee-saved register PUSH saves it in, so the pushes are not counted again),
        // the saved LR and frame link, rounded to AAPCS's 8 bytes.
        long locals = Math.max(allocMax, frameBytes);
        fi.savedRegs = savedRegs;
        long frame = (locals + SAVE_AREA + 7) / 8 * 8;
        fi.frameSize = (int) Math.min(frame, Integer.MAX_VALUE);
        // Prologue push + sub sp, epilogue add sp + pop.
        fi.thumbBytes = bytes + 4 + (allocMax > 0 ? 4 : 0);
    }

    private void genStart(FuncInfo info, FuncInfo staticInit) {
        beginFunction(info, null);
        info.paramOff = new int[0];
        info.paramSize = new int[0];
        if (staticInit != null)
            emit(Op.CALL, ALU, 0, 4, 1);
        if (sema.setup != null)
            emit(Op.CALL, ALU, 0, 4, funcIndex.get(sema.setup));
        Label top = new Label();
        place(top);
        if (sema.loop != null)
            emit(Op.CALL, ALU, 0, 4, funcIndex.get(sema.loop));
        emit(Op.LOOP, BRANCH, TAKEN, 2, top.pos, loopCount++);
        endFunction(1);
        // Reset_Handler, SystemInit and main() are in the core's flash count.
        info.thumbBytes = 0;
    }

    private void genStaticInit(FuncInfo info) {
        beginFunction(info, null);
        info.paramOff = new int[0];
        info.paramSize = new int[0];
        pushScope(info);
        for (Sym.Var v : sema.dynamicInits) {
            setLine(v.file, v.line);
            List<Temp> ts = sema.temps.get(v);
            int mark = beginTemps(ts);
            genPlan(sema.dynamicPlans.get(v), new GlobalBase(v), v.isRef ? CType.pointer(v.type.base) : v.type);
            endTemps(ts, mark, null);
        }
        scopes.pop();
        emit(Op.RET, ALU, 0, 2);
        endFunction(4);
    }

    private void genFunction(FuncInfo info, Sym.Func f) {
        beginFunction(info, f);
        setLine(f.def);
        // Parameters: the sret pointer first, then each in order.
        List<Sym.Var> ps = new ArrayList<>();
        if (f.sret != null)
            ps.add(f.sret);
        ps.addAll(f.params);
        info.paramOff = new int[ps.size()];
        info.paramSize = new int[ps.size()];
        for (int i = 0; i < ps.size(); i++) {
            Sym.Var p = ps.get(i);
            int size = p.slotSize();
            p.offset = allocate(size, p.slotAlign());
            info.paramOff[i] = p.offset;
            info.paramSize[i] = size;
        }
        // Register model: the first scalar, never-addressed locals and parameters stay in registers.
        int regs = 0;
        List<Sym.Var> all = new ArrayList<>(f.params);
        all.addAll(f.locals);
        for (Sym.Var v : all) {
            // volatile objects live in memory, as GCC keeps them: every access is a real load or store.
            if (v.addressTaken || !(v.objectType().isScalar()) || v.isRef || v.byAddress || v.type.isVolatile)
                continue;
            int need = v.type.size() > 4 ? 2 : 1;
            if (regs + need > REGISTER_LOCALS)
                break;
            regs += need;
            regVars.put(v, true);
        }
        info.returnsValue = !f.type.base.isVoid() && f.sret == null;
        collectLabelPaths(f.body);
        pushScope(f.body);
        beginTailRecursion(f);
        stmts(f.body.items);
        setLine(f.body.file, f.body.endLine);
        // Running off the end: void returns; a value-returning function returns whatever r0 held (0 here).
        destroyAllScopes();
        if (info.returnsValue) {
            pushZero(f.type.base);
            emit(Op.RETV, ALU, 0, 2);
        } else {
            emit(Op.RET, ALU, 0, 2);
        }
        scopes.clear();
        int saved = Math.min(8, 1 + Math.min(regs, 6));
        endFunction(saved);
    }

    private void pushZero(CType t) {
        switch (kind(t)) {
            case K_I64, K_F64 -> lconst(0);
            default -> iconst(0);
        }
    }

    private void destroyAllScopes() {
        for (ScopeInfo s : scopes)
            destroyScope(s);
    }

    // ================= statements =================

    private void stmt(Stmt s) {
        setLine(s);
        if (s instanceof Block b) {
            pushScope(b);
            stmts(b.items);
            popScope();
        } else if (s instanceof ExprStmt es) {
            List<Temp> ts = sema.temps.get(es);
            int mark = beginTemps(ts);
            effect(es.expr);
            endTemps(ts, mark, null);
        } else if (s instanceof DeclStmt ds) {
            for (Node n : ds.decls)
                if (n instanceof VarDecl vd && vd.var != null)
                    localDecl(vd);
        } else if (s instanceof If i) {
            pushScope(i);
            if (i.init != null)
                stmt(i.init);
            setLine(i);
            Label els = new Label(), end = new Label();
            List<Temp> ts = sema.temps.get(i);
            int mark = beginTemps(ts);
            if (ts != null && hasDtors(ts)) {
                rvalue(i.cond);
                endTemps(ts, mark, null);
                jump(Op.JZ, els, ALU, 0, 2);
            } else {
                condJump(i.cond, els, false);
                endTemps(ts, mark, null);
            }
            scopedStmt(i.then);
            if (i.otherwise != null) {
                jump(Op.JMP, end, BRANCH, TAKEN, 2);
                place(els);
                scopedStmt(i.otherwise);
                place(end);
            } else {
                place(els);
            }
            popScope();
        } else if (s instanceof While w) {
            int id = loopCount++;
            Label top = new Label(), end = new Label();
            place(top);
            condition(w, w.cond, end);
            ScopeInfo loop = loopScope(w, end, top, id);
            scopedStmt(w.body);
            scopes.remove(loop);
            emit(Op.LOOP, BRANCH, TAKEN, 2, top.pos, id);
            place(end);
        } else if (s instanceof DoWhile dw) {
            int id = loopCount++;
            Label top = new Label(), cont = new Label(), end = new Label();
            place(top);
            ScopeInfo loop = loopScope(dw, end, cont, id);
            loop.loopId = -1; // continue goes to the condition, a forward jump
            scopedStmt(dw.body);
            scopes.remove(loop);
            place(cont);
            setLine(dw.cond);
            condition(dw, dw.cond, end);
            emit(Op.LOOP, BRANCH, TAKEN, 2, top.pos, id);
            place(end);
        } else if (s instanceof For f) {
            pushScope(f);
            if (f.init != null)
                stmt(f.init);
            int id = loopCount++;
            Label top = new Label(), cont = new Label(), end = new Label();
            place(top);
            if (f.cond != null) {
                setLine(f.cond);
                condition(f, f.cond, end);
            }
            ScopeInfo loop = loopScope(f, end, cont, id);
            loop.loopId = -1;
            scopedStmt(f.body);
            scopes.remove(loop);
            place(cont);
            if (f.step != null) {
                setLine(f.step);
                List<Temp> ts = sema.temps.get(f.step);
                int mark = beginTemps(ts);
                effect(f.step);
                endTemps(ts, mark, null);
            }
            emit(Op.LOOP, BRANCH, TAKEN, 2, top.pos, id);
            place(end);
            popScope();
        } else if (s instanceof Switch sw) {
            genSwitch(sw);
        } else if (s instanceof Case c) {
            Label l = caseLabels.get(c);
            if (l != null)
                place(l);
            stmt(c.body);
        } else if (s instanceof Labeled l) {
            Label lab = labelMap.computeIfAbsent(l.label, k -> new Label());
            place(lab);
            stmt(l.body);
        } else if (s instanceof Goto g) {
            gotoStmt(g);
        } else if (s instanceof Break) {
            for (ScopeInfo sc : scopes) {
                if (sc.breakL != null) {
                    destroyUpTo(sc);
                    jump(Op.JMP, sc.breakL, BRANCH, TAKEN, 2);
                    return;
                }
            }
        } else if (s instanceof Continue) {
            for (ScopeInfo sc : scopes) {
                if (sc.contL != null) {
                    destroyUpTo(sc);
                    if (sc.loopId >= 0)
                        emit(Op.LOOP, BRANCH, TAKEN, 2, sc.contL.pos, sc.loopId);
                    else
                        jump(Op.JMP, sc.contL, BRANCH, TAKEN, 2);
                    return;
                }
            }
        } else if (s instanceof Return r) {
            returnStmt(r);
        }
    }

    private void scopedStmt(Stmt s) {
        if (s instanceof Block) {
            stmt(s);
            return;
        }
        pushScope(s);
        stmt(s);
        popScope();
    }

    private ScopeInfo loopScope(Object node, Label brk, Label cont, int id) {
        ScopeInfo sc = new ScopeInfo(node);
        sc.breakL = brk;
        sc.contL = cont;
        sc.loopId = id;
        scopes.push(sc);
        return sc;
    }

    /** Runs the destructors of every scope inside {@code target} (not target's own). */
    private void destroyUpTo(ScopeInfo target) {
        for (ScopeInfo sc : scopes) {
            if (sc == target)
                return;
            destroyScope(sc);
        }
    }

    /** A loop condition: temporaries destroyed before the branch. */
    private void condition(Object key, Expr c, Label falseL) {
        List<Temp> ts = sema.temps.get(key);
        int mark = beginTemps(ts);
        if (ts != null && hasDtors(ts)) {
            rvalue(c);
            endTemps(ts, mark, null);
            jump(Op.JZ, falseL, ALU, 0, 2);
        } else {
            condJump(c, falseL, false);
            endTemps(ts, mark, null);
        }
    }

    private static boolean hasDtors(List<Temp> ts) {
        for (Temp t : ts)
            if (t.needsDtor)
                return true;
        return false;
    }

    private final Map<Case, Label> caseLabels = new IdentityHashMap<>();

    private void genSwitch(Switch sw) {
        List<Temp> ts = sema.temps.get(sw);
        int mark = beginTemps(ts);
        rvalue(sw.cond);
        endTemps(ts, mark, null);
        boolean wide = sw.cond.type.is64();
        Label end = new Label();
        Label dflt = sw.dflt != null ? new Label() : end;
        if (sw.dflt != null)
            caseLabels.put(sw.dflt, dflt);
        // The table holds ranges, not single keys: a GNU case range is one entry however
        // wide it is (case 1 ... LLONG_MAX included), and the VM finds the key by binary
        // search. Keys are in the operand stack's representation (sign-extended for
        // 32-bit types), ordered as signed 64-bit values; an unsigned range that crosses
        // the sign bit in that order is split in two.
        java.util.TreeMap<Long, Object[]> ranges = new java.util.TreeMap<>();
        long values = 0;
        for (Case c : sw.cases) {
            Label l = new Label();
            caseLabels.put(c, l);
            long lo = c.constant, hi = sema.caseHi.getOrDefault(c, c.constant);
            // Compare in the switch's own type: an unsigned long long range may cross the sign bit.
            if (Sema.caseCompare(hi, lo, sw.cond.type) < 0)
                continue; // an empty GNU range (Sema warned): it matches nothing
            long rlo = wide ? lo : (long) (int) lo, rhi = wide ? hi : (long) (int) hi;
            if (rlo <= rhi) {
                ranges.put(rlo, new Object[] { rhi, l });
            } else {
                ranges.put(rlo, new Object[] { wide ? Long.MAX_VALUE : (long) Integer.MAX_VALUE, l });
                ranges.put(wide ? Long.MIN_VALUE : (long) Integer.MIN_VALUE, new Object[] { rhi, l });
            }
            values += Math.min(hi - lo + 1L, 1L << 20);
        }
        int n = ranges.size();
        ensure(3 + Op.SWITCH_ENTRY * n);
        lines[pc] = curLine;
        code[pc++] = Op.header(Op.SWITCH, ALU, 4);
        code[pc++] = n;
        int dfix = pc++;
        if (dflt.pos >= 0)
            code[dfix] = dflt.pos;
        else
            dflt.fixups.add(dfix);
        for (Map.Entry<Long, Object[]> e : ranges.entrySet()) {
            long lo = e.getKey(), hi = (Long) e.getValue()[0];
            code[pc++] = (int) lo;
            code[pc++] = (int) (lo >>> 32);
            code[pc++] = (int) hi;
            code[pc++] = (int) (hi >>> 32);
            Label l = (Label) e.getValue()[1];
            if (l.pos >= 0)
                code[pc] = l.pos;
            else
                l.fixups.add(pc);
            pc++;
        }
        // -Os: a compare chain for a few cases (two compares per range), a TBB table for dense ones.
        long span = n == 0 ? 0 : (Long) ranges.lastEntry().getValue()[0] - ranges.firstKey();
        bytes += n <= 4 ? 8 * n + 2 : (span >= 0 && span < 3L * values && span < 4096 ? 8 + (int) span + 1 : 8 * n + 2);
        lastOp = -1;
        ScopeInfo sc = new ScopeInfo(sw);
        sc.isSwitch = true;
        sc.breakL = end;
        scopes.push(sc);
        pushScope(sw.body);
        Stmt body = sw.body;
        if (body instanceof Block b) {
            for (Stmt x : b.items)
                stmt(x);
        } else {
            stmt(body);
        }
        popScope();
        scopes.remove(sc);
        place(end);
        if (dflt != end && dflt.pos < 0)
            place(dflt);
    }

    private void collectLabelPaths(Block body) {
        walkLabels(body, new ArrayList<>());
    }

    private void walkLabels(Stmt s, List<Object> path) {
        if (s == null)
            return;
        if (s instanceof Block b) {
            path.add(b);
            for (Stmt x : b.items)
                walkLabels(x, path);
            path.remove(path.size() - 1);
        } else if (s instanceof Labeled l) {
            labelPaths.put(l.label, new ArrayList<>(path));
            walkLabels(l.body, path);
        } else if (s instanceof If i) {
            path.add(i);
            walkLabels(i.then, path);
            walkLabels(i.otherwise, path);
            path.remove(path.size() - 1);
        } else if (s instanceof While w) {
            walkLabels(w.body, path);
        } else if (s instanceof DoWhile dw) {
            walkLabels(dw.body, path);
        } else if (s instanceof For f) {
            path.add(f);
            walkLabels(f.body, path);
            path.remove(path.size() - 1);
        } else if (s instanceof Switch sw) {
            walkLabels(sw.body, path);
        } else if (s instanceof Case c) {
            walkLabels(c.body, path);
        }
    }

    private void gotoStmt(Goto g) {
        List<Object> target = labelPaths.getOrDefault(g.label, List.of());
        // Leave every scope that is not on the label's path.
        for (ScopeInfo sc : scopes) {
            if (sc.node instanceof Block || sc.node instanceof If || sc.node instanceof For) {
                if (target.contains(sc.node))
                    break;
                destroyScope(sc);
            }
        }
        Label l = labelMap.computeIfAbsent(g.label, k -> new Label());
        if (l.pos >= 0) {
            // A backward goto is a loop like any other: the idle fast-forward watches it too.
            int id = gotoLoopIds.computeIfAbsent(g.label, k -> loopCount++);
            emit(Op.LOOP, BRANCH, TAKEN, 2, l.pos, id);
            return;
        }
        jump(Op.JMP, l, BRANCH, TAKEN, 2);
    }

    /** Loop ids of labels that a backward goto targets, in the current function. */
    private final Map<String, Integer> gotoLoopIds = new HashMap<>();

    private void returnStmt(Return r) {
        List<Temp> ts = sema.temps.get(r);
        int mark = beginTemps(ts);
        InitPlan plan = sema.returnPlans.get(r);
        if (plan != null) {
            genPlan(plan, new SlotBase(fn.sret.offset), fn.type.base);
            endTemps(ts, mark, null);
            destroyAllScopes();
            emit(Op.RET, ALU, 0, 2);
            return;
        }
        if (r.value == null) {
            destroyAllScopes();
            if (fi.returnsValue) {
                pushZero(fn.type.base);
                emit(Op.RETV, ALU, 0, 2);
            } else {
                emit(Op.RET, ALU, 0, 2);
            }
            return;
        }
        if (fi.returnsValue) {
            if (tailOk && (ts == null || ts.isEmpty()) && containsSelfCall(r.value)) {
                tailReturn(r.value);
                return;
            }
            rvalue(r.value);
            endTemps(ts, mark, null);
            destroyAllScopes();
            applyAccumulators();
            emit(Op.RETV, ALU, 0, 2);
        } else {
            effect(r.value);
            endTemps(ts, mark, null);
            destroyAllScopes();
            emit(Op.RET, ALU, 0, 2);
        }
    }

    // ================= tail recursion =================
    //
    // arm-none-eabi-gcc -Os runs -foptimize-sibling-calls and the tail-recursion pass,
    // which turn a function that returns a call to itself into a loop, also when the
    // call's result is first added to or multiplied by something (an accumulator):
    // "return n == 0 ? 0 : 1 + d(n - 1)" runs in one frame on the board, however deep.
    // Without the same here such a sketch would overflow the stack in the emulator
    // and not on the chip. Lowered as GCC does: the arguments are evaluated, stored
    // into the parameters, and the body is entered again; "A + M * self(...)" folds
    // A and M into two hidden accumulators that every other return applies
    // (value = accA + accM * value). Only where it is provably the same program:
    // integer results (wrapping + and * are associative), scalar parameters, no local
    // or parameter whose address is taken (the frame is reused), nothing to destroy,
    // no variable-length array, and an operand evaluated after the call in the
    // source only if the call cannot change it (a constant or a local).

    /** The function's body may be re-entered by a tail self-call. */
    private boolean tailOk;
    private Label tailEntry;
    /** The accumulators, or null when no tail site needs one. */
    private Sym.Var accAdd, accMul;

    private void beginTailRecursion(Sym.Func f) {
        tailOk = false;
        tailEntry = null;
        accAdd = null;
        accMul = null;
        if (!fi.returnsValue || f.type.variadic || kind(f.type.base) == K_F32 || kind(f.type.base) == K_F64
                || !f.type.base.isInteger() && !f.type.base.isPointer())
            return;
        for (Sym.Var p : f.params)
            if (p.addressTaken || p.byAddress || !p.objectType().isScalar() && !p.isRef)
                return;
        for (Sym.Var v : f.locals)
            if (v.addressTaken || v.type.isNonTrivial() || v.vlaBound != null)
                return;
        // Which accumulators the tail sites need.
        boolean[] need = new boolean[3];
        scanTails(f.body, need);
        if (!need[0])
            return;
        tailOk = true;
        CType t = f.type.base.unqualified();
        if (need[1]) {
            accAdd = hiddenRegister("tail.add", t);
            pushZero(t);
            storeLocal(accAdd);
        }
        if (need[2]) {
            accMul = hiddenRegister("tail.mul", t);
            if (kind(t) == K_I64)
                lconst(1);
            else
                iconst(1);
            storeLocal(accMul);
        }
        tailEntry = new Label();
        place(tailEntry);
    }

    private Sym.Var hiddenRegister(String name, CType t) {
        Sym.Var v = new Sym.Var(name, t, Sym.Var.Storage.LOCAL, 0, 0, 0);
        v.offset = allocate(t.size(), t.align());
        regVars.put(v, true);
        return v;
    }

    /** Finds tail sites: need[0] any, need[1] one with an addend, need[2] one with a factor. */
    private void scanTails(Stmt s, boolean[] need) {
        if (s instanceof Block b) {
            for (int i = 0; i < b.items.size(); i++) {
                if (callThenReturn(b.items, i) != null)
                    need[0] = true;
                scanTails(b.items.get(i), need);
            }
        } else if (s instanceof Return r && r.value != null && sema.returnPlans.get(r) == null) {
            List<Temp> ts = sema.temps.get(r);
            if (ts == null || ts.isEmpty())
                scanTailExpr(r.value, need);
        } else if (s instanceof If i) {
            scanTails(i.then, need);
            if (i.otherwise != null)
                scanTails(i.otherwise, need);
        } else if (s instanceof While w) {
            scanTails(w.body, need);
        } else if (s instanceof DoWhile dw) {
            scanTails(dw.body, need);
        } else if (s instanceof For f) {
            scanTails(f.body, need);
        } else if (s instanceof Switch sw) {
            scanTails(sw.body, need);
        } else if (s instanceof Case c) {
            scanTails(c.body, need);
        } else if (s instanceof Labeled l) {
            scanTails(l.body, need);
        }
    }

    private void scanTailExpr(Expr e, boolean[] need) {
        e = stripNop(e);
        if (e instanceof Cond c) {
            scanTailExpr(c.a, need);
            scanTailExpr(c.b, need);
            return;
        }
        List<Object[]> layers = tailLayers(e);
        if (layers == null)
            return;
        need[0] = true;
        for (Object[] l : layers) {
            if (l[0].equals("+"))
                need[1] = true;
            else
                need[2] = true;
        }
    }

    private static Expr stripNop(Expr e) {
        while (e instanceof Conv c && c.k == Conv.K.NOP)
            e = c.e;
        return e;
    }

    private boolean isSelfCall(Expr e) {
        e = stripNop(e);
        return e instanceof Call c && c.target == fn && fn != null && c.sret == null && c.args.size() == fn.params.size();
    }

    /**
     * {@code self(...)} wrapped in {@code +} / {@code *} layers, outermost first, each
     * {operator, the other operand, whether it is the left one}; null if {@code e} is
     * not such a form (an empty list for the bare call).
     */
    private List<Object[]> tailLayers(Expr e) {
        List<Object[]> layers = new ArrayList<>();
        CType rt = fn.type.base.unqualified();
        while (true) {
            e = stripNop(e);
            if (isSelfCall(e))
                return layers;
            if (!(e instanceof Binary b) || !(b.op.equals("+") || b.op.equals("*")) || b.opType == null
                    || !b.opType.unqualified().equals(rt) || !rt.isInteger() || b.stride != 0)
                return null;
            boolean leftTail = containsSelfCallShallow(b.l);
            boolean rightTail = containsSelfCallShallow(b.r);
            if (leftTail == rightTail)
                return null;
            Expr other = leftTail ? b.r : b.l;
            // An operand left of the call is evaluated before it, here as in the source;
            // one the source evaluates after the call must be one the call cannot change.
            if (leftTail && !stableAcrossCall(other))
                return null;
            layers.add(new Object[] { b.op, other, !leftTail });
            e = leftTail ? b.l : b.r;
        }
    }

    private boolean containsSelfCallShallow(Expr e) {
        e = stripNop(e);
        if (isSelfCall(e))
            return true;
        return e instanceof Binary b && (b.op.equals("+") || b.op.equals("*")) && (containsSelfCallShallow(b.l) || containsSelfCallShallow(b.r));
    }

    private boolean containsSelfCall(Expr e) {
        e = stripNop(e);
        if (e instanceof Cond c)
            return containsSelfCall(c.a) || containsSelfCall(c.b);
        return tailLayers(e) != null;
    }

    /** A literal, or a local or parameter (never address-taken here): nothing a call can change. */
    private static boolean stableAcrossCall(Expr e) {
        e = stripNop(e);
        if (e instanceof IntLit)
            return true;
        if (e instanceof Conv c && c.k == Conv.K.INT)
            return stableAcrossCall(c.e);
        return e instanceof VarRef v && (v.var.storage == Sym.Var.Storage.LOCAL || v.var.storage == Sym.Var.Storage.PARAM)
                && !v.var.isRef && !v.var.type.isVolatile;
    }

    /**
     * {@code T r = self(...); return r;}: the call's result returned unchanged. Returns
     * the call, or null.
     */
    private Call callThenReturn(List<Stmt> items, int i) {
        if (fn == null || i + 1 >= items.size() || !(items.get(i) instanceof DeclStmt ds) || ds.decls.size() != 1
                || !(ds.decls.get(0) instanceof VarDecl vd) || vd.var == null || !(items.get(i + 1) instanceof Return r))
            return null;
        Sym.Var v = vd.var;
        if (v.storage != Sym.Var.Storage.LOCAL || v.isRef || v.addressTaken || v.vlaBound != null
                || !v.type.unqualified().equals(fn.type.base.unqualified()) || r.value == null || !(stripNop(r.value) instanceof VarRef rv)
                || rv.var != v)
            return null;
        List<Temp> ts = sema.temps.get(vd);
        if (ts != null && !ts.isEmpty())
            return null;
        InitPlan plan = sema.localPlans.get(vd);
        if (plan == null || plan.zeroFill || plan.leaves.size() != 1 || plan.leaves.get(0).k != Leaf.K.SCALAR)
            return null;
        Expr init = stripNop(plan.leaves.get(0).value);
        return isSelfCall(init) ? (Call) init : null;
    }

    /** A statement list, with "T r = self(...); return r;" taken as one tail call. */
    private void stmts(List<Stmt> items) {
        for (int i = 0; i < items.size(); i++) {
            Call c = tailOk ? callThenReturn(items, i) : null;
            if (c != null) {
                setLine(items.get(i));
                tailReturn(c);
                return; // what follows the return is unreachable
            }
            stmt(items.get(i));
        }
    }

    /** {@code return e} where {@code e} holds a self-call in tail position. */
    private void tailReturn(Expr e) {
        e = stripNop(e);
        if (e instanceof Cond c) {
            Label els = new Label();
            condJump(c.c, els, false);
            tailArm(c.a);
            place(els);
            tailArm(c.b);
            return;
        }
        tailArm(e);
    }

    private void tailArm(Expr e) {
        e = stripNop(e);
        if (e instanceof Cond) {
            tailReturn(e);
            return;
        }
        List<Object[]> layers = tailLayers(e);
        if (layers == null) {
            rvalue(e);
            destroyAllScopes();
            applyAccumulators();
            emit(Op.RETV, ALU, 0, 2);
            return;
        }
        Expr inner = e;
        for (Object[] l : layers) {
            Binary b = (Binary) stripNop(inner);
            inner = (Boolean) l[2] ? b.r : b.l;
        }
        Call call = (Call) stripNop(inner);
        CType t = fn.type.base.unqualified();
        int mark = alloc;
        // The source's order: operands left of the call, the arguments, then those right of it.
        int[] slots = new int[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            if ((Boolean) layers.get(i)[2]) {
                rvalue((Expr) layers.get(i)[1]);
                slots[i] = spill(t);
            }
        }
        for (Expr a : call.args)
            rvalue(a);
        for (int i = 0; i < layers.size(); i++) {
            if (!(Boolean) layers.get(i)[2]) {
                rvalue((Expr) layers.get(i)[1]);
                slots[i] = spill(t);
            }
        }
        for (int i = fn.params.size() - 1; i >= 0; i--)
            storeLocal(fn.params.get(i));
        // Outermost first: value = accA + accM * (x op inner).
        boolean wide = kind(t) == K_I64;
        for (int i = 0; i < layers.size(); i++) {
            emit(wide ? Op.LLD64 : Op.LLD32, ALU, 0, 0, slots[i]);
            if (layers.get(i)[0].equals("+")) {
                if (accMul != null) {
                    loadLocal(accMul);
                    emit(wide ? Op.LMUL : Op.MUL, MUL, wide ? 3 : 1, 2);
                }
                loadLocal(accAdd);
                emit(wide ? Op.LADD : Op.ADD, ALU, wide ? 2 : 1, 2);
                storeLocal(accAdd);
            } else {
                loadLocal(accMul);
                emit(wide ? Op.LMUL : Op.MUL, MUL, wide ? 3 : 1, 2);
                storeLocal(accMul);
            }
        }
        alloc = mark;
        destroyAllScopes();
        jump(Op.JMP, tailEntry, BRANCH, TAKEN, 2);
    }

    /** Keeps the value on top of the stack in a scratch slot standing for a register. */
    private int spill(CType t) {
        int s = allocate(t.size(), Math.min(t.align(), 8));
        emit(kind(t) == K_I64 ? Op.LST64 : Op.LST32, ALU, 0, 0, s);
        return s;
    }

    /** At a return that is not a tail call: the accumulated additions and factors. */
    private void applyAccumulators() {
        if (accMul == null && accAdd == null)
            return;
        boolean wide = kind(fn.type.base) == K_I64;
        if (accMul != null) {
            loadLocal(accMul);
            emit(wide ? Op.LMUL : Op.MUL, MUL, wide ? 3 : 1, 2);
        }
        if (accAdd != null) {
            loadLocal(accAdd);
            emit(wide ? Op.LADD : Op.ADD, ALU, wide ? 2 : 1, 2);
        }
        if (!wide)
            narrow(fn.type.base);
    }

    // ================= temporaries =================

    /** Allocates a full-expression's temporaries; zeroes the destructible ones so a skipped branch destroys nothing. */
    private int beginTemps(List<Temp> ts) {
        int mark = alloc;
        if (ts == null)
            return mark;
        for (Temp t : ts) {
            t.offset = allocate(t.type.size(), t.type.align());
            if (t.needsDtor) {
                emit(Op.LADDR, ALU, 0, 0, t.offset);
                emit(Op.MEMZERO, STORE, 0, 0, t.type.size());
            }
        }
        return mark;
    }

    /** Destroys a full-expression's temporaries (except {@code keep}, whose lifetime a reference extended). */
    private void endTemps(List<Temp> ts, int mark, Temp keep) {
        if (ts == null)
            return;
        for (int i = ts.size() - 1; i >= 0; i--) {
            Temp t = ts.get(i);
            if (t == keep || !t.needsDtor)
                continue;
            destroyAt(new FrameBase(t.offset), t.type);
        }
        if (keep == null)
            alloc = mark;
    }

    // ================= declarations =================

    private void localDecl(VarDecl vd) {
        Sym.Var v = vd.var;
        setLine(vd);
        if (v.storage == Sym.Var.Storage.STATIC_LOCAL) {
            if (v.staticInit == null)
                return;
            // A guarded one-time initialisation (-fno-threadsafe-statics: no lock).
            Label done = new Label();
            addrConst(v.guard, 0);
            emit(Op.LD8U, LOAD, 2, 2);
            jump(Op.JNZ, done, ALU, 0, 2);
            List<Temp> ts = sema.temps.get(vd);
            int mark = beginTemps(ts);
            genPlan(v.staticInit, new GlobalBase(v), v.isRef ? CType.pointer(v.type.base) : v.type);
            endTemps(ts, mark, null);
            addrConst(v.guard, 0);
            iconst(1);
            emit(Op.ST8, STORE, 2, 2);
            place(done);
            return;
        }
        if (v.storage != Sym.Var.Storage.LOCAL)
            return;
        if (v.vlaBound != null) {
            vlaDecl(vd, v);
            return;
        }
        v.offset = allocate(v.slotSize(), v.slotAlign());
        InitPlan plan = sema.localPlans.get(vd);
        CType t = v.isRef ? CType.pointer(v.type.base) : v.type;
        List<Temp> ts = sema.temps.get(vd);
        // A reference bound to a temporary keeps it alive to the end of the scope.
        Temp keep = null;
        if (v.isRef && plan != null && plan.leaves.size() == 1 && plan.leaves.get(0).value instanceof AddrOf a)
            keep = tempOf(a.e);
        int mark = beginTemps(ts);
        // A const scalar with a constant initialiser is folded into every use, so GCC never stores it.
        if (plan != null && !(v.constValue != null && !v.addressTaken))
            genPlan(plan, new FrameBase(v.offset), t);
        endTemps(ts, mark, keep);
        if (keep != null && keep.needsDtor)
            scopes.peek().dtors.add(new Object[] { keep.offset, keep.type });
        if (!v.isRef && v.type.isNonTrivial())
            scopes.peek().dtors.add(new Object[] { v.offset, v.type });
    }

    /**
     * A variable-length array: save SP (restored when the scope ends, by any way out
     * of it), compute the size, lower SP by it rounded up to 8 (faulting below SRAM,
     * as a frame does), and keep the address. What GCC emits: the multiply, ADDS #7,
     * BIC #7, SUB SP, MOV.
     */
    private void vlaDecl(VarDecl vd, Sym.Var v) {
        v.offset = allocate(4, 4);
        v.vlaSize.offset = allocate(4, 4);
        int saved = allocate(4, 4);
        emit(Op.GETSP, ALU, 1, 2);
        emit(Op.LST32, STORE, 2, 2, saved);
        List<Temp> ts = sema.temps.get(vd);
        int mark = beginTemps(ts);
        rvalue(v.vlaBound);
        endTemps(ts, mark, null);
        int es = v.type.base.size();
        if (es != 1) {
            iconst(es);
            emit(Op.MUL, MUL, 1, 2);
        }
        emit(Op.DUP, ALU, 0, 0);
        storeLocal(v.vlaSize);
        emit(Op.ALLOCA, ALU, 3, 6);
        emit(Op.LST32, STORE, 2, 2, v.offset);
        scopes.peek().dtors.add(new Object[] { saved, null });
    }

    // ================= initialiser plans =================

    private void genPlan(InitPlan plan, Base base, CType t) {
        if (plan == null)
            return;
        if (plan.zeroFill) {
            base.push(0);
            emit(Op.MEMZERO, STORE, 2 + (t.size() + 3) / 4, t.size() <= 16 ? 2 + t.size() / 2 : 10, t.size());
        }
        for (Leaf l : plan.leaves)
            genLeaf(l, base);
    }

    private void genLeaf(Leaf l, Base base) {
        switch (l.k) {
            case SCALAR -> {
                base.push(l.offset);
                rvalue(l.value);
                // An initialiser's target may be a packed struct's member: never the faulting STRD.
                store(l.type, true);
            }
            case BITFIELD -> {
                base.push(l.offset);
                rvalue(l.value);
                storeBitfield(l.field, false);
            }
            case CHARS -> {
                Sym.Global g = new Sym.Global(".Lchars" + chars++, l.chars.length, 1, Sym.Global.Section.RODATA);
                g.data = l.chars;
                extraGlobals.add(g);
                base.push(l.offset);
                addrConst(g, 0);
                memcpy(l.chars.length);
            }
            case STRING_CTOR -> {
                base.push(l.offset);
                for (Expr a : l.ctorArgs)
                    rvalue(a);
                intrinsic(l.ctor, 1 + l.ctorArgs.size());
            }
            case BLOCK_COPY -> copyConstruct(l.type, base, l.offset, l.value);
            default -> throw new IllegalStateException(l.k.toString());
        }
    }

    /** Builds a struct at base+off as a copy of (or, from a temporary, by moving) {@code src}. */
    private void copyConstruct(CType t, Base base, int off, Expr src) {
        if (!t.isNonTrivial()) {
            base.push(off);
            rvalue(src);
            memcpy(t.size());
            return;
        }
        boolean fromTemp = SemaBase.isTempClass(src);
        int mark = alloc;
        int s = allocate(4, 4);
        rvalue(src);
        emit(Op.LST32, STORE, 2, 2, s);
        base.push(off);
        emit(Op.LLD32, LOAD, 2, 2, s);
        memcpy(t.size());
        if (!fromTemp) {
            // The bytes came over; give each String member a buffer of its own.
            for (int o : stringOffsets(t)) {
                base.push(off + o);
                emit(Op.LLD32, LOAD, 2, 2, s);
                if (o != 0)
                    emit(Op.ADDI, ALU, 0, 0, o);
                intrinsic(Intrinsics.Op.STR_CTOR_COPY, 2);
            }
        } else {
            // Moved: the temporary gives its buffers up, so its destructor frees nothing.
            for (int o : stringOffsets(t)) {
                emit(Op.LLD32, LOAD, 2, 2, s);
                if (o != 0)
                    emit(Op.ADDI, ALU, 0, 0, o);
                emit(Op.MEMZERO, STORE, 3, 4, 12);
            }
        }
        alloc = mark;
    }

    /** Byte offsets of every String inside an object of type {@code t}. */
    static List<Integer> stringOffsets(CType t) {
        List<Integer> out = new ArrayList<>();
        collectStrings(t, 0, out);
        return out;
    }

    private static void collectStrings(CType t, int off, List<Integer> out) {
        if (t.isString()) {
            out.add(off);
        } else if (t.isArray()) {
            if (t.base.isNonTrivial())
                for (int i = 0; i < t.length; i++)
                    collectStrings(t.base, off + i * t.base.size(), out);
        } else if (t.isStruct()) {
            for (CType.Field f : t.struct.fields)
                if (f.type.isNonTrivial())
                    collectStrings(f.type, off + f.offset, out);
        }
    }

    /** Runs the destructor of an object of type {@code t} at {@code base}. */
    private void destroyAt(Base base, CType t) {
        if (t.isString()) {
            base.push(0);
            intrinsic(Intrinsics.Op.STR_DTOR, 1);
            return;
        }
        if (t.isArray() && t.base.isString() && t.length > 2) {
            base.push(0);
            iconst(t.length);
            iconst(12);
            intrinsic(Intrinsics.Op.STR_DTOR_ARRAY, 3);
            return;
        }
        List<Integer> offs = stringOffsets(t);
        for (int i = offs.size() - 1; i >= 0; i--) {
            base.push(offs.get(i));
            intrinsic(Intrinsics.Op.STR_DTOR, 1);
        }
    }

    // ================= loads, stores, intrinsics =================

    private void load(CType t) {
        load(t, false);
    }

    /**
     * Loads a {@code t} from the address on the stack. A 64-bit value is one LDRD,
     * which faults on an address that is not word aligned (UsageFault, whatever
     * UNALIGN_TRP says) — unless {@code unaligned}: a member of a packed struct, for
     * which GCC knows the alignment is lost and loads it a piece at a time.
     */
    private void load(CType t, boolean unaligned) {
        CType u = t.isEnum() ? t.base : t;
        switch (u.kind) {
            case BOOL, CHAR, UCHAR -> emit(Op.LD8U, LOAD, 2, 2);
            case SCHAR -> emit(Op.LD8S, LOAD, 2, 2);
            case SHORT -> emit(Op.LD16S, LOAD, 2, 2);
            case USHORT -> emit(Op.LD16U, LOAD, 2, 2);
            case LLONG, ULLONG, DOUBLE, LDOUBLE -> {
                if (unaligned)
                    emit(Op.LD64U, LOAD, 4, 8);
                else
                    emit(Op.LD64, LOAD, 3, 4);
            }
            default -> emit(Op.LD32, LOAD, 2, 2);
        }
    }

    private void store(CType t) {
        store(t, false);
    }

    /** Stores a {@code t}; as {@link #load(CType, boolean)} for 64-bit values (STRD). */
    private void store(CType t, boolean unaligned) {
        CType u = t.isEnum() ? t.base : t;
        switch (u.kind) {
            case BOOL, CHAR, UCHAR, SCHAR -> emit(Op.ST8, STORE, 2, 2);
            case SHORT, USHORT -> emit(Op.ST16, STORE, 2, 2);
            case LLONG, ULLONG, DOUBLE, LDOUBLE -> {
                if (unaligned)
                    emit(Op.ST64U, STORE, 4, 8);
                else
                    emit(Op.ST64, STORE, 3, 4);
            }
            default -> emit(Op.ST32, STORE, 2, 2);
        }
    }

    /**
     * Whether GCC would treat the lvalue's address as possibly unaligned: a member
     * reached through a packed (or {@code #pragma pack}ed) member chain, whose
     * alignment is below a word.
     */
    static boolean lvalueMaybeUnaligned(Expr e) {
        while (e instanceof Conv c && c.k == Conv.K.NOP)
            e = c.e;
        while (e instanceof Ast.Member m) {
            CType ot = m.obj.type;
            if (m.arrow && ot != null && ot.isPointer())
                ot = ot.base;
            if (ot != null && ot.isStruct() && ot.struct.fieldAlign(m.field) < 4)
                return true;
            if (m.arrow)
                return false;
            e = m.obj;
            while (e instanceof Conv c && c.k == Conv.K.NOP)
                e = c.e;
        }
        return false;
    }

    private boolean isReg(Sym.Var v) {
        return regVars.containsKey(v);
    }

    private void loadLocal(Sym.Var v) {
        CType u = v.type.isEnum() ? v.type.base : v.type;
        boolean reg = isReg(v);
        int cyc = reg ? 0 : 2, th = reg ? 0 : 2, cls = LOAD;
        switch (u.kind) {
            case BOOL, CHAR, UCHAR -> emit(Op.LLD8U, cls, cyc, th, v.offset);
            case SCHAR -> emit(Op.LLD8S, cls, cyc, th, v.offset);
            case SHORT -> emit(Op.LLD16S, cls, cyc, th, v.offset);
            case USHORT -> emit(Op.LLD16U, cls, cyc, th, v.offset);
            case LLONG, ULLONG, DOUBLE, LDOUBLE -> emit(Op.LLD64, cls, reg ? 0 : 3, th * 2, v.offset);
            default -> emit(Op.LLD32, cls, cyc, th, v.offset);
        }
    }

    private void storeLocal(Sym.Var v) {
        CType u = v.type.isEnum() ? v.type.base : v.type;
        boolean reg = isReg(v);
        boolean computed = lastOp >= Op.ADD && lastOp <= Op.LNOTB;
        int cyc = reg ? (computed ? 0 : 1) : 2, th = reg ? (computed ? 0 : 2) : 2;
        int cls = reg ? ALU : STORE;
        switch (u.kind) {
            case BOOL, CHAR, UCHAR, SCHAR -> emit(Op.LST8, cls, cyc, th, v.offset);
            case SHORT, USHORT -> emit(Op.LST16, cls, cyc, th, v.offset);
            case LLONG, ULLONG, DOUBLE, LDOUBLE -> emit(Op.LST64, cls, reg ? cyc * 2 : 3, th * 2, v.offset);
            default -> emit(Op.LST32, cls, cyc, th, v.offset);
        }
    }

    private void memcpy(int n) {
        int words = (n + 3) / 4;
        emit(Op.MEMCPY, LOAD, 2 + 2 * words, n <= 16 ? 4 + words : 10, n);
    }

    private void intrinsic(Intrinsics.Op op, int argc) {
        intrinsicUsed[op.ordinal()] = true;
        if (op == Intrinsics.Op.STR_CTOR_FLOAT || op == Intrinsics.Op.STR_CTOR_DOUBLE || op == Intrinsics.Op.STR_CONCAT_FLOAT
                || op == Intrinsics.Op.STR_CONCAT_DOUBLE || op == Intrinsics.Op.STR_SUM_FLOAT || op == Intrinsics.Op.STR_SUM_DOUBLE
                || op == Intrinsics.Op.STR_TO_FLOAT || op == Intrinsics.Op.STR_TO_DOUBLE) {
            usesStringFloat = true;
            usesDouble = true;
        }
        emit(Op.INTR, ALU, 0, 4 + 2 * Math.min(argc, 4), op.ordinal(), argc);
    }

    // ================= expressions: effect =================

    /**
     * Evaluates for side effects only. The instructions an expression emits carry its
     * line; once it is done the enclosing expression's line is back in force, so an
     * operand that comes from elsewhere (a default argument, which belongs to the
     * declaration or the core) does not take the rest of the call with it.
     */
    void effect(Expr e) {
        int saved = curLine;
        setLine(e);
        effect0(e);
        curLine = saved;
    }

    private void effect0(Expr e) {
        if (e instanceof Assign a) {
            assign(a, EFFECT);
            return;
        }
        if (e instanceof Unary u && (u.op.equals("++x") || u.op.equals("--x") || u.op.equals("x++") || u.op.equals("x--"))) {
            incDec(u, false);
            return;
        }
        if (e instanceof Conv c && c.k == Conv.K.TO_VOID) {
            effect(c.e);
            return;
        }
        if (e instanceof Binary b && b.op.equals(",")) {
            effect(b.l);
            effect(b.r);
            return;
        }
        if (e instanceof Cond c && kind(c.type) == K_VOID) {
            Label els = new Label(), end = new Label();
            condJump(c.c, els, false);
            effect(c.a);
            jump(Op.JMP, end, BRANCH, TAKEN, 2);
            place(els);
            effect(c.b);
            place(end);
            return;
        }
        if (e instanceof IntLit || e instanceof FloatLit || e instanceof VarRef)
            return;
        int k = valueKind(e);
        rvalue(e);
        if (k != K_VOID)
            emit(Op.POP, ALU, 0, 0);
    }

    /** What {@link #rvalue} leaves on the stack for {@code e}. */
    private static int valueKind(Expr e) {
        if (e instanceof Call c && c.sret != null)
            return K_ADDR;
        if (e instanceof Intrinsic i && i.returnsThis)
            return K_ADDR;
        return kind(e.type);
    }

    // ================= expressions: addresses =================

    /** Pushes the address of an lvalue or of a temporary-backed object. */
    void addr(Expr e) {
        if (e instanceof VarRef vr) {
            Sym.Var v = vr.var;
            switch (v.storage) {
                case LOCAL, PARAM -> {
                    if (v.isRef || v.byAddress)
                        emit(Op.LLD32, LOAD, isReg(v) ? 0 : 2, 2, v.offset);
                    else
                        emit(Op.LADDR, ALU, 1, 2, v.offset);
                }
                default -> {
                    addrConst(v, 0);
                    if (v.isRef)
                        emit(Op.LD32, LOAD, 2, 2);
                }
            }
            return;
        }
        if (e instanceof StrLit s) {
            addrConst(s.global, 0);
            return;
        }
        if (e instanceof Deref dr) {
            rvalue(dr.p);
            return;
        }
        if (e instanceof Ast.Member m) {
            if (m.arrow)
                rvalue(m.obj);
            else
                addr(m.obj);
            if (m.field.offset != 0)
                emit(Op.ADDI, ALU, 0, 0, m.field.offset);
            return;
        }
        if (e instanceof Conv c && (c.k == Conv.K.NOP)) {
            addr(c.e);
            return;
        }
        if (e instanceof TempRef tr) {
            initTemp(tr);
            emit(Op.LADDR, ALU, 1, 2, tr.temp.offset);
            return;
        }
        if (e instanceof Call || e instanceof StringSum || e instanceof Intrinsic) {
            rvalue(e);
            return;
        }
        if (e instanceof Cond c) {
            Label els = new Label(), end = new Label();
            condJump(c.c, els, false);
            addr(c.a);
            jump(Op.JMP, end, BRANCH, TAKEN, 2);
            place(els);
            addr(c.b);
            place(end);
            return;
        }
        if (e instanceof Binary b && b.op.equals(",")) {
            effect(b.l);
            addr(b.r);
            return;
        }
        if (e instanceof Assign a) {
            assign(a, ADDRESS);
            return;
        }
        if (e instanceof Unary u && (u.op.equals("++x") || u.op.equals("--x"))) {
            addr(u.e);
            emit(Op.DUP, ALU, 0, 0);
            incDecAt(u, false, false);
            return;
        }
        throw new IllegalStateException("not an lvalue: " + e.getClass().getSimpleName());
    }

    // ================= expressions: values =================

    /** Pushes the value of {@code e} (for class types, its address); lines as for {@link #effect}. */
    void rvalue(Expr e) {
        int saved = curLine;
        setLine(e);
        rvalue0(e);
        curLine = saved;
    }

    private void rvalue0(Expr e) {
        if (e instanceof IntLit l) {
            int k = kind(l.type);
            if (k == K_I64)
                lconst(l.value);
            else if (k == K_F32)
                iconst(Float.floatToRawIntBits((float) l.value));
            else if (k == K_F64)
                lconst(Double.doubleToRawLongBits(l.value));
            else
                iconst(l.value);
            return;
        }
        if (e instanceof FloatLit f) {
            if (kind(f.type) == K_F32)
                iconst(Float.floatToRawIntBits((float) f.value));
            else
                lconst(Double.doubleToRawLongBits(f.value));
            return;
        }
        if (e instanceof NullPtr) {
            iconst(0);
            return;
        }
        if (e instanceof VarRef vr) {
            Sym.Var v = vr.var;
            CType t = v.objectType();
            if (kind(t) == K_ADDR) {
                addr(e);
                return;
            }
            if (v.constValue != null && !v.isRef) {
                if (v.constValue instanceof Long lv) {
                    if (kind(t) == K_I64)
                        lconst(lv);
                    else
                        iconst(lv);
                } else {
                    double dv = (Double) v.constValue;
                    if (kind(t) == K_F32)
                        iconst(Float.floatToRawIntBits((float) dv));
                    else
                        lconst(Double.doubleToRawLongBits(dv));
                }
                return;
            }
            if ((v.storage == Sym.Var.Storage.LOCAL || v.storage == Sym.Var.Storage.PARAM) && !v.isRef && !v.byAddress) {
                loadLocal(v);
                return;
            }
            addr(e);
            load(t);
            return;
        }
        if (e instanceof GlobalAddr g) {
            addrConst(g.global, g.offset);
            return;
        }
        if (e instanceof StrLit) {
            addr(e);
            return;
        }
        if (e instanceof Deref dr) {
            if (kind(dr.type) == K_ADDR) {
                rvalue(dr.p);
                return;
            }
            rvalue(dr.p);
            load(dr.type);
            return;
        }
        if (e instanceof AddrOf a) {
            addr(a.e);
            return;
        }
        if (e instanceof Ast.Member m) {
            if (m.field.isBitfield()) {
                addr(m);
                loadBitfield(m.field);
                return;
            }
            addr(m);
            if (kind(m.type) != K_ADDR)
                load(m.type, lvalueMaybeUnaligned(m));
            return;
        }
        if (e instanceof Conv c) {
            conv(c);
            return;
        }
        if (e instanceof Unary u) {
            unary(u);
            return;
        }
        if (e instanceof Binary b) {
            binary(b);
            return;
        }
        if (e instanceof Assign a) {
            assign(a, VALUE);
            return;
        }
        if (e instanceof Cond c) {
            if (c.lvalue && kind(c.type) == K_ADDR) {
                addr(c);
                return;
            }
            Label els = new Label(), end = new Label();
            condJump(c.c, els, false);
            rvalue(c.a);
            jump(Op.JMP, end, BRANCH, TAKEN, 2);
            place(els);
            rvalue(c.b);
            place(end);
            return;
        }
        if (e instanceof Call c) {
            call(c);
            return;
        }
        if (e instanceof Intrinsic in) {
            intrinsicCall(in);
            return;
        }
        if (e instanceof TempRef tr) {
            initTemp(tr);
            emit(Op.LADDR, ALU, 1, 2, tr.temp.offset);
            if (kind(tr.type) != K_ADDR)
                load(tr.type);
            return;
        }
        if (e instanceof StringSum s) {
            Base b = new FrameBase(s.temp.offset);
            Leaf first = s.first;
            b.push(0);
            for (Expr a : first.ctorArgs)
                rvalue(a);
            intrinsic(first.ctor, 1 + first.ctorArgs.size());
            for (Expr r : s.rest) {
                Intrinsic in = (Intrinsic) r;
                b.push(0);
                for (Expr a : in.args)
                    rvalue(a);
                intrinsic(in.op, 1 + in.args.size());
            }
            b.push(0);
            return;
        }
        if (e instanceof New n) {
            newExpr(n);
            return;
        }
        if (e instanceof Delete dl) {
            deleteExpr(dl);
            return;
        }
        if (e instanceof FuncRef f) {
            funcAddress(f);
            return;
        }
        throw new IllegalStateException("cannot generate " + e.getClass().getSimpleName());
    }

    private static Temp tempOf(Expr e) {
        if (e instanceof TempRef tr)
            return tr.temp;
        if (e instanceof StringSum ss)
            return ss.temp;
        if (e instanceof Call c)
            return c.sret;
        if (e instanceof Intrinsic in)
            return in.result;
        return null;
    }

    private void initTemp(TempRef tr) {
        if (tr.temp.offset < 0)
            tr.temp.offset = allocate(tr.temp.type.size(), tr.temp.type.align());
        if (tr.plan != null)
            genPlan(tr.plan, new FrameBase(tr.temp.offset), tr.temp.type);
        if (tr.init != null)
            effect(tr.init);
    }

    private void funcAddress(FuncRef f) {
        // The linker's function addresses already carry the Thumb bit.
        if (f.func != null)
            addrConst(f.func, 0);
        else
            addrConst(f.builtin, 0);
    }

    // ---- conversions ----

    private void conv(Conv c) {
        switch (c.k) {
            case DECAY -> {
                if (c.e instanceof FuncRef f)
                    funcAddress(f);
                else
                    addr(c.e);
            }
            case TO_VOID -> effect(c.e);
            case NOP -> {
                if (c.e.lvalue && kind(c.e.type) == K_ADDR)
                    addr(c.e);
                else
                    rvalue(c.e);
            }
            case STRING_TO_BOOL -> {
                addr(c.e);
                intrinsic(Intrinsics.Op.STR_VALID, 1);
            }
            case TO_BOOL -> {
                rvalue(c.e);
                switch (kind(c.e.type)) {
                    case K_I64 -> emit(Op.LTOBOOL, ALU, 2, 4);
                    case K_F32 -> softFloat(Op.FTOBOOL, 25, false);
                    case K_F64 -> softFloat(Op.DTOBOOL, 45, true);
                    default -> {
                        if (c.e.type.kind != CType.Kind.BOOL)
                            emit(Op.TOBOOL, ALU, 1, 4);
                    }
                }
            }
            default -> {
                rvalue(c.e);
                convert(c.e.type, c.type);
            }
        }
    }

    private void softFloat(int op, int cycles, boolean dbl) {
        if (dbl)
            usesDouble = true;
        else
            usesFloat = true;
        emit(op, SOFT, cycles, dbl ? 8 : 6);
    }

    /** Converts the value on the stack from one scalar type to another. */
    void convert(CType from, CType to) {
        if (from.isEnum())
            from = from.base;
        if (to.isEnum())
            to = to.base;
        int fk = kind(from), tk = kind(to);
        if (to.kind == CType.Kind.BOOL && from.kind != CType.Kind.BOOL) {
            switch (fk) {
                case K_I64 -> emit(Op.LTOBOOL, ALU, 2, 4);
                case K_F32 -> softFloat(Op.FTOBOOL, 25, false);
                case K_F64 -> softFloat(Op.DTOBOOL, 45, true);
                default -> emit(Op.TOBOOL, ALU, 1, 4);
            }
            return;
        }
        if (fk == K_F32 || fk == K_F64) {
            boolean dbl = fk == K_F64;
            if (tk == K_F32) {
                if (dbl)
                    softFloat(Op.D2F, 30, true);
                return;
            }
            if (tk == K_F64) {
                if (!dbl)
                    softFloat(Op.F2D, 30, true);
                return;
            }
            if (tk == K_I64) {
                softFloat(to.isUnsigned() ? (dbl ? Op.D2UL : Op.F2UL) : (dbl ? Op.D2L : Op.F2L), dbl ? 70 : 60, dbl);
                return;
            }
            boolean u = to.isUnsigned() || to.isPointer();
            softFloat(u ? (dbl ? Op.D2U : Op.F2U) : (dbl ? Op.D2I : Op.F2I), dbl ? 50 : 30, dbl);
            narrow(to);
            return;
        }
        if (tk == K_F32 || tk == K_F64) {
            boolean dbl = tk == K_F64;
            boolean u = from.isUnsigned();
            if (fk == K_I64)
                softFloat(u ? (dbl ? Op.UL2D : Op.UL2F) : (dbl ? Op.L2D : Op.L2F), dbl ? 70 : 60, dbl);
            else
                softFloat(u ? (dbl ? Op.U2D : Op.U2F) : (dbl ? Op.I2D : Op.I2F), dbl ? 50 : 30, dbl);
            return;
        }
        if (fk == K_I64 && tk == K_I32) {
            emit(Op.L2I, ALU, 0, 0);
            narrow(to);
            return;
        }
        if (fk == K_I32 && tk == K_I64) {
            emit(from.isUnsigned() ? Op.U2L : Op.I2L, ALU, 1, 2);
            return;
        }
        if (fk == K_I32 && tk == K_I32) {
            if (to.size() < 4 && (to.size() < from.size() || to.isUnsigned() != from.isUnsigned()))
                narrow(to);
        }
    }

    /** Wraps a 32-bit value into an 8- or 16-bit type. */
    private void narrow(CType to) {
        CType u = to.isEnum() ? to.base : to;
        switch (u.kind) {
            case CHAR, UCHAR -> emit(Op.TRUNC8U, ALU, 1, 2);
            case SCHAR -> emit(Op.TRUNC8S, ALU, 1, 2);
            case SHORT -> emit(Op.TRUNC16S, ALU, 1, 2);
            case USHORT -> emit(Op.TRUNC16U, ALU, 1, 2);
            case BOOL -> emit(Op.TOBOOL, ALU, 1, 2);
            default -> { }
        }
    }

    // ---- unary ----

    private void unary(Unary u) {
        switch (u.op) {
            case "-" -> {
                rvalue(u.e);
                switch (kind(u.type)) {
                    case K_I64 -> emit(Op.LNEG, ALU, 2, 4);
                    case K_F32 -> emit(Op.FNEG, ALU, 1, 4);
                    case K_F64 -> emit(Op.DNEG, ALU, 1, 4);
                    default -> emit(Op.NEG, ALU, 1, 2);
                }
            }
            case "+" -> rvalue(u.e);
            case "~" -> {
                rvalue(u.e);
                emit(kind(u.type) == K_I64 ? Op.LNOT : Op.NOT, ALU, kind(u.type) == K_I64 ? 2 : 1, 2);
            }
            case "!" -> {
                rvalue(u.e);
                emit(Op.LNOTB, ALU, 2, 4);
            }
            default -> incDec(u, true);
        }
    }

    private void incDec(Unary u, boolean wantValue) {
        Expr e = u.e;
        boolean pre = u.op.endsWith("x");
        if (e instanceof VarRef vr && (vr.var.storage == Sym.Var.Storage.LOCAL || vr.var.storage == Sym.Var.Storage.PARAM)
                && !vr.var.isRef && !vr.var.byAddress && kind(u.type) == K_I32 && u.type.size() == 4) {
            Sym.Var v = vr.var;
            int step = u.type.isPointer() ? u.stride : 1;
            if (u.op.contains("--"))
                step = -step;
            if (wantValue && !pre)
                loadLocal(v);
            emit(Op.LINC, isReg(v) ? ALU : STORE, isReg(v) ? 1 : 5, isReg(v) ? 2 : 6, v.offset, step);
            if (wantValue && pre)
                loadLocal(v);
            return;
        }
        if (e instanceof Ast.Member m && m.field.isBitfield()) {
            // Read, step, write back; the value is the stepped (re-read, so truncated) or the original field value.
            int mark = alloc;
            int slot = allocate(4, 4);
            addr(m);
            emit(Op.LST32, ALU, 0, 0, slot);
            emit(Op.LLD32, ALU, 0, 0, slot);
            loadBitfield(m.field);
            if (wantValue && !pre)
                emit(Op.DUP, ALU, 0, 0);
            if ((m.field.type.isEnum() ? m.field.type.base : m.field.type).size() == 8) {
                lconst(u.op.contains("++") ? 1 : -1);
                emit(Op.LADD, ALU, 2, 4);
            } else {
                emit(Op.ADDI, ALU, 1, 2, u.op.contains("++") ? 1 : -1);
            }
            emit(Op.LLD32, ALU, 0, 0, slot);
            emit(Op.SWAP, ALU, 0, 0);
            storeBitfield(m.field, false);
            if (wantValue && pre) {
                emit(Op.LLD32, ALU, 0, 0, slot);
                loadBitfield(m.field);
            }
            alloc = mark;
            return;
        }
        addr(e);
        incDecAt(u, wantValue, true);
    }

    /** With the operand's address on the stack: steps the value in place. */
    private void incDecAt(Unary u, boolean wantValue, boolean consumeAddr) {
        boolean pre = u.op.endsWith("x");
        boolean inc = u.op.contains("++");
        CType t = u.type;
        boolean unaligned = lvalueMaybeUnaligned(u.e);
        emit(Op.DUP, ALU, 0, 0);
        load(t, unaligned);
        if (wantValue && !pre)
            emit(Op.DUPX1, ALU, 0, 0);
        switch (kind(t)) {
            case K_I64 -> {
                lconst(1);
                emit(inc ? Op.LADD : Op.LSUB, ALU, 2, 4);
            }
            case K_F32 -> {
                iconst(Float.floatToRawIntBits(1.0f));
                softFloat(inc ? Op.FADD : Op.FSUB, 50, false);
            }
            case K_F64 -> {
                lconst(Double.doubleToRawLongBits(1.0));
                softFloat(inc ? Op.DADD : Op.DSUB, 100, true);
            }
            default -> {
                int step = t.isPointer() ? u.stride : 1;
                emit(Op.ADDI, ALU, 1, 2, inc ? step : -step);
                narrow(t);
            }
        }
        if (wantValue && pre)
            emit(Op.DUPX1, ALU, 0, 0);
        store(t, unaligned);
    }

    // ---- binary ----

    private void binary(Binary b) {
        switch (b.op) {
            case ",":
                effect(b.l);
                rvalue(b.r);
                return;
            case "&&", "||": {
                Label f = new Label(), end = new Label();
                condJump(b, f, false);
                iconst(1);
                jump(Op.JMP, end, BRANCH, TAKEN, 2);
                place(f);
                iconst(0);
                place(end);
                return;
            }
            default:
                break;
        }
        CType ot = b.opType;
        if (ot != null && ot.isPointer() && (b.op.equals("+") || b.op.equals("-"))) {
            pointerArith(b);
            return;
        }
        rvalue(b.l);
        rvalue(b.r);
        arithOp(b.op, ot);
    }

    private void pointerArith(Binary b) {
        if (b.l.type.isPointer() && b.r.type.isPointer()) {
            rvalue(b.l);
            rvalue(b.r);
            emit(Op.SUB, ALU, 1, 2);
            if (b.stride > 1) {
                if (Integer.bitCount(b.stride) == 1) {
                    iconst(Integer.numberOfTrailingZeros(b.stride));
                    emit(Op.SAR, ALU, 1, 2);
                } else {
                    iconst(b.stride);
                    emit(Op.SDIV, DIV, 4, 4);
                }
            }
            return;
        }
        rvalue(b.l);
        Long k = SemaBase.intConst(b.r);
        if (k != null) {
            long off = (b.r.type.isUnsigned() ? (k & 0xFFFFFFFFL) : k) * b.stride;
            if (b.op.equals("-"))
                off = -off;
            if (off != 0)
                emit(Op.ADDI, ALU, 1, 2, (int) off);
            return;
        }
        rvalue(b.r);
        scale(b.stride);
        emit(b.op.equals("+") ? Op.ADD : Op.SUB, ALU, 1, 2);
    }

    private void scale(int stride) {
        if (stride == 1)
            return;
        if (Integer.bitCount(stride) == 1) {
            iconst(Integer.numberOfTrailingZeros(stride));
            emit(Op.SHL, ALU, 0, 0);
        } else {
            iconst(stride);
            emit(Op.MUL, MUL, 1, 2);
        }
    }

    /** The operator {@code op} on two values of type {@code t} already on the stack. */
    private void arithOp(String op, CType t) {
        int k = kind(t);
        boolean u = t.isUnsigned() || t.isPointer();
        if (k == K_F32 || k == K_F64) {
            boolean dbl = k == K_F64;
            int o;
            int cyc;
            switch (op) {
                case "+" -> { o = dbl ? Op.DADD : Op.FADD; cyc = dbl ? 100 : 50; }
                case "-" -> { o = dbl ? Op.DSUB : Op.FSUB; cyc = dbl ? 100 : 50; }
                case "*" -> { o = dbl ? Op.DMUL : Op.FMUL; cyc = dbl ? 110 : 45; }
                case "/" -> { o = dbl ? Op.DDIV : Op.FDIV; cyc = dbl ? 250 : 110; }
                case "==" -> { o = dbl ? Op.DEQ : Op.FEQ; cyc = dbl ? 45 : 25; }
                case "!=" -> { o = dbl ? Op.DNE : Op.FNE; cyc = dbl ? 45 : 25; }
                case "<" -> { o = dbl ? Op.DLT : Op.FLT; cyc = dbl ? 45 : 25; }
                case "<=" -> { o = dbl ? Op.DLE : Op.FLE; cyc = dbl ? 45 : 25; }
                case ">" -> { o = dbl ? Op.DGT : Op.FGT; cyc = dbl ? 45 : 25; }
                default -> { o = dbl ? Op.DGE : Op.FGE; cyc = dbl ? 45 : 25; }
            }
            softFloat(o, cyc, dbl);
            return;
        }
        if (k == K_I64) {
            switch (op) {
                case "+" -> emit(Op.LADD, ALU, 2, 4);
                case "-" -> emit(Op.LSUB, ALU, 2, 4);
                case "*" -> emit(Op.LMUL, MUL, 4, 8);
                case "/" -> { usesLongDiv = true; emit(u ? Op.LUDIV : Op.LSDIV, DIV, 0, 8); }
                case "%" -> { usesLongDiv = true; emit(u ? Op.LUREM : Op.LSREM, DIV, 0, 8); }
                case "&" -> emit(Op.LAND, ALU, 2, 4);
                case "|" -> emit(Op.LOR, ALU, 2, 4);
                case "^" -> emit(Op.LXOR, ALU, 2, 4);
                case "<<" -> { usesLongShift = true; emit(Op.LSHL, ALU, 5, 10); }
                case ">>" -> { usesLongShift = true; emit(u ? Op.LSHR : Op.LSAR, ALU, 5, 10); }
                case "==" -> emit(Op.LEQ, ALU, 3, 6);
                case "!=" -> emit(Op.LNE, ALU, 3, 6);
                case "<" -> emit(u ? Op.LULT : Op.LLT, ALU, 3, 6);
                case "<=" -> emit(u ? Op.LULE : Op.LLE, ALU, 3, 6);
                case ">" -> emit(u ? Op.LUGT : Op.LGT, ALU, 3, 6);
                default -> emit(u ? Op.LUGE : Op.LGE, ALU, 3, 6);
            }
            return;
        }
        switch (op) {
            case "+" -> emit(Op.ADD, ALU, 1, 2);
            case "-" -> emit(Op.SUB, ALU, 1, 2);
            case "*" -> emit(Op.MUL, MUL, 1, 2);
            case "/" -> emit(u ? Op.UDIV : Op.SDIV, DIV, 0, 4);
            case "%" -> emit(u ? Op.UREM : Op.SREM, DIV, 0, 8);
            case "&" -> emit(Op.AND, ALU, 1, 2);
            case "|" -> emit(Op.OR, ALU, 1, 2);
            case "^" -> emit(Op.XOR, ALU, 1, 2);
            case "<<" -> emit(Op.SHL, ALU, 1, 2);
            case ">>" -> emit(u ? Op.SHR : Op.SAR, ALU, 1, 2);
            case "==" -> emit(Op.EQ, ALU, 1, 4);
            case "!=" -> emit(Op.NE, ALU, 1, 4);
            case "<" -> emit(u ? Op.ULT : Op.LT, ALU, 1, 4);
            case "<=" -> emit(u ? Op.ULE : Op.LE, ALU, 1, 4);
            case ">" -> emit(u ? Op.UGT : Op.GT, ALU, 1, 4);
            default -> emit(u ? Op.UGE : Op.GE, ALU, 1, 4);
        }
        // Results narrower than int never arise: operands were promoted.
    }

    // ---- conditions ----

    /** Jumps to {@code target} when {@code e} (a bool) is {@code when}; falls through otherwise. */
    private void condJump(Expr e, Label target, boolean when) {
        if (e instanceof IntLit l) {
            if ((l.value != 0) == when)
                jump(Op.JMP, target, BRANCH, TAKEN, 2);
            return;
        }
        if (e instanceof Binary b && (b.op.equals("&&") || b.op.equals("||"))) {
            boolean and = b.op.equals("&&");
            if (and != when) {
                // && jumping on false, || jumping on true: either operand decides.
                condJump(b.l, target, when);
                condJump(b.r, target, when);
            } else {
                Label skip = new Label();
                condJump(b.l, skip, !when);
                condJump(b.r, target, when);
                place(skip);
            }
            return;
        }
        if (e instanceof Unary u && u.op.equals("!")) {
            condJump(u.e, target, !when);
            return;
        }
        if (e instanceof Conv c && c.k == Conv.K.TO_BOOL && kind(c.e.type) == K_I32) {
            rvalue(c.e);
            jump(when ? Op.JNZ : Op.JZ, target, ALU, 0, 2);
            return;
        }
        rvalue(e);
        jump(when ? Op.JNZ : Op.JZ, target, ALU, 0, 2);
    }

    // ---- assignment ----

    private static final int EFFECT = 0, VALUE = 1, ADDRESS = 2;

    /**
     * An assignment, leaving nothing ({@link #EFFECT}), the stored value, or the target's address.
     *
     * <h2>Order of evaluation</h2>
     * STM32duino builds with -std=gnu++17, where GCC turns on -fstrong-eval-order:
     * for {@code E1 = E2} and {@code E1 op= E2} every side effect of E2 is sequenced
     * before E1 (P0145, [expr.ass]/1), and that holds for an overloaded
     * {@code operator=} used as an operator too (String). So the right operand is
     * evaluated first, then the target's address, then (for a compound assignment)
     * the target's current value: {@code a[i] = i++} stores into the element the
     * incremented {@code i} selects, {@code x += (x = 5)} is 10, and in
     * {@code arr[L()] = R()} R runs before L. The operand stack is shuffled with
     * DUPX1/SWAP, which cost nothing: GCC simply keeps both in registers.
     */
    private void assign(Assign a, int mode) {
        Expr l = a.l;
        CType t = a.type;
        boolean wantAddr = mode == ADDRESS;
        boolean wantValue = mode == VALUE;
        if (kind(t) == K_ADDR) {
            // Struct assignment.
            if (t.isNonTrivial()) {
                structAssignNonTrivial(a, mode != EFFECT);
            } else {
                rvalue(a.r);
                addr(l);
                if (wantAddr || wantValue) {
                    // src dst -> dst src dst -> dst dst src
                    emit(Op.DUPX1, ALU, 0, 0);
                }
                emit(Op.SWAP, ALU, 0, 0);
                memcpy(t.size());
            }
            return;
        }
        if (l instanceof Ast.Member m && m.field.isBitfield()) {
            rvalue(a.r);
            addr(m);
            if (a.op.equals("=")) {
                emit(Op.SWAP, ALU, 0, 0);
            } else {
                // r a -> a r a -> a r v
                emit(Op.DUPX1, ALU, 0, 0);
                loadBitfield(m.field);
                convert(m.type, a.opType);
                emit(Op.SWAP, ALU, 0, 0);
                arithOp(a.op.substring(0, a.op.length() - 1), a.opType);
                convert(a.opType, m.type);
            }
            storeBitfield(m.field, wantValue);
            return;
        }
        boolean local = l instanceof VarRef vr && (vr.var.storage == Sym.Var.Storage.LOCAL || vr.var.storage == Sym.Var.Storage.PARAM)
                && !vr.var.isRef && !vr.var.byAddress && !wantAddr;
        if (local) {
            Sym.Var v = ((VarRef) l).var;
            if (a.op.equals("=")) {
                rvalue(a.r);
            } else if (compoundConst(a)) {
                loadLocal(v);
                compound(a);
            } else {
                rvalue(a.r);
                loadLocal(v);
                compoundAfter(a);
            }
            if (wantValue)
                emit(Op.DUP, ALU, 0, 0);
            storeLocal(v);
            return;
        }
        if (!a.op.equals("=") && compoundConst(a)) {
            // A constant right operand has no side effects to order: GCC's own sequence.
            addr(l);
            if (wantAddr)
                emit(Op.DUP, ALU, 0, 0);
            emit(Op.DUP, ALU, 0, 0);
            load(t, lvalueMaybeUnaligned(l));
            compound(a);
        } else {
            rvalue(a.r);
            addr(l);
            if (a.op.equals("=")) {
                if (wantAddr) {
                    // r a -> a r a -> a a r
                    emit(Op.DUPX1, ALU, 0, 0);
                }
                emit(Op.SWAP, ALU, 0, 0);
            } else {
                if (wantAddr) {
                    // r a -> a r a
                    emit(Op.DUPX1, ALU, 0, 0);
                }
                // r a -> a r a -> a r v
                emit(Op.DUPX1, ALU, 0, 0);
                load(t, lvalueMaybeUnaligned(l));
                compoundAfter(a);
            }
        }
        if (wantValue)
            emit(Op.DUPX1, ALU, 0, 0);
        store(t, lvalueMaybeUnaligned(l));
    }

    /** Whether a compound assignment's right operand is a constant (nothing to sequence). */
    private static boolean compoundConst(Assign a) {
        return a.r instanceof IntLit || a.r instanceof FloatLit || SemaBase.intConst(a.r) != null && !(a.r instanceof Call);
    }

    /** {@code v op= r} with the target's value {@code v} on top of the stack: pushes the result. */
    private void compound(Assign a) {
        String op = a.op.substring(0, a.op.length() - 1);
        CType t = a.type;
        if (t.isPointer()) {
            Long k = SemaBase.intConst(a.r);
            if (k != null) {
                long off = k * a.stride;
                emit(Op.ADDI, ALU, 1, 2, (int) (op.equals("-") ? -off : off));
                return;
            }
            rvalue(a.r);
            scale(a.stride);
            emit(op.equals("+") ? Op.ADD : Op.SUB, ALU, 1, 2);
            return;
        }
        convert(t, a.opType);
        rvalue(a.r);
        arithOp(op, a.opType);
        convert(a.opType, t);
    }

    /**
     * {@code v op= r} with the already evaluated right operand below the target's
     * value: {@code r v} becomes the result, the operands kept in source order for
     * {@code -}, {@code /}, {@code <<} and the rest.
     */
    private void compoundAfter(Assign a) {
        String op = a.op.substring(0, a.op.length() - 1);
        CType t = a.type;
        if (t.isPointer()) {
            emit(Op.SWAP, ALU, 0, 0);
            scale(a.stride);
            emit(op.equals("+") ? Op.ADD : Op.SUB, ALU, 1, 2);
            return;
        }
        convert(t, a.opType);
        emit(Op.SWAP, ALU, 0, 0);
        arithOp(op, a.opType);
        convert(a.opType, t);
    }

    private void structAssignNonTrivial(Assign a, boolean keepAddr) {
        CType t = a.type;
        int mark = alloc;
        int dst = allocate(4, 4), src = allocate(4, 4);
        // Right operand first, as for every assignment (see assign).
        rvalue(a.r);
        emit(Op.LST32, STORE, 2, 2, src);
        addr(a.l);
        emit(Op.LST32, STORE, 2, 2, dst);
        List<Integer> offs = stringOffsets(t);
        int pos = 0;
        for (int o : offs) {
            if (o > pos)
                copyRange(dst, src, pos, o - pos);
            emit(Op.LLD32, LOAD, 2, 2, dst);
            if (o != 0)
                emit(Op.ADDI, ALU, 0, 0, o);
            emit(Op.LLD32, LOAD, 2, 2, src);
            if (o != 0)
                emit(Op.ADDI, ALU, 0, 0, o);
            intrinsic(Intrinsics.Op.STR_ASSIGN_COPY, 2);
            emit(Op.POP, ALU, 0, 0);
            pos = o + 12;
        }
        if (pos < t.size())
            copyRange(dst, src, pos, t.size() - pos);
        if (keepAddr)
            emit(Op.LLD32, LOAD, 2, 2, dst);
        alloc = mark;
    }

    private void copyRange(int dstSlot, int srcSlot, int off, int n) {
        emit(Op.LLD32, LOAD, 2, 2, dstSlot);
        if (off != 0)
            emit(Op.ADDI, ALU, 0, 0, off);
        emit(Op.LLD32, LOAD, 2, 2, srcSlot);
        if (off != 0)
            emit(Op.ADDI, ALU, 0, 0, off);
        memcpy(n);
    }

    // ---- bitfields ----

    private void loadBitfield(CType.Field f) {
        CType ct = f.type.isEnum() ? f.type.base : f.type;
        int size = ct.size();
        if (size == 8) {
            loadBitfield64(f, ct);
            return;
        }
        switch (size) {
            case 1 -> emit(Op.LD8U, LOAD, 2, 2);
            case 2 -> emit(Op.LD16U, LOAD, 2, 2);
            default -> emit(Op.LD32, LOAD, 2, 2);
        }
        int w = f.bitWidth, off = f.bitOffset;
        if (!ct.isUnsigned()) {
            // ubfx/sbfx: one instruction.
            iconst(32 - w - off);
            emit(Op.SHL, ALU, 0, 0);
            iconst(32 - w);
            emit(Op.SAR, ALU, 1, 4);
        } else {
            if (off != 0) {
                iconst(off);
                emit(Op.SHR, ALU, 0, 0);
            }
            if (w < 32) {
                iconst((int) ((1L << w) - 1));
                emit(Op.AND, ALU, 1, 4);
            }
        }
        if (ct.kind == CType.Kind.BOOL)
            emit(Op.TOBOOL, ALU, 0, 0);
    }

    /** A {@code long long} bit-field: a doubleword load, then ubfx/sbfx done across the register pair. */
    private void loadBitfield64(CType.Field f, CType ct) {
        emit(Op.LD64, LOAD, 3, 4);
        int w = f.bitWidth, off = f.bitOffset;
        if (!ct.isUnsigned()) {
            iconst(64 - w - off);
            emit(Op.LSHL, ALU, 2, 4);
            iconst(64 - w);
            emit(Op.LSAR, ALU, 2, 4);
        } else {
            if (off != 0) {
                iconst(off);
                emit(Op.LSHR, ALU, 2, 4);
            }
            if (w < 64) {
                lconst((1L << w) - 1);
                emit(Op.LAND, ALU, 2, 4);
            }
        }
    }

    /** {@link #storeBitfield} for a {@code long long} bit-field. */
    private void storeBitfield64(CType.Field f, CType ct, boolean keep) {
        int w = f.bitWidth, off = f.bitOffset;
        long mask = w >= 64 ? -1L : (1L << w) - 1;
        int mark = alloc;
        int vslot = allocate(8, 8);
        emit(Op.LST64, ALU, 0, 0, vslot);
        emit(Op.DUP, ALU, 0, 0);
        emit(Op.LD64, LOAD, 3, 4);
        lconst(~(mask << off));
        emit(Op.LAND, ALU, 0, 0);
        emit(Op.LLD64, ALU, 0, 0, vslot);
        lconst(mask);
        emit(Op.LAND, ALU, 0, 0);
        if (off != 0) {
            iconst(off);
            emit(Op.LSHL, ALU, 0, 0);
        }
        emit(Op.LOR, ALU, 2, 8);
        emit(Op.ST64, STORE, 3, 4);
        if (keep) {
            emit(Op.LLD64, ALU, 0, 0, vslot);
            if (!ct.isUnsigned() && w < 64) {
                iconst(64 - w);
                emit(Op.LSHL, ALU, 0, 0);
                iconst(64 - w);
                emit(Op.LSAR, ALU, 2, 4);
            } else if (w < 64) {
                lconst(mask);
                emit(Op.LAND, ALU, 2, 4);
            }
        }
        alloc = mark;
    }

    /** [addr, value] → stores value into the field; with {@code keep}, leaves the stored value. */
    private void storeBitfield(CType.Field f, boolean keep) {
        CType ct = f.type.isEnum() ? f.type.base : f.type;
        int size = ct.size();
        if (size == 8) {
            storeBitfield64(f, ct, keep);
            return;
        }
        int w = f.bitWidth, off = f.bitOffset;
        long mask = w >= 32 ? 0xFFFFFFFFL : (1L << w) - 1;
        int mark = alloc;
        int vslot = allocate(4, 4);
        emit(Op.LST32, ALU, 0, 0, vslot);
        // [addr]
        emit(Op.DUP, ALU, 0, 0);
        switch (size) {
            case 1 -> emit(Op.LD8U, LOAD, 2, 2);
            case 2 -> emit(Op.LD16U, LOAD, 2, 2);
            default -> emit(Op.LD32, LOAD, 2, 2);
        }
        iconst((int) ~(mask << off));
        emit(Op.AND, ALU, 0, 0);
        emit(Op.LLD32, ALU, 0, 0, vslot);
        iconst((int) mask);
        emit(Op.AND, ALU, 0, 0);
        if (off != 0) {
            iconst(off);
            emit(Op.SHL, ALU, 0, 0);
        }
        emit(Op.OR, ALU, 1, 4); // bfi
        switch (size) {
            case 1 -> emit(Op.ST8, STORE, 2, 2);
            case 2 -> emit(Op.ST16, STORE, 2, 2);
            default -> emit(Op.ST32, STORE, 2, 2);
        }
        if (keep) {
            emit(Op.LLD32, ALU, 0, 0, vslot);
            if (!ct.isUnsigned() && w < 32) {
                iconst(32 - w);
                emit(Op.SHL, ALU, 0, 0);
                iconst(32 - w);
                emit(Op.SAR, ALU, 1, 2);
            } else if (w < 32) {
                iconst((int) mask);
                emit(Op.AND, ALU, 1, 2);
            }
        }
        alloc = mark;
    }

    // ---- calls ----

    private void call(Call c) {
        if (c.target != null) {
            int idx = funcIndex.get(c.target);
            if (c.sret != null)
                emit(Op.LADDR, ALU, 1, 2, c.sret.offset);
            for (Expr a : c.args)
                rvalue(a);
            emit(Op.CALL, ALU, 0, 4 + 2 * Math.min(c.args.size(), 4), idx);
            if (c.sret != null)
                emit(Op.LADDR, ALU, 1, 2, c.sret.offset);
            return;
        }
        if (c.builtin != null) {
            for (Expr a : c.args)
                rvalue(a);
            CallDesc cd = new CallDesc();
            cd.builtin = c.builtin;
            cd.argc = c.args.size();
            cd.argTypes = new CType[c.args.size()];
            for (int i = 0; i < c.args.size(); i++)
                cd.argTypes[i] = c.args.get(i).type;
            cd.ret = c.builtin.type.base;
            cd.returnsValue = !cd.ret.isVoid();
            descs.add(cd);
            emit(Op.CALLB, ALU, 0, 4 + 2 * Math.min(c.args.size(), 4), descs.size() - 1);
            return;
        }
        // Indirect.
        rvalue(c.fn);
        if (c.sret != null)
            emit(Op.LADDR, ALU, 1, 2, c.sret.offset);
        for (Expr a : c.args)
            rvalue(a);
        CallDesc cd = new CallDesc();
        cd.argc = c.args.size() + (c.sret != null ? 1 : 0);
        cd.argTypes = new CType[cd.argc];
        int base = c.sret != null ? 1 : 0;
        if (c.sret != null)
            cd.argTypes[0] = CType.VOID_PTR;
        for (int i = 0; i < c.args.size(); i++)
            cd.argTypes[base + i] = c.args.get(i).type;
        cd.ret = c.indirectType.base;
        cd.returnsValue = !cd.ret.isVoid() && c.sret == null;
        descs.add(cd);
        emit(Op.CALLI, ALU, 0, 2 + 2 * Math.min(c.args.size(), 4), descs.size() - 1);
        if (c.sret != null)
            emit(Op.LADDR, ALU, 1, 2, c.sret.offset);
    }

    /**
     * GCC's bit builtins as -Os emits them on a Cortex-M3: {@code clz} is CLZ (1
     * cycle), {@code ctz} RBIT + CLZ (2); the 64-bit forms test the high word first
     * (CMP, IT, two CLZ/RBIT and an ADD: ~5-6 cycles). {@code popcount} is a call to
     * libgcc's __popcountsi2, ~14 cycles of shifts and masks plus the BL/BX refills
     * the VM adds ({@link #CALL_REFILL}); __popcountdi2 does it twice.
     */
    private boolean bitBuiltin(Intrinsic in) {
        int op, cyc, th;
        switch (in.op) {
            case BUILTIN_CLZ, BUILTIN_CLZL -> { op = Op.CLZ; cyc = 1; th = 4; }
            case BUILTIN_CTZ, BUILTIN_CTZL -> { op = Op.CTZ; cyc = 2; th = 8; }
            case BUILTIN_POPCOUNT, BUILTIN_POPCOUNTL -> { op = Op.POPCNT; cyc = 14; th = 4; }
            case BUILTIN_CLZLL -> { op = Op.LCLZ; cyc = 5; th = 12; }
            case BUILTIN_CTZLL -> { op = Op.LCTZ; cyc = 6; th = 14; }
            case BUILTIN_POPCOUNTLL -> { op = Op.LPOPCNT; cyc = 30; th = 4; }
            default -> {
                return false;
            }
        }
        intrinsicUsed[in.op.ordinal()] = true;
        rvalue(in.args.get(0));
        emit(op, ALU, cyc, th);
        return true;
    }

    private void intrinsicCall(Intrinsic in) {
        if (bitBuiltin(in))
            return;
        Intrinsics.Op op = in.op;
        int argc = in.args.size();
        if (in.result != null) {
            emit(Op.LADDR, ALU, 1, 2, in.result.offset);
            argc++;
        }
        if (in.rhsFirst && in.args.size() == 2 && in.result == null) {
            // String's operator= and +=: the right operand first (see assign).
            rvalue(in.args.get(1));
            rvalue(in.args.get(0));
            if (in.returnsThis) {
                // r this -> this r this -> this this r
                emit(Op.DUPX1, ALU, 0, 0);
            }
            emit(Op.SWAP, ALU, 0, 0);
        } else {
            boolean first = true;
            for (Expr a : in.args) {
                rvalue(a);
                if (first && in.returnsThis) {
                    emit(Op.DUP, ALU, 0, 0);
                    emit(Op.SWAP, ALU, 0, 0);
                }
                first = false;
            }
        }
        intrinsic(op, argc);
        if (in.returnsThis) {
            if (!op.ret.isVoid())
                emit(Op.POP, ALU, 0, 0);
        }
        if (in.result != null)
            emit(Op.LADDR, ALU, 1, 2, in.result.offset);
    }

    // ---- new / delete ----

    private void newExpr(New n) {
        CType t = n.allocType;
        boolean cookie = n.count != null && t.isNonTrivial();
        int mark = alloc;
        int pslot = allocate(4, 4);
        if (n.count != null) {
            int cslot = allocate(4, 4);
            rvalue(n.count);
            emit(Op.LST32, ALU, 1, 2, cslot);
            emit(Op.LLD32, ALU, 0, 0, cslot);
            iconst(t.size());
            emit(Op.MUL, MUL, 1, 2);
            if (cookie)
                emit(Op.ADDI, ALU, 1, 2, 8);
            intrinsic(Intrinsics.Op.NEW, 1);
            emit(Op.LST32, ALU, 1, 2, pslot);
            if (cookie) {
                // ARM C++ ABI array cookie: {element size, element count} ahead of the elements.
                emit(Op.LLD32, ALU, 0, 0, pslot);
                iconst(t.size());
                emit(Op.ST32, STORE, 2, 2);
                emit(Op.LLD32, ALU, 0, 0, pslot);
                emit(Op.ADDI, ALU, 0, 0, 4);
                emit(Op.LLD32, ALU, 0, 0, cslot);
                emit(Op.ST32, STORE, 2, 2);
                emit(Op.LLD32, ALU, 0, 0, pslot);
                emit(Op.ADDI, ALU, 1, 2, 8);
                emit(Op.LST32, ALU, 1, 2, pslot);
            }
            if (n.init != null && n.init.leaves.isEmpty() && n.init.zeroFill) {
                // new int[n](): value-initialised; new String[n]: zeroed, then every String default-constructed.
                emit(Op.LLD32, ALU, 0, 0, pslot);
                emit(Op.LLD32, ALU, 0, 0, cslot);
                iconst(t.size());
                emit(Op.MUL, MUL, 1, 2);
                intrinsic(Intrinsics.Op.MEMSET0, 2);
                for (int o : stringOffsets(t)) {
                    emit(Op.LLD32, ALU, 0, 0, pslot);
                    if (o != 0)
                        emit(Op.ADDI, ALU, 1, 2, o);
                    emit(Op.LLD32, ALU, 0, 0, cslot);
                    iconst(t.size());
                    intrinsic(Intrinsics.Op.STR_CTOR_ARRAY, 3);
                }
            } else if (n.init != null) {
                CType arr = CType.array(t, Math.max(1, (int) Math.min(Integer.MAX_VALUE / Math.max(1, t.size()), countOf(n))));
                genPlan(n.init, new SlotBase(pslot), arr);
            }
        } else {
            iconst(t.size());
            intrinsic(Intrinsics.Op.NEW, 1);
            emit(Op.LST32, ALU, 1, 2, pslot);
            if (n.init != null)
                genPlan(n.init, new SlotBase(pslot), t);
        }
        emit(Op.LLD32, ALU, 0, 0, pslot);
        alloc = mark;
    }

    private static long countOf(New n) {
        Long c = SemaBase.intConst(n.count);
        return c == null ? 1 : c;
    }

    private void deleteExpr(Delete dl) {
        CType t = dl.e.type.base;
        int mark = alloc;
        int pslot = allocate(4, 4);
        rvalue(dl.e);
        emit(Op.LST32, ALU, 1, 2, pslot);
        if (t.isNonTrivial()) {
            Label end = new Label();
            emit(Op.LLD32, ALU, 0, 0, pslot);
            jump(Op.JZ, end, ALU, 0, 2);
            if (dl.array) {
                // Count from the cookie, then each element's Strings, last element first.
                List<Integer> offs = stringOffsets(t);
                for (int o : offs) {
                    emit(Op.LLD32, ALU, 0, 0, pslot);
                    if (o != 0)
                        emit(Op.ADDI, ALU, 0, 0, o);
                    emit(Op.LLD32, ALU, 0, 0, pslot);
                    emit(Op.ADDI, ALU, 0, 0, -4);
                    emit(Op.LD32, LOAD, 2, 2);
                    iconst(t.size());
                    intrinsic(Intrinsics.Op.STR_DTOR_ARRAY, 3);
                }
                emit(Op.LLD32, ALU, 0, 0, pslot);
                emit(Op.ADDI, ALU, 1, 2, -8);
                intrinsic(Intrinsics.Op.DELETE, 1);
            } else {
                destroyAt(new SlotBase(pslot), t);
                emit(Op.LLD32, ALU, 0, 0, pslot);
                intrinsic(Intrinsics.Op.DELETE, 1);
            }
            place(end);
        } else {
            emit(Op.LLD32, ALU, 0, 0, pslot);
            intrinsic(Intrinsics.Op.DELETE, 1);
        }
        alloc = mark;
    }
}
