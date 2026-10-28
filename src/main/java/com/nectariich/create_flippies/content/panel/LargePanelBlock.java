package com.nectariich.create_flippies.content.panel;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if neoforge {
/*import net.minecraft.world.ItemInteractionResult;
*///?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.IElectric;

/**
 * One cell of a large panel: Create's copycat panel, with the two differences a
 * large panel is for.
 *
 * It extends {@link CopycatBlock} rather than reimplementing it, and that is the
 * whole point. A copycat that is not a {@code CopycatBlock} does not merely lose
 * conveniences — {@code CopycatModel.gatherModelData} tests for one and returns
 * early when it fails, so the model never receives its data and the block renders
 * as nothing at all. Wearing a material is Create's, and everything about how it is
 * applied, saved, dropped, sounded and lit comes with it untouched.
 *
 * What this adds is only:
 *
 * <ul>
 * <li>a shape that comes from a {@link PanelDefinition} rather than from a facing,
 * so a panel may be any size and may stand at forty-five degrees;</li>
 * <li>breaking one cell breaks the whole panel it belongs to, leaving any other
 * panel crossing the same block standing.</li>
 * </ul>
 *
 * It hosts no controls and knows nothing about them. A control mounts on a panel
 * the way it mounts on any other block — the screwdriver opens a host in the cell
 * in front of the face — so the panel is a surface to build on and not a
 * participant in the control system.
 *
 * <h2>Breaking</h2>
 *
 * Only a player's break takes the panel. An explosion, a piston or an editor
 * removing one cell takes that cell alone, because those are not gestures anyone
 * aimed at the panel, and a panel that evaporated because a creeper clipped its
 * corner would be a worse surprise than a hole in it.
 */
public class LargePanelBlock extends CopycatBlock implements IProtrudingHitbox {

    /**
     * What a cell that has lost its panel definition looks like: a plain slab across
     * the middle of the block. Something has to be there or the cell would be
     * invisible, unpickable and unbreakable, which is a worse failure than a slab in
     * the wrong plane.
     */
    public static final VoxelShape ORPHAN_PLATE = Block.box(0, 7, 0, 16, 9, 16);

    /** The same slab as boxes, so an orphan cell still draws something. */
    /**
     * The same slab as {@link #ORPHAN_PLATE}, as the one tile that draws it: the
     * whole face of the material, laid flat, two pixels of it taken from the front.
     */
    public static final PanelTile[] ORPHAN_SKIN = { new PanelTile(new AABB(0, 0, 0, 1, 1, 2 / 16d),
            new Vec3(0, 9 / 16d, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, -1, 0), null) };

    /**
     * Guards the recursion a whole-panel break would otherwise be: taking down a
     * sibling is itself a block removal, and without this the first cell would ask
     * the second to take down the panel, which would ask the third, once per cell per
     * cell. A thread local rather than a plain static because an integrated server
     * runs its level on its own thread beside the client.
     */
    private static final ThreadLocal<Boolean> DEMOLISHING = ThreadLocal.withInitial(() -> false);

    /** How far the break ray is followed to find which plate was hit, in blocks. */
    private static final double PICK_RANGE = 8;

    public LargePanelBlock(Properties properties) {
        super(properties);
    }

    // Shape: the plate, wherever the definition puts it in this cell

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        return cell != null ? cell.outlineShape() : ORPHAN_PLATE;
    }

    /**
     * The plate collides, and so does whatever part of a mounted control stands in
     * front of it — but not the contact racks hanging through into the cell behind,
     * where collision would stop anyone building against the back of the panel.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        return cell != null ? cell.mountedCollisionShape() : ORPHAN_PLATE;
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    /**
     * A panel is a thin plate in a cell of air, so it must not cast the shadow the
     * default would infer from a collision shape that, on a diagonal, is a staircase
     * of sixteen boxes.
     */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /**
     * Yes, and saying so is what stops the rest of the block from being described by a
     * cell that does not exist.
     *
     * A panel takes its shape from its block entity, so there is nothing about it a
     * block state alone can answer. Minecraft caches the sturdiness of a state's faces
     * and whether its collision shape fills a block by evaluating it once against an
     * empty world at the origin — where a panel has no block entity and falls back to
     * {@link #ORPHAN_PLATE}, a slab lying flat across the middle. Every panel in the
     * world would then be described by that slab regardless of where its plate really
     * is. Declaring the shape dynamic turns the cache off and the questions are asked
     * of the real cell instead.
     */
    @Override
    public boolean hasDynamicShape() {
        return true;
    }

    /**
     * Never. Connected textures are drawn across a shared face, and two cells of a
     * diagonal panel meet along an edge rather than a face; on a cardinal one the
     * seam falls wherever the rectangle happened to cross a block boundary, which is
     * not a seam the material knows anything about.
     */
    @Override
    public boolean canConnectTexturesToward(BlockAndTintGetter level, BlockPos fromPos, BlockPos toPos,
            BlockState state) {
        return false;
    }

    // Breaking

    /**
     * A player breaking one cell breaks the one panel they were aiming at, and leaves
     * any others crossing the same block standing.
     *
     * Taken here rather than in {@code playerWillDestroy} because a block holding two
     * panels must survive losing one, and this is the hook that can say so: returning
     * false leaves the block where it is. The vanilla path is otherwise reproduced
     * exactly, so a cell that does empty out breaks, drops and sounds as it always did.
     *
     * The guard is still needed. Taking down a sibling is itself a block removal, and
     * without it the first cell would ask the second to take down the panel, which
     * would ask the third, once per cell per cell.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
            net.minecraft.world.level.material.FluidState fluid) {
        if (level.isClientSide || DEMOLISHING.get())
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);

        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        PanelDefinition panel = cell == null ? null : cell.nearest(aimedAt(player, pos));
        if (panel == null)
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);

        DEMOLISHING.set(true);
        try {
            panel.forEachCell(sibling -> {
                if (sibling.equals(pos))
                    return;
                LargePanelBlockEntity holder = LargePanelBlockEntity.cellAt(level, sibling);
                if (holder == null || !holder.removePanel(panel))
                    return;
                // A cell still carrying another panel keeps its block; only one emptied
                // by this break is taken away
                if (holder.isEmpty())
                    demolish(level, sibling, player);
            });
        } finally {
            DEMOLISHING.set(false);
        }

        // The block that was hit goes the same way as its siblings: away if this was
        // the last panel in it, and otherwise merely lighter by one
        cell.removePanel(panel);
        if (cell.isEmpty())
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);

        // The block stays, so vanilla will play nothing; a panel that came off a cell
        // and left another behind still has to look and sound like it came off
        level.levelEvent(2001, pos, Block.getId(state));
        return false;
    }

    /**
     * Where in the block the player was looking, which is what picks one panel out of a
     * block holding several.
     *
     * The ray is cast against the block rather than against the plates: a plate is a
     * staircase on an angled panel, so clipping to it would answer differently from
     * what the player saw, and the entry point is enough to tell two plates apart when
     * the block is only a block across.
     */
    /**
     * Takes one cell of a panel away, dropping what it owed but without the break
     * effect.
     *
     * The drops are exactly what {@code Level.destroyBlock} would have given — it is
     * that method less its {@code levelEvent} — and leaving the effect out is the
     * point. A panel is one thing to the player, so breaking a chamfer of a dozen
     * cells should be one crack and one puff of dust rather than a dozen of each
     * going off together, which is what it looked like. The cell the player actually
     * hit plays the effect for all of them.
     *
     * Shared with the edit gesture, which takes cells away for a different reason and
     * owes exactly the same things when it does.
     */
    public static void demolish(Level level, BlockPos cell, Player player) {
        if (!player.isCreative())
            Block.dropResources(level.getBlockState(cell), level, cell, level.getBlockEntity(cell), player,
                    ItemStack.EMPTY);
        level.removeBlock(cell, false);
    }

    private static Vec3 aimedAt(Player player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getLookAngle().scale(PICK_RANGE));
        return new AABB(pos).clip(eye, reach).orElseGet(() -> Vec3.atCenterOf(pos));
    }

    // Controls mounted on the panel itself

    /**
     * A right click goes to whatever is mounted here before it goes to the copycat.
     *
     * A panel cell carries controls of its own on a chamfer, where there is no cell
     * in front of the surface that the panel does not already occupy — see
     * {@link com.nectariich.create_flippies.foundation.control.ControlRackHolder}. So
     * the same three questions a bare host frame asks have to be asked here, in the
     * same order, before the block falls back to Create's own handling: a click on a
     * contact is a wire connection, sneaking and the wrench belong to the tools, and
     * anything else operates the control under the crosshair.
     *
     * Falling through is what keeps a bare panel behaving exactly as it did: an empty
     * cell has no contacts and no controls, so every click reaches
     * {@code CopycatBlock} and applies a material.
     */
    @Override
    //? if forge {
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
    //?} else {
    /*protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
    *///?}
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());

        IElectric electric = IElectric.getAt(level, pos);
        if (electric != null && electric.terminalAt(state, local) != null)
            //? if forge {
            return InteractionResult.PASS;
            //?} else {
            /*return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
            *///?}
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(hand)))
            //? if forge {
            return super.use(state, level, pos, player, hand, hit);
            //?} else {
            /*return super.useItemOn(stack, state, level, pos, player, hand, hit);
            *///?}

        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        HostedControl control = cell == null ? null : cell.controlAt(local);
        if (control == null)
            //? if forge {
            return super.use(state, level, pos, player, hand, hit);
            //?} else {
            /*return super.useItemOn(stack, state, level, pos, player, hand, hit);
            *///?}
        if (level.isClientSide)
            //? if forge {
            return InteractionResult.SUCCESS;
            //?} else {
            /*return ItemInteractionResult.SUCCESS;
            *///?}
        //? if forge {
        return control.use(player, hand, local);
        //?} else {
        /*// A pass leaves the click to the item, as a pass from use did
        return switch (control.use(player, hand, local)) {
            case SUCCESS, SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL;
            case PASS -> ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
            case FAIL -> ItemInteractionResult.FAIL;
        };
        *///?}
    }

    /**
     * The wrench cycles the panel's material, except where a control is mounted.
     *
     * Aimed at a control, cycling the material underneath it would be both surprising
     * and hard to undo — the player is pointing at a gauge, not at the wall behind it.
     * It no longer opens that control's editor either: configuring anything is a
     * sneaking right click with a screwdriver, which is {@link ControlConfig}'s.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        HostedControl control = cell == null ? null
                : cell.controlAt(context.getClickLocation().subtract(Vec3.atLowerCornerOf(pos)));
        return control == null ? super.onWrenched(state, context) : InteractionResult.PASS;
    }

    /** Middle-clicking picks the control under the crosshair, not the panel's material. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos,
            Player player) {
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        HostedControl control = cell == null ? null
                : cell.controlAt(target.getLocation().subtract(Vec3.atLowerCornerOf(pos)));
        return control != null ? control.asItem() : super.getCloneItemStack(state, target, level, pos, player);
    }

    /**
     * Whatever was mounted on this cell comes back when the cell goes, however it
     * goes — broken by hand, taken down with the rest of its panel, or blown up.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock())) {
            LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
            if (cell != null)
                cell.dropControls();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * Panels tick, which copycats do not — and only the ones carrying something.
     *
     * {@code CopycatBlock} returns no ticker at all, and rightly for what it is:
     * nothing about wearing another block's appearance needs one. A panel cell
     * carries controls, and a control eases its handle towards where it has been
     * turned to, a frame at a time, on the client. Without a ticker that easing never
     * runs: the switch changes position, the wires do what they should, and the
     * handle sits exactly where it was drawn the first time.
     *
     * A ticker is chosen per block state, so it cannot be given to some cells and
     * withheld from others — but it can decline. A wall is thousands of cells and a
     * handful of instruments, and the empty ones have nothing to ease and no circuit
     * to keep: skipping them keeps the cost of a panelled room proportional to what
     * is mounted in it rather than to how much wall was built. An empty cell is then
     * exactly as inert as it was before any of this, which is also what stops the
     * copycat behaviours underneath from being initialized for the first time by a
     * panel that is doing nothing.
     */
    @Override
    public <S extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityTicker<S> getTicker(Level level,
            BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<S> type) {
        SmartBlockEntityTicker<S> ticker = new SmartBlockEntityTicker<>();
        return (world, pos, blockState, entity) -> {
            if (entity instanceof LargePanelBlockEntity cell && !cell.rack().isEmpty())
                ticker.tick(world, pos, blockState, entity);
        };
    }

    // Plumbing

    @Override
    public net.minecraft.world.level.block.entity.BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() {
        return ModBlockEntities.LARGE_PANEL.get();
    }
}
