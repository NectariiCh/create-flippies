package com.nectariich.create_flippies.content.host;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRack;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.PlacementRefusal;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;

/**
 * The bare frame that carries mounted controls: a cell of air with instruments in
 * it and nothing else.
 *
 * Everything about carrying controls lives in {@link ControlRack}, which this
 * owns and a large panel cell owns too — see {@link ControlRackHolder} for why
 * they cannot simply share a superclass. What is left here is what is particular
 * to a frame: that it is electrical and nothing else, that it has no orientation
 * of its own, and that emptying it takes the block away.
 *
 * The host is the cell in front of the panel, not the panel — except on a chamfer,
 * where there is no cell in front that the panel does not already occupy and the
 * panel hosts its own controls instead. A control is authored plate-down, so its
 * operator side is inside its host's cell and its contact rack passes through into
 * the cell behind. That is the same convention a standalone instrument block
 * follows, which is what lets one control implementation serve all of them.
 */
public class ControlHostBlockEntity extends ElectricBlockEntity implements IElectric, ControlRackHolder,
        IHaveGoggleInformation {

    /**
     * What a host with nothing in it, or one whose block entity has not arrived
     * yet, offers instead of nothing: a small block at the centre of the cell.
     * Something has to be there or the block would be invisible, unpickable and
     * unbreakable, and a full cube would briefly wall off the cell during chunk
     * load and shove anyone standing in it.
     */
    public static final VoxelShape ORPHAN_SHAPE = Block.box(6, 6, 6, 10, 10, 10);

    /**
     * Deliberately not initialized where it is declared. SmartBlockEntity builds
     * its behaviours from its own constructor, so Power Grid asks for the circuit
     * while this class is still a superclass under construction — before any
     * initializer here has run.
     */
    private ControlRack rack;

    public ControlHostBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.CONTROL_HOST.get(), pos, state);
    }

    /**
     * For a block that hosts controls but is something else as well — a run of DIN
     * rail carrying breakers is the first — and so has an appearance, a block state
     * and a block entity type of its own.
     */
    protected ControlHostBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        rack = new ControlRack(this);
    }

    // What the rack needs of whatever is carrying it

    @Override
    public ControlRack rack() {
        return rack;
    }

    @Override
    public ElectricBehaviour rackBehaviour() {
        return getElectricBehaviour();
    }

    @Override
    public void rackChanged() {
        notifyUpdate();
    }

    @Override
    public void rackResized() {
        invalidateRenderBoundingBox();
    }

    /** A frame is nothing but its controls, so an empty one has no reason to stay. */
    @Override
    public boolean removeWhenEmpty() {
        return true;
    }

    /**
     * Whatever the block looks like before anything is mounted on it. A bare host
     * has only the stub, and only while it is empty — once it is carrying something
     * the stub would be a phantom box in the middle of the panel.
     */
    @Override
    public VoxelShape baseShape() {
        return rack == null || rack.isEmpty() ? ORPHAN_SHAPE : Shapes.empty();
    }

    // Power Grid's electrical side

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        if (rack == null) {
            builder.setTerminalCount(0);
            return;
        }
        rack.buildCircuit(builder);
    }

    @Override
    public int terminalCount() {
        // Asked while the behaviours are still being built, before the constructor
        // body that makes the rack has run
        return rack == null ? 0 : rack.terminalCount();
    }

    /**
     * The block state is ignored: a host has no orientation of its own, and every
     * control carries the facing and the turn it was mounted with.
     */
    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return rack == null ? null : rack.terminal(index);
    }

    /** Wrenching a host is the block's business, not a control's. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    // Shapes, ticking, saving and syncing

    public VoxelShape shape() {
        return rack.shape();
    }

    public VoxelShape collisionShape() {
        return rack.collisionShape();
    }

    /** Whether a control of this kind could be mounted here, without mounting it. */
    public PlacementRefusal check(ControlType type, ControlMount mount) {
        return rack.check(type, mount);
    }

    public PlacementRefusal check(ControlFootprint footprint, int terminalCount, ControlMount mount) {
        return rack.check(footprint, terminalCount, mount);
    }

    @Override
    protected AABB createRenderBoundingBox() {
        AABB bounds = rack == null ? null : rack.renderBounds();
        return bounds == null ? super.createRenderBoundingBox() : bounds;
    }

    @Override
    public void tick() {
        super.tick();
        rack.tick();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        rack.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        rack.read(tag);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return rack.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }
}
