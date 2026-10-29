package com.nectariich.create_flippies.client.design;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.AlphanumDisplayDrawing;
import com.nectariich.create_flippies.client.ControlRenderer;
import com.nectariich.create_flippies.client.ControlRenderers;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;
import com.nectariich.create_flippies.content.design_workbench.DesignedButtonControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedButtonControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedGaugeControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedGaugeControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedRackControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedRackControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedScreenControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedScreenControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedSwitchControl;
import com.nectariich.create_flippies.content.design_workbench.DesignedSwitchControlType;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.indicator.IndicatorColor;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.indicator.IndicatorSize;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a designed control: the rear of the control it is built on as a partial,
 * the same contacts its terminals are boxed around, its imported front through
 * {@link DesignedModels}, and the text printed on it through
 * {@link DesignTextRenderer}.
 *
 * A switch's moving parts follow the handle angle the control eases between
 * detents, turned into a fractional position of the design's own poses; a button's
 * travel is eased by {@link DesignedModels}, since the control only knows pressed
 * or not. A breaker's flag follows the position the control eases it to between its
 * two latched poses. A designed button's lamp parts glow while it is lit, as brightly as its
 * voltage drives it. An annunciator rack is its generated model and legends alone:
 * it has no rear, nothing moves, and its windows stay dark until racks are wired. A
 * gauge's rear is the Square gauge's barrel and studs, placed as a mounted Square
 * gauge's are; its needle is its moving parts, posed at the reading's eased progress
 * along the scale, and its printed face is a material of its model
 * ({@link DesignMaterials}). A screen's rear is the alphanumeric displays' barrel and
 * pins, placed exactly as a mounted display's are, so it is wired where a display is;
 * its glass glows while its backlight is fed, and its elements are drawn on the glass
 * through {@link DesignScreenRenderer}, after the model, whether or not the design
 * prints any text.
 *
 * The rear goes in the cutout buffer exactly as the original controls' bodies do. The
 * reference state each partial is buffered against is the one the original's own
 * drawing uses, because Catnip caches a buffered partial under the partial alone and
 * the indicator body is shared with the indicators.
 */
public class DesignedControlRenderer implements ControlRenderer {

    private static final DesignedControlRenderer INSTANCE = new DesignedControlRenderer();

    /**
     * How far a designed control's rear is lifted out along the mounting axis, in
     * blocks: a hundredth of a pixel. The rear's face at the plate otherwise lies
     * exactly in the panel's surface and the two z-fight; this is too small to see
     * as a gap and enough for the depth buffer to tell them apart.
     */
    private static final float REAR_LIFT = 0.01f / 16;

    private DesignedControlRenderer() {}

