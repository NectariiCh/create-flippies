package com.nectariich.create_flippies.content.design_workbench.annunciator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;

import dev.nectariich.hypergraphical.mesh.MeshBounds;
import dev.nectariich.hypergraphical.mesh.MeshData;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * The annunciator rack generator: the two example racks the Unity project ships,
 * read the two ways it reads them, the rack's sanitising and legends, and the model
 * it builds, parsed by Hypergraphical as the game would.
 */
class AnnunciatorRackTest {

    /** Rack A from the Unity examples: an 8 x 4 process rack as a legends grid and a colours grid. */
    private static final String RACK_A_LABELS = String.join("\n",
            "RCP 1|LO SPEED,RCP 2|LO SPEED,RCP 3|LO SPEED,RCP 4|LO SPEED,RCS PRESS|LO,RCS PRESS|HI,PZR LEVEL|LO,PZR LEVEL|HI",
            "SG 1 LEVEL|LO,SG 2 LEVEL|LO,SG 3 LEVEL|LO,SG 4 LEVEL|LO,SG PRESS|LO,FEEDWATER|TROUBLE,CVCS|TROUBLE,RHR|TROUBLE",
            "TURB|OVERSPEED,TURB STOP VLV|SHUT,GEN LOAD|HI,GRID FREQ|ABNORM,BYPASS VLV|OPEN,COND VAC|LO,LUBE OIL|PRESS LO,GEN|DIFF TRIP",
            "RX TRIP,RX POWER|HI,EMERG DSL|RUNNING,10BBA BUS|LO VOLTS,RAD MON|HI,FIRE|ALARM,-,-");

    private static final String RACK_A_COLOURS = String.join("\n",
            "amber,amber,amber,amber,red,red,amber,amber",
            "amber,amber,amber,amber,amber,amber,amber,amber",
            "red,amber,amber,amber,white,amber,red,red",
            "red,red,xgreen,amber,amber,xred,,");

    /** Rack B: a 6 x 2 turbine rack as one list, row 2 column 4 left out. */
    private static final String RACK_B = String.join("\n",
            "row,column,text,colour,id",
            "1,1,TURBINE|TRIP,red,TURB_TRIP",
            "1,2,THRUST BRG|WEAR,red,",
            "1,3,VIBRATION|HI,amber,",
            "1,4,LUBE OIL|TEMP HI,amber,",
            "1,5,\"GEN H2|PRESS LO\",amber,",
            "1,6,EXCITER|TROUBLE,amber,",
            "2,1,GEN BREAKER|OPEN,xgreen,",
            "2,2,SYNC|PERMISSIVE,white,",
            "2,3,GEN|MOTORING,xred,",
            "2,5,STATOR|TEMP HI,amber,",
            "2,6,ANNUNCIATOR|POWER FAIL,xamber,");

    private static AnnunciatorDraft fresh() {
        return AnnunciatorDraft.of(AnnunciatorRack.defaults());
    }

    @Test
    void readsAGridPairInEitherOrder() throws Exception {
        AnnunciatorDraft draft = fresh();
        // Colours first on purpose: they are applied after the legends whatever the order
        AnnunciatorCsv.importFiles(draft, List.of(RACK_A_COLOURS, RACK_A_LABELS));
        AnnunciatorRack rack = draft.build();

        assertEquals(8, rack.cellsX);
        assertEquals(4, rack.cellsY);
        assertEquals("RCP 1\nLO SPEED", rack.tile(0, 0).legend());
        assertEquals("RCP_1_LO_SPEED", rack.tile(0, 0).id());
        assertEquals(LensColor.RED, rack.tile(0, 4).color());
        assertEquals(LensColor.XGREEN, rack.tile(3, 2).color());
        assertEquals("RX TRIP", rack.tile(3, 0).legend());
        assertTrue(rack.tile(3, 6).blank());
        assertTrue(rack.tile(3, 7).blank());
        assertEquals(AnnunciatorCsv.Content.COLOURS, AnnunciatorCsv.parse(RACK_A_COLOURS).content());
        assertEquals(AnnunciatorCsv.Content.LABELS, AnnunciatorCsv.parse(RACK_A_LABELS).content());
    }

    @Test
    void readsAListAsBothAndLeavesWhatItSkips() throws Exception {
        AnnunciatorDraft draft = fresh();
        draft.tile(1, 3).legend = "KEEP ME";
        AnnunciatorCsv.Sheet sheet = AnnunciatorCsv.parse(RACK_B);
        assertEquals(AnnunciatorCsv.Content.BOTH, sheet.content());
        AnnunciatorCsv.importFiles(draft, List.of(RACK_B));
        AnnunciatorRack rack = draft.build();

        assertEquals(6, rack.cellsX);
        assertEquals(2, rack.cellsY);
        assertEquals("TURB_TRIP", rack.tile(0, 0).id());
        assertEquals("GEN H2\nPRESS LO", rack.tile(0, 4).legend());
        assertEquals(LensColor.XAMBER, rack.tile(1, 5).color());
        // Not in the list, so as it was: the grid kept the window where it stood on the panel
        assertEquals("KEEP ME", rack.tile(1, 3).legend());
    }

