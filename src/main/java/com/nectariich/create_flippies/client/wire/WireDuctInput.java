package com.nectariich.create_flippies.client.wire;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.wire_duct.DuctIndex;
import com.nectariich.create_flippies.content.wire_duct.DuctRouting;
import com.nectariich.create_flippies.content.wire_duct.DuctedWires;
import com.nectariich.create_flippies.content.wire_duct.HangingDuctRoute;
import com.nectariich.create_flippies.content.wire_duct.WireDuctEntity;
import com.nectariich.create_flippies.content.wire_duct.WireDuctItem;
import com.nectariich.create_flippies.content.wire_duct.WireDuctService;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;
import com.nectariich.create_flippies.network.DuctActionPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeItem;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntityEndpoint;
import org.patryk3211.powergrid.electricity.wire.BlockWireRenderer;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.BlockTrace;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The client's side of wire ducts. The game never targets a duct, so this picks
 * them itself, out to the player's block reach, and tells the server what was
 * done to which ({@link DuctActionPacket}):
 * <ul>
 * <li>use, with a placeable wire being laid: run the wire into the duct here, or,
 * with its end already in this duct or one joined to it, set its next point here
 * (the end follows the crosshair along the ducts until then);</li>
 * <li>use on a block, with the end of the wire being laid in a duct: take it out
 * of the duct through the side facing the block, then on as Power Grid takes the
 * click ({@code DuctRouting.exit});</li>
 * <li>use, with a dye: recolour the duct's run;</li>
 * <li>use while sneaking, with a screwdriver in either hand: take the duct back
 * (the only way a duct comes off; aimed anywhere else, the click opens the
 * screwdriver's menu as before);</li>
 * <li>use while sneaking, with wire cutters, on a placeable wire that runs
 * through ducts (anywhere along it, or on a duct holding it): cut the whole
 * wire.</li>
 * </ul>
 * A hanging wire being laid (Power Grid's alternate placement) is taken into ducts
 * the same way, by a use on a duct ({@link HangingDuctRoute}); its way so far is
 * drawn as lines, and Power Grid's own preview hangs from where it got to.
 *
 * It also previews the duct a screwdriver click would lay with a duct item in
 * the other hand ({@code ScrewdriverActions}), and what a click on a duct would lay, the same plan the server
 * then lays ({@link DuctRouting#plan}), draws the wire's way through the ducts as a
 * dashed line on their lids while it is being laid, says what the next click will
 * do while its end is in a duct, and keeps Power Grid's own preview true when the
 * wire's end is in a duct ({@code WirePreviewDuctMixin}).
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class WireDuctInput {

    /** Power Grid's preview colour for a path that can be laid. */
    private static final int PREVIEW_OK = 0x80AAFFAA;

    /** The dashed line over the ducts while a wire is laid: what is laid, and where the next click takes it. */
    private static final int ROUTE_LAID = 0xFFFFC830, ROUTE_NEXT = 0xFF40E0FF;

    /** How far past a duct's box the crosshair still counts as on it while a wire's end is held to it. */
    private static final double HOLD_MARGIN = 1 / 32.0;

    /** How often the hint for a wire in a duct is shown again, ticks: about as long as the action bar keeps it. */
    private static final int HINT_EVERY = 50;

    private static final net.minecraft.resources.ResourceLocation PLAIN =
            CreateFlippies.asResource("textures/entity/wire_duct.png");

    // This tick's preview, worked out by previewTick
    private static DuctRouting.Plan preview;
    private static WireDuctEntity previewDuct;
    private static Vec3 previewAt;
    private static WireItemEntry previewEntry;
    private static Vec3 exitFrom, exitTo;
    private static HangingDuctRoute.Plan hangingPreview;

    private static long lastCut = Long.MIN_VALUE;
    private static long lastHint = Long.MIN_VALUE;

    private WireDuctInput() {}

    // Picking

    public record DuctPick(WireDuctEntity duct, Vec3 at, double distance) {}

    private record WirePick(BlockWireEntity wire, Vec3 at) {}

    private static double blockDistance(Minecraft minecraft, Vec3 eye, Vec3 far) {
        BlockHitResult block = minecraft.level.clip(new ClipContext(eye, far, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, minecraft.player));
        return block.getType() == HitResult.Type.MISS ? eye.distanceTo(far) : eye.distanceTo(block.getLocation());
    }

    /** The nearest duct under the crosshair and within reach, unless a block is in front of it. */
    public static DuctPick pickDuct(Minecraft minecraft, float partialTicks) {
        return pickDuct(minecraft, partialTicks, 0);
    }

    /** {@link #pickDuct(Minecraft, float)}, with every duct's box grown by {@code margin}. */
    private static DuctPick pickDuct(Minecraft minecraft, float partialTicks, double margin) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return null;
        Vec3 eye = player.getEyePosition(partialTicks);
        double reach = PlayerUtilities.getReachDistance(player);
        Vec3 far = eye.add(player.getViewVector(partialTicks).scale(reach));
        double limit = blockDistance(minecraft, eye, far) + 0.05;
        DuctPick best = null;
        for (WireDuctEntity duct : DuctIndex.ducts(minecraft.level, new AABB(eye, far))) {
            var clip = duct.ductBox().inflate(margin).clip(eye, far);
            if (clip.isEmpty())
                continue;
            double d = eye.distanceTo(clip.get());
            if (d <= limit && (best == null || d < best.distance()))
                best = new DuctPick(duct, clip.get(), d);
        }
        return best;
    }

    /** A duct under the crosshair with nothing the game targets (a wire, a mob) in front of it. */
    private static DuctPick pickUncovered(Minecraft minecraft) {
        return pickUncovered(minecraft, 0);
    }

    private static DuctPick pickUncovered(Minecraft minecraft, double margin) {
        DuctPick pick = pickDuct(minecraft, 1, margin);
        if (pick == null)
            return null;
        HitResult target = minecraft.hitResult;
        if (target instanceof EntityHitResult
                && minecraft.player.getEyePosition().distanceTo(target.getLocation()) < pick.distance())
            return null;
        return pick;
    }

    /** The placeable wire running through ducts under the crosshair: aimed at itself, or at a duct holding it. */
    private static WirePick pickDuctedWire(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof BlockWireEntity wire
                && DuctedWires.isDucted(level, wire))
            return new WirePick(wire, hit.getLocation());
        DuctPick pick = pickDuct(minecraft, 1);
        if (pick == null)
            return null;
        BlockWireEntity wire = DuctedWires.nearestIn(level, pick.duct(), pick.at());
        return wire == null ? null : new WirePick(wire, pick.at());
    }

    private static boolean layingWire(ClientLevel level, ItemStack held) {
        return DuctRouting.isPlaceableWire(level, held) && DuctRouting.hasConnection(held);
    }

    /** The wire being laid with {@code held} when its end is inside a duct, else null. */
    private static BlockWireEntity heldInDuct(ClientLevel level, ItemStack held) {
        if (!layingWire(level, held))
            return null;
        BlockWireEntity wire = DuctRouting.layingEnd(level, held);
        return wire != null && DuctedWires.ductAt(level, DuctRouting.chainEnd(wire)) != null ? wire : null;
    }

    /**
     * The duct a click with a placeable wire being laid goes to, and the point of it:
     * with the wire's end held in a duct, the crosshair counts as on a duct a little
     * past its box, and the point is where the line of sight passes nearest the
     * duct's centre line, so the end follows the crosshair along it.
     */
    private static DuctPick routePick(Minecraft minecraft, ClientLevel level, ItemStack held) {
        boolean holding = heldInDuct(level, held) != null;
        DuctPick pick = pickUncovered(minecraft, holding ? HOLD_MARGIN : 0);
        if (pick == null || aimedPast(minecraft, pick.duct()))
            return null;
        if (!holding)
            return pick;
        LocalPlayer player = minecraft.player;
        Vec3 at = DuctWires.nearestToSight(pick.duct().segment(), player.getEyePosition(1), player.getViewVector(1));
        return new DuctPick(pick.duct(), at, pick.distance());
    }

    // Clicks

    @SubscribeEvent
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || event.getHand() != InteractionHand.MAIN_HAND)
            return;
        ItemStack held = player.getMainHandItem();

        if (!event.isUseItem())
            return;

        // A duct half laid is cancelled by this click instead (ScrewdriverItem), so it takes nothing off
        if (player.isShiftKeyDown() && ScrewdriverItem.heldBy(player) != null && WireDuctItem.pending(player) == null) {
            DuctPick pick = pickDuct(minecraft, 1);
            if (pick != null)
                send(event, DuctActionPacket.Action.REMOVE, pick.duct(), pick.at());
            return;
        }
        if (player.isShiftKeyDown()) {
            if (WireDuctService.isCutters(held)) {
                WirePick pick = pickDuctedWire(minecraft);
                if (pick != null)
                    send(event, DuctActionPacket.Action.CUT, pick.wire(), pick.at());
            }
            return;
        }
        if (held.getItem() instanceof DyeItem) {
            DuctPick pick = pickUncovered(minecraft);
            if (pick != null)
                send(event, DuctActionPacket.Action.DYE, pick.duct(), pick.at());
            return;
        }
        if (layingWire(level, held)) {
            DuctPick pick = routePick(minecraft, level, held);
            if (pick != null) {
                send(event, DuctActionPacket.Action.ROUTE, pick.duct(), pick.at());
                return;
            }
            // The end is in a duct and a block elsewhere was clicked: out of the duct, then on to it
            BlockWireEntity wire = heldInDuct(level, held);
            if (wire != null && minecraft.hitResult instanceof BlockHitResult block
                    && block.getType() == HitResult.Type.BLOCK) {
                event.setCanceled(true);
                event.setSwingHand(true);
                ModPackets.CHANNEL.sendToServer(new DuctActionPacket(DuctActionPacket.Action.EXIT, wire.getId(),
                        block.getLocation(), block));
            }
        }
    }

    private static void send(InputEvent.InteractionKeyMappingTriggered event, DuctActionPacket.Action action,
            Entity entity, Vec3 at) {
        event.setCanceled(true);
        event.setSwingHand(true);
        ModPackets.CHANNEL.sendToServer(new DuctActionPacket(action, entity.getId(), at));
    }

    /**
     * Power Grid's wire cutters, sneaking, aimed at a placeable wire that runs
     * through ducts: cuts the whole wire. Power Grid asks on both the press and the
     * release of the button, so a second ask straight after is only swallowed.
     * Whether the click was taken.
     */
    public static boolean cutDuctedWire(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !player.isShiftKeyDown()
                || !WireDuctService.isCutters(player.getMainHandItem()))
            return false;
        long now = minecraft.level.getGameTime();
        if (now - lastCut < 5)
            return true;
        WirePick pick = pickDuctedWire(minecraft);
        if (pick == null)
            return false;
        lastCut = now;
        player.swing(InteractionHand.MAIN_HAND);
        ModPackets.CHANNEL.sendToServer(new DuctActionPacket(DuctActionPacket.Action.CUT, pick.wire().getId(),
                pick.at()));
        return true;
    }

    // The preview

    /**
     * Works out this tick's preview of a click on a duct, from Power Grid's preview
     * tick. Whether the crosshair is on a duct with a wire being laid, in which case
     * Power Grid's own preview is hidden.
     */
    public static boolean previewTick() {
        preview = null;
        previewDuct = null;
        previewEntry = null;
        exitFrom = exitTo = null;
        hangingPreview = null;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null)
            return false;
        ItemStack held = player.getMainHandItem();
        if (!layingWire(level, held))
            return false;
        if (HangingDuctRoute.laying(level, player, held)) {
            DuctPick pick = pickUncovered(minecraft);
            if (pick == null || aimedPast(minecraft, pick.duct()))
                return false;
            hangingPreview = HangingDuctRoute.plan(level, held, pick.duct(), pick.at());
            previewDuct = pick.duct();
            previewAt = pick.at();
            return true;
        }
        hint(level, player, held);
        DuctPick pick = routePick(minecraft, level, held);
        if (pick == null)
            return false;
        preview = DuctRouting.plan(level, player, held, pick.duct(), pick.at());
        previewDuct = pick.duct();
        previewAt = pick.at();
        previewEntry = WireRegistry.forItem(level, held.getItem());
        return true;
    }

    /**
     * Whether the crosshair only grazes {@code duct} on its way to the face the duct
     * lies on, landing on that face clear of the duct's side: a look past the duct at
     * the face beyond, which is a click there, not on the duct. A duct is a couple of
     * pixels tall, so a look at the face just beyond it passes through its box.
     */
    private static boolean aimedPast(Minecraft minecraft, WireDuctEntity duct) {
        if (!(minecraft.hitResult instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK)
            return false;
        DuctSegment s = duct.segment();
        Vec3 n = s.normal();
        Vec3 at = block.getLocation();
        // On the face the duct lies on (its back), not on some other block face
        if (Math.abs(at.subtract(s.from()).dot(n) + s.size().depth() / 2) > 1e-3)
            return false;
        double along = s.along(at);
        double reach = s.size().width() / 2 + HOLD_MARGIN;
        return along < -reach || along > s.length() + reach || Math.abs(s.lateralOffset(at)) > reach;
    }

    /** While the end of the wire being laid is in a duct, says what the next click does, now and again. */
    private static void hint(ClientLevel level, LocalPlayer player, ItemStack held) {
        if (heldInDuct(level, held) == null) {
            lastHint = Long.MIN_VALUE;
            return;
        }
        long now = level.getGameTime();
        if (lastHint != Long.MIN_VALUE && now - lastHint < HINT_EVERY && now >= lastHint)
            return;
        lastHint = now;
        player.displayClientMessage(Component.translatable("gui.create_flippies.wire_duct.in_duct")
                .withStyle(ChatFormatting.GRAY), true);
    }

    /**
     * With the wire's end in a duct, Power Grid's traced preview (from
     * {@code from}, along {@code trace}) redone from where the wire will leave the
     * duct; null to keep Power Grid's.
     */
    public static Pair<BlockTrace.TraceState, BlockTrace.TraceResult> traceOutOfDuct(Vec3 from,
            BlockTrace.TraceResult trace) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null)
            return null;
        CompoundTag connection = minecraft.player.getMainHandItem().getTagElement(DuctRouting.CONNECTION_KEY);
        IWireEndpoint endpoint = connection == null ? null : WireEndpointType.deserialize(connection);
        if (!(endpoint instanceof BlockWireEntityEndpoint end) || !end.getEnd())
            return null;
        BlockWireEntity wire = end.getEntity(level);
        if (wire == null || wire.segments.isEmpty())
            return null;
        Vec3 at = DuctRouting.chainEnd(wire);
        WireDuctEntity in = DuctedWires.ductAt(level, at);
        if (in == null)
            return null;
        Vec3 target = from;
        for (var point : trace.points())
            target = target.add(point.vector());
        Vec3 out = DuctRouting.exitPoint(level, in.segment(), at, target);
        Vec3 d = out.subtract(at);
        Pair<BlockTrace.TraceState, BlockTrace.TraceResult> redone = BlockTrace.findPathWithState(level, out, target,
                null, Direction.getNearest(d.x, d.y, d.z));
        if (redone == null || redone.getSecond() == null)
            return null;
        exitFrom = at;
        exitTo = out;
        previewEntry = WireRegistry.forItem(level, wire.getItem());
        return redone;
    }

    /** Where Power Grid's redone preview starts. */
    public static Vec3 exitStart() {
        return exitTo;
    }

    /** Where a hanging wire being laid through ducts hangs on from, or null when it has no way through ducts. */
    public static Vec3 hangingFrom() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return null;
        ItemStack held = player.getMainHandItem();
        if (!HangingDuctRoute.laying(minecraft.level, player, held))
            return null;
        List<Vec3> points = HangingDuctRoute.points(held);
        return points.isEmpty() ? null : points.get(points.size() - 1);
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

        drawRoute(ms, buffers, level, camera, held);
        drawHanging(ms, buffers, level, camera, player, held);
        if (preview != null && previewDuct != null && !previewDuct.isRemoved())
            drawPreview(ms, buffers, level, camera, held);
        if (exitFrom != null && exitTo != null && previewEntry != null)
            drawLeg(ms, buffers, camera, exitFrom, exitTo, PREVIEW_OK);

        boolean dye = held.getItem() instanceof DyeItem;
        boolean tool = player.isShiftKeyDown() && ScrewdriverItem.heldBy(player) != null;
        if (dye || tool) {
            DuctPick pick = dye ? pickUncovered(minecraft) : pickDuct(minecraft, event.getPartialTick());
            if (pick != null) {
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                for (WireDuctEntity piece : dye ? WireDuctService.run(level, pick.duct()) : List.of(pick.duct()))
                    LevelRenderer.renderLineBox(ms, lines, piece.ductBox().inflate(0.004).move(camera.reverse()),
                            0.2f, 0.9f, 1f, 0.8f);
                buffers.endBatch(RenderType.lines());
            }
        }
        ItemStack duct = WireDuctItem.placing(player);
        if (duct != null)
            drawPlacement(minecraft, ms, buffers, level, camera, duct, (WireDuctItem) duct.getItem());
    }

    /** The duct under the crosshair, and what clicking it would lay: the wire's way there and its strokes inside. */
    private static void drawPreview(PoseStack ms, MultiBufferSource.BufferSource buffers, ClientLevel level,
            Vec3 camera, ItemStack held) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        boolean ok = preview.ok();
        LevelRenderer.renderLineBox(ms, lines, previewDuct.ductBox().inflate(0.004).move(camera.reverse()),
                ok ? 0.2f : 1f, ok ? 0.9f : 0.3f, ok ? 1f : 0.3f, 0.8f);
        Vec3 at = previewDuct.segment().project(previewAt);
        LevelRenderer.renderLineBox(ms, lines, new AABB(at, at).inflate(0.05).move(camera.reverse()), 1f, 0.85f,
                0.2f, 1f);
        buffers.endBatch(RenderType.lines());
        if (!ok || previewEntry == null || preview.points().isEmpty())
            return;
        List<Vec3> path = preview.path();
        DuctWireRenderer.Drawn drawn = DuctWireRenderer.work(level, path, previewEntry,
                slotsFor(level, preview, DuctedWires.awg(held.getItem())));
        RenderType type = RenderType.entityTranslucent(previewEntry.texture());
        Vec3 origin = path.get(0);
        ms.pushPose();
        ms.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
        if (drawn != null) {
            DuctWireRenderer.draw(ms, buffers.getBuffer(type), level, origin, drawn, PREVIEW_OK,
                    previewEntry.wireThickness(), 0, true);
        } else {
            for (int i = 0; i + 1 < path.size(); i++)
                segment(ms, buffers.getBuffer(type), origin, path.get(i), path.get(i + 1), PREVIEW_OK);
        }
        ms.popPose();
        buffers.endBatch(type);
        if (drawn != null)
            drawDashes(ms, buffers, camera, drawn.strokes(), ROUTE_NEXT);
    }

    /** The slots a preview's strokes take: its wire's own, with the part to be laid counted in. */
    private static DuctWires.Slots slotsFor(ClientLevel level, DuctRouting.Plan plan, int awg) {
        long tie = plan.wire() == null ? 0 : DuctedWires.tie(plan.wire());
        List<DuctWires.Occupant> extra = new java.util.ArrayList<>();
        List<Vec3> way = plan.path();
        for (WireDuctEntity duct : DuctedWires.around(level, way).keySet())
            extra.addAll(DuctWires.occupancy(duct.segment(), way, awg, tie));
        return DuctedWires.slots(level, tie, extra);
    }

    /** The way the wire being laid has taken through ducts, as a bright dashed line over their lids. */
    private static void drawRoute(PoseStack ms, MultiBufferSource.BufferSource buffers, ClientLevel level,
            Vec3 camera, ItemStack held) {
        if (!layingWire(level, held))
            return;
        BlockWireEntity wire = DuctRouting.layingEnd(level, held);
        if (wire == null || wire.isRemoved())
            return;
        DuctWireRenderer.Drawn drawn = DuctWireRenderer.drawn(wire);
        if (drawn != null)
            drawDashes(ms, buffers, camera, drawn.strokes(), ROUTE_LAID);
    }

    /**
     * A hanging wire being laid through ducts: its way so far, and what a click on
     * the duct under the crosshair would add, as lines (the legs in ducts just over
     * their lids), with the duct outlined green or red.
     */
    private static void drawHanging(PoseStack ms, MultiBufferSource.BufferSource buffers, ClientLevel level,
            Vec3 camera, LocalPlayer player, ItemStack held) {
        if (!layingWire(level, held) || !HangingDuctRoute.laying(level, player, held))
            return;
        WireItemEntry entry = WireRegistry.forItem(level, held.getItem());
        BlockWireEndpoint start = HangingDuctRoute.start(held);
        if (entry == null || start == null)
            return;
        List<Vec3> way = new java.util.ArrayList<>();
        way.add(start.getExactPosition(level));
        way.addAll(HangingDuctRoute.points(held));
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        polyline(ms, lines, level, camera, way, entry, ROUTE_LAID);
        HangingDuctRoute.Plan plan = hangingPreview;
        if (plan != null && previewDuct != null && !previewDuct.isRemoved()) {
            boolean ok = plan.ok();
            LevelRenderer.renderLineBox(ms, lines, previewDuct.ductBox().inflate(0.004).move(camera.reverse()),
                    ok ? 0.2f : 1f, ok ? 0.9f : 0.3f, ok ? 1f : 0.3f, 0.8f);
            if (ok) {
                List<Vec3> next = new java.util.ArrayList<>();
                next.add(way.get(way.size() - 1));
                next.addAll(plan.points());
                polyline(ms, lines, level, camera, next, entry, ROUTE_NEXT);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    /** A hanging wire's path as lines: legs in a duct straight and lifted over its lid, the rest hanging. */
    private static void polyline(PoseStack ms, VertexConsumer lines, ClientLevel level, Vec3 camera,
            List<Vec3> path, WireItemEntry entry, int color) {
        long ducted = DuctedWires.ductLegs(level, path);
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 from = path.get(leg), to = path.get(leg + 1);
            List<Vec3> vertices = BendGeometry.isDucted(ducted, leg) ? List.of(overLid(level, from), overLid(level, to))
                    : BendGeometry.legVertices(from, to, entry.horizontalCoefficient(), entry.verticalCoefficient(),
                            entry.wireThickness(), 0.25);
            for (int i = 1; i < vertices.size(); i++)
                line(ms, lines, vertices.get(i - 1).subtract(camera), vertices.get(i).subtract(camera), color);
        }
    }

    /** A point inside a duct moved out to just over its lid. */
    private static Vec3 overLid(ClientLevel level, Vec3 p) {
        WireDuctEntity duct = DuctedWires.ductAt(level, p);
        if (duct == null)
            return p;
        DuctSegment s = duct.segment();
        return p.add(s.normal().scale(Math.max(0, s.size().depth() - DuctWires.WIRE_DEPTH) + 0.01));
    }

    private static void line(PoseStack ms, VertexConsumer lines, Vec3 a, Vec3 b, int color) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-12)
            return;
        d = d.normalize();
        PoseStack.Pose pose = ms.last();
        for (Vec3 p : new Vec3[] {a, b})
            lines.vertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z).color(color)
                    .normal(pose.normal(), (float) d.x, (float) d.y, (float) d.z).endVertex();
    }

    /** Dashes drawn fully lit just above where a wire's strokes lie, plain, in one colour. */
    private static void drawDashes(PoseStack ms, MultiBufferSource.BufferSource buffers, Vec3 camera,
            List<DuctWires.Dash> dashes, int color) {
        if (dashes.isEmpty())
            return;
        RenderType type = RenderType.entityTranslucent(PLAIN);
        VertexConsumer buffer = buffers.getBuffer(type);
        for (DuctWires.Dash dash : dashes) {
            Vec3 lift = dash.normal().scale(0.002);
            DuctWireRenderer.dash(ms, buffer, camera, new DuctWires.Dash(dash.from().add(lift), dash.to().add(lift),
                    dash.normal(), dash.width()), color, LightTexture.FULL_BRIGHT);
        }
        buffers.endBatch(type);
    }

    private static void drawLeg(PoseStack ms, MultiBufferSource.BufferSource buffers, Vec3 camera, Vec3 from,
            Vec3 to, int color) {
        RenderType type = RenderType.entityTranslucent(previewEntry.texture());
        ms.pushPose();
        ms.translate(from.x - camera.x, from.y - camera.y, from.z - camera.z);
        segment(ms, buffers.getBuffer(type), from, from, to, color);
        ms.popPose();
        buffers.endBatch(type);
    }

    private static void segment(PoseStack ms, VertexConsumer buffer, Vec3 origin, Vec3 a, Vec3 b, int color) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-12)
            return;
        BlockWireRenderer.renderSegment(ms, buffer, LightTexture.FULL_BRIGHT, color, a.subtract(origin),
                Direction.getNearest(d.x, d.y, d.z), previewEntry.wireThickness(), (float) d.length(), 0);
    }

    /** With the first point of a duct chosen, the duct that clicking here would lay. */
    private static void drawPlacement(Minecraft minecraft, PoseStack ms, MultiBufferSource.BufferSource buffers,
            ClientLevel level, Vec3 camera, ItemStack held, WireDuctItem item) {
        CompoundTag start = held.getTagElement(WireDuctItem.TAG_START);
        if (start == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() == HitResult.Type.MISS)
            return;
        Vec3 from = new Vec3(start.getDouble("X"), start.getDouble("Y"), start.getDouble("Z"));
        Direction face = hit.getDirection();
        Vec3 to = WireDuctService.snapPoint(level, minecraft.player.getEyePosition(), hit.getLocation(), face,
                item.size());
        int f = start.getInt("Face");
        Direction firstFace = f >= 0 && f < 6 ? Direction.from3DDataValue(f) : face;
        List<DuctSegment> pieces = WireDuctService.plan(level, from, firstFace, to, face, item.size());
        boolean ok = !pieces.isEmpty();
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
