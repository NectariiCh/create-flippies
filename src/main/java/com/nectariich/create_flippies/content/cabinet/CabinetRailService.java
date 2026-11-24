package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Putting a cabinet's DIN rails up and taking them down, server side.
 *
 * A rail is two things at once and both have to change together: an entry in the
 * cabinet's rail list ({@link ElectricalCabinetEntity#rails}), which is its geometry
 * and what is drawn, and a {@link RailSpan} in the host block of every cell it runs
 * through ({@link CabinetRailHostBlockEntity}), which is what gear clips to. The
 * cells are worked out from the cabinet ({@link CabinetRailFrame}) whenever a rail
 * goes on or comes off, so the two are only ever written here.
 *
 * <h2>Repair</h2>
 *
 * A cell can outlive its cabinet: the cabinet destroyed while a cell of its rail was
 * in a chunk that was not loaded, say. Each cell looks for its cabinets now and then
 * ({@link #repair}) and lets go of a rail whose cabinet is gone or no longer has it,
 * dropping whatever was clipped there. It only looks once the entities round it have
 * loaded, because an entity section loads after the block chunk and a cabinet not
 * loaded yet is not a cabinet gone. Nothing is repaired while a chunk loads: the
 * cells and the cabinet arrive at different times, and changing blocks then would be
 * changing them on half the picture.
 */
public final class CabinetRailService {

    private static final String MESSAGE = "message." + CreateFlippies.MOD_ID + ".cabinet_rail.";

    /** How far a player may be from a cabinet they lay a rail in, blocks. */
    private static final double MAX_REACH = 8;

    private CabinetRailService() {}

    // Putting rails up

    /**
     * Lays the rail a player drew from {@code (ay, az)} to {@code (by, bz)} on the back
     * plate of {@code cabinet}, model px, if it can go: the door open, the rail clear
     * of the others and the walls ({@link CabinetRails#validate}), and every cell it
     * runs through free — air, something that gives way, or already one of the
     * cabinet's rail cells. Takes a rail from the player's hand unless in creative.
     */
    public static void addRail(ServerPlayer player, ElectricalCabinetEntity cabinet, double ay, double az, double by,
            double bz) {
        ServerLevel level = (ServerLevel) cabinet.level();
        if (!player.mayBuild() || cabinet.isRemoved()
                || player.distanceToSqr(cabinet.getBoundingBox().getCenter()) > MAX_REACH * MAX_REACH)
            return;
        InteractionHand hand = railHand(player);
        if (hand == null)
            return;
        if (!cabinet.doorOpen()) {
            refuse(player, "door_closed");
            return;
        }

        CabinetRail rail = CabinetRails.fromTwoPoints(cabinet.spec(), -1, ay, az, by, bz);
        CabinetRails.Problem problem = CabinetRails.validate(cabinet.spec(), cabinet.rails(), rail);
        if (!problem.ok()) {
            refuse(player, problem.key());
            return;
        }

        // The id only matters to the spans, so it is fixed before they are made
        CabinetRail placed = rail.withId(cabinet.nextRailId());
        List<CabinetRailFrame.Cell> cells = cabinet.railFrame().cells(cabinet.getUUID(), placed, cabinet.spec());
        for (CabinetRailFrame.Cell cell : cells) {
            BlockState state = level.getBlockState(cell.pos());
            boolean ours = state.is(ModBlocks.CABINET_RAIL_HOST.get());
            if (!ours && !state.isAir() && !state.canBeReplaced() || !level.mayInteract(player, cell.pos())) {
                refuse(player, "obstructed");
                return;
            }
        }

        CabinetRail added = cabinet.addRail(rail);
        if (added.id() != placed.id())
            CreateFlippies.LOGGER.error("Cabinet {} gave rail id {}, expected {}", cabinet.getUUID(), added.id(),
                    placed.id());
        for (CabinetRailFrame.Cell cell : cells) {
            if (!level.getBlockState(cell.pos()).is(ModBlocks.CABINET_RAIL_HOST.get()))
                level.setBlock(cell.pos(), ModBlocks.CABINET_RAIL_HOST.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(cell.pos()) instanceof CabinetRailHostBlockEntity host)
                host.addSpan(cell.span());
        }

        if (!player.getAbilities().instabuild)
            player.getItemInHand(hand).shrink(1);
        Vec3 at = cabinet.toWorld(CabinetGeometry.interiorCentre(cabinet.spec()));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.BLOCKS, 0.6f, 1.4f);
    }

    /** The hand holding a DIN rail, main first, or null. */
    private static InteractionHand railHand(Player player) {
        for (InteractionHand hand : InteractionHand.values())
            if (player.getItemInHand(hand).is(ModItems.DIN_RAIL.get()))
                return hand;
        return null;
    }

    // Taking rails down

    /**
     * Takes down the rail {@code span} is a piece of, from a left click on it in
     * {@code clicked}: every cell of it, and the rail back to the player unless in
     * creative. Refused while anything is clipped anywhere along it, as a rail block
     * is mined only once it is bare.
     *
     * @return whether the click was spent on it
     */
    public static boolean removeRail(Player player, ServerLevel level, CabinetRailHostBlockEntity clicked,
            RailSpan span) {
        Entity entity = level.getEntity(span.cabinet());
        if (!(entity instanceof ElectricalCabinetEntity cabinet)) {
            // A piece of a rail whose cabinet is gone: let it go, nothing else to do
            letGo(level, clicked, span.cabinet(), span.railId());
            return true;
        }
        CabinetRail rail = CabinetRails.byId(cabinet.rails(), span.railId());
        if (rail == null) {
            letGo(level, clicked, span.cabinet(), span.railId());
            return true;
        }

        List<CabinetRailFrame.Cell> cells = cabinet.railFrame().cells(cabinet.getUUID(), rail, cabinet.spec());
        List<CabinetRailHostBlockEntity> hosts = new ArrayList<>();
        for (CabinetRailFrame.Cell cell : cells)
            if (level.isLoaded(cell.pos()) && level.getBlockEntity(cell.pos()) instanceof CabinetRailHostBlockEntity host)
                hosts.add(host);
        if (!hosts.contains(clicked))
            hosts.add(clicked);
        for (CabinetRailHostBlockEntity host : hosts)
            if (!host.controlsOn(cabinet.getUUID(), rail.id()).isEmpty()) {
                refuse(player, "occupied");
                return true;
            }

        for (CabinetRailHostBlockEntity host : hosts)
            letGo(level, host, cabinet.getUUID(), rail.id());
        cabinet.removeRail(rail.id());
        if (!player.getAbilities().instabuild)
            player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.DIN_RAIL.get()));
        BlockPos at = clicked.getBlockPos();
        level.playSound(null, at, SoundEvents.NETHERITE_BLOCK_BREAK, SoundSource.BLOCKS, 0.6f, 1.2f);
        return true;
    }

    /**
     * Breaking the cabinet: every piece of gear on its rails is dropped, the cells
     * let go of its rails (and go, if that was all they carried), and the rails drop
     * as items when {@code dropRails}.
     */
    public static void dismantle(ElectricalCabinetEntity cabinet, boolean dropRails) {
        if (!(cabinet.level() instanceof ServerLevel level))
            return;
        UUID id = cabinet.getUUID();
        Map<BlockPos, List<Integer>> touched = new LinkedHashMap<>();
        for (CabinetRail rail : cabinet.rails())
            for (CabinetRailFrame.Cell cell : cabinet.railFrame().cells(id, rail, cabinet.spec()))
                touched.computeIfAbsent(cell.pos(), p -> new ArrayList<>()).add(rail.id());
        for (Map.Entry<BlockPos, List<Integer>> entry : touched.entrySet()) {
            if (!level.isLoaded(entry.getKey())
                    || !(level.getBlockEntity(entry.getKey()) instanceof CabinetRailHostBlockEntity host))
                continue;
            for (int railId : entry.getValue())
                letGo(level, host, id, railId);
        }
        if (dropRails && !cabinet.rails().isEmpty()) {
            Vec3 at = cabinet.toWorld(CabinetGeometry.interiorCentre(cabinet.spec()));
            Block.popResource(level, BlockPos.containing(at),
                    new ItemStack(ModItems.DIN_RAIL.get(), cabinet.rails().size()));
        }
    }

    /**
     * {@code host} lets go of that cabinet's rail {@code railId}: the gear on it there
     * is dropped, the span goes, and the cell with it if it carried nothing else.
     */
    private static void letGo(ServerLevel level, CabinetRailHostBlockEntity host, UUID cabinet, int railId) {
        BlockPos pos = host.getBlockPos();
        for (HostedControl control : host.controlsOn(cabinet, railId))
            if (host.remove(control) != null)
                for (ItemStack drop : control.drops())
                    Block.popResource(level, pos, drop);
        // The last control out may already have taken the block
        if (host.isRemoved())
            return;
        host.removeSpans(span -> span.isOf(cabinet, railId));
        if (host.spans().isEmpty())
            level.removeBlock(pos, false);
    }

    // Repair

    /**
     * Lets go of every rail in {@code host} whose cabinet is gone or has no such rail,
     * once the entities round it have loaded; and of the cell itself if it is left
     * with neither rail nor gear.
     */
    public static void repair(ServerLevel level, CabinetRailHostBlockEntity host) {
        BlockPos pos = host.getBlockPos();
        // A cabinet is never more than three blocks from a cell of its rails
        for (int dx = -3; dx <= 3; dx += 3)
            for (int dz = -3; dz <= 3; dz += 3)
                if (!level.areEntitiesLoaded(ChunkPos.asLong(pos.offset(dx, 0, dz))))
                    return;
        for (RailSpan span : List.copyOf(host.spans())) {
            if (host.isRemoved())
                return;
            Entity entity = level.getEntity(span.cabinet());
            if (entity instanceof ElectricalCabinetEntity cabinet && !cabinet.isRemoved()
                    && CabinetRails.byId(cabinet.rails(), span.railId()) != null)
                continue;
            letGo(level, host, span.cabinet(), span.railId());
        }
        if (!host.isRemoved() && host.spans().isEmpty() && host.controls().isEmpty())
            level.removeBlock(pos, false);
    }

    private static void refuse(Player player, String key) {
        player.displayClientMessage(Component.translatable(MESSAGE + key).withStyle(ChatFormatting.RED), true);
    }
}