    @Test
    void resizingNeverLosesAWindow() {
        AnnunciatorDraft draft = fresh();
        draft.tile(2, 5).legend = "FAR CORNER";
        // Typing "12" into the width passes through "1"
        draft.cellsX = 1;
        assertEquals(1 * 3, draft.build().tiles.size());
        draft.cellsX = 12;
        assertEquals("FAR CORNER", draft.build().tile(2, 5).legend());
    }

    @Test
    void refusesWhatIsNotARack() {
        assertThrows(AnnunciatorCsv.CsvException.class, () -> AnnunciatorCsv.parse("  \n"));
        assertThrows(AnnunciatorCsv.CsvException.class,
                () -> AnnunciatorCsv.parse("row,column,text\nx,1,A"));
        assertThrows(AnnunciatorCsv.CsvException.class,
                () -> AnnunciatorCsv.parse("row,column,text\n0,1,A"));
        // A bad file among good ones changes nothing
        AnnunciatorDraft draft = fresh();
        String before = draft.build().write().toString();
        assertThrows(AnnunciatorCsv.CsvException.class,
                () -> AnnunciatorCsv.importFiles(draft, List.of(RACK_A_LABELS, "row,column,text\n0,1,A")));
        assertEquals(before, draft.build().write().toString());
    }

    @Test
    void numbersDuplicateIdsAndParsesColourSpellings() throws Exception {
        AnnunciatorDraft draft = fresh();
        AnnunciatorCsv.importFiles(draft, List.of("FIRE,FIRE;x\nFIRE,B"));
        // Semicolons were not the most common separator on the first line, so ";x" is text
        AnnunciatorRack rack = draft.build();
        assertEquals("FIRE", rack.tile(0, 0).id());
        assertEquals("FIRE_X", rack.tile(0, 1).id());
        assertEquals("FIRE_2", rack.tile(1, 0).id());

        assertEquals(LensColor.XRED, LensColor.parse("Dark Red"));
        assertEquals(LensColor.XRED, LensColor.parse("x-red"));
        assertEquals(LensColor.AMBER, LensColor.parse("YELLOW"));
        assertNull(LensColor.parse("purple"));
    }

    @Test
    void exportReadsBackTheSame() throws Exception {
        AnnunciatorDraft draft = fresh();
        AnnunciatorCsv.importFiles(draft, List.of(RACK_A_LABELS, RACK_A_COLOURS));
        draft.tile(0, 0).legend = "A, \"quoted\"\nLINE";
        AnnunciatorRack rack = draft.build();

        AnnunciatorDraft back = fresh();
        AnnunciatorCsv.importFiles(back, List.of(AnnunciatorCsv.export(rack)));
        assertEquals(rack.write(), back.build().write());
    }

    @Test
    void sanitisesWhatItReads() {
        CompoundTag tag = AnnunciatorRack.defaults().write();
        tag.putInt("CellsX", 99);
        tag.putFloat("Width", Float.NaN);
        tag.putFloat("Depth", 1000);
        tag.putString("Font", "evil:font");
        AnnunciatorRack rack = AnnunciatorRack.read(tag);
        assertEquals(AnnunciatorRack.MAX_CELLS, rack.cellsX);
        assertEquals(24, rack.width);
        assertEquals(AnnunciatorRack.MAX_DEPTH, rack.depth);
        assertEquals(DesignText.DEFAULT_FONT, rack.font);
        assertEquals(rack.cellsX * rack.cellsY, rack.tiles.size());

        // A missing tag is the default rack
        assertEquals(AnnunciatorRack.defaults().write(), AnnunciatorRack.read(new CompoundTag()).write());

        // No room for windows: an error, and no legends to print
        AnnunciatorDraft draft = fresh();
        draft.bezel = 8;
        draft.tile(0, 0).legend = "X";
        assertEquals("rack.error.room", draft.build().error());
        assertTrue(draft.build().legends().isEmpty());
    }

    @Test
    void laysLegendsOutOnTheirLenses() {
        AnnunciatorDraft draft = fresh();
        draft.tile(1, 2).legend = "TWO\nLINES";
        draft.tile(0, 0).legend = "ONE";
        draft.tile(0, 1).blank = true;
        draft.tile(0, 1).legend = "HIDDEN";
        AnnunciatorRack rack = draft.build();

        List<DesignText> legends = rack.legends();
        assertEquals(3, legends.size());
        DesignText one = legends.get(0);
        assertEquals("ONE", one.text());
        assertEquals(rack.cellLeft(0) + rack.legendPadding, one.x1(), 1e-5);
        assertTrue(one.y() > rack.lensY() && one.y() < rack.depth);
        DesignText top = legends.get(1), bottom = legends.get(2);
        assertEquals("TWO", top.text());
        assertEquals("LINES", bottom.text());
        assertEquals(top.z2(), bottom.z1(), 1e-5);
        assertTrue(top.z1() >= rack.cellTop(1) && bottom.z2() <= rack.cellTop(1) + rack.cellHeight() + 1e-5);
        assertTrue(top.size() <= rack.legendSize);
    }

