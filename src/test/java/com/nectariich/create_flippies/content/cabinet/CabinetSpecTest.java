package com.nectariich.create_flippies.content.cabinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

/** What a cabinet spec keeps in range, and how it is saved and sent. */
class CabinetSpecTest {

    @Test
    void sizesAreClampedToTheirRanges() {
        CabinetSpec low = new CabinetSpec(0, -5, 0, 0x123456, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT).clamp();
        // A door between sides, top and bottom; a back and a door deep
        assertEquals(3, low.widthPx());
        assertEquals(3, low.heightPx());
        assertEquals(2, low.depthPx());
        CabinetSpec under = new CabinetSpec(2, 2, 1, 0x123456, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT).clamp();
        assertEquals(3, under.widthPx());
        assertEquals(3, under.heightPx());
        assertEquals(2, under.depthPx());
        CabinetSpec least = new CabinetSpec(3, 3, 2, 0x123456, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT);
        assertSame(least, least.clamp());
        CabinetSpec high = new CabinetSpec(99, 49, 17, 0x123456, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT).clamp();
        assertEquals(48, high.widthPx());
        assertEquals(48, high.heightPx());
        assertEquals(16, high.depthPx());
    }

    @Test
    void aPresetsColourIsAlwaysItsOwn() {
        CabinetSpec spec = new CabinetSpec(16, 16, 8, 0xABCDEF, 4, CabinetSpec.Hinge.RIGHT).clamp();
        assertEquals(4, spec.preset());
        assertEquals(CabinetSpec.PRESETS.get(4).rgb(), spec.rgb());
    }

    @Test
    void anUnknownPresetIsACustomColourCutTo24Bits() {
        CabinetSpec spec = new CabinetSpec(16, 16, 8, 0xFF123456, 99, null).clamp();
        assertEquals(CabinetSpec.CUSTOM, spec.preset());
        assertEquals(0x123456, spec.rgb());
        assertEquals(CabinetSpec.Hinge.LEFT, spec.hinge());
    }

    @Test
    void aTypedColourThatIsAPresetIsThatPreset() {
        int orange = CabinetSpec.PRESETS.get(4).rgb();
        assertEquals(4, CabinetSpec.DEFAULT.withColour(0xFF000000 | orange).preset());
        assertEquals(CabinetSpec.CUSTOM, CabinetSpec.DEFAULT.withColour(0x010203).preset());
    }

    @Test
    void anInRangeSpecClampsToItself() {
        assertSame(CabinetSpec.DEFAULT, CabinetSpec.DEFAULT.clamp());
    }

    @Test
    void savedAndLoadedItIsTheSame() {
        CabinetSpec spec = new CabinetSpec(37, 5, 3, 0x0A0B0C, CabinetSpec.CUSTOM, CabinetSpec.Hinge.RIGHT);
        assertEquals(spec, CabinetSpec.load(spec.save()));
        assertEquals(CabinetSpec.DEFAULT, CabinetSpec.load(null));
        assertEquals(CabinetSpec.DEFAULT, CabinetSpec.load(new CompoundTag()));
    }

    @Test
    void sentAndReadItIsTheSameAndClamped() {
        CabinetSpec spec = new CabinetSpec(48, 3, 2, 0x0A0B0C, CabinetSpec.CUSTOM, CabinetSpec.Hinge.RIGHT);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        spec.write(buffer);
        assertEquals(spec, CabinetSpec.read(buffer));

        // What a client might send instead
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        new CabinetSpec(200, 0, 255, 0xFF00FF00, 50, CabinetSpec.Hinge.LEFT).write(bad);
        CabinetSpec read = CabinetSpec.read(bad);
        assertEquals(new CabinetSpec(48, 3, 16, 0x00FF00, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT), read);
    }

    @Test
    void standingCabinetsGoLarger() {
        CabinetSpec high = new CabinetSpec(99, 99, 99, 0x123456, CabinetSpec.CUSTOM, CabinetSpec.Hinge.LEFT, true)
                .clamp();
        assertEquals(64, high.widthPx());
        assertEquals(64, high.heightPx());
        assertEquals(32, high.depthPx());
        // Hung on a wall again, it is held to the wall sizes
        CabinetSpec wall = high.withStanding(false);
        assertEquals(48, wall.widthPx());
        assertEquals(16, wall.depthPx());
    }

    @Test
    void standingSurvivesSavingAndTheWire() {
        CabinetSpec spec = new CabinetSpec(60, 50, 30, 0x0A0B0C, CabinetSpec.CUSTOM, CabinetSpec.Hinge.RIGHT, true);
        assertEquals(spec, CabinetSpec.load(spec.save()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        spec.write(buffer);
        assertEquals(spec, CabinetSpec.read(buffer));
    }
}
