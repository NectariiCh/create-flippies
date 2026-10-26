package com.nectariich.create_flippies.content.keyboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** The keyboard and mouse's two ports (spec 11.2.15) are where its model draws them, and apart. */
class KeyboardMousePortsTest {

    private static double[] cube(String model, String name) throws IOException {
        try (Reader reader = new InputStreamReader(KeyboardMousePortsTest.class.getResourceAsStream(
                "/assets/create_flippies/models/block/" + model + ".json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("elements")) {
                JsonObject element = e.getAsJsonObject();
                if (element.has("name") && element.get("name").getAsString().equals(name)) {
                    JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
                    return new double[] {from.get(0).getAsDouble(), from.get(1).getAsDouble(),
                            from.get(2).getAsDouble(), to.get(0).getAsDouble(), to.get(1).getAsDouble(),
                            to.get(2).getAsDouble()};
                }
            }
        }
        return null;
    }

    @Test
    void portsMatchTheModel() throws IOException {
        double[] keyboard = cube("keyboard_and_mouse", "ps/2");
        double[] mouse = cube("keyboard_and_mouse", "ps/2 2");
        assertNotNull(keyboard);
        assertNotNull(mouse);
        assertArrayEquals(KeyboardGeometry.PORT, keyboard, 1e-9);
        assertArrayEquals(KeyboardGeometry.MOUSE_PORT, mouse, 1e-9);
        // The keyboard's own model is unchanged
        assertArrayEquals(KeyboardGeometry.PORT, cube("keyboard", "ps/2"), 1e-9);
    }

    @Test
    void portsDoNotOverlapAndSitOnTheBody() {
        double[] a = KeyboardGeometry.portBox(KeyboardGeometry.PORT);
        double[] b = KeyboardGeometry.portBox(KeyboardGeometry.MOUSE_PORT);
        boolean overlap = a[0] < b[3] && b[0] < a[3] && a[1] < b[4] && b[1] < a[4] && a[2] < b[5] && b[2] < a[5];
        assertFalse(overlap);
        for (double[] port : new double[][] {KeyboardGeometry.PORT, KeyboardGeometry.MOUSE_PORT}) {
            assertArrayEquals(new double[] {KeyboardGeometry.BODY[3]}, new double[] {port[0]}, 1e-9);
            assertFalse(port[2] < KeyboardGeometry.BODY[2] || port[5] > KeyboardGeometry.BODY[5]);
        }
    }
}