    @Test
    void travelsOnADesignOfItsKindOnly() {
        AnnunciatorDraft draft = fresh();
        draft.tile(0, 0).legend = "A";
        AnnunciatorRack rack = draft.build();
        ControlDesign design = design(DesignKind.ANNUNCIATOR, rack);
        ControlDesign read = ControlDesign.read(design.write());
        assertNotNull(read.rack());
        assertEquals(rack.write(), read.rack().write());
        assertEquals(1, read.positions());
        assertEquals(1, read.legends().size());
        assertTrue(read.hasPrint());

        assertNull(ControlDesign.read(design(DesignKind.SWITCH_2P, rack).write()).rack());
        // A rack design always has a rack, even one written without
        CompoundTag tag = design.write();
        tag.remove(AnnunciatorRack.TAG);
        assertNotNull(ControlDesign.read(tag).rack());
    }

    @Test
    void buildsAModelHypergraphicalReads() throws Exception {
        AnnunciatorDraft draft = fresh();
        AnnunciatorCsv.importFiles(draft, List.of(RACK_B));
        AnnunciatorRack rack = draft.build();
        DesignBundle bundle = AnnunciatorMesh.build(rack);

        // The same rack makes the same bytes, so the same hash
        assertArrayEquals(bundle.encode(), AnnunciatorMesh.build(draft.build()).encode());

        MeshData mesh = parse(bundle);
        Set<String> expected = new TreeSet<>();
        expected.add(AnnunciatorMesh.FRAME_PART);
        for (int row = 0; row < rack.cellsY; row++)
            for (int column = 0; column < rack.cellsX; column++)
                expected.add(AnnunciatorMesh.windowPart(row, column));
        assertEquals(expected, new TreeSet<>(mesh.partBounds().keySet()));

        MeshBounds bounds = mesh.bounds();
        double[] box = rack.box();
        assertEquals(box[0], bounds.minX(), 1e-3);
        assertEquals(box[2], bounds.minZ(), 1e-3);
        assertEquals(box[3], bounds.maxX(), 1e-3);
        assertEquals(box[4], bounds.maxY(), 1e-3);
        assertEquals(box[5], bounds.maxZ(), 1e-3);
        MeshBounds lens = mesh.partBounds().get(AnnunciatorMesh.windowPart(1, 2));
        assertEquals(rack.lensY(), lens.maxY(), 1e-3);
        assertEquals(rack.cellLeft(2), lens.minX(), 1e-3);
        assertEquals(rack.cellTop(1), lens.minZ(), 1e-3);

        // Every triangle wound to face the way its normal says, so none is culled from the front
        float[] v = mesh.vertices();
        int[] indices = mesh.indices();
        int stride = MeshData.STRIDE_FLOATS;
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i] * stride, b = indices[i + 1] * stride, c = indices[i + 2] * stride;
            float ux = v[b] - v[a], uy = v[b + 1] - v[a + 1], uz = v[b + 2] - v[a + 2];
            float wx = v[c] - v[a], wy = v[c + 1] - v[a + 1], wz = v[c + 2] - v[a + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            int n = a + MeshData.OFF_NORMAL;
            assertTrue(nx * v[n] + ny * v[n + 1] + nz * v[n + 2] > 0, "triangle " + i / 3 + " faces away");
        }

        // Blanking a window takes its lens off and closes the face over it
        draft.tile(0, 0).blank = true;
        MeshData blanked = parse(AnnunciatorMesh.build(draft.build()));
        assertTrue(!blanked.partBounds().containsKey(AnnunciatorMesh.windowPart(0, 0)));
    }

    private static MeshData parse(DesignBundle bundle) throws Exception {
        String base = "designs/test/";
        return ObjMeshReader.read(new ByteArrayInputStream(bundle.obj()),
                new ResourceLocation("create_flippies", base + bundle.objName()), ObjImportSettings.DEFAULT, mtl -> {
                    byte[] bytes = bundle.files().get(mtl.getPath().substring(base.length()));
                    return bytes == null ? null : new ByteArrayInputStream(bytes);
                }).mesh();
    }

    private static ControlDesign design(DesignKind kind, AnnunciatorRack rack) {
        double[] box = rack.box();
        return new ControlDesign(kind, "0".repeat(32), "Rack", new ArrayList<>(), List.of(), 1,
                0, 0, 0, ControlDesign.defaultPoses(kind), null, box, box, IndicatorAction.SPRING_RETURN,
                IndicatorRating.V24, List.of(), 0, rack, null);
    }
}
