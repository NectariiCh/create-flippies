package com.nectariich.create_flippies.content.plc.mcu;

/** One compiler message, positioned in the sketch's own source (1-based line and column). */
public record Diagnostic(Severity severity, int line, int column, String message) {

    public enum Severity { ERROR, WARNING, NOTE }

    @Override
    public String toString() {
        return "sketch.ino:" + line + ":" + column + ": " + severity.name().toLowerCase() + ": " + message;
    }
}
