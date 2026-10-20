package com.nectariich.create_flippies.content.host;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.registry.ModBlockEntities;
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
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IElectric;

/**
 * The block form of a cell carrying mounted controls: a frame with no appearance
 * and no orientation of its own, whose shape, terminals and behaviour all come
 * from {@link ControlHostBlockEntity}.
 *
 * It deliberately never calls {@code setTerminalCollection}. That call is what
 * freezes a {@code BlockStateTerminalCollection} and an outline per block state
 * into the block at construction, and a host's terminals depend on what has been
 * mounted in it rather than on its state, so the tables would be both wrong and
 * unbuildable. Leaving them unset makes {@code ElectricBlock.terminalCount()}
 * answer zero — which is correct and harmless, because {@code IElectric.getAt}
 * resolves the block entity ahead of the block and every consumer of a terminal
 * goes through it. The one thing that cannot be moved is
 * {@code ElectricBlock.getShape}, so it is overridden here to ask the block entity
 * instead; it is called often enough that the block entity caches the answer.
 *
 * There is no block state at all. A host wears controls facing any of six ways at
 * once and each of them remembers its own facing and turn, so there is nothing
 * left for a state to say — and nothing for the wrench to turn, which is why
 * wrenching one is refused rather than silently doing nothing.
 */
public class ControlHostBlock extends ElectricBlock implements IBE<ControlHostBlockEntity>, IProtrudingHitbox {

    public ControlHostBlock(Properties properties) {
        super(properties);
    }

    /**
     * Both shapes come from the block entity, and both fall back to its orphan stub
     * when there is none — which happens during chunk load and in any
     * {@code BlockGetter} that carries no block entities at all, such as the one
     * vanilla probes a block state with while registering it.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ControlHostBlockEntity host = getBlockEntity(level, pos);
        return host != null ? host.shape() : ControlHostBlockEntity.ORPHAN_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ControlHostBlockEntity host = getBlockEntity(level, pos);
        return host != null ? host.collisionShape() : ControlHostBlockEntity.ORPHAN_SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());

        // A click on a contact is a wire connection, not an operation. The question
        // has to go to IElectric.getAt rather than to this block, whose own terminal
        // collection is deliberately empty
        IElectric electric = IElectric.getAt(level, pos);
        if (electric != null && electric.terminalAt(state, local) != null)
            return InteractionResult.PASS;
        // Leave sneaking and the wrench to the tools that mount and dismantle
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(hand)))
            return InteractionResult.PASS;

        return onBlockEntityUse(level, pos, host -> {
            HostedControl control = host.controlAt(local);
            if (control == null)
                return InteractionResult.PASS;
            if (level.isClientSide)
                return InteractionResult.SUCCESS;
            return control.use(player, hand, local);
        });
    }

    /**
     * Turning the host would turn nothing — every control carries its own facing —
     * and the wrench no longer opens anything either: a control's editor is reached
     * by a sneaking right click with a screwdriver, wherever that control is, which
     * is {@link ControlConfig}'s. So the gesture is left alone here.
     *
     * Sneak-wrenching dismantles nothing here either, because the frame is not an
     * item; taking a control off a panel is the screwdriver's job.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Middle-clicking a panel picks the control under the crosshair, not the frame. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos,
            Player player) {
        ControlHostBlockEntity host = getBlockEntity(level, pos);
        if (host == null)
            return ItemStack.EMPTY;
        HostedControl control = host.controlAt(target.getLocation().subtract(Vec3.atLowerCornerOf(pos)));
        return control != null ? control.asItem() : ItemStack.EMPTY;
    }

    /**
     * The loot table is empty because a host is not an item; what a broken panel
     * owes the player is one item per control, which only the block entity knows.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()))
            withBlockEntityDo(level, pos, ControlHostBlockEntity::dropControls);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * A host is a cell of air with instruments in it, so it must not cast the
     * shadow the default would infer from its collision shape.
     */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public Class<ControlHostBlockEntity> getBlockEntityClass() {
        return ControlHostBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ControlHostBlockEntity> getBlockEntityType() {
        return ModBlockEntities.CONTROL_HOST.get();
    }
}
