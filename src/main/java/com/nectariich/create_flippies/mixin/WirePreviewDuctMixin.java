package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.wire.WireDuctInput;

import net.createmod.catnip.data.Pair;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.utility.BlockTrace;
import org.patryk3211.powergrid.electricity.wire.WirePreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE DUCTS 2: Power Grid's preview of a placeable wire being laid, kept true to
 * what a click will do with ducts about.
 * <ul>
 * <li>Aimed at a duct, a click runs the wire into it or along it, which
 * {@link WireDuctInput} previews; Power Grid's own preview (of a click on whatever
 * is behind the duct) is hidden.</li>
 * <li>With the wire's end in a duct, a click elsewhere takes it out through the
 * duct's side first; the traced part of the preview then starts there.</li>
 * </ul>
 * Every return of the tick, not only the last: it gives up early when the
 * crosshair is on nothing, and the duct preview is still worth working out then.
 */
@Mixin(value = WirePreview.class, remap = false)
public abstract class WirePreviewDuctMixin {

    @Shadow
    private static int renderPath;
    @Shadow
    private static Vec3 renderedPos1;
    @Shadow
    private static Pair<BlockTrace.TraceState, BlockTrace.TraceResult> renderedTrace;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private static void create_flippies$ducts(CallbackInfo ci) {
        if (WireDuctInput.previewTick()) {
            renderPath = 0;
            return;
        }
        if (renderPath != 2 || renderedTrace == null || renderedTrace.getSecond() == null)
            return;
        Pair<BlockTrace.TraceState, BlockTrace.TraceResult> fromDuct = WireDuctInput.traceOutOfDuct(renderedPos1,
                renderedTrace.getSecond());
        if (fromDuct != null) {
            renderedPos1 = WireDuctInput.exitStart();
            renderedTrace = fromDuct;
        }
    }
}
