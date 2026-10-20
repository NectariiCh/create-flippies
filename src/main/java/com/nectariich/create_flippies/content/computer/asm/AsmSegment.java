package com.nectariich.create_flippies.content.computer.asm;

/** One contiguous initialised range of an image. {@link AsmImage} hands out copies. */
public record AsmSegment(int address, byte[] bytes) {

    /** First address past the segment, zero-extended. */
    public long end() {
        return Integer.toUnsignedLong(address) + bytes.length;
    }
}
