package com.nectariich.create_flippies.content.computer.asm;

import java.util.Objects;

/** One assembly source file: a name for diagnostics and file-local symbols, and its text. */
public record AsmSource(String fileName, String text) {

    public AsmSource {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(text, "text");
    }
}
