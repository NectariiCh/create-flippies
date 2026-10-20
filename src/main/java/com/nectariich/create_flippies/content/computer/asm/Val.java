package com.nectariich.create_flippies.content.computer.asm;

/**
 * An expression's value and what it is relative to. {@link #ABS} is a plain number. A section
 * index means "an address in that section": during the first layout pass the value is an offset
 * from the section's start (its base is not known yet), afterwards an absolute address.
 * {@link #ADDR} is an absolute address outside any section (predefined layout symbols, an
 * undefined weak symbol). The distinction matters for branch targets, where a plain number is an
 * offset and an address is a target, as in GNU as.
 */
public record Val(long value, int section) {

    public static final int ABS = -1;
    public static final int ADDR = -2;

    public static Val abs(long v) {
        return new Val(v, ABS);
    }

    public boolean isAbs() {
        return section == ABS;
    }
}
