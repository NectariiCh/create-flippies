package com.nectariich.create_flippies.content.plc.mcu.lang;

/**
 * The VM's instruction set.
 *
 * A program is one {@code int[]}. Each instruction is a header word followed by its
 * operands. The header packs the opcode with the Cortex-M3 cost the compiler
 * assigned to that instruction — what the Thumb-2 code GCC -Os emits for the same
 * operation would take — so the interpreter charges time without knowing anything
 * about the source:
 *
 * <pre>
 *   bits  0..7   opcode
 *   bits  8..11  CycleClass ordinal
 *   bits 12..31  cycles
 * </pre>
 *
 * Costs that depend on data are added at run time: division (by operand
 * magnitude), loads from flash (wait states), taken versus untaken branches, calls
 * and returns (by the callee's register saves).
 *
 * Values on the operand stack are longs: 32-bit integers and pointers
 * sign-extended from their int form, 64-bit integers as they are, {@code float} as
 * its raw bits in the low word, {@code double} as its raw bits.
 */
final class Op {

    static final int NOP = 0;
    static final int ICONST = 1;    // v
    static final int LCONST = 2;    // lo hi
    static final int LADDR = 3;     // off: SRAM_BASE + fp + off
    static final int LD8U = 4, LD8S = 5, LD16U = 6, LD16S = 7, LD32 = 8, LD64 = 9;
    static final int ST8 = 10, ST16 = 11, ST32 = 12, ST64 = 13;
    static final int LLD8U = 14, LLD8S = 15, LLD16U = 16, LLD16S = 17, LLD32 = 18, LLD64 = 19; // off
    static final int LST8 = 20, LST16 = 21, LST32 = 22, LST64 = 23; // off
    static final int LINC = 24;     // off imm: 32-bit local += imm
    static final int POP = 25, DUP = 26, DUPX1 = 27, SWAP = 28;

    static final int ADD = 30, SUB = 31, MUL = 32, SDIV = 33, UDIV = 34, SREM = 35, UREM = 36,
            AND = 37, OR = 38, XOR = 39, SHL = 40, SAR = 41, SHR = 42, NEG = 43, NOT = 44, ADDI = 45;
    static final int EQ = 46, NE = 47, LT = 48, LE = 49, GT = 50, GE = 51, ULT = 52, ULE = 53, UGT = 54, UGE = 55;

    static final int LADD = 56, LSUB = 57, LMUL = 58, LSDIV = 59, LUDIV = 60, LSREM = 61, LUREM = 62,
            LAND = 63, LOR = 64, LXOR = 65, LSHL = 66, LSAR = 67, LSHR = 68, LNEG = 69, LNOT = 70;
    static final int LEQ = 71, LNE = 72, LLT = 73, LLE = 74, LGT = 75, LGE = 76, LULT = 77, LULE = 78, LUGT = 79, LUGE = 80;

    static final int FADD = 81, FSUB = 82, FMUL = 83, FDIV = 84, FNEG = 85,
            FEQ = 86, FNE = 87, FLT = 88, FLE = 89, FGT = 90, FGE = 91;
    static final int DADD = 92, DSUB = 93, DMUL = 94, DDIV = 95, DNEG = 96,
            DEQ = 97, DNE = 98, DLT = 99, DLE = 100, DGT = 101, DGE = 102;

    static final int TRUNC8U = 103, TRUNC8S = 104, TRUNC16U = 105, TRUNC16S = 106, I2L = 107, U2L = 108, L2I = 109;
    static final int I2F = 110, U2F = 111, L2F = 112, UL2F = 113, I2D = 114, U2D = 115, L2D = 116, UL2D = 117;
    static final int F2I = 118, F2U = 119, F2L = 120, F2UL = 121, D2I = 122, D2U = 123, D2L = 124, D2UL = 125;
    static final int F2D = 126, D2F = 127;
    static final int TOBOOL = 128, LTOBOOL = 129, FTOBOOL = 130, DTOBOOL = 131, LNOTB = 132;

    static final int JMP = 133;     // target
    static final int JZ = 134;      // target
    static final int JNZ = 135;     // target
    static final int LOOP = 136;    // target loopId: a backward jump, where idle iterations are detected
    static final int SWITCH = 137;  // count default (loLo loHi hiLo hiHi target)*count: disjoint ranges sorted (signed 64)
    static final int CALL = 138;    // function index
    static final int CALLI = 139;   // descriptor index (argument count, return kind)
    static final int CALLB = 140;   // descriptor index
    static final int INTR = 141;    // Intrinsics.Op ordinal, argument count
    static final int RET = 142;
    static final int RETV = 143;
    static final int MEMCPY = 144;  // n: pop src, pop dst
    static final int MEMZERO = 145; // n: pop dst
    /** GCC's bit builtins: CLZ; RBIT+CLZ; libgcc __popcountsi2 (and the 64-bit forms). Results are int. */
    static final int CLZ = 146, CTZ = 147, POPCNT = 148, LCLZ = 149, LCTZ = 150, LPOPCNT = 151;
    /** 64-bit load/store that tolerates any alignment (a packed member, read a piece at a time). */
    static final int LD64U = 152, ST64U = 153;
    /** Variable-length arrays: pop a byte count, lower SP by it rounded up to 8, push the new SP. */
    static final int ALLOCA = 154;
    /** Push the stack pointer (as an address); pop one back into it. */
    static final int GETSP = 155, SETSP = 156;
    static final int COUNT = 157;

    /** Operand words after the header, per opcode; -1 for variable (SWITCH). */
    static final int[] OPERANDS = new int[COUNT];
    static final String[] NAMES = new String[COUNT];

    static {
        java.util.Arrays.fill(OPERANDS, 0);
        for (int op : new int[] { ICONST, LADDR, LLD8U, LLD8S, LLD16U, LLD16S, LLD32, LLD64, LST8, LST16, LST32, LST64,
                ADDI, JMP, JZ, JNZ, CALL, CALLI, CALLB, MEMCPY, MEMZERO })
            OPERANDS[op] = 1;
        for (int op : new int[] { LCONST, LINC, LOOP, INTR })
            OPERANDS[op] = 2;
        OPERANDS[SWITCH] = -1;
        for (java.lang.reflect.Field f : Op.class.getDeclaredFields()) {
            if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !f.getName().equals("COUNT")) {
                try {
                    int v = f.getInt(null);
                    if (v >= 0 && v < COUNT)
                        NAMES[v] = f.getName();
                } catch (IllegalAccessException ignored) {
                    // never: own static fields
                }
            }
        }
    }

    /** Total instruction length in words, header included. */
    static int length(int[] code, int pc) {
        int op = code[pc] & 0xFF;
        if (op == SWITCH)
            return 3 + SWITCH_ENTRY * code[pc + 1];
        return 1 + OPERANDS[op];
    }

    /** Words per SWITCH table entry: a range's low and high keys (two words each) and the target. */
    static final int SWITCH_ENTRY = 5;

    static int header(int op, int cls, int cycles) {
        return op | (cls << 8) | (cycles << 12);
    }

    private Op() {}
}
