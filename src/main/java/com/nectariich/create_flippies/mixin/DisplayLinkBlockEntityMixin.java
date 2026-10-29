package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.content.display.link.ElementAddressedTarget;
import com.nectariich.create_flippies.content.display.link.LinkAddress;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps a display link's packed target line away from targets that do not know how to
 * read it.
 *
 * A link aimed at one of our element displays stores a row, an element and a
 * generation in Create's one {@code targetLine} ({@link LinkAddress}), which can be a
 * number in the hundreds of millions. That number stays on the link when the block it
 * points at is swapped for something else, and Create's own targets take the line at
 * face value: a lectern pads its book with blank pages up to it (up to fifty), a
 * display board looks for a row that is not there. So on its way to any target that
 * is not an {@link ElementAddressedTarget}, only the row part is passed, which is
 * exactly the plain line number every such link held before elements existed. The
 * link's own field is left alone, so swapping the block back restores the element.
 *
 * The one call site Create has: {@code updateGatheredData} hands the line to
 * {@code DisplaySource.transferData}, which passes it on to the target (and to a
 * display board's flap text) untouched. Create's names, so nothing here is remapped.
 */
@Mixin(value = DisplayLinkBlockEntity.class, remap = false)
public abstract class DisplayLinkBlockEntityMixin {

    @Shadow
    public DisplayTarget activeTarget;

    @ModifyArg(method = "updateGatheredData",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/api/behaviour/display/DisplaySource;transferData(Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;Lcom/simibubi/create/api/behaviour/display/DisplayTarget;I)V"),
            index = 2)
    private int create_flippies$plainLineForOtherTargets(int line) {
        return activeTarget instanceof ElementAddressedTarget ? line : LinkAddress.row(line);
    }
}
