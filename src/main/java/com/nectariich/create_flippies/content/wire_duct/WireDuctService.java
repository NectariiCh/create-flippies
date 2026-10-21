package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.wire.duct.DuctColor;
import com.nectariich.create_flippies.foundation.wire.duct.DuctNetwork;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPlacement;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;
import com.nectariich.create_flippies.network.DuctActionPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.equipment.wrench.WrenchItem;

import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The server's side of wire ducts: laying them, dyeing them, breaking them, and
 * the actions on them a client picked ({@link DuctActionPacket}). Nothing a client
 * says is taken on trust; every action is checked against the world, the player's
 * reach and the item in their hand. Running a wire through ducts is
 * {@link DuctRouting}'s.
 *
 * Ducts are found through {@link DuctIndex}, by where they are, never by searching
 * a radius: a duct of any length joins, dyes and breaks the same way.
 */
public final class WireDuctService {

    private WireDuctService() {}

    // Finding ducts

    /** A clicked point on a face as a duct end: snapped to the grid, or onto an end of a duct of that size nearby. */
    public static Vec3 snapPoint(Level level, Vec3 hit, Direction face, DuctSize size) {
        Vec3 point = DuctPlacement.snap(hit, face, size);
        List<Vec3> ends = new ArrayList<>();
        for (WireDuctEntity duct : DuctIndex.ducts(level, new AABB(point, point).inflate(DuctPlacement.END_SNAP))) {
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

    static void refuse(Player player, String key) {
        WireDuctItem.message(player, Component.translatable("gui.create_flippies.wire_duct." + key), true);
    }

    // Laying a duct

    /**
     * The second click of a placement: lays the duct from {@code from} to {@code to},
     * in the colour of the items it is laid from, or says why not. Any length will
     * do, as long as every chunk it crosses is loaded.
     */
    static void place(Player player, ItemStack stack, DuctSize size, Vec3 from, Direction firstFace, Vec3 to,
            Direction face) {
        Level level = player.level();
        List<DuctSegment> pieces = DuctPlacement.plan(from, to, face, size);
        if (pieces.isEmpty() && firstFace != face)
            pieces = DuctPlacement.plan(from, to, firstFace, size);
        DuctPlacement.Verdict verdict = DuctPlacement.check(pieces, player.getEyePosition(),
                PlayerUtilities.getReachDistance(player));
        if (verdict == DuctPlacement.Verdict.OK) {
            for (DuctSegment piece : pieces) {
                if (!level.hasChunkAt(BlockPos.containing(piece.from().add(piece.to()).scale(0.5)))) {
                    verdict = DuctPlacement.Verdict.NOT_LOADED;
                    break;
                }
            }
        }
        int[] costs = new int[pieces.size()];
        int cost = 0;
        for (int i = 0; i < pieces.size(); i++) {
            costs[i] = DuctPlacement.itemCost(pieces.get(i).length());
            cost += costs[i];
        }
        int color = WireDuctItem.color(stack);
        if (verdict == DuctPlacement.Verdict.OK && !player.isCreative() && count(player, stack, color) < cost)
            verdict = DuctPlacement.Verdict.MISSING_ITEMS;
        if (verdict != DuctPlacement.Verdict.OK) {
            WireDuctItem.message(player, Component.translatable(verdict.langKey()), true);
            return;
        }

        UUID run = UUID.randomUUID();
        List<WireDuctEntity> added = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            DuctSegment piece = pieces.get(i);
            WireDuctEntity duct = WireDuctEntity.create(level, piece, costs[i], run, color);
            for (Vec3 end : new Vec3[] {piece.from(), piece.to()})
                for (WireDuctEntity other : DuctIndex.ductsAt(level, end))
                    join(other, duct);
            if (!added.isEmpty())
                join(added.get(added.size() - 1), duct);
            added.add(duct);
        }
        for (WireDuctEntity duct : added)
            level.addFreshEntity(duct);
        stack.removeTagKey(WireDuctItem.TAG_START);
        if (!player.isCreative())
            remove(player, stack, color, cost);
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

    private static boolean matches(ItemStack candidate, ItemStack like, int color) {
        return candidate.is(like.getItem()) && WireDuctItem.color(candidate) == color;
    }

    /** How many duct items of this kind and colour the player has. */
    private static int count(Player player, ItemStack like, int color) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (matches(s, like, color))
                total += s.getCount();
        }
        return total;
    }

