package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.gauge.GaugeTheme;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlock;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlockEntity;
import com.simibubi.create.AllPartialModels;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

public class UniversalGaugeRenderer implements BlockEntityRenderer<UniversalGaugeBlockEntity> {

    /**
     * The thermometer needle pivots on (8, 6), the point Power Grid's own
     * ThermometerRenderer spins it about. The sweep is the full 180° — needle flat
     * left at the bottom of the range, flat right at the top — rather than Power
     * Grid's 135°, so it runs the dial's blue-to-red gradient corner to corner.
     *
     * <p>The needle partial is modelled tilted 22.5° up from flat, since a 135°
     * sweep is centred on vertical; undoing that tilt is where the 180° starts.
     */
    private static final float NEEDLE_PIVOT_X = 8 / 16f;
    private static final float NEEDLE_PIVOT_Y = 6 / 16f;
    private static final float NEEDLE_SPAN = Mth.PI;
    private static final float NEEDLE_MODEL_TILT = Mth.PI / 8;

    public UniversalGaugeRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(UniversalGaugeBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof UniversalGaugeBlock))
            return;
        Direction facing = state.getValue(UniversalGaugeBlock.FACING);
        Direction top = be.getTopDirection();
        float progress = Mth.lerp(partialTicks, be.prevDialState, be.dialState);

        if (be.getTheme() == GaugeTheme.THERMOMETER)
            renderThermometer(state, ms, buffer, light, facing, top, progress);
        else
            renderCreateGauge(state, be.getTheme(), ms, buffer, light, facing, top, progress);
    }

    private void renderCreateGauge(BlockState state, GaugeTheme theme, PoseStack ms, MultiBufferSource buffer,
            int light, Direction facing, Direction top, float progress) {
        PartialModel headModel = theme == GaugeTheme.STRESSOMETER
                ? AllPartialModels.GAUGE_HEAD_STRESS
                : AllPartialModels.GAUGE_HEAD_SPEED;
        SuperByteBuffer headBuffer = CachedBuffers.partial(headModel, state);
        SuperByteBuffer dialBuffer = CachedBuffers.partial(AllPartialModels.GAUGE_DIAL, state);

        // Same pivot and rotation math as Create's GaugeRenderer.
        float dialPivot = 5.75f / 16;

        VertexConsumer vb = buffer.getBuffer(RenderType.solid());
        orient(dialBuffer, facing, top)
                .translate(0, dialPivot, dialPivot)
                .rotate((float) (Math.PI / 2 * -progress), Direction.EAST)
                .translate(0, -dialPivot, -dialPivot)
                .light(light)
                .renderInto(ms, vb);
        orient(headBuffer, facing, top).light(light)
                .renderInto(ms, vb);
    }

    /**
     * Power Grid's thermometer casing and needle. Only the needle that follows the
     * reading is drawn — the thermometer's second, red needle marks the hottest
     * temperature it has seen, which the universal gauge has no equivalent of.
     */
    private void renderThermometer(BlockState state, PoseStack ms, MultiBufferSource buffer, int light,
            Direction facing, Direction top, float progress) {
        orientThermometer(CachedBuffers.partial(ModPartialModels.THERMOMETER_BASE, state), facing, top)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        orientThermometer(CachedBuffers.partial(ModPartialModels.THERMOMETER_NEEDLE, state), facing, top)
                .translate(NEEDLE_PIVOT_X, NEEDLE_PIVOT_Y, 0)
                .rotateZ(NEEDLE_MODEL_TILT - NEEDLE_SPAN * progress)
                .translate(-NEEDLE_PIVOT_X, -NEEDLE_PIVOT_Y, 0)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    /**
     * The partials are modeled facing east at the east edge of the block. Rotate them to
     * face {@code facing} (spinning the dial's top toward {@code top} on floors/ceilings)
     * and pull them back against the mounting surface.
     */
    private SuperByteBuffer orient(SuperByteBuffer buffer, Direction facing, Direction top) {
        float wallOffset = -14 / 16f;
        if (facing.getAxis().isHorizontal())
            return buffer.rotateCentered((float) ((-facing.toYRot() - 90) / 180 * Math.PI), Direction.UP)
                    .translate(wallOffset, 0, 0);
        boolean up = facing == Direction.UP;
        float spin = (up ? 90 : 270) - top.toYRot();
        float tilt = up ? Mth.HALF_PI : -Mth.HALF_PI;
        return buffer.rotateCentered((float) (spin / 180 * Math.PI), Direction.UP)
                .rotateCentered(tilt, Direction.SOUTH)
                .translate(wallOffset, 0, 0);
    }

    /**
     * The thermometer partials are modeled looking south from the north edge of the
     * block, so a plain rotation about the block centre both aims the dial along
     * {@code facing} and lands the casing against the surface behind it — no
     * pull-back needed. Power Grid's own FACING points into that surface rather
     * than away from it, which is why this is {@code -facing.toYRot()} where
     * ThermometerRenderer has {@code -facing.toYRot() - 180}.
     */
    private SuperByteBuffer orientThermometer(SuperByteBuffer buffer, Direction facing, Direction top) {
        if (facing.getAxis().isHorizontal())
            return buffer.rotateYCenteredDegrees(-facing.toYRot());
        // Lying flat: the tilt must happen in model space, so it is called last —
        // tilting drops the dial's top to the south (facing down) or north (facing
        // up), and the spin carries it round to `top`.
        boolean up = facing == Direction.UP;
        return buffer.rotateYCenteredDegrees((up ? 180 : 0) - top.toYRot())
                .rotateXCenteredDegrees(up ? -90 : 90);
    }
}
