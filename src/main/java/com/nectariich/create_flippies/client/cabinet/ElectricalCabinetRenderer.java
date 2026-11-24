package com.nectariich.create_flippies.client.cabinet;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.compat.Verts;
import com.nectariich.create_flippies.content.cabinet.CabinetCut;
import com.nectariich.create_flippies.content.cabinet.CabinetCuts;
import com.nectariich.create_flippies.content.cabinet.CabinetGeometry;
import com.nectariich.create_flippies.content.cabinet.CabinetWall;
import com.nectariich.create_flippies.content.cabinet.CabinetRail;
import com.nectariich.create_flippies.content.din_rail.DinRailBlock;
import com.nectariich.create_flippies.content.cabinet.CabinetSpec;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws an electrical cabinet from its {@link CabinetGeometry}: every wall and the
 * door a box, each of its six faces drawn, so the inside reads as well as the
 * outside. A greyscale sheet-metal texture tinted the cabinet's colour, tiled one
 * texture a block whatever the size; each face carries its true normal, so the game
 * lights it as any entity, from the room inside ({@code getLightProbePosition}).
 *
 * The door is a shade darker than the frame round it, so where it ends shows, and its
 * handle is dark on whichever edge is free. There is no block model: the boxes are
 * a few dozen quads, made again each frame from the geometry's shared numbers.
 */
public class ElectricalCabinetRenderer extends DeviceRenderer<ElectricalCabinetEntity> {

    private static final ResourceLocation TEXTURE = CreateFlippies.asResource("textures/entity/electrical_cabinet.png");

    /** The door's shade against the frame's, and the handle's colour, 0xRRGGBB. */
    private static final float DOOR_SHADE = 0.9f;
    private static final int HANDLE = 0x2A2C2E;

    /** The rail block's own texture, bound by itself so that it repeats down a long rail. */
    private static final ResourceLocation RAIL_TEXTURE = CreateFlippies.asResource("textures/block/din_rail.png");
    /** Strips of {@link #RAIL_TEXTURE}, {v0, v1} as fractions, as {@code tools/gen_din_rail.py} lays them out. */
    private static final float[] CHANNEL = {0, 2.7f / 16}, LIP = {3 / 16f, 4 / 16f}, SHADE = {4 / 16f, 5 / 16f},
            PLAIN = {6 / 16f, 7 / 16f};
    /** The rail being drawn, where it can go and where it cannot, 0xRRGGBB. */
    private static final int GHOST_OK = 0xBFE8FF, GHOST_BLOCKED = 0xFF5050;