    public static void register() {
        for (DesignedSwitchControlType type : DesignedSwitchControlType.all())
            ControlRenderers.register(type, INSTANCE);
        ControlRenderers.register(DesignedButtonControlType.get(), INSTANCE);
        ControlRenderers.register(DesignedRackControlType.get(), INSTANCE);
        for (DesignedGaugeControlType type : DesignedGaugeControlType.all())
            ControlRenderers.register(type, INSTANCE);
        ControlRenderers.register(DesignedScreenControlType.get(), INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        ControlMount mount = control.mount();
        BlockPos host = control.hostPos();
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        float position;
        float indicatorPosition = 0;
        boolean ease = false;
        float glow = 0;
        if (control instanceof DesignedSwitchControl rotary) {
            rear(ModPartialModels.designedRear(rotary.kind()), rotaryReference(), mount, ms, vertices, light);
            position = switchPosition(rotary, partialTicks);
            indicatorPosition = Mth.lerp(partialTicks, rotary.prevIndicatorPosition, rotary.indicatorPosition);
        } else if (control instanceof DesignedButtonControl button) {
            rear(ModPartialModels.indicatorBody(IndicatorSize.MED, IndicatorColor.BLACK), indicatorReference(), mount,
                    ms, vertices, light);
            position = DesignMotion.buttonPosition(button.isPressed());
            ease = true;
            // Full glow at the lamp's rating, dimmer below it, dark below the
            // threshold the built-in indicators light at
            if (button.isLit())
                glow = Mth.clamp(button.brightness(), 0, 1);
        } else if (control instanceof DesignedGaugeControl gauge) {
            gaugeRear(mount, ms, vertices, light);
            position = Mth.lerp(partialTicks, gauge.prevDialState, gauge.dialState);
        } else if (control instanceof DesignedRackControl) {
            position = 0;
        } else if (control instanceof DesignedScreenControl screen) {
            // The 1.5 px display's body partial is the barrel and the pins alone (its
            // glass is a partial of its own), which is what DisplayPins boxes
            rear(ModPartialModels.displayBody(DisplayForm.BRDRL_1P5_SQ), AlphanumDisplayDrawing.referenceState(),
                    mount, ms, vertices, light);
            position = 0;
            // The backlight is a lamp on its own feed, so the glass glows with it
            // whether or not the logic supply is up, as a display's glass does
            if (screen.isBacklit())
                glow = 1;
        } else {
            return;
        }

        ControlDesign design = ((DesignedControl) control).design();
        if (design == null || host == null)
            return;
        DesignedModels.draw(control, design, DesignPose.origin(host, mount), DesignPose.rotation(mount, 0), position,
                indicatorPosition, ease, glow, light);
    }

    /**
     * The design's text, posed like its model. Text on the moving parts follows them
     * to wherever {@link DesignedModels} drew them this frame, eased travel included.
     * A screen's elements come first, since a screen shows them with or without print.
     */
    @Override
    public void renderLegend(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        if (!(control instanceof DesignedControl designed))
            return;
        ControlDesign design = designed.design();
        if (design == null)
            return;
        ControlMount mount = control.mount();
        if (control instanceof DesignedScreenControl screen)
            screen(screen, design, mount, ms, buffer, light);
        if (!design.hasPrint())
            return;
        float fallback;
        if (control instanceof DesignedSwitchControl rotary)
            fallback = switchPosition(rotary, partialTicks);
        else if (control instanceof DesignedButtonControl button)
            fallback = DesignMotion.buttonPosition(button.isPressed());
        else if (control instanceof DesignedGaugeControl gauge)
            fallback = Mth.lerp(partialTicks, gauge.prevDialState, gauge.dialState);
        else if (control instanceof DesignedRackControl || control instanceof DesignedScreenControl)
            fallback = 0;
        else
            return;
        Matrix4f motion = DesignMotion.pose(design, DesignedModels.position(control, fallback));
        Vec3 offset = mount.offset();
        ms.pushPose();
        ms.translate(offset.x, offset.y, offset.z);
        // Recorded and printed after Hypergraphical has drawn the model, so the text
        // lands on it rather than on whatever was behind it
        DesignTextRenderer.drawPlaced(ms, buffer, design, DesignPose.rotation(mount, 0), motion, light);
        ms.popPose();
    }

    /**
     * A screen's elements on its glass, recorded to be drawn after the model like the
     * text is. Nothing, and no buffer asked for, when it is dark or blank; nothing
     * either until its design names the glass, since there is then nowhere to draw.
     */
    private static void screen(DesignedScreenControl screen, ControlDesign design, ControlMount mount, PoseStack ms,
            MultiBufferSource buffer, int light) {
        if (design.screen() == null || !design.screen().hasFace())
            return;
        Vec3 offset = mount.offset();
        ms.pushPose();
        ms.translate(offset.x, offset.y, offset.z);
        DesignScreenRenderer.drawPlaced(ms, buffer, design.screen(), DesignPose.rotation(mount, 0),
                screen.elements(), screen.elements().snapshot(), screen.viewport(), screen.isPowered(),
                screen.isBacklit(), light);
        ms.popPose();
    }

    /** Where a switch's handle is between its detents this frame, as a fractional position. */
    private static float switchPosition(DesignedSwitchControl rotary, float partialTicks) {
        return DesignMotion.switchPosition(rotary.kind(),
                Mth.lerp(partialTicks, rotary.prevHandleAngle, rotary.handleAngle));
    }

    /**
     * Draws a rear partial on its mount, lifted {@link #REAR_LIFT} out along the
     * mounting axis. The lift is the last transform in the chain, so it moves the
     * partial in its own authored frame, along +y, before the mount turns it, and is
     * therefore outwards on every face and at every tilt.
     */
    private static void rear(PartialModel partial, BlockState reference, ControlMount mount, PoseStack ms,
            VertexConsumer vertices, int light) {
        CachedBuffers.partial(partial, reference)
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .translate(0, REAR_LIFT, 0)
                .light(light)
                .renderInto(ms, vertices);
    }

    /**
     * A gauge's barrel and studs, turned and placed exactly as a mounted Square gauge's
     * body is ({@code ProceduralGaugeDrawing.drawBody}): its north-facing model first
     * turned into the plate-down frame its boxes are measured in, then aimed by the
     * mount. The studs drawn are where the gauge's terminals are boxed.
     */
    private static void gaugeRear(ControlMount mount, PoseStack ms, VertexConsumer vertices, int light) {
        CachedBuffers.partial(ModPartialModels.DESIGNED_GAUGE_REAR, gaugeReference())
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(ProceduralGaugeBlock.MOUNTED_AS))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(ProceduralGaugeBlock.MOUNTED_AS))
                .light(light)
                .renderInto(ms, vertices);
    }

    private static BlockState gaugeReference() {
        return ModBlocks.SQUARE_PROCEDURAL_GAUGE.get().defaultBlockState();
    }

    private static BlockState rotaryReference() {
        return ModBlocks.ROTARY_SWITCHES.get(RotarySwitchKind.TWO_POSITION).get().defaultBlockState();
    }

    private static BlockState indicatorReference() {
        return ModBlocks.INDICATORS.get(IndicatorKind.MED_6V).get().defaultBlockState();
    }
}
