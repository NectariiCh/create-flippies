package com.nectariich.create_flippies.foundation.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.ShapeUnion;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.utility.ClientSideAccess;

/**
 * One block's worth of mounted controls, and the owner of that block's Power Grid
 * terminals.
 *
 * The reason this can exist at all is that Power Grid's terminal count and
 * terminal geometry are per block entity, not per block state:
 * {@code terminalCount()} and {@code terminal(BlockState, int)} are abstract on
 * {@code IElectric}, {@code IElectric.getAt} resolves the block entity in
 * preference to the block, and every consumer of a terminal in the mod — the wire
 * item, the terminal highlight, the wire preview, the multimeter — goes through
 * {@code getAt}. Power Grid's own circuit board does exactly this, answering both
 * questions from its baked circuit while its block never sets a terminal
 * collection at all. So a panel of controls needs neither a mixin nor a block
 * state per arrangement, and the alternative of making each control a smaller
 * block is not merely worse but impossible: a wire endpoint is a block position
 * and an index, so one position can hold one electric part.
 *
 * The two hard parts are both consequences of that flat run of indices. It may
 * never be renumbered or shortened, which is what {@link TerminalSlots} is for;
 * and the client must have rebuilt to the identical shape before it decodes a
 * state packet, because {@code ElectricBehaviour} streams node values
 * positionally with no count and no identity. Hence the layout is part of the
 * owning block entity's saved and synced data, controls are always baked in slot
 * order, and any change of layout goes through a full rebuild on both sides.
 *
 * <h2>Why this is a component and not a superclass</h2>
 *
 * Because the two kinds of block that carry controls cannot share one. A bare
 * host frame is an {@code ElectricBlockEntity}; a large panel cell is a
 * {@code CopycatBlockEntity}, because that is the only way Create will let it wear
 * a material, and the two are siblings. A panel has to carry controls of its own
 * all the same: a chamfer's surface runs diagonally through the middle of its
 * blocks, so the cell a control's body occupies is the very cell the panel is
 * built from, measured at every point of every diagonal panel. Putting the work
 * here and reaching it through {@link ControlRackHolder} is what lets one
 * implementation serve both.
 */
public class ControlRack implements ControlHost {

    /**
     * How far off a control's surface the ray may land and still count as aiming at
     * it. The hit point comes back from clipping the union of the controls' own
     * shapes, so it is on one of them to within the arithmetic and the floats the
     * click location crosses the wire as; a tenth of a millipixel absorbs both.
     * Power Grid's circuit board inflates by half a pixel instead, which on a face
     * carrying controls a couple of pixels apart would pick the wrong one.
     */
    private static final double AIM_SLACK = 1.0e-4;

    private final SmartBlockEntity owner;
    private final ControlRackHolder holder;

    private final List<HostedControl> controls = new ArrayList<>();
    private final TerminalSlots slots = new TerminalSlots();

    /**
     * Volatile, and that is not caution for its own sake. A shape is asked for from
     * more threads than the one that fills these in: a chunk build thread reaches
     * {@link #shape} through the block's occlusion shape while deciding whether a
     * face against the panel can be skipped, and the integrated server's own thread
     * runs its level beside the client's. Without it a thread can be handed the
     * reference to a {@link VoxelShape} before the writes that built it, which is a
     * shape with a half-filled bit set — a block with holes in it that nothing can
     * reproduce. Two threads both building the same shape and one of them winning is
     * harmless by comparison, since they build the same thing.
     */
    private volatile ITerminalPlacement[] terminalCache;
    private volatile VoxelShape shapeCache;
    private volatile VoxelShape collisionCache;

    /**
     * The layout the circuit was last built for. Compared after a read to decide
     * whether the nodes on hand still match the controls, which is the one thing
     * the positional state sync cannot survive being wrong about.
     */
    private String builtLayout = "";

    public <T extends SmartBlockEntity & ControlRackHolder> ControlRack(T owner) {
        this.owner = owner;
        this.holder = owner;
    }

    // What a control may ask of its host

    @Override
    public Level level() {
        return owner.getLevel();
    }

