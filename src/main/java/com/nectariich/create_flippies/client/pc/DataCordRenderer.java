package com.nectariich.create_flippies.client.pc;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.wire.BentWireRenderer;
import com.nectariich.create_flippies.compat.Ids;
import com.nectariich.create_flippies.content.data_cord.DataCordEntity;
import com.nectariich.create_flippies.foundation.wire.bend.WireSweep;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;

/**
 * Draws a data cord (spec 11.2.5): Power Grid's hanging cord, drawn by its own
 * public helper with its insulated wire texture and the copper cord's curve, tinted
 * by port type, and a plug cube on each port.
 */
public class DataCordRenderer extends EntityRenderer<DataCordEntity> {

    private static final ResourceLocation TEXTURE =
            Ids.of("powergrid", "textures/special/insulated_wire.png");

    public DataCordRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(DataCordEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(DataCordEntity cord, float yaw, float partialTicks, PoseStack ms, MultiBufferSource buffers,
            int light) {
        Vec3 pos = cord.position();
        VertexConsumer wire = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        List<Vec3> path = cord.bendPath();
        int colour = 0xFF000000 | cord.portType().colour();
        if (cord.bendPoints().isEmpty()) {
            HangingWireRenderer.renderFromPositions(ms, wire, path.get(0).subtract(pos), path.get(1).subtract(pos),
                    DataCordEntity.CURVE_COEFFICIENT, DataCordEntity.CURVE_COEFFICIENT, DataCordEntity.THICKNESS,
                    light, colour);
        } else {
            // WIRE BENDS 2: a bent cord hangs leg by leg, each leg the curve a cord between its two ends would be,
            // drawn as one tube so the legs meet flush at the bend points
            double c = DataCordEntity.CURVE_COEFFICIENT, t = DataCordEntity.THICKNESS;
            List<WireSweep.Strip> strips = WireSweep.sweep(path, 0, c, c, t, 0.5,
                    BentWireRenderer.firstCross(path, c, c, t));
            BentWireRenderer.draw(ms, wire, strips, pos, at -> light, colour, (float) t, 0,
                    !Minecraft.useFancyGraphics());
        }
        VertexConsumer solid = buffers.getBuffer(RenderType.cutout());
        plug(cord, cord.originA(), cord.normalA(), pos, ms, solid);
        plug(cord, cord.originB(), cord.normalB(), pos, ms, solid);
    }

    /** The plug model's back face is on x = 8 px and its face centred on (8, 8): turn +x to the port's normal. */
    private static void plug(DataCordEntity cord, Vec3 origin, Vec3 normal, Vec3 pos, PoseStack ms,
            VertexConsumer vertices) {
        int light = LevelRenderer.getLightColor(cord.level(), BlockPos.containing(origin.add(normal.scale(0.25))));
        float angle = (float) Math.toDegrees(Math.atan2(-normal.z, normal.x));
        CachedBuffers.partial(ModPartialModels.dataPlug(cord.portType()), ModBlocks.PC_CASE.get().defaultBlockState())
                .translate(origin.subtract(pos))
                .rotateYDegrees(angle)
                .translate(-0.5, -0.5, -0.5)
                .light(light)
                .renderInto(ms, vertices);
    }
}
