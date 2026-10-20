package com.nectariich.create_flippies.client.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.*;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.keyboard.KeyboardEvents;

import org.junit.jupiter.api.Test;

class GlfwPs2KeysTest {

    @Test
    void mappedKeys() {
        assertEquals(Ps2Key.A, GlfwPs2Keys.map(GLFW_KEY_A));
        assertEquals(Ps2Key.Z, GlfwPs2Keys.map(GLFW_KEY_Z));
        assertEquals(Ps2Key.DIGIT_0, GlfwPs2Keys.map(GLFW_KEY_0));
        assertEquals(Ps2Key.DIGIT_9, GlfwPs2Keys.map(GLFW_KEY_9));
        assertEquals(Ps2Key.ENTER, GlfwPs2Keys.map(GLFW_KEY_ENTER));
        assertEquals(Ps2Key.ESCAPE, GlfwPs2Keys.map(GLFW_KEY_ESCAPE));
        assertTrue(GlfwPs2Keys.isEscape(GLFW_KEY_ESCAPE));
        assertEquals(Ps2Key.UP, GlfwPs2Keys.map(GLFW_KEY_UP));
        assertEquals(Ps2Key.DOWN, GlfwPs2Keys.map(GLFW_KEY_DOWN));
        assertEquals(Ps2Key.LEFT, GlfwPs2Keys.map(GLFW_KEY_LEFT));
        assertEquals(Ps2Key.RIGHT, GlfwPs2Keys.map(GLFW_KEY_RIGHT));
        assertEquals(Ps2Key.RIGHT_CTRL, GlfwPs2Keys.map(GLFW_KEY_RIGHT_CONTROL));
        assertEquals(Ps2Key.LEFT_CTRL, GlfwPs2Keys.map(GLFW_KEY_LEFT_CONTROL));
        assertEquals(Ps2Key.KP_7, GlfwPs2Keys.map(GLFW_KEY_KP_7));
        assertEquals(Ps2Key.KP_ENTER, GlfwPs2Keys.map(GLFW_KEY_KP_ENTER));
        assertEquals(Ps2Key.KP_DIVIDE, GlfwPs2Keys.map(GLFW_KEY_KP_DIVIDE));
        assertEquals(Ps2Key.F12, GlfwPs2Keys.map(GLFW_KEY_F12));
    }

    @Test
    void unmappedKeysAreIgnored() {
        assertNull(GlfwPs2Keys.map(GLFW_KEY_F13));
        assertNull(GlfwPs2Keys.map(GLFW_KEY_UNKNOWN));
        assertNull(GlfwPs2Keys.map(GLFW_KEY_KP_EQUAL));
    }

    @Test
    void packetEventEncodingRoundTrip() {
        Ps2Key key = GlfwPs2Keys.map(GLFW_KEY_RIGHT_ALT);
        short event = KeyboardEvents.encode(key, true);
        assertEquals(key, KeyboardEvents.key(event));
        assertTrue(KeyboardEvents.down(event));
    }
}
