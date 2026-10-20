package com.nectariich.create_flippies.client.wire;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.wire_duct.WireDuctEntity;
import com.nectariich.create_flippies.content.wire_duct.WireDuctItem;
import com.nectariich.create_flippies.content.wire_duct.WireDuctService;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireSweep;
import com.nectariich.create_flippies.foundation.wire.duct.DuctNetwork;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPass;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPlacement;
import com.nectariich.create_flippies.foundation.wire.duct.DuctRoute;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.PendingRoute;
import com.nectariich.create_flippies.network.DuctActionPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.simibubi.create.content.equipment.wrench.WrenchItem;

import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.WirePreview;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The client's side of wire ducts. The game never targets a duct, so this picks
 * them itself, out to the player's block reach, and tells the server what was
 * done to which ({@link DuctActionPacket}):
 * <ul>
 * <li>use, with a wire whose first terminal is chosen: take the wire into the duct
 * here, or, once inside, out of it here;</li>
 * <li>attack: break the duct;</li>
 * <li>use while sneaking, with a wrench: take the duct back;</li>
 * <li>use while sneaking, with wire cutters, on a wire routed through ducts (its
 * strokes in a duct or any hanging part): cut the whole wire.</li>
 * </ul>
 * It also draws what a wire being routed will look like: the passes chosen so far
 * as strokes along their ducts and the hanging legs between them, and, while the
 * wire is inside a duct, its strokes running on to the point of the duct under the
 * crosshair. Power Grid's own preview then hangs only the last leg, from where the
 * wire leaves the last duct ({@link #previewAnchor}).
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class WireDuctInput {

    /** Handed back to Power Grid's preview to have it draw nothing: the wire is inside a duct. */
    public static final Vec3 HIDE_PREVIEW = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    private static final int PREVIEW_COLOR = 0x80AAFFAA;

    // The route preview, worked out once a tick
    private static List<Vec3> previewPath;
    private static long previewDucted;
    private static boolean previewTail;
    private static WireItemEntry previewEntry;

    private WireDuctInput() {}

    // Picking

    public record DuctPick(WireDuctEntity duct, Vec3 at, double distance) {}

    private static double blockDistance(Minecraft minecraft, Vec3 eye, Vec3 far) {
        BlockHitResult block = minecraft.level.clip(new ClipContext(eye, far, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, minecraft.player));
        return block.getType() == HitResult.Type.MISS ? eye.distanceTo(far) : eye.distanceTo(block.getLocation());
    }

    /** The nearest duct under the crosshair and within reach, unless a block is in front of it. */
    public static DuctPick pickDuct(Minecraft minecraft, float partialTicks) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return null;
        Vec3 eye = player.getEyePosition(partialTicks);
        double reach = PlayerUtilities.getReachDistance(player);
        Vec3 far = eye.add(player.getViewVector(partialTicks).scale(reach));
        double limit = blockDistance(minecraft, eye, far) + 0.05;
        DuctPick best = null;
        for (WireDuctEntity duct : WireDuctService.near(minecraft.level, new AABB(eye, far))) {
            var clip = duct.ductBox().clip(eye, far);
            if (clip.isEmpty())
                continue;
            double d = eye.distanceTo(clip.get());
            if (d <= limit && (best == null || d < best.distance()))
                best = new DuctPick(duct, clip.get(), d);
        }
        return best;
    }

    /** The nearest wire routed through ducts under the crosshair, anywhere along its path. */
    private static BendGeometry.Hit pickDuctedWire(Minecraft minecraft, Entity[] picked) {
        LocalPlayer player = minecraft.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1);
        double reach = PlayerUtilities.getReachDistance(player);
        Vec3 far = eye.add(look.scale(reach));
        double limit = blockDistance(minecraft, eye, far) + 0.05;
        BendGeometry.Hit best = null;
        for (Entity entity : minecraft.level.getEntities((Entity) null, new AABB(eye, far).inflate(1),
                e -> e.isAlive() && e instanceof BendableWire w && w.bendDucted())) {
            BendableWire wire = (BendableWire) entity;
            List<Vec3> path = wire.bendPath();
            if (path == null)
                continue;
            BendGeometry.Hit hit = BendGeometry.pickWire(eye, look.normalize(), limit, path, wire.bendDuctLegs(),
                    wire.bendHorizontalCoefficient(), wire.bendVerticalCoefficient(), wire.bendThickness());
            if (hit != null && (best == null || hit.distance() < best.distance())) {
                best = hit;
                picked[0] = entity;
            }
        }
        return best;
    }

    private static boolean hasConnection(ItemStack stack) {
        return stack.getTagElement(PendingRoute.CONNECTION_KEY) != null;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || event.getHand() != InteractionHand.MAIN_HAND)
            return;
        ItemStack held = player.getMainHandItem();

        if (event.isAttack()) {
            DuctPick pick = pickDuct(minecraft, 1);
            if (pick == null)
                return;
            // A wire or anything else the game targets in front of the duct keeps the click
            HitResult target = minecraft.hitResult;
            if (target instanceof EntityHitResult
                    && player.getEyePosition().distanceTo(target.getLocation()) < pick.distance())
                return;
            send(event, DuctActionPacket.Action.BREAK, pick.duct(), pick.at());
            return;
        }
        if (!event.isUseItem())
            return;

        if (player.isShiftKeyDown()) {
            if (WireDuctService.isCutters(held)) {
                Entity[] picked = {null};
                BendGeometry.Hit hit = pickDuctedWire(minecraft, picked);
                if (hit != null)
                    send(event, DuctActionPacket.Action.CUT, picked[0], hit.onWire());
            } else if (held.getItem() instanceof WrenchItem) {
                DuctPick pick = pickDuct(minecraft, 1);
                if (pick != null)
                    send(event, DuctActionPacket.Action.WRENCH, pick.duct(), pick.at());
            }
            return;
        }
        if (WireDuctService.isRoutableWire(level, held) && hasConnection(held)) {
            DuctPick pick = pickDuct(minecraft, 1);
            if (pick != null)
                send(event, DuctActionPacket.Action.ROUTE, pick.duct(), pick.at());
        }
    }

    private static void send(InputEvent.InteractionKeyMappingTriggered event, DuctActionPacket.Action action,
            Entity entity, Vec3 at) {
        event.setCanceled(true);
        event.setSwingHand(true);
        ModPackets.CHANNEL.sendToServer(new DuctActionPacket(action, entity.getId(), at));
    }

    /** The server's word on a routed wire's path. */
    public static void routeReceived(int entityId, List<Vec3> points, long ductLegs) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(entityId) instanceof BendableWire wire)
            wire.setBendRoute(points, ductLegs);
    }

    // The route preview

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // Worked out again by Power Grid's preview tick, which runs after this
        if (event.phase == TickEvent.Phase.START)
            previewPath = null;
    }

    /**
     * Called from Power Grid's preview tick ({@code WirePreviewDuctMixin}) with the
     * point its last leg would hang to (null when it hangs none). Works out this
     * tick's preview, and answers where Power Grid's leg should start instead: the
     * point the wire leaves its last duct, {@link #HIDE_PREVIEW} while it is still
     * inside one, or null for a wire with no route, which is left alone.
     */
    public static Vec3 previewAnchor(Vec3 target) {
        previewPath = null;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null)
            return null;
        ItemStack stack = WirePreview.getUsedWireStack(player);
        if (stack == null || !WireDuctService.isRoutableWire(level, stack))
            return null;
        PendingRoute route = PendingRoute.of(stack);
        CompoundTag connection = stack.getTagElement(PendingRoute.CONNECTION_KEY);
        if (route == null || route.isEmpty() || connection == null)
            return null;
        IWireEndpoint first = WireEndpointType.deserialize(connection);
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (!(first instanceof BlockWireEndpoint terminal) || entry == null)
            return null;
        Vec3 start = terminal.getExactPosition(level);
        if (start == null)
            return null;
        double thickness = entry.wireThickness();
        List<DuctPass> passes = new ArrayList<>(route.passes());
        previewEntry = entry;

        if (route.open() != null) {
            Vec3 end = route.open().at();
            DuctPass live = livePass(minecraft, level, route.open());
            if (live != null) {
                passes.add(live);
                end = live.centers().get(live.centers().size() - 1);
            }
            DuctRoute.Built built = DuctRoute.build(start, passes, end, thickness);
            previewPath = built.path(start, end);
            previewDucted = built.ductLegs();
            previewTail = true;
            return HIDE_PREVIEW;
        }
        Vec3 toward = target != null ? target : player.getEyePosition().add(player.getViewVector(1).scale(3));
        DuctRoute.Built built = DuctRoute.build(start, passes, toward, thickness);
        previewPath = built.path(start, toward);
        previewDucted = built.ductLegs();
        previewTail = false;
        List<Vec3> points = built.points();
        return target == null ? null : points.get(points.size() - 1);
    }

    /** The strokes of a wire inside a duct, from where it went in to the point of a joined duct under the crosshair. */
    private static DuctPass livePass(Minecraft minecraft, ClientLevel level, PendingRoute.Open open) {
        DuctPick pick = pickDuct(minecraft, 1);
        if (pick == null)
            return null;
        Vec3 at = pick.duct().segment().project(pick.at());
        List<WireDuctEntity> ducts = WireDuctService.near(level,
                new AABB(open.at(), at).inflate(DuctNetwork.MAX_SEGMENTS));
        WireDuctEntity entered = null;
        for (WireDuctEntity d : ducts)
            if (d.getUUID().equals(open.duct()))
                entered = d;
        if (entered == null)
            return null;
        List<DuctSegment> segments = new ArrayList<>(ducts.size());
        for (WireDuctEntity d : ducts)
            segments.add(d.segment());
        DuctPass pass = DuctNetwork.route(segments, ducts.indexOf(entered), open.at(), ducts.indexOf(pick.duct()), at,
                0);
        if (pass == null)
            return null;
        int lane = DuctNetwork.freeLane(pass.size(), DuctNetwork.segmentsOf(pass, segments),
                WireDuctService.occupiedLegs(level, new AABB(open.at(), at)));
        return pass.withLane(Math.max(0, lane));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null)
            return;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        ItemStack held = player.getMainHandItem();

        if (previewPath != null && previewEntry != null)
            drawRoute(ms, buffers, level, camera);

        boolean routing = WireDuctService.isRoutableWire(level, held) && hasConnection(held);
        boolean tool = held.getItem() instanceof WrenchItem && player.isShiftKeyDown();
        if (routing || tool) {
            DuctPick pick = pickDuct(minecraft, event.getPartialTick());
            if (pick != null) {
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                LevelRenderer.renderLineBox(ms, lines, pick.duct().ductBox().inflate(0.004).move(camera.reverse()),
                        0.2f, 0.9f, 1f, 0.8f);
                if (routing) {
                    Vec3 at = pick.duct().segment().project(pick.at());
                    LevelRenderer.renderLineBox(ms, lines, new AABB(at, at).inflate(0.05).move(camera.reverse()),
                            1f, 0.85f, 0.2f, 1f);
                }
                buffers.endBatch(RenderType.lines());
            }
        }
        if (held.getItem() instanceof WireDuctItem item)
            drawPlacement(minecraft, ms, buffers, level, camera, held, item);
    }

    private static void drawRoute(PoseStack ms, MultiBufferSource.BufferSource buffers, ClientLevel level,
            Vec3 camera) {
        WireItemEntry entry = previewEntry;
        RenderType type = RenderType.entityTranslucent(entry.texture());
        VertexConsumer buffer = buffers.getBuffer(type);
        List<Vec3> path = previewPath;
        // Without the tail, the last leg is Power Grid's to hang
        int legs = path.size() - 1 - (previewTail ? 0 : 1);
        if (legs > 0) {
            // WIRE BENDS 2: one tube, so the dashes in a duct and the hanging legs meet flush
            List<Vec3> drawn = path.subList(0, legs + 1);
            double h = entry.horizontalCoefficient(), v = entry.verticalCoefficient(), t = entry.wireThickness();
            List<WireSweep.Strip> strips = WireSweep.sweep(drawn, previewDucted, h, v, t, 0.5,
                    BentWireRenderer.firstCross(drawn, h, v, t));
            BentWireRenderer.draw(ms, buffer, strips, camera, at -> 0xF000F0, PREVIEW_COLOR, (float) t, 0,
                    !Minecraft.useFancyGraphics());
        }
        buffers.endBatch(type);
    }

    /** With the first point of a duct chosen, the duct that clicking here would lay. */
    private static void drawPlacement(Minecraft minecraft, PoseStack ms, MultiBufferSource.BufferSource buffers,
            ClientLevel level, Vec3 camera, ItemStack held, WireDuctItem item) {
        CompoundTag start = held.getTagElement("DuctStart");
        if (start == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() == HitResult.Type.MISS)
            return;
        Vec3 from = new Vec3(start.getDouble("X"), start.getDouble("Y"), start.getDouble("Z"));
        Direction face = hit.getDirection();
        Vec3 to = WireDuctService.snapPoint(level, hit.getLocation(), face, item.size());
        List<DuctSegment> pieces = DuctPlacement.plan(from, to, face, item.size());
        int f = start.getInt("Face");
        if (pieces.isEmpty() && f >= 0 && f < 6)
            pieces = DuctPlacement.plan(from, to, Direction.from3DDataValue(f), item.size());
        boolean ok = !pieces.isEmpty() && DuctPlacement.totalLength(pieces) <= DuctPlacement.MAX_LENGTH;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (pieces.isEmpty())
            LevelRenderer.renderLineBox(ms, lines, new AABB(from, from).inflate(item.size().width() / 2)
                    .move(camera.reverse()), 1f, 0.3f, 0.3f, 1f);
        for (DuctSegment piece : pieces)
            LevelRenderer.renderLineBox(ms, lines, piece.box().move(camera.reverse()), ok ? 0.3f : 1f,
                    ok ? 1f : 0.3f, 0.3f, 1f);
        buffers.endBatch(RenderType.lines());
    }
}
