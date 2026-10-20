package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * The case's boxes against the model the user authored (docs/PC_SPEC.md 11.4): the
 * export is read here, so a re-export that moves a port fails this test instead of
 * leaving a click box beside the drawn port.
 */
class PcCaseGeometryTest {

    private static final Path MODEL = Path.of("src/main/resources/assets/create_flippies/models/block/pc-case-1.json");
    private static final String[] PORT_NAMES = {"ps/2 1", "ps/2 2", "ethernet", "com1", "com2", "com3", "vga1",
            "vga2", "vga3"};

    private static List<JsonObject> elements() throws IOException {
        JsonObject model = JsonParser.parseString(Files.readString(MODEL)).getAsJsonObject();
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : model.getAsJsonArray("elements"))
            list.add(e.getAsJsonObject());
        return list;
    }

    private static double[] box(JsonObject element) {
        JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
        return new double[] {from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()};
    }

    private static double[] named(List<JsonObject> elements, String name, int occurrence) {
        int seen = 0;
        for (JsonObject e : elements)
            if (e.has("name") && e.get("name").getAsString().equals(name) && seen++ == occurrence)
                return box(e);
        throw new AssertionError("no element " + name + " #" + occurrence);
    }

    @Test
    void boxesEqualTheModelsCubes() throws IOException {
        List<JsonObject> elements = elements();
        for (int port = 0; port < PcCaseGeometry.PORTS; port++)
            assertArrayEquals(named(elements, PORT_NAMES[port], 0), PcCaseGeometry.PORT_BOXES[port], 1e-9,
                    PORT_NAMES[port]);
        assertArrayEquals(named(elements, "power connector", 0), PcCaseGeometry.SOCKET, 1e-9);
        assertArrayEquals(named(elements, "power button", 0), PcCaseGeometry.POWER_BUTTON, 1e-9);
        assertArrayEquals(named(elements, "reset button", 0), PcCaseGeometry.RESET_BUTTON, 1e-9);
        assertArrayEquals(named(elements, "power indicator", 0), PcCaseGeometry.POWER_LED, 1e-9);
        assertArrayEquals(named(elements, "error indicator", 0), PcCaseGeometry.ERROR_LED, 1e-9);
        assertArrayEquals(named(elements, "front panel", 0), PcCaseGeometry.FRONT_PANEL, 1e-9);
        assertArrayEquals(named(elements, "motherboard", 0), PcCaseGeometry.MOTHERBOARD, 1e-9);
        assertArrayEquals(named(elements, "floppy disc", 0), PcCaseGeometry.FLOPPY_DISK, 1e-9);
        assertArrayEquals(named(elements, "hdd", 0), PcCaseGeometry.HDD, 1e-9);
        assertArrayEquals(named(elements, "cpu", 0), PcCaseGeometry.CPU, 1e-9);
        // Two sticks named `ram`; slot A is the lower x
        double[] first = named(elements, "ram", 0), second = named(elements, "ram", 1);
        double[] a = first[0] < second[0] ? first : second, b = first[0] < second[0] ? second : first;
        assertArrayEquals(a, PcCaseGeometry.RAM_A, 1e-9);
        assertArrayEquals(b, PcCaseGeometry.RAM_B, 1e-9);
        for (int i = 0; i < PcCaseGeometry.SHELL.length; i++)
            assertArrayEquals(box(elements.get(i)), PcCaseGeometry.SHELL[i], 1e-9, "shell element " + i);
    }

    @Test
    void grownPortBoxesDoNotOverlap() {
        List<double[]> boxes = new ArrayList<>();
        for (int port = 0; port < PcCaseGeometry.PORTS; port++)
            boxes.add(PcCaseGeometry.portBox(port));
        boxes.add(PcCaseGeometry.socketBox());
        for (int i = 0; i < boxes.size(); i++)
            for (int j = i + 1; j < boxes.size(); j++)
                assertFalse(overlap(boxes.get(i), boxes.get(j)), "boxes " + i + " and " + j + " overlap");
    }

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    private static Vec3 centre(double[] b) {
        return new Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    @Test
    void eachBoxResolvesToItself() {
        Set<PcSlot> closed = EnumSet.of(PcSlot.FRONT_PANEL);
        for (int port = 0; port < PcCaseGeometry.PORTS; port++) {
            PcCaseGeometry.Target t = PcCaseGeometry.targetAt(centre(PcCaseGeometry.portBox(port)), closed);
            assertEquals(PcCaseGeometry.Kind.PORT, t.kind());
            assertEquals(port, t.port());
            // The outer face of the cube, where a ray from outside lands
            t = PcCaseGeometry.targetAt(PcCaseGeometry.portFaceCentre(port), closed);
            assertEquals(port, t.port());
        }
        assertEquals(PcCaseGeometry.Kind.SOCKET,
                PcCaseGeometry.targetAt(PcCaseGeometry.socketFaceCentre(), closed).kind());
        assertEquals(PcCaseGeometry.Kind.POWER_BUTTON,
                PcCaseGeometry.targetAt(centre(PcCaseGeometry.POWER_BUTTON), closed).kind());
        assertEquals(PcCaseGeometry.Kind.RESET_BUTTON,
                PcCaseGeometry.targetAt(centre(PcCaseGeometry.RESET_BUTTON), closed).kind());
        // The slot on the front wall's face, with and without a disk
        Vec3 slot = new Vec3(2, 9.75, 8);
        assertEquals(PcCaseGeometry.Kind.FLOPPY_SLOT, PcCaseGeometry.targetAt(slot, closed).kind());
        assertEquals(PcCaseGeometry.Kind.FLOPPY_SLOT,
                PcCaseGeometry.targetAt(slot, EnumSet.of(PcSlot.FRONT_PANEL, PcSlot.FLOPPY)).kind());
        // The panel's outer face
        PcCaseGeometry.Target panel = PcCaseGeometry.targetAt(new Vec3(8, 6, 4), closed);
        assertEquals(PcSlot.FRONT_PANEL, panel.slot());

        Set<PcSlot> open = EnumSet.of(PcSlot.MOTHERBOARD, PcSlot.CPU, PcSlot.RAM_A, PcSlot.RAM_B, PcSlot.HDD);
        for (PcSlot slotPart : open) {
            PcCaseGeometry.Target t = PcCaseGeometry.targetAt(centre(PcCaseGeometry.partBox(slotPart)), open);
            assertEquals(PcCaseGeometry.Kind.PART, t.kind(), slotPart.name());
            assertEquals(slotPart, t.slot());
        }
        // The back wall's inner face, with the panel off and nothing in: the open side
        assertEquals(PcCaseGeometry.Kind.INTERIOR,
                PcCaseGeometry.targetAt(new Vec3(8, 6, 11), EnumSet.noneOf(PcSlot.class)).kind());
        // ...and with the panel on it cannot be reached; the outside of the back wall is shell
        assertEquals(PcCaseGeometry.Kind.SHELL, PcCaseGeometry.targetAt(new Vec3(8, 6, 12), closed).kind());
    }

    @Test
    void rotationFollowsTheBlockState() {
        Vec3 front = new Vec3(2, 6, 8);
        // West is as authored; each later facing turns the -x end a quarter clockwise
        assertEquals(new Vec3(2 / 16.0, 6 / 16.0, 0.5), PcCaseGeometry.toBlock(front, Direction.WEST));
        assertVec(new Vec3(0.5, 6 / 16.0, 2 / 16.0), PcCaseGeometry.toBlock(front, Direction.NORTH));
        assertVec(new Vec3(14 / 16.0, 6 / 16.0, 0.5), PcCaseGeometry.toBlock(front, Direction.EAST));
        assertVec(new Vec3(0.5, 6 / 16.0, 14 / 16.0), PcCaseGeometry.toBlock(front, Direction.SOUTH));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Vec3 p = new Vec3(3.25, 7.5, 10.125);
            assertVec(p, PcCaseGeometry.toModel(PcCaseGeometry.toBlock(p, facing), facing));
            // The ports face away from the front
            Vec3 port = PcCaseGeometry.toBlock(PcCaseGeometry.portFaceCentre(0), facing).subtract(0.5, 0, 0.5);
            Vec3 back = Vec3.atLowerCornerOf(PcCaseGeometry.back(facing).getNormal());
            assertEquals(1, Math.signum(port.dot(back)), facing.getName());
            AABB box = PcCaseGeometry.toBlock(PcCaseGeometry.portBox(0), facing);
            assertNotNull(box);
            assertEquals(PcCaseGeometry.portBox(0)[4] - PcCaseGeometry.portBox(0)[1], box.getYsize() * 16, 1e-9);
        }
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-9);
        assertEquals(expected.y, actual.y, 1e-9);
        assertEquals(expected.z, actual.z, 1e-9);
    }
}