    @Override
    public BlockPos pos() {
        return owner.getBlockPos();
    }

    @Override
    public void controlChanged() {
        holder.rackChanged();
    }

    // Power Grid's electrical side

    /** The width of the flat run of terminal indices this block answers for. */
    public int terminalCount() {
        return slots.capacity();
    }

    /** A tombstoned index answers null, which Power Grid's terminal search already skips. */
    public ITerminalPlacement terminal(int index) {
        ITerminalPlacement[] cache = terminals();
        return index >= 0 && index < cache.length ? cache[index] : null;
    }

    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(slots.capacity());
        builtLayout = layoutSignature();

        ElectricBehaviour behaviour = holder.rackBehaviour();
        if (behaviour != null && behaviour.getExternalNodes().size() < slots.capacity()) {
            // Only reachable if something rebuilt without letting the external
            // nodes grow, which would make every index below wrong rather than
            // merely absent
            CreateFlippies.LOGGER.error("Control rack at {} has {} terminals but {} nodes; circuit not built", pos(),
                    slots.capacity(), behaviour.getExternalNodes().size());
            return;
        }

        for (HostedControl control : controls) {
            HostedCircuitBuilder local = new HostedCircuitBuilder(pos(), control.terminalCount(),
                    index -> builder.terminalNode(control.slot() + index));
            control.buildCircuit(local);
            local.handOver(builder);
        }
    }

    // Installing and removing

    /** In slot order, which is the order they are baked and synced in. */
    public List<HostedControl> controls() {
        return Collections.unmodifiableList(controls);
    }

    public boolean isEmpty() {
        return controls.isEmpty();
    }

    /**
     * The control mounted at exactly this mount, or null if there is none. A mount
     * identifies a control within its host because no two may occupy the same space,
     * which makes it the one handle that does not depend on the order this list
     * happens to be in.
     */
    public HostedControl installedAt(ControlMount mount) {
        for (HostedControl control : controls)
            if (mount.equals(control.mount()))
                return control;
        return null;
    }

    /**
     * Whether a control of this kind could be mounted here, without mounting it.
     * The checks run in the order that makes the message most useful: where it
     * would go, then whether it can go anywhere at all, then whether the block has
     * the terminals for it.
     *
     * Nothing here limits how far across its own surface a control may sit, and that
     * is deliberate. A control is free to overhang the edge of its cell and to
     * straddle the boundary between two panel blocks; what it may not leave is
     * whatever the player was placing inside, which is a block face in free
     * placement and a ruled grid in constrained placement. Only the placement path
     * knows which of those applies, so that limit is enforced there — see
     * {@code ScrewdriverActions.withinBounds}.
     */
    public PlacementRefusal check(ControlType type, ControlMount mount) {
        return check(type.footprint(), type.terminalCount(), mount);
    }

    /**
     * The same question asked of a footprint rather than of a kind, which is what a
     * control the player sized as they placed it has to be checked by: its extent is
     * its own and not its kind's, so the kind cannot answer for it.
     *
     * <h2>What is deliberately not checked</h2>
     *
     * Whether two controls overlap. There was such a check, comparing the real
     * occupied volumes of everything in this cell and the cells around it, and it is
     * gone on purpose. It could not be made to mean the same thing on a chamfer — a
     * turned control's volumes are the axis-aligned boxes around it, which are half
     * as big again as the control, so two things a pixel apart on a diagonal read as
     * colliding — and even on a wall it refused arrangements that were perfectly
     * buildable and that people wanted. A panel is decoration and wiring, not a
     * physics problem: two instruments that interpenetrate look wrong and that is the
     * whole of the consequence, which is the player's business rather than the
     * game's.
     *
     * Nor whether the control fits the face or the ruling it was aimed into; that one
     * lived in the placement path and went with it, for the same reasons.
     *
     * What is left are the two limits that are not about taste. A footprint reaching
     * further than {@link ControlFootprint#REACH_BUDGET} out of its cell is one the
     * hitbox picker cannot follow and the server will refuse to interact with, so it
     * would be placed and then be untouchable; and a control needs terminals to
     * exist at all.
     */
    public PlacementRefusal check(ControlFootprint footprint, int terminalCount, ControlMount mount) {
        if (!mount.isOnFace())
            return PlacementRefusal.OFF_FACE;
        if (footprint.reach(mount) > ControlFootprint.REACH_BUDGET)
            return PlacementRefusal.REACHES_TOO_FAR;
        if (!slots.hasRoom(terminalCount))
            return PlacementRefusal.NO_TERMINALS;
        return PlacementRefusal.NONE;
    }

    /**
     * Mounts a fresh control of this kind, telling {@code placer} why not if it
     * cannot be mounted. Pass the player whenever there is one — a refused
     * placement that says nothing is indistinguishable from one that never
     * happened — or null when nobody is watching.
     */
    public PlacementRefusal install(ControlType type, ControlMount mount, Player placer) {
        return install(type.create(), mount, placer);
    }

    /**
     * Mounts a control that has already been made, which is the only way to mount
     * one the player sized as they placed it: what has to be checked is that
     * control's own footprint, and the kind does not know it. The control must be
     * fresh — an installed one cannot be moved, for the reasons
     * {@link HostedControl} gives.
     */
    public PlacementRefusal install(HostedControl control, ControlMount mount, Player placer) {
        PlacementRefusal refusal = check(control.footprint(), control.terminalCount(), mount);
        if (!refusal.ok()) {
            if (placer != null)
                refusal.tell(placer);
            return refusal;
        }

        int slot = slots.allocate(control.terminalCount());
        if (slot == TerminalSlots.NO_SLOT)
            return PlacementRefusal.NO_TERMINALS;

        control.installAt(this, mount, slot);
        controls.add(control);
        controls.sort(Comparator.comparingInt(HostedControl::slot));

        invalidateCaches();
        rebuildCircuit();
        holder.rackChanged();
        return PlacementRefusal.NONE;
    }

    /**
     * Takes a control out, freeing its slot for another of the same width and
     * unhooking whatever was wired to it — a terminal whose control has gone is
     * still a node in the solver, so wires left on it would go on conducting into
     * nothing.
     *
     * A block that is nothing but its controls is removed once the last one goes;
     * see {@link ControlRackHolder#removeWhenEmpty}.
     *
     * @return the control that was removed, so the caller can give it back to the
     *         player, or null if it was not mounted in this block
     */
    public HostedControl remove(HostedControl control) {
        if (!controls.remove(control))
            return null;

        detachWires(control.slot(), control.terminalCount());
        slots.release(control.slot(), control.terminalCount());

        invalidateCaches();
        rebuildCircuit();
        holder.rackChanged();

        if (holder.removeWhenEmpty() && controls.isEmpty() && level() != null)
            level().removeBlock(pos(), false);
        return control;
    }

    /**
     * Takes out the one control a player is pointing at and gives it back to them,
     * which is what breaking a mounted control has to mean. Vanilla breaking has no
     * finer target than the block, so left to itself it takes the frame and
     * everything screwed into it — and a panel is built one instrument at a time.
     *
     * {@code local} is the crosshair in this block's own coordinates, sent up by the
     * client because the server is told nothing about where a break was aimed; see
     * {@link com.nectariich.create_flippies.network.BreakControlPacket}.
     *
     * @return whether there was anything there to take
     */
    public boolean breakControl(Player player, Vec3 local) {
        Level level = level();
        if (level == null)
            return false;
        HostedControl control = controlAt(local);
        // A locked control is refused here as well as on the client, because the
        // client is the side that was asked politely
        if (control == null || control.isLocked())
            return false;
        // Something fitted into it comes out before the control itself does
        if (control.breakPart(player))
            return true;
        // Removing the last control may remove the block, so the sound it makes has
        // to be asked for while there is still a block to ask
        SoundType sound = owner.getBlockState().getSoundType();
        if (remove(control) == null)
            return false;

        if (!player.isCreative())
            for (ItemStack drop : control.drops())
                Block.popResource(level, pos(), drop);
        level.playSound(null, pos(), sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 4,
                sound.getPitch() * 0.8f);
        return true;
    }

    /**
     * Protects one control from being unscrewed, or gives it back.
     *
     * @return whether anything changed
     */
    public boolean lock(HostedControl control, boolean locked) {
        if (!controls.contains(control) || control.isLocked() == locked)
            return false;
        control.setLocked(locked);
        holder.rackChanged();
        return true;
    }

    // Aiming

    /**
     * The control the crosshair is on, or null when it is on none of them.
     *
     * Nearest to the surface rather than first to contain the point: controls may
     * sit a pixel apart, so a first-match test on bounds inflated enough to be
     * forgiving would be decided by the order they were mounted in.
     */
    public HostedControl controlAt(Vec3 local) {
        HostedControl nearest = null;
        double best = Double.MAX_VALUE;
        for (HostedControl control : controls)
            for (AABB volume : control.volumes()) {
                double distance = distanceTo(volume, local);
                if (distance > AIM_SLACK || distance >= best)
                    continue;
                best = distance;
                nearest = control;
            }
        return nearest;
    }

    /** The shape of everything mounted here, for picking and for the block outline. */
    public VoxelShape shape() {
        VoxelShape cached = shapeCache;
        if (cached != null)
            return cached;

        List<VoxelShape> parts = new ArrayList<>(controls.size() + 1);
        parts.add(holder.baseShape());
        for (HostedControl control : controls)
            parts.add(control.shape());
        return shapeCache = ShapeUnion.of(parts);
    }

    /**
     * Only the part of the controls inside this cell collides. Their racks stand in
     * the cell behind, where collision would stop anyone building against the back
     * of the panel.
     */
    public VoxelShape collisionShape() {
        VoxelShape cached = collisionCache;
        if (cached != null)
            return cached;
        return collisionCache = Shapes.join(shape(), Shapes.block(), BooleanOp.AND);
    }

    /**
     * Everything mounted here, in world coordinates, or null when there is nothing
     * — in which case the owner keeps whatever box it would have had.
     *
     * The default box is the block's own cell, and a mounted control reaches well
     * outside it — a rotary switch's contact rack alone hangs seven and a half
     * pixels through the panel, and a control may straddle a cell boundary sideways.
     * The renderer is skipped as soon as that one cell leaves the view frustum, so
     * the default box reads in play as controls disappearing when the panel drifts
     * towards the edge of the screen.
     *
     * The box comes from the controls' own footprints rather than from an inflation
     * of the cell. A footprint is already the real occupied volume — swept handles
     * and all — and it is derived from the same {@link ControlMount} as the hitboxes
     * and the drawing, so it cannot be smaller than what is drawn inside it.
     */
    public AABB renderBounds() {
        AABB bounds = null;
        for (HostedControl control : controls)
            for (AABB volume : control.volumes())
                bounds = bounds == null ? volume : bounds.minmax(volume);
        return bounds == null ? null : bounds.move(pos());
    }

    // Ticking, saving and syncing

    public void tick() {
        for (HostedControl control : controls)
            control.tick();
    }

    public void write(CompoundTag tag) {
        ListTag list = new ListTag();
        for (HostedControl control : controls) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Type", control.type().id());
            entry.putInt("Slot", control.slot());
            if (control.isLocked())
                entry.putBoolean("Locked", true);
            control.mount().save(entry);
            CompoundTag data = new CompoundTag();
            control.write(data);
            if (!data.isEmpty())
                entry.put("Data", data);
            list.add(entry);
        }
        tag.put("Controls", list);

        CompoundTag slotTag = new CompoundTag();
        slots.save(slotTag);
        tag.put("Slots", slotTag);
    }

    /**
     * Reads the layout back and, if it is not the one the circuit was built for,
     * rebuilds. The rebuild has to come after the read rather than during it:
     * Power Grid's behaviour has already read its own tag by the time this runs and
     * will have used whatever layout was in place then, which on a client receiving
     * its first packet is no layout at all.
     *
     * Controls that are still installed exactly as they were are updated in place
     * and only genuinely new ones are constructed, for the reason
     * {@link HostedControl#isInstalledAs} sets out: the instance owns state that the
     * packet does not carry, so replacing it throws that state away. Any change to
     * any control syncs the whole block, so this runs on a client for every flick of
     * every switch on the panel — rebuilding the list there is what made all of them
     * snap back to their first detent and ease forward again, and made the one being
     * turned towards that detent appear to snap because it had already been put
     * there.
     */
    public void read(CompoundTag tag) {
        // As with a panel's rectangles: a tag that does not mention the controls is a
        // partial update about something else, and reading it as an empty rack would
        // take every instrument off the block until the next full one arrived
        if (!tag.contains("Controls", Tag.TAG_LIST))
            return;

        List<HostedControl> installed = new ArrayList<>(controls);
        Geometry was = geometry();
        controls.clear();
        slots.load(tag.getCompound("Slots"));
        for (Tag entry : tag.getList("Controls", Tag.TAG_COMPOUND))
            loadControl((CompoundTag) entry, installed);
        controls.sort(Comparator.comparingInt(HostedControl::slot));

        // Only when the packet actually moved something. A cell syncs whole on every
        // change to anything in it — a lamp lighting, a handle easing over, a display
        // link writing this second's reading onto a label — and none of those move a
        // hitbox by a thousandth of a pixel. Throwing the shape away for them means
        // building it again the next time anything asks, which on a panel of two dozen
        // instruments is tens of milliseconds paid on whichever thread happened to ask
        // first, over and over, for a shape that never changed. A label driven by a
        // display link is the case that makes it constant rather than occasional.
        if (!was.equals(geometry()))
            invalidateCaches();
        if (!layoutSignature().equals(builtLayout))
            rebuildCircuit();
    }

    /**
     * Everything the cached shapes and terminals are derived from, as a value that
     * can be compared before and after a read.
     *
     * It is deliberately made of identities rather than of measurements. A control
     * that survived a read is the same instance, holding the same {@link ControlMount}
     * — {@link HostedControl#isInstalledAs} will not reuse one otherwise — and the
     * same {@link ControlFootprint}, since a footprint is the kind's and a control
     * that sizes its own keeps one object until that size genuinely changes. So an
     * identity comparison is exact here in the direction that matters: it can report a
     * change that did not happen, which costs a rebuild, and it cannot miss one that
     * did, which would leave the block with a shape that no longer describes it.
     *
     * The capacity is in it because the terminal array is that long, and a slot table
     * can be reloaded without the controls in front of it appearing to differ.
     */
    private Geometry geometry() {
        List<Object> parts = new ArrayList<>(controls.size() * 2);
        for (HostedControl control : controls) {
            parts.add(control);
            parts.add(control.footprint());
        }
        return new Geometry(slots.capacity(), parts);
    }

    /** Compared by identity of its parts; see {@link #geometry}. */
    private record Geometry(int capacity, List<Object> parts) {

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Geometry that) || capacity != that.capacity
                    || parts.size() != that.parts.size())
                return false;
            for (int i = 0; i < parts.size(); i++)
                if (parts.get(i) != that.parts.get(i))
                    return false;
            return true;
        }

        @Override
        public int hashCode() {
            int hash = capacity;
            for (Object part : parts)
                hash = hash * 31 + System.identityHashCode(part);
            return hash;
        }
    }

    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        // addToGoggleTooltip is told nothing about what is being looked at, so the
        // control has to come from the client's own hit result, the way Power
        // Grid's circuit board picks the component under the crosshair
        HitResult hit = ClientSideAccess.getHitResult();
        if (hit == null)
            return false;
        HostedControl control = controlAt(hit.getLocation().subtract(Vec3.atLowerCornerOf(pos())));
        if (control == null)
            return false;
        return control.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

    /** Gives back everything mounted here, for when the block itself is broken. */
    public void dropControls() {
        Level level = level();
        if (level == null || level.isClientSide)
            return;
        for (HostedControl control : controls)
            for (ItemStack drop : control.drops())
                Block.popResource(level, pos(), drop);
    }

    // Internals

    /**
     * One entry of the saved layout, reusing the control that was already mounted
     * there if there was one. {@code installed} is what this block was carrying a
     * moment ago and is consumed as entries claim from it.
     */
    private void loadControl(CompoundTag entry, List<HostedControl> installed) {
        ControlType type = ControlTypes.get(entry.getString("Type"));
        ControlMount mount = ControlMount.load(entry);
        if (type == null || mount == null) {
            CreateFlippies.LOGGER.error("Control rack at {} holds a control it cannot read back", pos());
            return;
        }

        int slot = entry.getInt("Slot");
        if (!slots.claim(slot, type.terminalCount())) {
            // Letting it through would give two controls one terminal, and a wire
            // on that terminal would then be attached to whichever answered first
            CreateFlippies.LOGGER.error("Control rack at {} claims terminals {}..{} twice; dropping one {}", pos(),
                    slot, slot + type.terminalCount() - 1, type.id());
            return;
        }

        HostedControl control = claim(installed, type, mount, slot);
        if (control == null) {
            control = type.create();
            control.installAt(this, mount, slot);
        }
        control.setLocked(entry.getBoolean("Locked"));
        control.read(entry.getCompound("Data"));
        controls.add(control);
    }

    /**
     * The control already installed exactly as an entry describes, taken out of the
     * list so that two entries cannot both claim it — which nothing should produce,
     * but a save that did would otherwise hand one instance to both and leave them
     * fighting over its state.
     */
    private static HostedControl claim(List<HostedControl> installed, ControlType type, ControlMount mount, int slot) {
        for (int i = 0; i < installed.size(); i++)
            if (installed.get(i).isInstalledAs(type, mount, slot))
                return installed.remove(i);
        return null;
    }

    /**
     * Everything about the arrangement that the terminal indices depend on. Compared
     * as text rather than hashed, because a collision here would be a desync
     * nobody could diagnose.
     */
    private String layoutSignature() {
        StringBuilder signature = new StringBuilder().append(slots.capacity());
        for (HostedControl control : controls)
            signature.append(';').append(control.slot()).append(':').append(control.type().id());
        return signature.toString();
    }

    /**
     * Rebuilds with the external nodes included, which is what re-attaches every
     * wire whose terminal index still exists and lets the node list grow. The
     * cheaper {@code rebuildCircuit(false)} is for state changes only; it leaves
     * the node list exactly as long as it was.
     */
    private void rebuildCircuit() {
        ElectricBehaviour behaviour = holder.rackBehaviour();
        if (behaviour != null)
            behaviour.rebuildCircuit(true);
    }

    /**
     * Unhooks the wires on a range of terminals, in the same order Power Grid does
     * it for a terminal that has gone past the end of the node list: tell each wire
     * its endpoint is gone, then forget the endpoint.
     */
    private void detachWires(int base, int width) {
        ElectricBehaviour behaviour = holder.rackBehaviour();
        if (behaviour == null)
            return;
        for (int index = base; index < base + width; index++) {
            BlockWireEndpoint endpoint = new BlockWireEndpoint(pos(), index);
            Set<BaseWireEntity> attached = behaviour.getConnections().get(endpoint);
            if (attached == null)
                continue;
            for (BaseWireEntity wire : List.copyOf(attached))
                wire.endpointRemoved(endpoint);
            behaviour.getConnections().remove(endpoint);
        }
    }

    private ITerminalPlacement[] terminals() {
        ITerminalPlacement[] cached = terminalCache;
        if (cached != null)
            return cached;

        ITerminalPlacement[] built = new ITerminalPlacement[slots.capacity()];
        for (HostedControl control : controls) {
            ITerminalPlacement[] own = control.terminals();
            for (int i = 0; i < own.length && control.slot() + i < built.length; i++)
                built[control.slot() + i] = own[i];
        }
        return terminalCache = built;
    }

    private void invalidateCaches() {
        terminalCache = null;
        shapeCache = null;
        collisionCache = null;
        holder.rackResized();
    }

    /** How far a point is from a box, zero when it is inside. */
    private static double distanceTo(AABB box, Vec3 point) {
        double x = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
        double y = Math.max(0, Math.max(box.minY - point.y, point.y - box.maxY));
        double z = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }
}
