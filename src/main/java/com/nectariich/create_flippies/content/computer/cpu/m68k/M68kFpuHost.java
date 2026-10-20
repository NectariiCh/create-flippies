package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * What the 68040 integer core gives its FPU (spec 10.4, 10.9): instruction stream, registers,
 * effective addresses, memory and exceptions. Implemented by the core; the FPU never touches the
 * bus itself. Frozen: the core and the FPU are written in parallel against it.
 *
 * Faults: any method that reads the instruction stream or memory may hit a bus, access or address
 * error, and {@link #effectiveAddress} may find a reserved encoding. The core then builds that
 * exception's frame and throws an unchecked exception of its own that unwinds the whole
 * instruction. The FPU must let it pass (never catch {@code RuntimeException} around host calls)
 * and must not depend on running anything after it. Writes done before the fault stay done, as
 * on the real chip for FMOVEM.
 *
 * Registers: {@code n} 0..7 are D0..D7, 8..15 are A0..A7, with A7 the active stack pointer.
 */
public interface M68kFpuHost {

    /** The next instruction word, zero-extended; advances the PC by 2. */
    int fetch16();

    /** Address of the FP instruction's first word: FPIAR, and the PC a pre-instruction frame stacks. */
    int instructionAddress();

    /** Address of the next instruction word not yet fetched: the PC a post-instruction frame stacks once decoding is done. */
    int pc();

    int register(int n);

    void setRegister(int n, int value);

    /**
     * The address of the memory effective address {@code mode}, {@code reg} (the opword's EA
     * field, modes 2..7) for an operand of {@code bytes} bytes, with the side effects the
     * instruction has on the real chip, all done here exactly once:
     * <ul>
     * <li>(An)+ adds {@code bytes} to An after taking the address, -(An) subtracts it before;
     * a 1-byte operand moves A7 by 2. For FMOVEM and FSAVE pass the whole transfer size.</li>
     * <li>Displacement, index and full-format extension words are fetched from the instruction
     * stream; PC-relative modes use the address of their first extension word as base.</li>
     * <li>Immediate (mode 7, register 4): the address of the immediate data in the instruction
     * stream, whose length is {@code bytes} rounded up to a whole word; the PC moves past it. A
     * 1-byte immediate is the low byte of its word, so the address returned is the word's + 1.</li>
     * </ul>
     * Modes 0 and 1 are not memory: the FPU reads and writes those registers through
     * {@link #register}; passing them is a programming error (IllegalArgumentException). The FPU
     * checks itself which modes an instruction allows (spec 10.2.4) and takes the F-line
     * exception for the rest; a reserved encoding the core finds (mode 7 with register 5..7, a
     * reserved full extension word) is raised by the core as described above.
     */
    int effectiveAddress(int mode, int reg, int bytes);

    /** A data read in the current privilege, zero-extended. 68040 rules: any alignment. */
    int read8(int address);

    int read16(int address);

    int read32(int address);

    /** A data write of the low 8 bits of {@code value}. */
    void write8(int address, int value);

    void write16(int address, int value);

    void write32(int address, int value);

    /**
     * Takes exception {@code vector} with a 68040 frame of {@code format}
     * ({@link M68kVectors#FORMAT_0}, {@link M68kVectors#FORMAT_2} or {@link M68kVectors#FORMAT_3}),
     * stacking {@code stackedPc} and, for formats 2 and 3, {@code address}. The core enters
     * supervisor mode, pushes the frame, charges the exception cost and loads the handler's
     * address as the PC. Returns normally; the FPU then returns from its entry point at once and
     * changes nothing more.
     */
    void exception(int vector, int format, int stackedPc, int address);
}
