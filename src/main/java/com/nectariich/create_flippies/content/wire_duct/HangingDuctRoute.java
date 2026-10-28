package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.duct.DuctNetwork;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPass;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.event.tick.PlayerTickEvent;
*///?}
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * Running a Power Grid hanging wire through wire ducts while it is laid.
 *
 * Power Grid lays a hanging wire in its alternate placement mode, terminal to
 * terminal: the first click is kept on the wire in hand (its {@code Connection}),
 * the second makes the wire. Ducts add clicks in between:
 * <ul>
 * <li>a click on a duct takes the wire into it there, just outside the wires
 * already in it on the side it comes from; it hangs there from wherever it was;</li>
 * <li>with the wire in a duct, a click on that duct or one joined to it runs it
 * along them to there, keeping its place across the duct;</li>
 * <li>a click on another duct leaves the one it is in and hangs over to that one;</li>
 * <li>a click on a terminal finishes it ({@link #finish}); anywhere else is refused
 * while a way through ducts is pending.</li>
 * </ul>
 * The way so far is kept on the wire in hand beside the connection it belongs to
 * ({@link #points}). The finished wire is one hanging wire between the two
 * terminals whose bend points are that way: the legs inside ducts run straight and
 * hidden, the rest hang, each no longer than the wire type spans, and it costs and
 * resists by the length of the whole path ({@link WireBends}).
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class HangingDuctRoute {

    /** Where the way through ducts so far is kept on the wire in hand. */
    public static final String KEY = "create_flippies:HangingRoute";

    private static final String CONNECTION = "Connection";

    /** How often stale routes are cleared off a player's wire stacks, ticks. */
    private static final int CLEAN_EVERY = 20;

    /** What a click on a duct would add to the way: {@code points}, or {@code refusal} naming why nothing. */
    public record Plan(List<Vec3> points, boolean slide, String refusal) {

        static Plan refused(String why) {
            return new Plan(List.of(), false, why);
        }

        public boolean ok() {
            return refusal == null;
        }
    }

    private HangingDuctRoute() {}

    /** Whether {@code stack} is a wire being laid as a hanging wire from a terminal. */
    public static boolean laying(Level level, Player player, ItemStack stack) {
        return DuctRouting.isPlaceableWire(level, stack) && WireItem.alternateWirePlacement(player)
                && start(stack) != null;
    }

    /** The terminal the wire in {@code stack} hangs from, or null. */
    public static BlockWireEndpoint start(ItemStack stack) {
        CompoundTag connection = DuctRouting.connection(stack);
        IWireEndpoint endpoint = connection == null ? null : WireEndpointType.deserialize(connection);
        return endpoint instanceof BlockWireEndpoint block ? block : null;
    }

    /** The way through ducts chosen so far for the wire in {@code stack}: empty unless it belongs to its connection. */
    public static List<Vec3> points(ItemStack stack) {
        CompoundTag route = StackData.sub(stack, KEY);
        CompoundTag connection = DuctRouting.connection(stack);
        if (route == null || connection == null || !connection.equals(route.getCompound(CONNECTION)))
            return List.of();
        return WireBends.read(route);
    }

    private static void write(ItemStack stack, List<Vec3> points) {
        CompoundTag route = new CompoundTag();
        route.put(CONNECTION, DuctRouting.connection(stack).copy());
        WireBends.write(route, points);
        StackData.put(stack, KEY, route);
    }

    /** Where the wire being laid has got to: the last point of its way, or its terminal. */
    public static Vec3 last(Level level, ItemStack stack) {
        List<Vec3> points = points(stack);
        if (!points.isEmpty())
            return points.get(points.size() - 1);
        BlockWireEndpoint start = start(stack);
        return start == null ? null : start.getExactPosition(level);
    }

    /** What clicking {@code duct} at {@code hit} with the hanging wire in {@code stack} would add. Changes nothing. */
    public static Plan plan(Level level, ItemStack stack, WireDuctEntity duct, Vec3 hit) {
        BlockWireEndpoint start = start(stack);
        DuctSegment target = duct.segment();
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (start == null)
            return Plan.refused("start_first");
        if (target == null || entry == null)
            return Plan.refused("failed");
        List<Vec3> so = points(stack);
        Vec3 last = so.isEmpty() ? start.getExactPosition(level) : so.get(so.size() - 1);
        Vec3 center = DuctWires.snapOnCentre(target, hit);
        int awg = DuctedWires.awg(stack.getItem());

        WireDuctEntity in = so.isEmpty() ? null : DuctedWires.ductAt(level, last);
        if (in != null) {
            List<WireDuctEntity> chain = DuctNetwork.chain(in, duct, WireDuctEntity::segment,
                    d -> DuctedWires.neighbours(level, d));
            if (chain != null)
                return fits(so, slide(level, last, chain, center, awg));
        }

        // In from wherever it hangs from, on the side facing there
        int side = DuctWires.sideToward(target, center, last);
        List<DuctWires.Occupant> present = DuctedWires.occupants(level, List.of(duct), null);
        double along = target.along(center);
        DuctWires.Fit fit = DuctWires.fit(target.size(), awg,
                List.of(new DuctWires.Occupant(target, along, along, 0, 1, awg, 0)), present);
        if (fit != DuctWires.Fit.OK)
            return Plan.refused(DuctRouting.refusal(fit));
        Vec3 point = DuctWires.wirePoint(target, center, DuctWires.entryOffset(target, along, side, present));
        if (last.distanceTo(point) > entry.maximumLength())
            return Plan.refused("hang_too_long");
        return fits(so, new Plan(List.of(point), false, null));
    }

    private static Plan fits(List<Vec3> so, Plan plan) {
        if (plan.ok() && so.size() + plan.points().size() > WireBends.MAX_STORED)
            return Plan.refused("too_many_turns");
        return plan;
    }

    /** Along joined ducts from {@code from} to the centre-line point {@code center} of the last of {@code chain}. */
    private static Plan slide(Level level, Vec3 from, List<WireDuctEntity> chain, Vec3 center, int awg) {
        List<DuctSegment> segments = new ArrayList<>(chain.size());
        for (WireDuctEntity d : chain)
            segments.add(d.segment());
        DuctPass pass = DuctNetwork.route(segments, from, center, 0);
        if (pass == null || pass.length() < 1 / 32.0)
            return Plan.refused("too_short");
        List<Vec3> way = new ArrayList<>();
        way.add(from);
        for (Vec3 p : pass.withOffset(DuctWires.slideOffset(pass, from)).wirePoints())
            if (p.distanceToSqr(way.get(way.size() - 1)) > 1e-10)
                way.add(p);
        if (way.size() < 2)
            return Plan.refused("too_short");
        List<DuctWires.Occupant> adding = new ArrayList<>();
        for (DuctSegment s : segments)
            adding.addAll(DuctWires.occupancy(s, way, awg, 0));
        DuctWires.Fit fit = DuctWires.fit(pass.size(), awg, adding, DuctedWires.occupants(level, chain, null));
        if (fit != DuctWires.Fit.OK)
            return Plan.refused(DuctRouting.refusal(fit));
        return new Plan(way.subList(1, way.size()), true, null);
    }

    // The server's side

    /** A click on a duct with a hanging wire being laid: adds what {@link #plan} worked out to the way. */
    static void route(ServerPlayer player, WireDuctEntity duct, Vec3 hit) {
        ItemStack stack = player.getMainHandItem();
        Plan plan = plan(player.level(), stack, duct, hit);
        if (!plan.ok()) {
            WireDuctService.refuse(player, plan.refusal());
            return;
        }
        List<Vec3> points = new ArrayList<>(points(stack));
        points.addAll(plan.points());
        write(stack, points);
        WireDuctItem.message(player, Component.translatable("gui.create_flippies.wire_duct."
                + (plan.slide() ? "slid" : "hanging_entered")), false);
    }

    /**
     * Takes every click on a block while a hanging wire with a way through ducts is
     * being laid: on a terminal it makes the wire, anywhere else it is refused. Power
     * Grid would otherwise make a straight wire from the first terminal and forget
     * the way. Both sides cancel, so the client neither predicts Power Grid's result
     * nor swings twice; the click still reaches the server.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;
        Player player = event.getEntity();
        Level level = player.level();
        ItemStack stack = player.getMainHandItem();
        if (!laying(level, player, stack) || points(stack).isEmpty())
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer server)
            finish(server, stack, event.getHitVec());
    }

    /** Makes the hanging wire from its first terminal through the way chosen to the terminal clicked. */
    static void finish(ServerPlayer player, ItemStack stack, BlockHitResult hit) {
        Level level = player.level();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        IElectric electric = IElectric.getAt(level, pos);
        int terminal = electric == null ? -1
                : electric.terminalIndexAt(state, hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
        if (terminal < 0) {
            WireDuctService.refuse(player, "finish_on_terminal");
            return;
        }
        if (!electric.accepts(stack)) {
            WireDuctService.refuse(player, "wrong_wire");
            return;
        }
        BlockWireEndpoint start = start(stack);
        BlockWireEndpoint end = new BlockWireEndpoint(pos, terminal);
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (start == null || entry == null || start.equals(end)) {
            WireDuctService.refuse(player, "failed");
            return;
        }
        ElectricBehaviour first = start.getElectricBehaviour(level), second = end.getElectricBehaviour(level);
        if (first == null || second == null || start.getNode(level) == null || end.getNode(level) == null
                || start.getNode(level) == end.getNode(level)) {
            WireDuctService.refuse(player, "failed");
            return;
        }
        if (first.hasConnection(start, end) || second.hasConnection(end, start)) {
            WireDuctService.refuse(player, "already_connected");
            return;
        }

        List<Vec3> points = points(stack);
        List<Vec3> path = WireBends.path(start.getExactPosition(level), points, end.getExactPosition(level));
        long ducted = DuctedWires.ductLegs(level, path);
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            if (!BendGeometry.isDucted(ducted, leg) && path.get(leg).distanceTo(path.get(leg + 1))
                    > entry.maximumLength()) {
                WireDuctService.refuse(player, "hang_too_long");
                return;
            }
        }
        int items = Math.max(1, Math.round((float) WireBends.length(path) * entry.itemsPerMeter()));
        if (!PlayerUtilities.hasEnoughItems(player, stack, items)) {
            WireDuctService.refuse(player, "missing_wire");
            return;
        }

        HangingWireEntity wire = HangingWireEntity.create(level, start, end, new ItemStack(stack.getItemHolder(),
                items), null);
        ((BendableWire) wire).setBendPoints(points);
        if (entry.colorable() && player.getOffhandItem().getItem() instanceof DyeItem dye)
            wire.setColor(dye.getDyeColor());
        if (!((ServerLevel) level).tryAddFreshEntityWithPassengers(wire)) {
            WireDuctService.refuse(player, "failed");
            return;
        }
        WireBendService.sync(wire);
        PlayerUtilities.removeItems(player, stack, items);
        StackData.remove(stack, KEY);
        DuctRouting.clearConnection(stack);
    }

    /** A way left on a stack after its connection was cleared or replaced would only stop the stack stacking. */
    @SubscribeEvent
    //? if forge {
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide
                || event.player.tickCount % CLEAN_EVERY != 0)
            return;
        Inventory inventory = event.player.getInventory();
    //?} else {
    /*public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide || event.getEntity().tickCount % CLEAN_EVERY != 0)
            return;
        Inventory inventory = event.getEntity().getInventory();
    *///?}
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (StackData.sub(stack, KEY) != null && points(stack).isEmpty())
                StackData.remove(stack, KEY);
        }
    }
}
