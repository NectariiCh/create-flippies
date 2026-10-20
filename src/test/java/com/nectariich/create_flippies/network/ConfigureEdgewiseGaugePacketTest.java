package com.nectariich.create_flippies.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * The edgewise face menu's packet: every option the menu sets arrives as it was
 * sent, a reset arrives as a reset, and a face a client made up arrives sanitised.
 */
class ConfigureEdgewiseGaugePacketTest {

    private static final BlockPos POS = new BlockPos(3, 64, -7);

    /** A face with every option the menu offers moved off its default. */
    private static EdgewiseFaceDefinition edited() {
        return EdgewiseFaceDefinition.builder(-10, 40)
                .displayName("FLOW")
                .units("m3/h")
                .majorTickInterval(5)
                .minorTicksPerMajor(4)
                .labelFormat("0.0")
                .labelMultiplier(0.1f)
                .bands(new GaugeFaceDefinition.ColorBand(30, 40, 0xFFCC2020),
                        new GaugeFaceDefinition.ColorBand(-10, 0, 0xFF2040CC))
                .band(0.4f, 0.15f)
                .scale(0.1f, 0.8f)
                .tickEdge(0.35f)
                .flipTicks(true)
                .tickLengths(0.3f, 0.2f)
                .tickWidths(0.03f, 0.015f)
                .spine(0.02f, true)
                .labelGap(0.05f)
                .labelSize(0.3f)
                .textSize(0.2f)
                .faceColor(0xFF101010)
                .markingColor(0xFFF0F0F0)
                .pointerColor(0xFF20C020)
                .pointerStyle(EdgewiseFaceDefinition.PointerStyle.BAR)
                .pointerWidth(0.1f)
                .drawDisplayName(false)
                .nameOnSide(true)
                .sideNameMargin(0.03f)
                .faceAspect(4)
                .build();
    }

    private static FriendlyByteBuf encode(ConfigureEdgewiseGaugePacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buffer);
        return buffer;
    }

    /** What a packet decoded from {@code buffer} would send on. */
    private static FriendlyByteBuf relay(FriendlyByteBuf buffer) {
        return encode(new ConfigureEdgewiseGaugePacket(buffer));
    }

    @Test
    void carriesEveryMenuOptionAcrossTheWire() {
        FriendlyByteBuf sent = encode(new ConfigureEdgewiseGaugePacket(POS, edited(), SupplyVoltage.V24, null));
        FriendlyByteBuf relayed = relay(new FriendlyByteBuf(sent.copy()));
        assertEquals(sent, relayed);

        assertEquals(POS, relayed.readBlockPos());
        assertTrue(relayed.readBoolean());
        CompoundTag face = relayed.readNbt();
        assertEquals(edited().toNbt(), face);
        EdgewiseFaceDefinition read = EdgewiseFaceDefinition.fromNbt(face);
        assertEquals(edited().cacheKey(), read.cacheKey());
        assertEquals(EdgewiseFaceDefinition.PointerStyle.BAR, read.pointerStyle);
        assertEquals(0xFF20C020, read.pointerColor);
        assertEquals(0.1f, read.pointerWidth);
        assertTrue(relayed.readBoolean());
        assertEquals(SupplyVoltage.V24, relayed.readEnum(SupplyVoltage.class));
        assertFalse(relayed.readBoolean());
    }

    @Test
    void aResetStaysAReset() {
        FriendlyByteBuf sent = encode(new ConfigureEdgewiseGaugePacket(POS, null, null, null));
        FriendlyByteBuf relayed = relay(new FriendlyByteBuf(sent.copy()));
        assertEquals(sent, relayed);
        relayed.readBlockPos();
        assertFalse(relayed.readBoolean());
        assertFalse(relayed.readBoolean());
        assertFalse(relayed.readBoolean());
    }

    @Test
    void sanitisesAFaceTheClientMadeUp() {
        CompoundTag forged = edited().toNbt();
        forged.putFloat("ScaleTop", 7);
        forged.putFloat("PointerWidth", Float.NaN);
        forged.putString("LabelFormat", "#,##0.0000000");
        forged.putInt("MinorTicks", 5000);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(POS);
        buffer.writeBoolean(true);
        buffer.writeNbt(forged);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);

        FriendlyByteBuf relayed = relay(buffer);
        relayed.readBlockPos();
        assertTrue(relayed.readBoolean());
        EdgewiseFaceDefinition read = EdgewiseFaceDefinition.fromNbt(relayed.readNbt());
        assertTrue(read.scaleTop <= 1 && read.scaleBottom <= 1 && read.scaleTop <= read.scaleBottom);
        assertTrue(Float.isFinite(read.pointerWidth));
        assertEquals("0", read.labelFormat);
        assertTrue(read.minorTicksPerMajor <= 20);
    }

    @Test
    void aMenuEditKeepsWhatTheMenuDoesNotOffer() {
        EdgewiseFaceDefinition plate = EdgewiseFaceDefinition.builder(0, 12).faceAspect(4).bakeResolution(512)
                .build();
        EdgewiseFaceDefinition edited = plate.toBuilder(-5, 5).units("V").build();
        assertEquals(4, edited.faceAspect);
        assertEquals(512, edited.bakeResolution);
        assertEquals(-5, edited.minValue);
    }
}