    /** Takes duct items of this kind and colour, the one in hand last. */
    private static void remove(Player player, ItemStack like, int color, int count) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && count > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (s == like || !matches(s, like, color))
                continue;
            int take = Math.min(count, s.getCount());
            s.shrink(take);
            count -= take;
        }
        if (count > 0)
            like.shrink(Math.min(count, like.getCount()));
    }

    // Actions picked on the client

    public static void act(ServerPlayer player, DuctActionPacket.Action action, int entityId, Vec3 hit,
            BlockHitResult block) {
        Level level = player.level();
        if (action == DuctActionPacket.Action.EXIT) {
            // The hit is a block's: the game's own reach and permission checks for a click on one
            if (block == null || player.getEyePosition().distanceTo(block.getLocation())
                    > PlayerUtilities.getReachDistance(player) + 1.5
                    || !level.mayInteract(player, block.getBlockPos()) || !level.isLoaded(block.getBlockPos()))
                return;
            DuctRouting.exit(player, block);
            return;
        }
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
            if (!(entity instanceof BlockWireEntity wire) || !isCutters(held) || !player.isShiftKeyDown()
                    || distanceToBox(entity.getBoundingBox(), hit) > 0.5 || !DuctedWires.isDucted(level, wire))
                return;
            // Power Grid's own cut of the whole wire: the sound, the shock of a live wire, the items dropped
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
            case ROUTE -> DuctRouting.route(player, duct, hit);
            case DYE -> dye(player, duct, held);
            default -> {}
        }
    }

    public static boolean isCutters(ItemStack stack) {
        return stack.is(ModdedTags.Item.WIRE_CUTTERS.tag) || stack.is(ModdedTags.Item.BAD_WIRE_CUTTERS.tag);
    }

    // Dyeing a duct

    /**
     * Recolours the run the duct belongs to: every stretch laid together with it by
     * one placement (one straight run or one L), found along the joints. One dye
     * does the whole run.
     */
    static void dye(Player player, WireDuctEntity duct, ItemStack held) {
        if (!(held.getItem() instanceof DyeItem dye))
            return;
        int color = DuctColor.of(dye.getDyeColor());
        List<WireDuctEntity> run = run(duct.level(), duct);
        boolean changed = false;
        for (WireDuctEntity piece : run) {
            if (piece.color() != color) {
                piece.setColor(color);
                changed = true;
            }
        }
        if (!changed)
            return;
        if (!player.isCreative())
            held.shrink(1);
        duct.level().playSound(null, duct.blockPosition(), SoundEvents.DYE_USE, SoundSource.PLAYERS, 1, 1);
    }

    /** The stretches laid together with {@code duct}, it included. */
    public static List<WireDuctEntity> run(Level level, WireDuctEntity duct) {
        List<WireDuctEntity> run = new ArrayList<>();
        Set<WireDuctEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<WireDuctEntity> queue = new ArrayDeque<>();
        queue.add(duct);
        seen.add(duct);
        while (!queue.isEmpty() && run.size() < DuctNetwork.MAX_VISITED) {
            WireDuctEntity at = queue.poll();
            run.add(at);
            for (WireDuctEntity next : DuctedWires.neighbours(level, at))
                if (next.run().equals(duct.run()) && seen.add(next))
                    queue.add(next);
        }
        return run;
    }

    // Breaking a duct

    /**
     * Removes a duct and gives its items back, in its colour: dropped where it was,
     * or into the inventory when taken with a wrench. Every wire running in it is
     * cut whole, dropping its own items, since it cannot lie where its duct was.
     */
    public static void breakDuct(Player player, WireDuctEntity duct, boolean toInventory) {
        Level level = duct.level();
        DuctSegment segment = duct.segment();
        for (BlockWireEntity wire : DuctedWires.wiresIn(level, duct))
            wire.kill();
        for (WireDuctEntity other : DuctedWires.neighbours(level, duct)) {
            Vec3 joint = other.segment().jointWith(segment);
            if (joint != null)
                other.setExtension(joint, 0);
        }
        if (player == null || !player.isCreative()) {
            ItemStack items = duct.asItems(duct.items());
            if (toInventory && player != null)
                player.getInventory().placeItemBackInInventory(items);
            else
                duct.spawnAtLocation(items);
        }
        level.playSound(null, duct.blockPosition(), SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 1, 1.2f);
        duct.discard();
    }
}
