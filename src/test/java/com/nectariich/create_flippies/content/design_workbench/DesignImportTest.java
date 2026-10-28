package com.nectariich.create_flippies.content.design_workbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import com.nectariich.create_flippies.client.design.DesignGeometry;
import com.nectariich.create_flippies.client.design.DesignImporter;
import com.nectariich.create_flippies.compat.Ids;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;

import dev.nectariich.hypergraphical.api.MeshInfo;
import dev.nectariich.hypergraphical.mesh.MeshBounds;
import dev.nectariich.hypergraphical.mesh.MeshData;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model with a base and a knob, its material library in a subfolder and its
 * texture named with capitals and spaces, run through everything a design is made
 * of short of the game: import, the stored blob, Hypergraphical's parse of the
 * result, and the boxes the control is placed by.
 */
class DesignImportTest {

    private static final String OBJ = String.join("\n",
            "mtllib mats/Switch Parts.mtl",
            "o Base",
            "v 0 0 0", "v 1 0 0", "v 1 0 1", "v 0 0 1", "v 0 0.2 0", "v 1 0.2 0", "v 1 0.2 1", "v 0 0.2 1",
            "usemtl Body",
            "f 1 2 3 4", "f 5 8 7 6", "f 1 5 6 2", "f 2 6 7 3", "f 3 7 8 4", "f 4 8 5 1",
            "o Knob",
            "v 0.4 0.2 0.1", "v 0.6 0.2 0.1", "v 0.6 0.5 0.1", "v 0.4 0.5 0.1",
            "v 0.4 0.2 0.9", "v 0.6 0.2 0.9", "v 0.6 0.5 0.9", "v 0.4 0.5 0.9",
            "usemtl Body",
            "f 9 10 11 12", "f 13 16 15 14", "f 9 12 16 13", "f 10 14 15 11", "f 12 11 15 16", "f 9 13 14 10");

    private static final String MTL = String.join("\n",
            "newmtl Body", "Kd 0.8 0.8 0.8", "map_Kd -s 1 1 1 C:\\Users\\someone\\Textures\\Knob Wood.PNG");

    @TempDir
    Path dir;

