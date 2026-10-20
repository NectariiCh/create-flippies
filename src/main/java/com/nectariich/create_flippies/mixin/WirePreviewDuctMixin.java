package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.wire.WireDuctInput;

import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.WirePreview;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE DUCTS: Power Grid's preview of a wire being placed hangs it from the first
 * terminal to the crosshair. For a wire already routed through ducts the part up
 * to the last duct is drawn by {@link WireDuctInput}, so this moves the start of
 * Power Grid's leg to where the wire leaves that duct (and judges its length from
 * there), or hides the leg while the wire is still inside a duct. A wire with no
 * route is left exactly as Power Grid drew it.
 *
 * Every return of the tick, not only the last: it gives up early when the
 * crosshair is on nothing, and the routed part is still worth drawing then.
 */
@Mixin(value = WirePreview.class, remap = false)
public abstract class WirePreviewDuctMixin {

    @Shadow
    private static int renderPath;
    @Shadow
    private static Vec3 renderedPos1;
    @Shadow
    private static Vec3 renderedPos2;
    @Shadow
    private static int renderedColor;
    @Shadow
    private static WireItemEntry renderedItem;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private static void create_flippies$routeThroughDucts(CallbackInfo ci) {
        boolean hanging = renderPath == 1 && renderedPos2 != null;
        Vec3 anchor = WireDuctInput.previewAnchor(hanging ? renderedPos2 : null);
        if (anchor == null || !hanging)
            return;
        if (anchor == WireDuctInput.HIDE_PREVIEW) {
            renderPath = 0;
            return;
        }
        renderedPos1 = anchor;
        if (renderedItem != null)
            renderedColor = anchor.distanceTo(renderedPos2) < renderedItem.maximumLength() ? 0x80AAFFAA : 0x80FFAAAA;
    }
}