    public ElectricalCabinetRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return null;
    }

    @Override
    public ResourceLocation getTextureLocation(ElectricalCabinetEntity cabinet) {
        return TEXTURE;
    }

    @Override
    protected void renderBody(ElectricalCabinetEntity cabinet, PoseStack ms, MultiBufferSource buffers, int light) {
        draw(cabinet, ms.last(), buffers.getBuffer(RenderType.entitySolid(TEXTURE)), cabinet.spec().rgb(), 255, light,
                true);
    }

    @Override
    protected void renderGhostBody(ElectricalCabinetEntity cabinet, PoseStack ms, MultiBufferSource buffers, int rgb,
            int alpha) {
        draw(cabinet, ms.last(), buffers.getBuffer(RenderType.entityTranslucent(TEXTURE)), rgb, alpha,
                LightTexture.FULL_BRIGHT, false);
    }

    /**
     * The DIN rails on the back plate, and the one being drawn if it is being drawn
     * in this cabinet: the rails from the synced list, each the rail block's folded
     * section cut to its length, the gear on them being the host renderer's.
     */
    @Override
    protected void renderModelSpace(ElectricalCabinetEntity cabinet, float partialTicks, PoseStack ms,
            MultiBufferSource buffers, int light) {
        double plate = cabinet.backPlane().x();
        if (!cabinet.rails().isEmpty()) {
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(RAIL_TEXTURE));
            for (CabinetRail rail : cabinet.rails())
                rail(ms.last(), buffer, rail, plate, 0xFFFFFF, 255, light);
        }
        CabinetCutInput.Preview cut = CabinetCutInput.preview(cabinet, partialTicks);
        if (cut != null)
            cutPreview(cabinet, ms.last(), buffers, cut);
        CabinetRailInput.Preview preview = CabinetRailInput.preview(cabinet, partialTicks);
        if (preview != null && preview.rail().length() > 0)
            rail(ms.last(), buffers.getBuffer(RenderType.entityTranslucent(RAIL_TEXTURE)), preview.rail(), plate,
                    preview.problem().ok() ? GHOST_OK : GHOST_BLOCKED, 160, LightTexture.FULL_BRIGHT);
    }

    /** One rail: the rail block's five-box section, run along the rail from its start to its end. */
    private static void rail(PoseStack.Pose pose, VertexConsumer buffer, CabinetRail rail, double plate, int rgb,
            int alpha, int light) {
        double[][] section = DinRailBlock.railSection(rail.centre());
        for (int part = 0; part < section.length; part++) {
            double[] s = section[part];
            // Authored: x along, y off the panel, z across
            double x0 = plate + s[1], x1 = plate + s[4];
            double[] box = rail.vertical()
                    ? new double[] {x0, rail.start(), s[2], x1, rail.end(), s[5]}
                    : new double[] {x0, s[2], rail.start(), x1, s[5], rail.end()};
            // The base shows the slotted channel at the front, the lips their bright edge
            float[] front = part == 0 ? CHANNEL : part >= 3 ? LIP : SHADE;
            railBox(pose, buffer, box, rail.vertical() ? 1 : 2, front, rgb, alpha, light);
        }
    }

    /**
     * A box of rail, model px, along model axis {@code along} (1 y, 2 z). Every face
     * but the cut ends is textured along the rail, the texture repeating each block;
     * across, each face takes the strip of the rail texture that
     * {@code tools/gen_din_rail.py} gives that part of the section.
     */
    private static void railBox(PoseStack.Pose pose, VertexConsumer buffer, double[] b, int along, float[] front,
            int rgb, int alpha, int light) {
        int across = along == 1 ? 2 : 1;
        float[][] corners = new float[4][];
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            boolean high = face % 2 == 1;
            // The face's own two axes. Either winding will do: rails are drawn without
            // culling, since a cut end or the back of a lip is seen from either side
            int a = (axis + 1) % 3, c = (axis + 2) % 3;
            double plane = high ? b[axis + 3] : b[axis];
            double[][] uv = {{b[a], b[c]}, {b[a + 3], b[c]}, {b[a + 3], b[c + 3]}, {b[a], b[c + 3]}};
            float[] region = axis == 0 ? (high ? front : PLAIN) : axis == along ? PLAIN : SHADE;
            for (int i = 0; i < 4; i++) {
                double[] p = new double[3];
                p[axis] = plane;
                p[a] = uv[i][0];
                p[c] = uv[i][1];
                float u, v;
                if (axis == along) {
                    // A cut end: across and off the panel
                    u = (float) ((p[across] - b[across]) / 16);
                    v = region[0] + (float) ((p[0] - b[0]) / 16);
                } else {
                    int other = axis == 0 ? across : 0;
                    double span = b[other + 3] - b[other];
                    u = (float) (p[along] / 16);
                    v = region[0] + (float) (span <= 0 ? 0 : (p[other] - b[other]) / span) * (region[1] - region[0]);
                }
                corners[i] = new float[] {(float) p[0] / 16, (float) p[1] / 16, (float) p[2] / 16, u, v};
            }
            float nx = axis == 0 ? (high ? 1 : -1) : 0, ny = axis == 1 ? (high ? 1 : -1) : 0,
                    nz = axis == 2 ? (high ? 1 : -1) : 0;
            quad(pose, buffer, rgb, alpha, light, nx, ny, nz, corners[0][0], corners[0][1], corners[0][2],
                    corners[0][3], corners[0][4], corners[1][0], corners[1][1], corners[1][2], corners[1][3],
                    corners[1][4], corners[2][0], corners[2][1], corners[2][2], corners[2][3], corners[2][4],
                    corners[3][0], corners[3][1], corners[3][2], corners[3][3], corners[3][4]);
        }
    }

    /**
     * The walls as their holes leave them ({@link CabinetCuts}), each a box or the
     * rectangles left of it, and the handle, unless a hole has been cut where it is
     * screwed on.
     */
    private static void draw(ElectricalCabinetEntity cabinet, PoseStack.Pose pose, VertexConsumer buffer, int rgb,
            int alpha, int light, boolean detail) {
        CabinetSpec spec = cabinet.spec();
        boolean open = cabinet.doorOpen();
        CabinetCuts.Built walls = cabinet.walls();
        int doorRgb = detail ? shade(rgb, DOOR_SHADE) : rgb;
        for (CabinetWall wall : CabinetWall.values())
            for (double[] box : walls.boxes(wall, open, false))
                box(pose, buffer, box, wall == CabinetWall.DOOR ? doorRgb : rgb, alpha, light);
        double[] handle = CabinetGeometry.handle(spec, open);
        if (handle != null && handleHeld(spec, walls))
            box(pose, buffer, handle, detail ? HANDLE : rgb, alpha, light);
    }

    /** Whether the door is all there under its handle, which stands 2 px in from the free edge. */
    private static boolean handleHeld(CabinetSpec spec, CabinetCuts.Built walls) {
        if (walls.whole())
            return true;
        double[] closed = CabinetGeometry.handle(spec, false);
        double s = CabinetGeometry.doorWidth(spec) - 2;
        for (double y = closed[1] + 0.125; y < closed[4]; y += 0.25)
            for (double u = s + 0.125; u < s + 1; u += 0.25)
                if (!walls.solidAt(CabinetWall.DOOR, u, y))
                    return false;
        return true;
    }

    /** How far off the face a cut's preview is drawn, px, so it never fights the wall for depth. */
    private static final double PREVIEW_LIFT = 0.05;
    /** The preview where it can be made, and where it cannot, 0xRRGGBB. */
    private static final int CUT_OK = 0x3CE65A, CUT_BLOCKED = 0xFF4040;

    /** What the screwdriver would cut or fill, flat on the face being looked at, just off it. */
    private static void cutPreview(ElectricalCabinetEntity cabinet, PoseStack.Pose pose, MultiBufferSource buffers,
            CabinetCutInput.Preview preview) {
        CabinetCut cut = preview.cut();
        CabinetWall.Frame frame = cut.wall().frame(cabinet.spec(), cabinet.doorOpen());
        double t = preview.side() == 0 ? -PREVIEW_LIFT : CabinetGeometry.WALL + PREVIEW_LIFT;
        int rgb = preview.ok() ? CUT_OK : CUT_BLOCKED;
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, a = 110;
        VertexConsumer buffer = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = pose.pose();
        for (CabinetCuts.Rect rect : CabinetCuts.footprint(cabinet.spec(), cut)) {
            Vec3[] corners = {frame.at(rect.u0(), rect.v0(), t), frame.at(rect.u1(), rect.v0(), t),
                    frame.at(rect.u1(), rect.v1(), t), frame.at(rect.u0(), rect.v1(), t)};
            for (Vec3 c : corners)
                Verts.posColor(buffer, matrix, (float) c.x / 16, (float) c.y / 16, (float) c.z / 16, r, g, b, a);
        }
    }

    private static int shade(int rgb, float by) {
        int r = Math.round((rgb >> 16 & 0xFF) * by), g = Math.round((rgb >> 8 & 0xFF) * by),
                b = Math.round((rgb & 0xFF) * by);
        return r << 16 | g << 8 | b;
    }

    /** A box given in model px, all six faces, the texture tiled a block at a time across each. */
    private static void box(PoseStack.Pose pose, VertexConsumer buffer, double[] b, int rgb, int alpha, int light) {
        float x0 = (float) b[0] / 16, y0 = (float) b[1] / 16, z0 = (float) b[2] / 16;
        float x1 = (float) b[3] / 16, y1 = (float) b[4] / 16, z1 = (float) b[5] / 16;
        // Each face counter-clockwise seen from outside; u and v are the face's own two axes, in blocks
        quad(pose, buffer, rgb, alpha, light, 0, 1, 0, // up
                x0, y1, z0, z0, x0, x0, y1, z1, z1, x0, x1, y1, z1, z1, x1, x1, y1, z0, z0, x1);
        quad(pose, buffer, rgb, alpha, light, 0, -1, 0, // down
                x0, y0, z0, z0, x0, x1, y0, z0, z0, x1, x1, y0, z1, z1, x1, x0, y0, z1, z1, x0);
        quad(pose, buffer, rgb, alpha, light, 0, 0, -1, // north
                x0, y0, z0, x0, y0, x0, y1, z0, x0, y1, x1, y1, z0, x1, y1, x1, y0, z0, x1, y0);
        quad(pose, buffer, rgb, alpha, light, 0, 0, 1, // south
                x0, y0, z1, x0, y0, x1, y0, z1, x1, y0, x1, y1, z1, x1, y1, x0, y1, z1, x0, y1);
        quad(pose, buffer, rgb, alpha, light, -1, 0, 0, // west
                x0, y0, z0, z0, y0, x0, y0, z1, z1, y0, x0, y1, z1, z1, y1, x0, y1, z0, z0, y1);
        quad(pose, buffer, rgb, alpha, light, 1, 0, 0, // east
                x1, y0, z0, z0, y0, x1, y1, z0, z0, y1, x1, y1, z1, z1, y1, x1, y0, z1, z1, y0);
    }

    /** Four corners, each x, y, z, u, v, facing the normal given. */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int rgb, int alpha, int light, float nx,
            float ny, float nz, float... c) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        for (int i = 0; i < 20; i += 5)
            Verts.block(buffer, matrix, normal, c[i], c[i + 1], c[i + 2], r, g, b, alpha, c[i + 3], c[i + 4],
                    OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }
}
