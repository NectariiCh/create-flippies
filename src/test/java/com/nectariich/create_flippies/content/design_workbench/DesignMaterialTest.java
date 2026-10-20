package com.nectariich.create_flippies.content.design_workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorMesh;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * A design's edited materials: what they save and read back, that a design which
 * edits none writes exactly as before, and that whatever a tag holds is held in range.
 */
class DesignMaterialTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    private static ControlDesign design(Map<String, DesignMaterial> materials) {
        return new ControlDesign(DesignKind.BUTTON_2P, HASH, "Lamp", List.of("Cap"), List.of("Cap#lens"), 4, 6, 0, 6,
                ControlDesign.defaultPoses(DesignKind.BUTTON_2P), null, new double[] { 6, 0, 6, 10, 2, 10 },
                new double[] { 7, 0, 7, 9, 2, 9 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(),
                0, null, null, null, materials);
    }

    @Test
    void roundTrips() {
        DesignMaterial lens = new DesignMaterial(0x33AAFF, 0.4f, 0.1f, 0.2f, 1.5f, 0, 0xFF2000);
        DesignMaterial ring = new DesignMaterial(0x101010, 1, 1, 0.35f, 0, 1, DesignMaterial.AUTO);
        ControlDesign read = ControlDesign.read(design(Map.of("Cap#lens", lens, "Body#ring", ring)).write());
        assertEquals(lens, read.material("Cap", "lens"));
        assertEquals(ring, read.material("Body", "ring"));
        assertNull(read.material("Cap", "ring"));
        assertEquals(0xFF2000, lens.effectiveLitColor());
        // Automatic follows the surface colour
        assertEquals(0x101010, ring.effectiveLitColor());

        // and a blueprint renamed keeps them
        Blueprint blueprint = new Blueprint(java.util.UUID.randomUUID(), "Lamp", "", "me", 0, 0,
                design(Map.of("Cap#lens", lens))).withName("Mine");
        assertEquals(lens, blueprint.design().material("Cap", "lens"));
    }

    @Test
    void untouchedDesignWritesNoMaterials() {
        CompoundTag tag = design(null).write();
        assertFalse(tag.contains("Materials"));
        assertTrue(ControlDesign.read(tag).materials().isEmpty());
        // A design from before materials could be edited reads as none edited
        assertTrue(ControlDesign.read(design(Map.of()).write()).materials().isEmpty());
    }

    @Test
    void writesTheSameTagWhateverTheOrder() {
        DesignMaterial a = new DesignMaterial(1, 1, 0, 0.5f, 0, 1, DesignMaterial.AUTO);
        DesignMaterial b = new DesignMaterial(2, 1, 0, 0.5f, 0, 1, DesignMaterial.AUTO);
        java.util.LinkedHashMap<String, DesignMaterial> first = new java.util.LinkedHashMap<>();
        first.put("Z#z", a);
        first.put("A#a", b);
        java.util.LinkedHashMap<String, DesignMaterial> second = new java.util.LinkedHashMap<>();
        second.put("A#a", b);
        second.put("Z#z", a);
        assertEquals(design(first).write(), design(second).write());
    }

    @Test
    void holdsWhateverATagSaysInRange() {
        DesignMaterial wild = new DesignMaterial(0x7F123456, 3, -1, Float.NaN, 99, Float.POSITIVE_INFINITY, -42);
        assertEquals(0x123456, wild.color());
        assertEquals(1, wild.opacity());
        assertEquals(0, wild.metallic());
        assertEquals(0.5f, wild.roughness());
        assertEquals(DesignMaterial.MAX_GLOW, wild.glow());
        assertEquals(1, wild.occlusion());
        assertEquals(DesignMaterial.AUTO, wild.litColor());
        // Kept to hundredths, so a slider's value reads back equal
        assertEquals(0.33f, new DesignMaterial(0, 0.333333f, 0, 0, 0, 0, 0).opacity());

        // Keys that do not name a part and a material are dropped
        CompoundTag tag = design(null).write();
        CompoundTag materials = new CompoundTag();
        materials.put("NoHash", wild.write());
        materials.put("#lens", wild.write());
        materials.put("Cap#", wild.write());
        materials.put("Cap#lens", wild.write());
        tag.put("Materials", materials);
        assertEquals(List.of("Cap#lens"), List.copyOf(ControlDesign.read(tag).materials().keySet()));

        // A material with nothing in it reads as plain white, solid, half rough
        DesignMaterial empty = DesignMaterial.read(new CompoundTag());
        assertEquals(new DesignMaterial(0xFFFFFF, 1, 0, 0.5f, 0, 1, DesignMaterial.AUTO), empty);
    }

    @Test
    void rackLensesAreKnownByName() {
        assertTrue(AnnunciatorMesh.isLens(AnnunciatorMesh.windowPart(0, 2),
                AnnunciatorMesh.lensMaterial(com.nectariich.create_flippies.content.design_workbench.annunciator.LensColor.XRED)));
        assertFalse(AnnunciatorMesh.isLens(AnnunciatorMesh.FRAME_PART, "frame"));
    }
}
