package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.duct.DuctNetwork;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPass;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPlacement;
import com.nectariich.create_flippies.foundation.wire.duct.DuctRoute;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;
import com.nectariich.create_flippies.foundation.wire.duct.PendingRoute;
import com.nectariich.create_flippies.network.DuctActionPacket;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.equipment.wrench.WrenchItem;

import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWire;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The server's side of wire ducts: laying them, breaking them, and routing a wire
 * through them while it is being placed. Nothing a client says is taken on trust;
 * every action is checked against the world, the player's reach and the item in
 * their hand.
 *
 * Routing a wire. With a Power Grid wire in hand and its first terminal chosen,
 * clicking a duct takes the wire into it there; clicking the same duct, or one
 * joined to it, takes it out again. The passes made so far live on the wire stack
 * ({@link PendingRoute}). Clicking the second terminal then makes the one hanging
 * wire, its path running through every pass ({@link #finish}).
 */
public final class WireDuctService {

    /** How far past a duct the end of a search for ducts near a point reaches; every stretch is at most 16 long. */
    private static final double DUCT_SEARCH = 9;

    /** How far around a duct to look for wires routed through it: a routed wire is at most this far from its middle. */
    private static final double WIRE_SEARCH = DuctRoute.MAX_LENGTH / 2 + 8;

    private WireDuctService() {}

    // Finding ducts

    /** Every live duct whose stretch could reach into {@code box}. */
    public static List<WireDuctEntity> near(Level level, AABB box) {
        return level.getEntitiesOfClass(WireDuctEntity.class, box.inflate(DUCT_SEARCH),
                e -> e.isAlive() && e.segment() != null);
    }

    /** A clicked point on a face as a duct end: snapped to the grid, or onto an end of a duct of that size nearby. */
    public static Vec3 snapPoint(Level level, Vec3 hit, Direction face, DuctSize size) {
        Vec3 point = DuctPlacement.snap(hit, face, size);
        List<Vec3> ends = new ArrayList<>();
        for (WireDuctEntity duct : near(level, new AABB(point, point).inflate(1))) {
            if (duct.size() != size)
                continue;
            ends.add(duct.segment().from());
            ends.add(duct.segment().to());
        }
        return DuctPlacement.snapToEnds(point, ends);
    }

    static double distanceToBox(AABB box, Vec3 p) {
        double dx = Math.max(0, Math.max(box.minX - p.x, p.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - p.y, p.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - p.z, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void refuse(Player player, String key) {
        WireDuctItem.message(player, Component.translatable("gui.create_flippies.wire_duct." + key), true);
    }

    private static void tell(Player player, String key) {
        WireDuctItem.message(player, Component.translatable("gui.create_flippies.wire_duct." + key), false);
    }

    // Laying a duct

    /** The second click of a placement: lays the duct from {@code from} to {@code to}, or says why not. */
    static void place(Player player, ItemStack stack, DuctSize size, Vec3 from, Direction firstFace, Vec3 to,
            Direction face) {
        Level level = player.level();
        List<DuctSegment> pieces = DuctPlacement.plan(from, to, face, size);
        if (pieces.isEmpty() && firstFace != face)
            pieces = DuctPlacement.plan(from, to, firstFace, size);
        DuctPlacement.Verdict verdict = DuctPlacement.check(pieces, player.getEyePosition(),
                PlayerUtilities.getReachDistance(player));
        int[] costs = new int[pieces.size()];
        int cost = 0;
        for (int i = 0; i < pieces.size(); i++) {
            costs[i] = DuctPlacement.itemCost(pieces.get(i).length());
            cost += costs[i];
        }
        if (verdict == DuctPlacement.Verdict.OK && !player.isCreative()
                && !PlayerUtilities.hasEnoughItems(player, stack.getItem(), cost))
            verdict = DuctPlacement.Verdict.MISSING_ITEMS;
        if (verdict != DuctPlacement.Verdict.OK) {
            WireDuctItem.message(player, Component.translatable(verdict.langKey()), true);
            return;
        }

        AABB around = new AABB(from, to).inflate(1);
        List<WireDuctEntity> existing = near(level, around);
        List<WireDuctEntity> added = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            DuctSegment piece = pieces.get(i);
            WireDuctEntity duct = WireDuctEntity.create(level, piece, costs[i]);
            for (WireDuctEntity other : existing)
                join(other, duct);
            if (!added.isEmpty())
                join(added.get(added.size() - 1), duct);
            added.add(duct);
        }
        for (WireDuctEntity duct : added)
            level.addFreshEntity(duct);
        stack.removeTagKey(WireDuctItem.TAG_START);
        if (!player.isCreative())
            PlayerUtilities.removeItems(player, stack.getItem(), cost);
        level.playSound(null, BlockPos.containing(to), SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 1, 1.2f);
    }

    private static void join(WireDuctEntity existing, WireDuctEntity added) {
        Vec3 joint = existing.segment().jointWith(added.segment());
        if (joint == null)
            return;
        double[] ext = DuctNetwork.jointExtensions(existing.segment(), added.segment());
        existing.setExtension(joint, ext[0]);
        added.setExtension(joint, ext[1]);
    }

    // Actions picked on the client

    public static void act(ServerPlayer player, DuctActionPacket.Action action, int entityId, Vec3 hit) {
        Level level = player.level();
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.isAlive() || !Double.isFinite(hit.x) || !Double.isFinite(hit.y)
                || !Double.isFinite(hit.z))
            return;
        double reach = PlayerUtilities.getReachDistance(player) + 1.5;
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(hit) > reach || !level.mayInteract(player, BlockPos.containing(hit)))
            return;
        ItemStack held = player.getMainHandItem();
        if (action == DuctActionPacket.Action.CUT) {
            if (!(entity instanceof BendableWire wire) || !wire.bendDucted() || !isCutters(held)
                    || distanceToBox(entity.getBoundingBox(), hit) > 0.5)
                return;
            // Power Grid's own cut: the sound, the shock of a live wire, the items dropped
            entity.interact(player, InteractionHand.MAIN_HAND);
            return;
        }
        if (!(entity instanceof WireDuctEntity duct) || duct.segment() == null
                || distanceToBox(duct.ductBox(), hit) > 0.5)
            return;
        switch (action) {
            case BREAK -> breakDuct(player, duct, false);
            case WRENCH -> {
                if (held.getItem() instanceof WrenchItem && player.isShiftKeyDown())
                    breakDuct(player, duct, true);
            }
            case ROUTE -> route(player, duct, hit);
            default -> {}
        }
    }

    public static boolean isCutters(ItemStack stack) {
        return stack.is(ModdedTags.Item.WIRE_CUTTERS.tag) || stack.is(ModdedTags.Item.BAD_WIRE_CUTTERS.tag);
    }

    // Breaking a duct

    /**
     * Removes a duct and gives its items back: dropped where it was, or into the
     * inventory when taken with a wrench. Every wire running through it is cut,
     * dropping its own items, since a wire cannot hang where its duct was.
     */
    public static void breakDuct(Player player, WireDuctEntity duct, boolean toInventory) {
        Level level = duct.level();
        DuctSegment segment = duct.segment();
        cutWiresThrough(level, segment);
        for (WireDuctEntity other : near(level, segment.box())) {
            if (other == duct)
                continue;
            Vec3 joint = other.segment().jointWith(segment);
            if (joint != null)
                other.setExtension(joint, 0);
        }
        if (player == null || !player.isCreative()) {
            ItemStack items = new ItemStack(ModItems.wireDuct(duct.size()), duct.items());
            if (toInventory && player != null)
                player.getInventory().placeItemBackInInventory(items);
            else
                duct.spawnAtLocation(items);
        }
        level.playSound(null, duct.blockPosition(), SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 1, 1.2f);
        duct.discard();
    }

    /** Cuts every wire with a leg in this stretch of duct. */
    public static int cutWiresThrough(Level level, DuctSegment segment) {
        int cut = 0;
        for (Entity entity : level.getEntities((Entity) null, segment.box().inflate(WIRE_SEARCH),
                e -> e.isAlive() && e instanceof BendableWire w && w.bendDucted())) {
            BendableWire wire = (BendableWire) entity;
            List<Vec3> path = wire.bendPath();
            if (path == null)
                continue;
            List<Vec3> legs = DuctRoute.ductedLegs(path, wire.bendDuctLegs());
            for (int i = 0; i + 1 < legs.size(); i += 2) {
                if (segment.containsLid(legs.get(i).add(legs.get(i + 1)).scale(0.5))) {
                    entity.kill();
                    cut++;
                    break;
                }
            }
        }
        return cut;
    }

    // Routing a wire

    public static boolean isRoutableWire(Level level, ItemStack stack) {
        return !stack.isEmpty() && IWire.isWire(level, stack.getItem()) && !IWire.isCord(level, stack.getItem());
    }

    /** The first terminal of the wire being placed with {@code stack}, if it is one a hanging wire can start from. */
    private static BlockWireEndpoint firstTerminal(ItemStack stack) {
        var tag = stack.getTagElement(PendingRoute.CONNECTION_KEY);
        if (tag == null)
            return null;
        IWireEndpoint endpoint = WireEndpointType.deserialize(tag);
        return endpoint instanceof BlockWireEndpoint block ? block : null;
    }

    /** Where the wire last comes out of a duct, or its first terminal: where its next hanging leg starts. */
    private static Vec3 lastAnchor(Level level, PendingRoute route, BlockWireEndpoint first) {
        if (!route.passes().isEmpty()) {
            List<Vec3> c = route.passes().get(route.passes().size() - 1).centers();
            return c.get(c.size() - 1);
        }
        return first.getExactPosition(level);
    }

    static void route(ServerPlayer player, WireDuctEntity duct, Vec3 hit) {
        Level level = player.level();
        ItemStack stack = player.getMainHandItem();
        if (!isRoutableWire(level, stack))
            return;
        BlockWireEndpoint first = firstTerminal(stack);
        if (first == null) {
            refuse(player, "start_on_terminal");
            return;
        }
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (entry == null)
            return;
        PendingRoute route = PendingRoute.of(stack);
        if (route == null)
            route = PendingRoute.start(stack);
        Vec3 at = duct.segment().project(hit);

        if (route.open() == null) {
            if (route.passes().size() >= DuctRoute.MAX_PASSES) {
                refuse(player, "too_many_passes");
                return;
            }
            Vec3 anchor = lastAnchor(level, route, first);
            if (anchor != null && anchor.distanceTo(at) > entry.maximumLength() + 0.5) {
                refuse(player, "leg_too_long");
                return;
            }
            route.withOpen(new PendingRoute.Open(duct.getUUID(), at)).save(stack);
            tell(player, "entered");
            return;
        }

        PendingRoute.Open open = route.open();
        Entity entered = ((ServerLevel) level).getEntity(open.duct());
        if (!(entered instanceof WireDuctEntity entryDuct) || !entered.isAlive() || entryDuct.segment() == null) {
            route.withOpen(null).save(stack);
            refuse(player, "entry_gone");
            return;
        }
        List<WireDuctEntity> ducts = near(level, new AABB(open.at(), at).inflate(DuctNetwork.MAX_SEGMENTS));
        List<DuctSegment> segments = new ArrayList<>(ducts.size());
        for (WireDuctEntity d : ducts)
            segments.add(d.segment());
        DuctPass pass = DuctNetwork.route(segments, ducts.indexOf(entryDuct), open.at(), ducts.indexOf(duct), at, 0);
        if (pass == null) {
            refuse(player, "not_joined");
            return;
        }
        if (pass.length() < DuctPlacement.GRID) {
            refuse(player, "too_short");
            return;
        }
        int lane = DuctNetwork.freeLane(pass.size(), DuctNetwork.segmentsOf(pass, segments),
                occupiedLegs(level, new AABB(open.at(), at)));
        if (lane < 0) {
            refuse(player, "full");
            return;
        }
        route.withPass(pass.withLane(lane)).save(stack);
        tell(player, "left");
    }

    /** The ducted legs of every routed wire that could reach into {@code box}, as pairs. */
    public static List<Vec3> occupiedLegs(Level level, AABB box) {
        List<Vec3> legs = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, box.inflate(WIRE_SEARCH),
                e -> e.isAlive() && e instanceof BendableWire w && w.bendDucted())) {
            BendableWire wire = (BendableWire) entity;
            List<Vec3> path = wire.bendPath();
            if (path != null)
                legs.addAll(DuctRoute.ductedLegs(path, wire.bendDuctLegs()));
        }
        return legs;
    }

    /**
     * The click on the second terminal of a wire routed through ducts: makes the
     * one hanging wire, with Power Grid's checks, priced by its whole path.
     */
    static void finish(ServerPlayer player, ItemStack stack, PendingRoute route, BlockPos pos, BlockHitResult hit) {
        Level level = player.level();
        IElectric electric = IElectric.getAt(level, pos);
        BlockState state = level.getBlockState(pos);
        int index = electric == null || hit == null ? -1
                : electric.terminalIndexAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        if (index < 0) {
            refuse(player, "finish_on_terminal");
            return;
        }
        if (route.open() != null) {
            refuse(player, "leave_duct_first");
            return;
        }
        if (!electric.accepts(stack)) {
            refuse(player, "wrong_wire");
            return;
        }
        IWireEndpoint read = WireEndpointType.deserialize(route.connection());
        if (!(read instanceof BlockWireEndpoint first)) {
            PendingRoute.clear(stack);
            refuse(player, "start_on_terminal");
            return;
        }
        BlockWireEndpoint second = new BlockWireEndpoint(pos, index);
        ElectricBehaviour b1 = first.getElectricBehaviour(level), b2 = second.getElectricBehaviour(level);
        var n1 = first.getNode(level);
        var n2 = second.getNode(level);
        if (first.equals(second) || b1 == null || b2 == null || n1 == null || n2 == null || n1 == n2) {
            refuse(player, "failed");
            return;
        }
        if (b1.hasConnection(first, second) || b2.hasConnection(second, first)) {
            refuse(player, "exists");
            return;
        }
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        Vec3 a = first.getExactPosition(level), b = second.getExactPosition(level);
        if (entry == null || a == null || b == null) {
            refuse(player, "failed");
            return;
        }
        DuctRoute.Built built = DuctRoute.build(a, route.passes(), b, entry.wireThickness());
        DuctRoute.Verdict verdict = DuctRoute.check(built, a, b, route.passes().size(), entry.maximumLength());
        if (verdict != DuctRoute.Verdict.OK) {
            WireDuctItem.message(player, Component.translatable(verdict.langKey()), true);
            return;
        }
        int count = DuctRoute.itemCount(WireBends.length(built.path(a, b)), entry.itemsPerMeter());
        if (!player.isCreative() && !PlayerUtilities.hasEnoughItems(player, stack.getItem(), count)) {
            refuse(player, "missing_wire");
            return;
        }
        HangingWireEntity wire = HangingWireEntity.create(level, first, second, new ItemStack(stack.getItem(), count),
                null);
        ItemStack offhand = player.getOffhandItem();
        if (entry.colorable() && offhand.getItem() instanceof DyeItem dye)
            wire.setColor(dye.getDyeColor());
        ((BendableWire) (Object) wire).setBendRoute(built.points(), built.ductLegs());
        if (!((ServerLevel) level).tryAddFreshEntityWithPassengers(wire)) {
            refuse(player, "failed");
            return;
        }
        stack.removeTagKey(PendingRoute.CONNECTION_KEY);
        PendingRoute.clear(stack);
        if (!player.isCreative())
            PlayerUtilities.removeItems(player, stack.getItem(), count);
    }
}
