package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import org.patryk3211.powergrid.network.packets.BlockWireCutC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Reads the cut points a client sent in the finer segment unit.
 *
 * The handler uses that unit three times, and all three are conversions out of it:
 * once to place the cut along a segment, twice to price the removed run in wire
 * items. Nothing else in the method carries the constant, so replacing every
 * occurrence is the whole change.
 *
 * The target is the lambda the packet's {@code handle} defers to, named by the
 * compiler rather than by Power Grid; if a future release rearranges that method
 * this mixin will fail to apply loudly, which is the outcome to want here — a
 * silently unconverted cut would delete the wrong length of wire.
 */
@Mixin(value = BlockWireCutC2SPacket.class, remap = false)
public class BlockWireCutPacketMixin {

    @ModifyConstant(method = "lambda$handle$0", constant = @Constant(floatValue = 16.0f))
    private float create_flippies$fineUnitsPerBlock(float pixelsPerBlock) {
        return BlockWireGeometry.UNITS_PER_BLOCK;
    }
}
