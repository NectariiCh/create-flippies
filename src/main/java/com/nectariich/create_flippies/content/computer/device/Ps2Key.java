package com.nectariich.create_flippies.content.computer.device;

/**
 * The 104 keys of a standard PC keyboard with their scancode set 2 make codes (spec 3.6).
 * Extended keys send an 0xE0 prefix first. Print Screen and Pause have their own sequences,
 * given by {@link #make()} and {@link #brk()}.
 *
 * The declaration order is frozen: new keys are appended at the end and none is ever reordered
 * or removed, because the keyboard block's network packet sends the ordinal (spec 11.1.4, 11.2.12).
 */
public enum Ps2Key {
    ESCAPE(0x76), F1(0x05), F2(0x06), F3(0x04), F4(0x0C), F5(0x03), F6(0x0B), F7(0x83), F8(0x0A),
    F9(0x01), F10(0x09), F11(0x78), F12(0x07), PRINT_SCREEN(0x7C, true), SCROLL_LOCK(0x7E), PAUSE(0x77),

    BACKTICK(0x0E), DIGIT_1(0x16), DIGIT_2(0x1E), DIGIT_3(0x26), DIGIT_4(0x25), DIGIT_5(0x2E),
    DIGIT_6(0x36), DIGIT_7(0x3D), DIGIT_8(0x3E), DIGIT_9(0x46), DIGIT_0(0x45), MINUS(0x4E),
    EQUALS(0x55), BACKSPACE(0x66),

    TAB(0x0D), Q(0x15), W(0x1D), E(0x24), R(0x2D), T(0x2C), Y(0x35), U(0x3C), I(0x43), O(0x44),
    P(0x4D), LEFT_BRACKET(0x54), RIGHT_BRACKET(0x5B), BACKSLASH(0x5D),

    CAPS_LOCK(0x58), A(0x1C), S(0x1B), D(0x23), F(0x2B), G(0x34), H(0x33), J(0x3B), K(0x42),
    L(0x4B), SEMICOLON(0x4C), APOSTROPHE(0x52), ENTER(0x5A),

    LEFT_SHIFT(0x12), Z(0x1A), X(0x22), C(0x21), V(0x2A), B(0x32), N(0x31), M(0x3A), COMMA(0x41),
    PERIOD(0x49), SLASH(0x4A), RIGHT_SHIFT(0x59),

    LEFT_CTRL(0x14), LEFT_GUI(0x1F, true), LEFT_ALT(0x11), SPACE(0x29), RIGHT_ALT(0x11, true),
    RIGHT_GUI(0x27, true), MENU(0x2F, true), RIGHT_CTRL(0x14, true),

    INSERT(0x70, true), HOME(0x6C, true), PAGE_UP(0x7D, true), DELETE(0x71, true), END(0x69, true),
    PAGE_DOWN(0x7A, true), UP(0x75, true), LEFT(0x6B, true), DOWN(0x72, true), RIGHT(0x74, true),

    NUM_LOCK(0x77), KP_DIVIDE(0x4A, true), KP_MULTIPLY(0x7C), KP_MINUS(0x7B), KP_7(0x6C), KP_8(0x75),
    KP_9(0x7D), KP_PLUS(0x79), KP_4(0x6B), KP_5(0x73), KP_6(0x74), KP_1(0x69), KP_2(0x72),
    KP_3(0x7A), KP_0(0x70), KP_PERIOD(0x71), KP_ENTER(0x5A, true);

    private final int code;
    private final boolean extended;

    Ps2Key(int code) {
        this(code, false);
    }

    Ps2Key(int code, boolean extended) {
        this.code = code;
        this.extended = extended;
    }

    /** The set 2 code, without the 0xE0 prefix. */
    public int code() {
        return code;
    }

    public boolean extended() {
        return extended;
    }

    /** Bytes sent when the key goes down. */
    public byte[] make() {
        return switch (this) {
            case PRINT_SCREEN -> bytes(0xE0, 0x12, 0xE0, 0x7C);
            case PAUSE -> bytes(0xE1, 0x14, 0x77, 0xE1, 0xF0, 0x14, 0xF0, 0x77);
            default -> extended ? bytes(0xE0, code) : bytes(code);
        };
    }

    /** Bytes sent when the key goes up; empty for Pause, which has no break code. */
    public byte[] brk() {
        return switch (this) {
            case PRINT_SCREEN -> bytes(0xE0, 0xF0, 0x7C, 0xE0, 0xF0, 0x12);
            case PAUSE -> new byte[0];
            default -> extended ? bytes(0xE0, 0xF0, code) : bytes(0xF0, code);
        };
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++)
            b[i] = (byte) values[i];
        return b;
    }
}
