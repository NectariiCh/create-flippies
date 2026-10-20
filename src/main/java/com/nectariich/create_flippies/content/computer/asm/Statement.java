package com.nectariich.create_flippies.content.computer.asm;

import java.util.List;

/**
 * One parsed statement: a label, a numeric local label, an assignment ({@code name = expr}), a
 * directive or an instruction. The first pass records its section, offset and size; the second
 * pass must emit exactly that many bytes. {@link #backendData} lets a backend keep what it worked
 * out in the first pass.
 */
public final class Statement {

    public enum Kind { LABEL, LOCAL_LABEL, ASSIGN, DIRECTIVE, INSTRUCTION }

    final int seq;
    final int file;
    final String fileName;
    final int line;
    final int column;
    final Kind kind;
    final String name;
    final List<Operand> operands;

    /** Null while in a dropped section. */
    Section section;
    long offset;
    int size;
    boolean failed;
    SymbolTable.Symbol symbol;

    /** Free for the backend; survives from the first pass to the second. */
    public Object backendData;

    Statement(int seq, int file, String fileName, int line, int column, Kind kind, String name,
              List<Operand> operands) {
        this.seq = seq;
        this.file = file;
        this.fileName = fileName;
        this.line = line;
        this.column = column;
        this.kind = kind;
        this.name = name;
        this.operands = List.copyOf(operands);
    }

    public Kind kind() {
        return kind;
    }

    /** Lower-case mnemonic or directive (with its dot), the label name, or the assigned symbol. */
    public String mnemonic() {
        return name;
    }

    public List<Operand> operands() {
        return operands;
    }

    public Operand operand(int i) {
        return operands.get(i);
    }

    public int line() {
        return line;
    }

    /** Column of the mnemonic. */
    public int column() {
        return column;
    }

    public String fileName() {
        return fileName;
    }

    public Section section() {
        return section;
    }

    /** The size the first pass gave this statement (valid in the second pass). */
    public int size() {
        return size;
    }

    /** Throws the standard operand-count error unless there are {@code n} operands. */
    public void expectOperands(int n) {
        if (operands.size() != n)
            throw new AsmError(column, "'" + name + "' expects " + n + " operand" + (n == 1 ? "" : "s")
                    + ", got " + operands.size());
    }
}