    @Test
    void importsParsesAndBoxes() throws Exception {
        Path obj = dir.resolve("My Switch.obj");
        Files.writeString(obj, OBJ);
        Files.createDirectories(dir.resolve("mats"));
        Files.writeString(dir.resolve("mats/Switch Parts.mtl"), MTL);
        Path texture = dir.resolve("Knob Wood.PNG");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", texture.toFile());

        DesignImporter.Imported imported = DesignImporter.load(List.of(obj));
        DesignBundle bundle = imported.bundle();
        assertEquals("My Switch", imported.name());
        assertTrue(imported.warnings().isEmpty(), imported.warnings().toString());
        assertEquals("my_switch.obj", bundle.objName());
        assertTrue(bundle.files().containsKey("switch_parts.mtl"), bundle.files().keySet().toString());
        assertTrue(bundle.files().containsKey("knob_wood.png"), bundle.files().keySet().toString());
        bundle.files().keySet().forEach(name -> assertTrue(DesignBundle.isSafeName(name), name));
        assertTrue(new String(bundle.obj(), StandardCharsets.ISO_8859_1).startsWith("mtllib switch_parts.mtl"));
        assertTrue(new String(bundle.files().get("switch_parts.mtl"), StandardCharsets.ISO_8859_1)
                .contains("map_Kd knob_wood.png"));

        byte[] blob = bundle.encode();
        DesignBundle decoded = DesignBundle.decode(blob);
        assertEquals(bundle.objName(), decoded.objName());
        assertArrayEquals(bundle.obj(), decoded.obj());
        assertTrue(ControlDesign.isHash(DesignBundle.hash(blob)));

        ResourceLocation base = Ids.of("create_flippies", "designs/test/");
        ObjMeshReader.Result result = ObjMeshReader.read(new ByteArrayInputStream(decoded.obj()),
                Ids.of("create_flippies", "designs/test/" + decoded.objName()),
                ObjImportSettings.DEFAULT, mtl -> {
                    byte[] bytes = decoded.files().get(mtl.getPath().substring(base.getPath().length()));
                    return bytes == null ? null : new ByteArrayInputStream(bytes);
                });
        MeshData mesh = result.mesh();
        assertEquals(List.of("Base", "Knob"), List.copyOf(mesh.partBounds().keySet()));
        assertEquals(0.5f, mesh.partBounds().get("Knob").maxY(), 1e-6);
        assertTrue(result.materials().get("Body") != null, "material library resolved");

        MeshInfo info = meshInfo(mesh.bounds(), mesh.partBounds(), mesh.triangleCount());
        float[][] switchPoses = ControlDesign.defaultPoses(DesignKind.SWITCH_2P);
        // 4 px per unit, centred: the base spans 4 px, the knob is 0.8 px wide and 3.2 px long
        DesignGeometry.Boxes boxes = DesignGeometry.compute(DesignKind.SWITCH_2P, info, Set.of("Knob"), 4, 6, 0, 6,
                switchPoses, null);
        assertArrayEquals(new double[] { 7.6, 0.8, 6.4, 8.4, 2.0, 9.6 }, boxes.moving(), 1e-5);
        assertArrayEquals(new double[] { 6, 0, 6, 10, 2.0, 10 }, boxes.front(), 1e-5);

        // The knob alone: at -45 degrees its corners (0.4, 1.6) px off the axis swing to
        // 8 + 2 * 0.707 on x, so the front grows past the 8.4 px it has at rest
        MeshInfo knobOnly = meshInfo(mesh.partBounds().get("Knob"), Map.of("Knob", mesh.partBounds().get("Knob")),
                mesh.triangleCount());
        double[] swept = DesignGeometry.compute(DesignKind.SWITCH_2P, knobOnly, Set.of("Knob"), 4, 6, 0, 6,
                switchPoses, null).front();
        assertEquals(8 + 2 * Math.sqrt(0.5), swept[3], 1e-5);
        // and on z the corner reaches furthest out partway through the swing, at about
        // 14 degrees, where it stands sqrt(0.4^2 + 1.6^2) from the axis; the sweep is
        // sampled, so it lands within a hair of that
        assertEquals(8 - Math.sqrt(0.4 * 0.4 + 1.6 * 1.6), swept[2], 5e-3);
        assertTrue(swept[2] < 6.4);

        // A button whose cap rests a pixel out and sinks a quarter in: the press box is
        // where it rests, and the front reaches from there down to where it sinks
        float[][] buttonPoses = { { 0, 1, 0 }, { 0, -0.25f, 0 } };
        DesignGeometry.Boxes button = DesignGeometry.compute(DesignKind.BUTTON_2P, info, Set.of("Knob"), 4, 6, 0, 6,
                buttonPoses, null);
        assertArrayEquals(new double[] { 7.6, 1.8, 6.4, 8.4, 3.0, 9.6 }, button.moving(), 1e-5);
        assertArrayEquals(new double[] { 6, 0, 6, 10, 3.0, 10 }, button.front(), 1e-5);

        // Height under a point, for seeding a text box: the knob over the middle, the
        // base towards a corner, the plate off the model, and nothing for hidden parts
        assertEquals(2.0, DesignGeometry.topAt(info, null, 4, 6, 0, 6, 8, 8), 1e-5);
        assertEquals(0.8, DesignGeometry.topAt(info, null, 4, 6, 0, 6, 6.5, 6.5), 1e-5);
        assertEquals(0, DesignGeometry.topAt(info, null, 4, 6, 0, 6, 20, 20), 1e-5);
        assertEquals(0.8, DesignGeometry.topAt(info, Set.of("Base"), 4, 6, 0, 6, 8, 8), 1e-5);

        ControlDesign design = new ControlDesign(DesignKind.SWITCH_2P, DesignBundle.hash(blob), imported.name(),
                List.of("Knob"), List.of(), 4, 6, 0, 6,
                ControlDesign.defaultPoses(DesignKind.SWITCH_2P), null, boxes.front(), boxes.moving(),
                IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(), 0);
        ControlDesign read = ControlDesign.read(design.write());
        assertEquals(design.model(), read.model());
        assertArrayEquals(design.front(), read.front(), 1e-9);
    }

