package com.nectariich.create_flippies.content.cabinet;

import com.nectariich.create_flippies.content.din_rail.DinRailMountable;
import com.nectariich.create_flippies.content.din_rail.DinRailSeat;
import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.nectariich.create_flippies.registry.ModItems;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IElectric;

/**
 * The invisible block in a cell an electrical cabinet's DIN rail runs through. It has
 * no appearance: the cabinet draws its rails, and the gear on them is drawn by the
 * host renderer as any mounted control is. Its shape, terminals and behaviour all
 * come from {@link CabinetRailHostBlockEntity}.
 *
 * Like the bare host frame, it never calls {@code setTerminalCollection}, and for the
 * same reason ({@code ControlHostBlock} explains it): what its terminals are depends
 * on what is clipped in it, not on a block state, and {@code IElectric.getAt}
 * resolves the block entity first.
 *
 * It is put there and taken away only by its cabinet, never by a player: it has no
 * item and no loot, cannot be mined or blown up, pushed or carried off by a
 * contraption, and a left click on it unclips gear or takes a bare rail down
 * instead ({@code ControlBreakInput}). It is a {@link IProtrudingHitbox} because gear
 * seated here may straddle into the next cell along the rail.
 */
public class CabinetRailHostBlock extends ElectricBlock implements IBE<CabinetRailHostBlockEntity>,
        IProtrudingHitbox {

    public CabinetRailHostBlock(Properties properties) {
        super(properties);
    }

    /**
     * Both shapes come from the block entity. With none yet, during chunk load, the
     * cell is empty rather than a stub: it stands inside a cabinet, where a phantom
     * box would wall off the room for a moment and shove whoever is in it.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        CabinetRailHostBlockEntity host = getBlockEntity(level, pos);
        return host != null ? host.shape() : Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        CabinetRailHostBlockEntity host = getBlockEntity(level, pos);
        return host != null ? host.collisionShape() : Shapes.empty();
    }

    /**
     * What a right click on a cabinet rail can mean, in the order a rail block asks:
     * a contact is Power Grid's; the wrench is left alone; gear under the crosshair
     * is that gear's; with DIN rail gear in hand it clips on at the module clicked.
     *
     * A DIN rail in hand is swallowed. Rails go on a cabinet's back plate by two
     * clicks on the plate itself, and a rail placed as a block against a cabinet
     * rail would stand loose inside the cabinet.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());

        IElectric electric = IElectric.getAt(level, pos);
        if (electric != null && electric.terminalAt(state, local) != null)
            return InteractionResult.PASS;

        ItemStack held = player.getItemInHand(hand);
        if (AllItems.WRENCH.isIn(held))
            return InteractionResult.PASS;

        CabinetRailHostBlockEntity host = getBlockEntity(level, pos);
        if (host == null)
            return InteractionResult.PASS;

        HostedControl clipped = host.controlAt(local);
        if (clipped != null) {
            if (level.isClientSide)
                return InteractionResult.SUCCESS;
            return clipped.use(player, hand, local);
        }

        ControlType mountable = ControlTypes.forItem(held);
        if (mountable instanceof DinRailMountable din) {
            CabinetRailHostBlockEntity.Seat seat = host.seatAt(local, din.width());
            if (seat == null || !(level.getBlockEntity(seat.host()) instanceof CabinetRailHostBlockEntity target))
                return InteractionResult.PASS;
            return DinRailSeat.clipOn(level, target, seat.host(), mountable, seat.mount(), player, held);
        }
        if (held.is(ModItems.DIN_RAIL.get()))
            return InteractionResult.sidedSuccess(level.isClientSide);
        return InteractionResult.PASS;
    }

    /** There is nothing to turn: the rail runs where it was drawn. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Middle-clicking picks the gear under the crosshair, or a rail on bare rail. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos,
            Player player) {
        CabinetRailHostBlockEntity host = getBlockEntity(level, pos);
        if (host == null)
            return ItemStack.EMPTY;
        Vec3 local = target.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        HostedControl control = host.controlAt(local);
        if (control != null)
            return control.asItem();
        return host.spanAt(local) != null ? new ItemStack(ModItems.DIN_RAIL.get()) : ItemStack.EMPTY;
    }

    /**
     * Whatever is still clipped here when the cell goes is given back; the cell
     * itself is no item, and its rail is the cabinet's to give back.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()))
            withBlockEntityDo(level, pos, CabinetRailHostBlockEntity::dropControls);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** A cell of air inside a cabinet with a rail in it: it casts no shadow of its own. */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public Class<CabinetRailHostBlockEntity> getBlockEntityClass() {
        return CabinetRailHostBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CabinetRailHostBlockEntity> getBlockEntityType() {
        return ModBlockEntities.CABINET_RAIL_HOST.get();
    }
}
