package com.nectariich.create_flippies.client.pc;

import java.util.HashMap;
import java.util.Map;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW key codes to {@link Ps2Key} (spec 11.2.12). GLFW codes are positional, named
 * after a US layout, which is what a PS/2 set 2 scancode means too, so the table is
 * one-to-one by position. Keys with no PS/2 counterpart are absent and ignored.
 *
 * Only compile-time constants of GLFW are read, so the class loads without GLFW's
 * natives and is tested on its own.
 */
public final class GlfwPs2Keys {

    private static final Map<Integer, Ps2Key> KEYS = new HashMap<>();

    static {
        put(GLFW_KEY_ESCAPE, Ps2Key.ESCAPE);
        int[] f = {GLFW_KEY_F1, GLFW_KEY_F2, GLFW_KEY_F3, GLFW_KEY_F4, GLFW_KEY_F5, GLFW_KEY_F6, GLFW_KEY_F7,
                GLFW_KEY_F8, GLFW_KEY_F9, GLFW_KEY_F10, GLFW_KEY_F11, GLFW_KEY_F12};
        Ps2Key[] fk = {Ps2Key.F1, Ps2Key.F2, Ps2Key.F3, Ps2Key.F4, Ps2Key.F5, Ps2Key.F6, Ps2Key.F7, Ps2Key.F8,
                Ps2Key.F9, Ps2Key.F10, Ps2Key.F11, Ps2Key.F12};
        for (int i = 0; i < f.length; i++)
            put(f[i], fk[i]);
        put(GLFW_KEY_PRINT_SCREEN, Ps2Key.PRINT_SCREEN);
        put(GLFW_KEY_SCROLL_LOCK, Ps2Key.SCROLL_LOCK);
        put(GLFW_KEY_PAUSE, Ps2Key.PAUSE);

        put(GLFW_KEY_GRAVE_ACCENT, Ps2Key.BACKTICK);
        Ps2Key[] digits = {Ps2Key.DIGIT_0, Ps2Key.DIGIT_1, Ps2Key.DIGIT_2, Ps2Key.DIGIT_3, Ps2Key.DIGIT_4,
                Ps2Key.DIGIT_5, Ps2Key.DIGIT_6, Ps2Key.DIGIT_7, Ps2Key.DIGIT_8, Ps2Key.DIGIT_9};
        for (int i = 0; i < 10; i++)
            put(GLFW_KEY_0 + i, digits[i]);
        put(GLFW_KEY_MINUS, Ps2Key.MINUS);
        put(GLFW_KEY_EQUAL, Ps2Key.EQUALS);
        put(GLFW_KEY_BACKSPACE, Ps2Key.BACKSPACE);

        put(GLFW_KEY_TAB, Ps2Key.TAB);
        Ps2Key[] letters = {Ps2Key.A, Ps2Key.B, Ps2Key.C, Ps2Key.D, Ps2Key.E, Ps2Key.F, Ps2Key.G, Ps2Key.H,
                Ps2Key.I, Ps2Key.J, Ps2Key.K, Ps2Key.L, Ps2Key.M, Ps2Key.N, Ps2Key.O, Ps2Key.P, Ps2Key.Q, Ps2Key.R,
                Ps2Key.S, Ps2Key.T, Ps2Key.U, Ps2Key.V, Ps2Key.W, Ps2Key.X, Ps2Key.Y, Ps2Key.Z};
        for (int i = 0; i < 26; i++)
            put(GLFW_KEY_A + i, letters[i]);
        put(GLFW_KEY_LEFT_BRACKET, Ps2Key.LEFT_BRACKET);
        put(GLFW_KEY_RIGHT_BRACKET, Ps2Key.RIGHT_BRACKET);
        put(GLFW_KEY_BACKSLASH, Ps2Key.BACKSLASH);
        put(GLFW_KEY_CAPS_LOCK, Ps2Key.CAPS_LOCK);
        put(GLFW_KEY_SEMICOLON, Ps2Key.SEMICOLON);
        put(GLFW_KEY_APOSTROPHE, Ps2Key.APOSTROPHE);
        put(GLFW_KEY_ENTER, Ps2Key.ENTER);
        put(GLFW_KEY_LEFT_SHIFT, Ps2Key.LEFT_SHIFT);
        put(GLFW_KEY_COMMA, Ps2Key.COMMA);
        put(GLFW_KEY_PERIOD, Ps2Key.PERIOD);
        put(GLFW_KEY_SLASH, Ps2Key.SLASH);
        put(GLFW_KEY_RIGHT_SHIFT, Ps2Key.RIGHT_SHIFT);
        // The ISO key between left shift and Z has no key of its own in the set; it reads as backslash
        put(GLFW_KEY_WORLD_2, Ps2Key.BACKSLASH);

        put(GLFW_KEY_LEFT_CONTROL, Ps2Key.LEFT_CTRL);
        put(GLFW_KEY_LEFT_SUPER, Ps2Key.LEFT_GUI);
        put(GLFW_KEY_LEFT_ALT, Ps2Key.LEFT_ALT);
        put(GLFW_KEY_SPACE, Ps2Key.SPACE);
        put(GLFW_KEY_RIGHT_ALT, Ps2Key.RIGHT_ALT);
        put(GLFW_KEY_RIGHT_SUPER, Ps2Key.RIGHT_GUI);
        put(GLFW_KEY_MENU, Ps2Key.MENU);
        put(GLFW_KEY_RIGHT_CONTROL, Ps2Key.RIGHT_CTRL);

        put(GLFW_KEY_INSERT, Ps2Key.INSERT);
        put(GLFW_KEY_HOME, Ps2Key.HOME);
        put(GLFW_KEY_PAGE_UP, Ps2Key.PAGE_UP);
        put(GLFW_KEY_DELETE, Ps2Key.DELETE);
        put(GLFW_KEY_END, Ps2Key.END);
        put(GLFW_KEY_PAGE_DOWN, Ps2Key.PAGE_DOWN);
        put(GLFW_KEY_UP, Ps2Key.UP);
        put(GLFW_KEY_LEFT, Ps2Key.LEFT);
        put(GLFW_KEY_DOWN, Ps2Key.DOWN);
        put(GLFW_KEY_RIGHT, Ps2Key.RIGHT);

        put(GLFW_KEY_NUM_LOCK, Ps2Key.NUM_LOCK);
        put(GLFW_KEY_KP_DIVIDE, Ps2Key.KP_DIVIDE);
        put(GLFW_KEY_KP_MULTIPLY, Ps2Key.KP_MULTIPLY);
        put(GLFW_KEY_KP_SUBTRACT, Ps2Key.KP_MINUS);
        put(GLFW_KEY_KP_ADD, Ps2Key.KP_PLUS);
        put(GLFW_KEY_KP_ENTER, Ps2Key.KP_ENTER);
        put(GLFW_KEY_KP_DECIMAL, Ps2Key.KP_PERIOD);
        Ps2Key[] keypad = {Ps2Key.KP_0, Ps2Key.KP_1, Ps2Key.KP_2, Ps2Key.KP_3, Ps2Key.KP_4, Ps2Key.KP_5,
                Ps2Key.KP_6, Ps2Key.KP_7, Ps2Key.KP_8, Ps2Key.KP_9};
        for (int i = 0; i < 10; i++)
            put(GLFW_KEY_KP_0 + i, keypad[i]);
    }

    private GlfwPs2Keys() {}

    private static void put(int glfw, Ps2Key key) {
        KEYS.put(glfw, key);
    }

    /** The PS/2 key at this GLFW key's position, or null if it has none. */
    public static Ps2Key map(int glfwKey) {
        return KEYS.get(glfwKey);
    }

    public static boolean isEscape(int glfwKey) {
        return glfwKey == GLFW_KEY_ESCAPE;
    }
}