    @Test
    void clampsBoxesFromUntrustedDesigns() {
        ControlDesign wild = new ControlDesign(DesignKind.BUTTON_2P, "0".repeat(32), "x", List.of("y"),
                List.of(), Float.NaN, 0, 0, 0,
                ControlDesign.defaultPoses(DesignKind.BUTTON_2P), null,
                new double[] { -100, -5, 3, 100, 50, 3 }, new double[] { Double.NaN, 0, 0, 1, 1, 1 },
                IndicatorAction.NO_CLICK, IndicatorRating.V12, List.of(), 0);
        ControlDesign read = ControlDesign.read(wild.write());
        assertArrayEquals(new double[] { -8, 0, 3, 24, 16, 3.25 }, read.front(), 1e-9);
        assertArrayEquals(read.front(), read.moving(), 1e-9);
        assertEquals(1, read.scale());
        // a button that does not click is a pure indicator, and reads back as one
        assertEquals(IndicatorAction.NO_CLICK, read.action());
    }

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    /** A design of the given kind and poses, with nothing else of interest. */
    private static ControlDesign design(DesignKind kind, float[][] poses, float[] pivot) {
        return new ControlDesign(kind, HASH, "Test", List.of("Knob"), List.of(), 4, 6, 0, 6,
                poses, pivot, new double[] { 6, 0, 6, 10, 2, 10 }, new double[] { 7, 0, 7, 9, 2, 9 },
                IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(), 0);
    }

    @Test
    void readsDesignsSavedBeforeMovingParts() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", DesignKind.SWITCH_3P.id());
        tag.putString("Model", HASH);
        tag.putString("Name", "  Old switch  ");
        tag.putString("Handle", "Knob");
        tag.putFloat("Scale", 4);
        tag.putFloat("OffsetX", 6);
        tag.put("Front", doubles(6, 0, 6, 10, 2, 10));
        tag.put("Moving", doubles(7.6, 0.8, 6.4, 8.4, 2.0, 9.6));
        tag.putString("Action", IndicatorAction.SPRING_RETURN.id());
        tag.putString("Rating", IndicatorRating.V24.id());

        ControlDesign read = ControlDesign.read(tag);
        assertEquals(DesignKind.SWITCH_3P, read.kind());
        assertEquals("Old switch", read.name());
        assertEquals(List.of("Knob"), read.movingParts());
        assertArrayEquals(new double[] { 7.6, 0.8, 6.4, 8.4, 2.0, 9.6 }, read.moving(), 1e-9);
        assertEquals(3, read.positions());
        for (int i = 0; i < 3; i++)
            assertArrayEquals(ControlDesign.defaultPoses(DesignKind.SWITCH_3P)[i], read.poses()[i], 1e-6f);
        assertNull(read.pivot());
        assertTrue(read.texts().isEmpty());
        assertTrue(read.lights().isEmpty());

