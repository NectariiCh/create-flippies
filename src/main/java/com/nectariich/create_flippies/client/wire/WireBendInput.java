package com.nectariich.create_flippies.client.wire;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.RenderStageCompat;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.DoubleClickDetector;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendItems;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.network.EditWireBendPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * Bending hanging wires by hand. With a wire item in the main hand and sneak held,
 * every bend point on every hanging wire nearby is drawn as a small box, and the
 * use key edits them:
 * <ul>
 * <li>on a wire, it adds a point where the crosshair meets the wire and takes hold
 * of it;</li>
 * <li>on a point, it takes hold of that point;</li>
 * <li>twice quickly on the same point, it removes it.</li>
 * </ul>
 * A point taken hold of follows the crosshair at the distance from the eye it had
 * when the drag began ({@link BendGeometry#dragTarget}), until the use key or sneak
 * is let go or the wire item leaves the hand.
 *
 * The client moves the point at once, every frame, and tells the server once a tick;
 * the server checks each edit and sends the points back to everyone watching, and
 * that answer is what the wire settles on once the drag ends.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class WireBendInput {

    /** How far around the player wires show their points, blocks. */
    private static final double SHOW_RANGE = 32;

    /** The least a held point must move before the server is told again, blocks. */
    private static final double SEND_EPSILON = 1e-3;

    private static final double POINT_HALF = 0.07;
    private static final double MARK_HALF = 0.045;

    private static final DoubleClickDetector DOUBLE_CLICK = new DoubleClickDetector(DoubleClickDetector.DEFAULT_WINDOW_MS);

    // The drag. Nothing below means anything unless dragEntity is not -1.

    private static int dragEntity = -1;
    private static int dragIndex;
    private static double dragDistance;
    /** How many points the wire should have; any other count from the server ends the drag. */
    private static int expectedCount;
    private static Vec3 dragAt;
    private static Vec3 lastSent;

    private WireBendInput() {}

    /** Whether points are on show: sneaking with a wire in hand, or in the middle of a drag. */
    public static boolean active(LocalPlayer player) {
        return dragEntity != -1 || (player != null && player.isShiftKeyDown() && WireBendItems.holdsWireItem(player));
    }

    /** Whether a point is in hand; a sag drag ({@link WireSagInput}) does not start meanwhile. */
    public static boolean dragging() {
        return dragEntity != -1;
    }

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;
        // The key repeats while held; a drag swallows every repeat
        if (dragEntity != -1) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                || !WireBendItems.holdsWireItem(player) || WireSagInput.dragging())
            return;
        Pick pick = pick(minecraft, 1f);
        if (pick == null) {
            // WIRE SAG: Power Plant Grid starts its whole-wire sag on any click on a hanging
            // wire with the same wire item, sneaking or not. A sneaking click is bending's,
            // so one that Flippies' own pick just missed is swallowed rather than let
            // through to sag the wire
            if (WireGaugeCompat.ppgLoaded() && minecraft.hitResult instanceof EntityHitResult hit
                    && hit.getEntity() instanceof HangingWireEntity wire && WireSagInput.ppgWouldSag(player, wire)) {
                event.setSwingHand(false);
                event.setCanceled(true);
            }
            return;
        }
        // WIRE BENDS 2: pulling a cord off its socket stays Power Grid's
        if (!pick.hit().isPoint() && pick.wire().bendClickReserved(pick.hit().onWire()))
            return;
        event.setSwingHand(false);
        event.setCanceled(true);

        BendableWire wire = pick.wire();
        Entity entity = wire.bendEntity();
        List<Vec3> points = wire.bendPoints();
        BendGeometry.Hit hit = pick.hit();
        if (hit.isPoint()) {
            if (DOUBLE_CLICK.click(entity.getId(), hit.point(), Util.getMillis())) {
                WireBendService.applyEdit(wire, WireBends.Op.REMOVE, hit.point(), null);
                ModPackets.sendToServer(
                        new EditWireBendPacket(entity.getId(), WireBends.Op.REMOVE, hit.point(), null, true));
                return;
            }
            startDrag(entity, hit.point(), points.size(), hit.onWire(), player.getEyePosition());
            return;
        }
        DOUBLE_CLICK.reset();
        if (points.size() >= WireBends.MAX_POINTS) {
            player.displayClientMessage(Component.translatable(WireBends.Verdict.TOO_MANY.langKey())
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // A leg's index is the index the point between its two ends takes
        int index = hit.leg();
        WireBendService.applyEdit(wire, WireBends.Op.INSERT, index, hit.onWire());
        ModPackets.sendToServer(
                new EditWireBendPacket(entity.getId(), WireBends.Op.INSERT, index, hit.onWire(), true));
        startDrag(entity, index, points.size() + 1, hit.onWire(), player.getEyePosition());
    }

    private static void startDrag(Entity entity, int index, int count, Vec3 point, Vec3 eye) {
        dragEntity = entity.getId();
        dragIndex = index;
        expectedCount = count;
        dragDistance = BendGeometry.grabDistance(eye, point);
        dragAt = point;
        lastSent = point;
    }

    /** The held point, or null if the drag has lost its wire. */
    private static BendableWire dragged(ClientLevel level) {
        if (dragEntity == -1 || level == null)
            return null;
        Entity entity = level.getEntity(dragEntity);
        if (!(entity instanceof BendableWire wire) || !entity.isAlive() || wire.bendPoints().size() != expectedCount)
            return null;
        return wire;
    }

    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || dragEntity == -1)
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (dragEntity == -1)
    *///?}
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        BendableWire wire = dragged(minecraft.level);
        if (wire == null || player == null) {
            forget();
            return;
        }
        if (minecraft.screen != null || !player.isAlive() || !minecraft.options.keyUse.isDown()
                || !player.isShiftKeyDown() || !WireBendItems.holdsWireItem(player)) {
            release(wire);
            return;
        }
        follow(wire, player, 1f);
        if (dragAt.distanceToSqr(lastSent) > SEND_EPSILON * SEND_EPSILON) {
            lastSent = dragAt;
            ModPackets.sendToServer(new EditWireBendPacket(wire.bendEntity().getId(), WireBends.Op.MOVE,
                    dragIndex, dragAt, false));
        }
    }

    /** Every frame, so the held point moves with the view rather than once a tick. */
    @SubscribeEvent
    //? if forge {
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || dragEntity == -1)
    //?} else {
    /*public static void onRenderTick(RenderFrameEvent.Pre event) {
        if (dragEntity == -1)
    *///?}
            return;
        Minecraft minecraft = Minecraft.getInstance();
        BendableWire wire = dragged(minecraft.level);
        if (wire != null && minecraft.player != null)
            //? if forge {
            follow(wire, minecraft.player, event.renderTickTime);
            //?} else {
            /*follow(wire, minecraft.player, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            *///?}
    }

    private static void follow(BendableWire wire, LocalPlayer player, float partialTicks) {
        dragAt = BendGeometry.dragTarget(player.getEyePosition(partialTicks), player.getViewVector(partialTicks),
                dragDistance);
        List<Vec3> points = wire.bendPoints();
        if (!points.get(dragIndex).equals(dragAt))
            wire.setBendPoints(WireBends.apply(WireBends.Op.MOVE, points, dragIndex, dragAt));
    }

    /** Ends a drag with the move that tells the server the shape has settled. */
    private static void release(BendableWire wire) {
        ModPackets.sendToServer(new EditWireBendPacket(wire.bendEntity().getId(), WireBends.Op.MOVE,
                dragIndex, dragAt, true));
        forget();
    }

    private static void forget() {
        dragEntity = -1;
        dragAt = null;
        lastSent = null;
    }

    /**
     * The server's word on a wire's points and the sag of its legs. A drag on that wire
     * keeps its point in hand, and a sag drag its leg's sag ({@link WireSagInput#held}).
     */
    public static void received(int entityId, List<Vec3> points, float[] legSags) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entityId) instanceof BendableWire wire))
            return;
        if (entityId == dragEntity && dragAt != null && points.size() == expectedCount)
            points = WireBends.apply(WireBends.Op.MOVE, points, dragIndex, dragAt);
        wire.setBendPoints(points);
        if (legSags != null)
            wire.setBendLegSags(WireSagInput.held(entityId, points.size(), legSags));
    }

    // Picking

    private record Pick(BendableWire wire, BendGeometry.Hit hit) {}

    /**
     * The nearest bend point, or else the nearest wire, under the crosshair and within
     * reach, unless a block is in front of it.
     */
    private static Pick pick(Minecraft minecraft, float partialTicks) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null)
            return null;
        Vec3 eye = player.getEyePosition(partialTicks);
        Vec3 look = player.getViewVector(partialTicks);
        double reach = PlayerUtilities.getReachDistance(player);
        Vec3 far = eye.add(look.scale(reach));
        HitResult block = level.clip(new ClipContext(eye, far, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        double limit = block.getType() == HitResult.Type.MISS ? reach : eye.distanceTo(block.getLocation()) + 0.05;

        Pick bestPoint = null, bestWire = null;
        for (Entity entity : level.getEntities((Entity) null, new AABB(eye, far).inflate(1),
                e -> e instanceof BendableWire && e.isAlive())) {
            BendableWire wire = (BendableWire) entity;
            List<Vec3> path = wire.bendPath();
            if (path == null)
                continue;
            // WIRE SAG: a click adds a point onto the leg where it is drawn, sagging as it does
            BendGeometry.Hit hit = BendGeometry.pick(eye, look, limit, wire.bendPoints(), path, 0, wire.bendCurves(),
                    wire.bendThickness());
            if (hit == null)
                continue;
            if (hit.isPoint()) {
                if (bestPoint == null || hit.distance() < bestPoint.hit().distance())
                    bestPoint = new Pick(wire, hit);
            } else if (bestWire == null || hit.distance() < bestWire.hit().distance()) {
                bestWire = new Pick(wire, hit);
            }
        }
        return bestPoint != null ? bestPoint : bestWire;
    }

    // Drawing the points

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (level == null || !active(player))
            return;

        float partialTicks = RenderStageCompat.partialTick(event);
        Pick hover = dragEntity == -1 ? pick(minecraft, partialTicks) : null;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (Entity entity : level.getEntities((Entity) null, player.getBoundingBox().inflate(SHOW_RANGE),
                e -> e instanceof BendableWire && e.isAlive())) {
            BendableWire wire = (BendableWire) entity;
            List<Vec3> points = wire.bendPoints();
            for (int i = 0; i < points.size(); i++) {
                boolean held = entity.getId() == dragEntity && i == dragIndex;
                boolean hovered = hover != null && hover.wire() == wire && hover.hit().point() == i;
                if (held || hovered)
                    box(ms, lines, camera, points.get(i), POINT_HALF * 1.3, 1, 0.85f, 0.2f);
                else
                    box(ms, lines, camera, points.get(i), POINT_HALF, 0.95f, 0.95f, 0.95f);
            }
        }
        // Where a click would add a point
        if (hover != null && !hover.hit().isPoint() && !hover.wire().bendClickReserved(hover.hit().onWire()))
            box(ms, lines, camera, hover.hit().onWire(), MARK_HALF, 0.2f, 0.9f, 1);
        buffers.endBatch(RenderType.lines());
    }

    private static void box(PoseStack ms, VertexConsumer lines, Vec3 camera, Vec3 at, double half, float r, float g,
            float b) {
        AABB box = new AABB(at, at).inflate(half).move(camera.reverse());
        LevelRenderer.renderLineBox(ms, lines, box, r, g, b, 1);
    }
}