        // and once written in the new form it reads back the same
        assertEquals(read.write(), ControlDesign.read(read.write()).write());
    }

    @Test
    void roundTripsEverything() {
        DesignText text = new DesignText("ON", 4, 2, 12, 5, 1.5f, true, "ttf:" + HASH.replace('0', 'f'), true,
                false, true, false, 0x33AAFF, 3, 2, 1, 0.5f);
        ControlDesign design = new ControlDesign(DesignKind.BUTTON_2P, HASH, "Lamp", List.of("Cap", "Ring"),
                List.of(ControlDesign.elementKey("Cap", "Lens")), 2, 1, 2, 3,
                new float[][] { { 0, 0.5f, 0 }, { 0, -1, 0.25f } }, null, new double[] { 5, 0, 5, 11, 3, 11 },
                new double[] { 6, 1, 6, 10, 3, 10 }, IndicatorAction.NO_CLICK, IndicatorRating.V12,
                List.of(text), 0);
        ControlDesign read = ControlDesign.read(design.write());
        assertEquals(design.write(), read.write());
        assertEquals(IndicatorAction.NO_CLICK, read.action());
        assertEquals(List.of("Cap#Lens"), read.lights());
        assertEquals(text, read.texts().get(0));
        assertEquals(Set.of(HASH.replace('0', 'f')), read.fontHashes());

        float[] pivot = { 9, 1, 7 };
        ControlDesign pivoted = design(DesignKind.SWITCH_2P, ControlDesign.defaultPoses(DesignKind.SWITCH_2P), pivot);
        assertArrayEquals(pivot, ControlDesign.read(pivoted.write()).pivot(), 1e-6f);
    }

    @Test
    void lightsMixWholePartsAndElements() {
        // A part and one of its own elements, and an element of another part, together
        List<String> lights = List.of("Cap", ControlDesign.elementKey("Cap", "Lens"),
                ControlDesign.elementKey("Ring", "Chrome"));
        CompoundTag tag = design(DesignKind.BUTTON_2P, ControlDesign.defaultPoses(DesignKind.BUTTON_2P), null).write();
        ListTag list = new ListTag();
        lights.forEach(light -> list.add(net.minecraft.nbt.StringTag.valueOf(light)));
        tag.put("Lights", list);
        ControlDesign read = ControlDesign.read(tag);
        assertEquals(lights, read.lights());
        assertFalse(read.write().contains("Indication"));

        // Designs saved when a button lit either parts or elements still read their
        // lights as they were, whichever mode their tag names
        tag.putString("Indication", "bodies");
        assertEquals(lights, ControlDesign.read(tag).lights());
        tag.putString("Indication", "elements");
        assertEquals(lights, ControlDesign.read(tag).lights());
    }

    @Test
    void clampsAndPadsPoses() {
        CompoundTag tag = design(DesignKind.SWITCH_3P, ControlDesign.defaultPoses(DesignKind.SWITCH_3P), null).write();
        // one pose only, out of range and broken; the other two come from the defaults
        tag.put("Poses", floats(500, Float.NaN, -500));
        tag.put("Pivot", floats(-100, 5, Float.POSITIVE_INFINITY));
        ControlDesign read = ControlDesign.read(tag);
        assertEquals(3, read.positions());
        assertArrayEquals(new float[] { 180, 0, -180 }, read.poses()[0], 1e-6f);
        assertArrayEquals(new float[] { 0, 0, 0 }, read.poses()[1], 1e-6f);
        assertArrayEquals(new float[] { 0, -45, 0 }, read.poses()[2], 1e-6f);
        assertArrayEquals(new float[] { -8, 5, DesignMotion.DEFAULT_PIVOT[2] }, read.pivot(), 1e-6f);

        // a button keeps two poses whatever it is sent, and travels at most half a block
        CompoundTag button = design(DesignKind.BUTTON_2P, ControlDesign.defaultPoses(DesignKind.BUTTON_2P), null)
                .write();
        button.put("Poses", floats(0, 20, 0, 0, -20, 0, 1, 1, 1));
        ControlDesign buttonRead = ControlDesign.read(button);
        assertEquals(2, buttonRead.positions());
        assertArrayEquals(new float[] { 0, 8, 0 }, buttonRead.poses()[0], 1e-6f);
        assertArrayEquals(new float[] { 0, -8, 0 }, buttonRead.poses()[1], 1e-6f);
    }

    @Test
    void posesTurnAboutThePivotAndPressInALine() {
        // The default 2-position poses are the old handle angles about x = z = 8
        ControlDesign plain = design(DesignKind.SWITCH_2P, ControlDesign.defaultPoses(DesignKind.SWITCH_2P), null);
        Matrix4f old = new Matrix4f().translation(8, 0, 8).rotateY((float) Math.toRadians(-45)).translate(-8, 0, -8);
        assertPoint(old.transformPosition(new Vector3f(9, 3, 8)),
                DesignMotion.pose(plain, 1).transformPosition(new Vector3f(9, 3, 8)));
        assertPoint(new Vector3f(8 + (float) Math.sqrt(0.5), 3, 8 + (float) Math.sqrt(0.5)),
                DesignMotion.pose(plain, 1).transformPosition(new Vector3f(9, 3, 8)));
        // halfway is half the angle
        Matrix4f half = new Matrix4f().translation(8, 0, 8).rotateY((float) Math.toRadians(-22.5))
                .translate(-8, 0, -8);
        assertPoint(half.transformPosition(new Vector3f(9, 3, 8)),
                DesignMotion.pose(plain, 0.5f).transformPosition(new Vector3f(9, 3, 8)));
        // and the first pose is no motion at all
        assertPoint(new Vector3f(9, 3, 8), DesignMotion.pose(plain, 0).transformPosition(new Vector3f(9, 3, 8)));

        // A pivot moves the centre of the turn: the pivot itself stays put
        ControlDesign pivoted = design(DesignKind.SWITCH_2P, new float[][] { { 0, 0, 0 }, { 0, 90, 0 } },
                new float[] { 10, 0, 8 });
        assertPoint(new Vector3f(10, 0, 8), DesignMotion.pose(pivoted, 1).transformPosition(new Vector3f(10, 0, 8)));
        assertPoint(new Vector3f(10, 0, 7), DesignMotion.pose(pivoted, 1).transformPosition(new Vector3f(11, 0, 8)));

        // A button slides in a straight line between released and pressed
        ControlDesign button = design(DesignKind.BUTTON_2P, new float[][] { { 0, 0, 0 }, { 1, -0.5f, 2 } }, null);
        assertPoint(new Vector3f(0.5f, -0.25f, 1),
                DesignMotion.pose(button, 0.5f).transformPosition(new Vector3f()));
        assertPoint(new Vector3f(1, -0.5f, 2),
                DesignMotion.pose(button, DesignMotion.buttonPosition(true)).transformPosition(new Vector3f()));
        // and overshooting an end stays at it
        assertPoint(new Vector3f(1, -0.5f, 2), DesignMotion.pose(button, 7).transformPosition(new Vector3f()));
    }

    @Test
    void handleAnglesInvertToPositions() {
        RotarySwitchKind two = RotarySwitchKind.TWO_POSITION, three = RotarySwitchKind.THREE_POSITION;
        assertEquals(0, DesignMotion.switchPosition(two, two.handleAngle(0)), 1e-6);
        assertEquals(1, DesignMotion.switchPosition(two, two.handleAngle(1)), 1e-6);
        assertEquals(0.5f, DesignMotion.switchPosition(two, (two.handleAngle(0) + two.handleAngle(1)) / 2), 1e-6);
        for (int i = 0; i < three.positions(); i++)
            assertEquals(i, DesignMotion.switchPosition(three, three.handleAngle(i)), 1e-6);
        assertEquals(1.5f, DesignMotion.switchPosition(three, (three.handleAngle(1) + three.handleAngle(2)) / 2),
                1e-6);
        // past either end is that end
        assertEquals(0, DesignMotion.switchPosition(two, two.handleAngle(0) + 30 * Math.signum(
                two.handleAngle(0) - two.handleAngle(1))), 1e-6);
        assertEquals(1, DesignMotion.switchPosition(two, two.handleAngle(1) - 30 * Math.signum(
                two.handleAngle(0) - two.handleAngle(1))), 1e-6);
    }

    @Test
    void sweepsATiltedToggle() {
        // A stick 4 px tall, tipped 30 degrees forward about x at the plate's middle
        ControlDesign toggle = design(DesignKind.SWITCH_2P, new float[][] { { 0, 0, 0 }, { 30, 0, 0 } }, null);
        double[] stick = { 7, 0, 7.5, 9, 4, 8.5 };
        double[] swept = DesignMotion.sweep(stick, toggle);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        // x is the axis, so it does not move
        assertEquals(7, swept[0], 1e-5);
        assertEquals(9, swept[3], 1e-5);
        // the top front corner leans furthest forward
        assertEquals(8 + 4 * sin + 0.5 * cos, swept[5], 1e-4);
        assertEquals(7.5, swept[2], 1e-5);
        // the bottom front corner dips under the plate as the stick tips
        assertEquals(-0.5 * sin, swept[1], 1e-4);
        // and the top rises a little before it falls, which the samples catch
        assertTrue(swept[4] > 4 && swept[4] <= Math.sqrt(16 + 0.25) + 1e-5, Double.toString(swept[4]));
    }

    @Test
    void roundTripsAndSanitisesText() {
        DesignText fresh = DesignText.create(2, 3, 14, 6, 1);
        assertEquals(fresh, DesignText.read(fresh.write()));
        assertEquals("Text", fresh.text());
        assertNull(fresh.fontHash());

        CompoundTag wild = fresh.write();
        wild.putString("Text", "two\nlines" + "x".repeat(300));
        wild.putFloat("X1", 100);
        wild.putFloat("X2", Float.NaN);
        wild.putFloat("Z1", -50);
        wild.putFloat("Y", -3);
        wild.putString("Font", "ttf:not-a-hash");
        wild.putInt("Color", 0xFF123456);
        wild.putFloat("Size", 1000);
        wild.putInt("Align", 7);
        wild.putInt("Quarters", -1);
        wild.putFloat("Glow", 5);
        DesignText read = DesignText.read(wild);
        assertEquals(DesignText.MAX_LENGTH, read.text().length());
        assertTrue(read.text().startsWith("two lines"));
        assertEquals(8, read.x1(), 1e-6);
        assertEquals(24, read.x2(), 1e-6);
        assertEquals(-8, read.z1(), 1e-6);
        assertEquals(0, read.y(), 1e-6);
        assertEquals(DesignText.DEFAULT_FONT, read.font());
        assertEquals(0x123456, read.color());
        assertEquals(DesignText.MAX_SIZE, read.size(), 1e-6);
        assertEquals(2, read.align());
        assertEquals(3, read.quarters());
        assertEquals(1, read.glow(), 1e-6);
    }

    @Test
    void roundTripsSprings() {
        ControlDesign design = new ControlDesign(DesignKind.SWITCH_3P, HASH, "Sprung", List.of("Knob"),
                List.of(), 4, 6, 0, 6, ControlDesign.defaultPoses(DesignKind.SWITCH_3P), null,
                new double[] { 6, 0, 6, 10, 2, 10 }, new double[] { 7, 0, 7, 9, 2, 9 },
                IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(), 0b101);
        CompoundTag tag = design.write();
        assertEquals(0b101, tag.getInt("Springs"));
        ControlDesign read = ControlDesign.read(tag);
        assertEquals(0b101, read.springs());
        assertEquals(tag, read.write());

        // No springs is no tag at all, so a design made before there were any
        // writes the same tag it always did, and reads back as springing nowhere
        CompoundTag none = design(DesignKind.SWITCH_2P, ControlDesign.defaultPoses(DesignKind.SWITCH_2P), null)
                .write();
        assertFalse(none.contains("Springs"));
        assertEquals(0, ControlDesign.read(none).springs());

        // An untrusted mask keeps only what the kind allows: no rest position, no
        // positions past the last, and nothing at all on a button
        CompoundTag wild = design.write();
        wild.putInt("Springs", -1);
        assertEquals(0b101, ControlDesign.read(wild).springs());
        wild.putString("Kind", DesignKind.SWITCH_2P.id());
        assertEquals(0b10, ControlDesign.read(wild).springs());
        wild.putString("Kind", DesignKind.BUTTON_2P.id());
        assertEquals(0, ControlDesign.read(wild).springs());
    }

    @Test
    void roundTripsBlueprints() {
        DesignText text = new DesignText("A", 2, 3, 14, 6, 1, false, "ttf:" + HASH.replace('1', 'e'), false, false,
                false, false, 0xFFFFFF, 2, 1, 0, 0);
        ControlDesign design = new ControlDesign(DesignKind.SWITCH_2P, HASH, "Mine", List.of("Knob"),
                List.of(), 4, 6, 0, 6, ControlDesign.defaultPoses(DesignKind.SWITCH_2P),
                null, new double[] { 6, 0, 6, 10, 2, 10 }, new double[] { 7, 0, 7, 9, 2, 9 },
                IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(text), 0);
        Blueprint blueprint = new Blueprint(UUID.randomUUID(), "Mine", "panels/left", UUID.randomUUID().toString(),
                1000, 2000, design);
        Blueprint read = Blueprint.read(blueprint.write());
        assertEquals(blueprint.write(), read.write());
        assertEquals(List.of(HASH, HASH.replace('1', 'e')), List.copyOf(read.blobHashes()));
        assertEquals("Renamed", read.withName("Renamed").design().name());
    }

    @Test
    void recognisesFonts() {
        assertTrue(DesignTransfers.isFont(new byte[] { 0, 1, 0, 0, 9 }));
        assertTrue(DesignTransfers.isFont("OTTO....".getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(DesignTransfers.isFont("true....".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(DesignTransfers.isFont(new byte[] { 0x1f, (byte) 0x8b, 8, 0 }));
        assertFalse(DesignTransfers.isFont(new byte[] { 0, 1 }));
        assertFalse(DesignTransfers.isFont(new byte[DesignTransfers.MAX_FONT_BYTES + 1]));
    }

    private static void assertPoint(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-4, "x");
        assertEquals(expected.y, actual.y, 1e-4, "y");
        assertEquals(expected.z, actual.z, 1e-4, "z");
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double value : values)
            list.add(DoubleTag.valueOf(value));
        return list;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values)
            list.add(FloatTag.valueOf(value));
        return list;
    }

    /**
     * A {@link MeshInfo} from bounds and parts. Through its canonical constructor
     * whatever shape that has, so the test does not depend on whether the library's
     * material elements (an extra list, empty here) have landed yet.
     */
    private static MeshInfo meshInfo(MeshBounds bounds, Map<String, MeshBounds> parts, int triangles) {
        try {
            java.lang.reflect.Constructor<?> constructor = MeshInfo.class.getDeclaredConstructors()[0];
            for (java.lang.reflect.Constructor<?> candidate : MeshInfo.class.getDeclaredConstructors())
                if (candidate.getParameterCount() > constructor.getParameterCount())
                    constructor = candidate;
            Object[] args = constructor.getParameterCount() == 3 ? new Object[] { bounds, parts, triangles }
                    : new Object[] { bounds, parts, triangles, List.of() };
            return (MeshInfo) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
