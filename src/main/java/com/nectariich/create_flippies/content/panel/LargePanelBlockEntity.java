package com.nectariich.create_flippies.content.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.nectariich.create_flippies.foundation.ShapeUnion;
import com.nectariich.create_flippies.foundation.control.ControlRack;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;

import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

/**
 * One cell of one or more large panels: Create's copycat block entity, plus the
 * rectangles that pass through this block.
 *
 * Everything about wearing another block's appearance is inherited unchanged — the
 * material, the item it was made from, the saving, the syncing, the redraw when it
 * changes. A large panel is a copycat panel that happens to be bigger than a block
 * and may stand at an angle, so it should differ from one in exactly those respects
 * and in no others, and inheriting is the only way to be sure it does.
 *
 * <h2>Why every cell carries whole panels</h2>
 *
 * Each entry of {@link #panels} is the definition of an entire panel, not of this
 * cell's share of one, and every cell of one panel holds a definition that is
 * {@code equals} to every other. That is what makes breaking one cell break the
 * panel: the block that was hit already knows the rectangle, so the break walks it
 * and takes down every cell that agrees. Nothing has to be kept alive between them —
 * no controller, no shared id, no store to go stale — and a cell that disagrees, for
 * whatever reason a world can produce, is simply not part of this panel and is left
 * standing.
 *
 * <h2>Why there may be several</h2>
 *
 * Because panels meet. A chamfer springs from the end of a wall panel and wants the
 * block that wall already ends inside; a panel folded up from the edge of another
 * shares the block the fold runs through. Refusing those was the honest thing to do
 * while a block could hold one rectangle, but the corner is exactly where a control
 * room needs panels and exactly where they overlap, so a block holds a list.
 *
 * The one thing that does not divide is the material: a copycat wears one, and it
 * belongs to the block rather than to any rectangle crossing it. Two panels of
 * different materials meeting in one block therefore agree to differ, and the block
 * shows whichever was applied last. Panels that meet are nearly always the same
 * material, and the alternative — a material per rectangle — would mean leaving
 * Create's copycat behind entirely.
 */
public class LargePanelBlockEntity extends CopycatBlockEntity implements IElectric, IElectricEntity,
        ControlRackHolder, IHaveGoggleInformation {

    /**
     * The plates this cell holds, as the pieces of material the model places.
     *
     * They have to travel as model data because a baked model is handed a block state
     * and nothing else, and they have to describe the same plate the collision shape
     * is built from — a panel drawn a pixel off what it collides with is a panel you
     * cannot reliably click on.
     */
    public static final ModelProperty<PanelTile[]> SKIN = new ModelProperty<>();

    /**
     * How many panels one block will hold. Well above anything a corner produces —
     * three is a lot, four is a contrivance — and low enough that a cell can never
     * become expensive to draw or to test against.
     */
    public static final int MAX_PANELS = 8;

    private static final String PANELS = "Panels";

    /**
     * The panels passing through this cell, in the order they were added. Empty for a
     * cell that has lost them, which a world can produce by any number of routes and
     * which must not throw: such a cell draws and collides as a plain thin slab and
     * breaks alone.
     */
    private final List<PanelDefinition> panels = new ArrayList<>();

    /**
     * Volatile, because these are not filled in by one thread alone. A chunk build
     * thread asks a panel cell for its occlusion shape — which is its shape — while
     * deciding whether the face of the block beside it can be skipped, and the
     * integrated server runs its own level on its own thread beside the client's.
     * Two threads building the same shape and one of them winning costs nothing,
     * since they build the same thing; a thread being handed the reference to a
     * {@link VoxelShape} before the writes that filled its bit set costs a block with
     * holes in it that nothing afterwards can reproduce.
     */
    private volatile VoxelShape shapeCache;
    private volatile VoxelShape outlineCache;
    private volatile VoxelShape mountedCollisionCache;
    private volatile PanelTile[] skinCache;

    /**
     * Deliberately not initialized where they are declared. SmartBlockEntity builds
     * its behaviours from its own constructor, so Power Grid asks for the circuit
     * while this class is still a superclass under construction — before any
     * initializer here has run.
     */
    private ControlRack rack;
    private ElectricBehaviour electric;

    public LargePanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LARGE_PANEL.get(), pos, state);
        rack = new ControlRack(this);
    }

    /**
     * Adds the electrical behaviour a copycat block entity has no superclass to get
     * it from.
     *
     * {@code ElectricBlockEntity} would have supplied this, and a panel cannot
     * extend it: Create requires a copycat's block entity to be a
     * {@code CopycatBlockEntity} and the two are siblings. What that class actually
     * contributes is this behaviour, its removal, and a couple of conveniences, so
     * the behaviour is made here instead and the rest is below. A behaviour is the
     * unit Create composes with, which is what makes that possible at all.
     */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        electric = new ElectricBehaviour(this);
        behaviours.add(electric);
    }

    public ElectricBehaviour getElectricBehaviour() {
        return electric;
    }

    /** Power Grid holds nodes for this block; dropping the block has to drop them. */
    @Override
    public void remove() {
        super.remove();
        if (electric != null)
            electric.remove();
    }

    // Carrying controls

    @Override
    public ControlRack rack() {
        return rack;
    }

    @Override
    public ElectricBehaviour rackBehaviour() {
        return electric;
    }

    @Override
    public void rackChanged() {
        notifyUpdate();
    }

    @Override
    public void rackResized() {
        outlineCache = null;
        mountedCollisionCache = null;
        invalidateRenderBoundingBox();
    }

    /**
     * The plate is the block's own shape and is drawn and picked whether anything is
     * mounted on it or not, so the rack starts from nothing and the two are put
     * together by {@link LargePanelBlock#getShape}.
     */
    @Override
    public VoxelShape baseShape() {
        return Shapes.empty();
    }

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
     * The block state is ignored: a panel has no orientation in its state at all,
     * and every control carries the surface and the turn it was mounted with.
     */
    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return rack == null ? null : rack.terminal(index);
    }

    /**
     * Wrenching a panel is the block's business.
     *
     * Implementing {@link IElectric} brings {@code IWrenchable} with it, whose
     * defaults would rotate or dismantle whatever they are given — neither of which a
     * panel cell has any meaning for. Passing here leaves the gesture to
     * {@link LargePanelBlock#onWrenched}, which cycles the material as a copycat
     * always has, except where a control is mounted.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void tick() {
        super.tick();
        rack.tick();
    }

    @Override
    protected AABB createRenderBoundingBox() {
        AABB bounds = rack == null ? null : rack.renderBounds();
        return bounds == null ? super.createRenderBoundingBox() : bounds;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return rack.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

    public List<PanelDefinition> panels() {
        return Collections.unmodifiableList(panels);
    }

    public boolean isEmpty() {
        return panels.isEmpty();
    }

    public boolean holds(PanelDefinition panel) {
        return panels.contains(panel);
    }

    /**
     * Takes on a panel, or reports that it will not.
     *
     * A panel already held is not added twice: two identical rectangles are one
     * rectangle, and a cell listing it twice would draw it twice and need breaking
     * twice.
     *
     * Nor is one taken on that would have nothing of its own to show; see
     * {@link #smotheredBy}.
     */
    public boolean addPanel(PanelDefinition panel) {
        if (panel == null || panels.contains(panel) || panels.size() >= MAX_PANELS)
            return false;
        if (smotheredBy(panel))
            return false;
        panels.add(panel);
        forget();
        notifyUpdate();
        return true;
    }

    /**
     * Whether a panel arriving here would be laying plate over a plate already in this
     * cell, on the same surface and with nothing of its own left over.
     *
     * Panels meeting in one block is the normal case and the reason a block holds a
     * list at all, but those meet at an angle: a wall and the chamfer off its end, a
     * panel and the one folded up from its edge. Two on the <em>same</em> surface are a
     * different thing, and drawing both would put two identical faces in the same place
     * — which does not read as a seam, it reads as the flicker of a broken block.
     *
     * A flat loft is where it happens. The strip it springs from ends on a straight cut
     * and the loft ends on a forty-five degree one, and two such lines cross at a point:
     * either the loft stops short and leaves a triangle of floor missing, or it runs on
     * and lays a triangle of plate over ground the strip already covers. Missing floor
     * is the worse of the two by far — a player can always add a panel and can never
     * subtract part of one — so the loft runs on, and the cells it runs into turn it
     * away here. What is left is exactly the plate that was missing and none of the
     * plate that was doubled.
     *
     * Which of the two yields is whichever arrives second, and that asymmetry is real
     * rather than tidied away: a cell cannot drop a panel it already holds without
     * disagreeing with the rest of that panel's cells, and a panel whose cells disagree
     * is a panel that breaks in pieces.
     */
    private boolean smotheredBy(PanelDefinition panel) {
        for (PanelDefinition held : panels)
            if (held.plane().sameSurfaceAs(panel.plane()) && held.coversCell(worldPosition))
                return true;
        return false;
    }

    /** Gives up a panel, or reports that it was not holding it. */
    public boolean removePanel(PanelDefinition panel) {
        if (!panels.remove(panel))
            return false;
        forget();
        notifyUpdate();
        return true;
    }

    /**
     * Swaps one panel this cell holds for the same panel edited, in place.
     *
     * In place rather than a remove and an add, and that is what it is for. The two
     * differ in the order the cell ends up listing its panels in, and every cell of one
     * panel has to end up agreeing — but they also differ in that an add can be refused
     * by {@link #smotheredBy}, which would be exactly the wrong answer here: the plate
     * that would be smothering the new rectangle is the old rectangle, which is on its
     * way out.
     */
    public boolean replacePanel(PanelDefinition was, PanelDefinition now) {
        int index = panels.indexOf(was);
        if (index < 0)
            return false;
        if (panels.contains(now))
            panels.remove(index);
        else
            panels.set(index, now);
        forget();
        notifyUpdate();
        return true;
    }

    /**
     * Wears whatever another cell of the same panel is wearing.
     *
     * What it is for is a panel that has just been extended: the new cells are bare
     * copycats, and a wall that grows two blocks in a different material from the rest
     * of itself reads as a mistake rather than as a wall. Only the appearance is taken
     * and never the consumed item, so the material the new cells show is not a material
     * anyone can break them for; that is the honest way round, since nobody paid for it.
     */
    public void adoptMaterialFrom(LargePanelBlockEntity source) {
        if (source == null || !source.hasCustomMaterial() || hasCustomMaterial())
            return;
        // setMaterial saves and syncs by itself
        setMaterial(source.getMaterial());
    }

    /**
     * Whichever panel of this cell lies nearest a point, or null if it holds none.
     *
     * What picks a panel out of a block that holds several: a break, a chamfer click
     * and a fold all start from somewhere the player was aiming, and the plate their
     * crosshair was on is the one they meant. Distance is measured to the plate rather
     * than to the plane, so a rectangle that ends short of the point loses to one that
     * covers it even if its plane passes closer.
     */
    public PanelDefinition nearest(Vec3 point) {
        PanelDefinition closest = null;
        double best = Double.MAX_VALUE;
        for (PanelDefinition panel : panels) {
            double distance = point.distanceToSqr(closestOn(panel, point));
            if (distance < best) {
                best = distance;
                closest = panel;
            }
        }
        return closest;
    }

    /** The point of a panel's plate nearest a point, which is the clamp of all three coordinates. */
    private static Vec3 closestOn(PanelDefinition panel, Vec3 point) {
        PanelPlane plane = panel.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double u = clamp(plane.u(point), panel.minU() / scale, panel.maxU() / scale);
        double v = clamp(plane.v(point), panel.minV() / scale, panel.maxV() / scale);
        double depth = clamp(plane.depth(point), -PanelDefinition.THICKNESS / scale, 0);
        return plane.at(u, v, depth);
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }

    /** This cell, or null where there is not one. */
    public static LargePanelBlockEntity cellAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof LargePanelBlockEntity cell ? cell : null;
    }

    /**
     * The panel a block holds nearest a point, or null if it holds none.
     *
     * Both sides ask this — the click that joins or folds panels and the preview that
     * draws the result a frame earlier — and both have to get the same answer, which
     * they do because the definitions are synced whole.
     */
    public static PanelDefinition panelAt(BlockGetter level, BlockPos pos, Vec3 near) {
        LargePanelBlockEntity cell = cellAt(level, pos);
        return cell == null ? null : cell.nearest(near);
    }

    /** The plates in this cell, which are the block's shape and its outline. */
    public VoxelShape plateShape() {
        VoxelShape cached = shapeCache;
        if (cached != null)
            return cached;
        if (panels.isEmpty())
            return shapeCache = LargePanelBlock.ORPHAN_PLATE;
        List<VoxelShape> plates = new ArrayList<>(panels.size());
        for (PanelDefinition panel : panels)
            plates.add(PanelGeometry.plateShape(panel, worldPosition));
        return shapeCache = ShapeUnion.of(plates);
    }

    /**
     * The same plates as the pieces of material the model places, which is not the
     * same list of boxes.
     *
     * The shape has to stair-step an angled panel because it is made of axis-aligned
     * boxes; the drawing does not, so it gets turned boxes instead of straight ones.
     * See {@link PanelTile}.
     */
    public PanelTile[] plateTiles() {
        PanelTile[] cached = skinCache;
        if (cached != null)
            return cached;
        if (panels.isEmpty())
            return skinCache = LargePanelBlock.ORPHAN_SKIN;
        List<PanelTile> tiles = new ArrayList<>();
        for (PanelDefinition panel : panels)
            tiles.addAll(Arrays.asList(PanelGeometry.plateTiles(panel, worldPosition)));
        return skinCache = tiles.toArray(new PanelTile[0]);
    }

    private void forget() {
        shapeCache = null;
        outlineCache = null;
        mountedCollisionCache = null;
        skinCache = null;
    }

    /**
     * The plate and everything mounted on this cell, which is what the block answers
     * with when it is asked for its shape.
     *
     * Cached, and that is not an optimisation. A panel declares
     * {@link LargePanelBlock#hasDynamicShape}, so Minecraft caches nothing for it and
     * asks again every time — for every entity's collision sweep, every tick, against
     * every cell of a wall. {@link Shapes#or} allocates and then calls
     * {@code optimize}, which on a chamfer's sixteen-box staircase is not cheap, and
     * doing that per call took a panelled room from playable to three frames a
     * second.
     *
     * A cell with nothing mounted on it — which is nearly all of them — returns the
     * plate itself and never joins anything at all.
     */
    public VoxelShape outlineShape() {
        VoxelShape cached = outlineCache;
        if (cached != null)
            return cached;
        VoxelShape mounted = rack.shape();
        return outlineCache = mounted.isEmpty() ? plateShape() : ShapeUnion.of(plateShape(), mounted);
    }

    /**
     * The same, less the part of every control that stands behind the panel: their
     * contact racks reach into the cell beyond, where collision would stop anyone
     * building against the back of the wall.
     */
    public VoxelShape mountedCollisionShape() {
        VoxelShape cached = mountedCollisionCache;
        if (cached != null)
            return cached;
        VoxelShape mounted = rack.collisionShape();
        return mountedCollisionCache = mounted.isEmpty() ? plateShape() : ShapeUnion.of(plateShape(), mounted);
    }

    /**
     * The material, as Create already supplies it, plus this cell's plates.
     *
     * Derived from the inherited data rather than built fresh, because
     * {@code CopycatBlockEntity} is the only thing that knows how it states a
     * material and a second statement of it here would be one to keep in step.
     */
    @Override
    public ModelData getModelData() {
        return super.getModelData().derive().with(SKIN, plateTiles()).build();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        ListTag saved = new ListTag();
        for (PanelDefinition panel : panels)
            saved.add(panel.save());
        tag.put(PANELS, saved);
        rack.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        // Before the panels, and so before the early return below. The rectangles are
        // unchanged on all but the first packet, while the controls change on every
        // one of them — a switch being turned syncs the whole cell — so reading the
        // rack only when the panels moved would freeze every control on the client
        rack.read(tag);

        // A tag that says nothing about the panels is not a tag that says there are
        // none. Every write puts the list in, so its absence means this is a partial
        // update about something else — and taking that as an empty panel would leave
        // the cell holding no rectangle at all, which is a block that draws the orphan
        // slab lying flat across its middle in place of whatever the panel was. That
        // is the one way a cell can reach that state, since nothing else ever empties
        // the list without removing the block in the same breath.
        if (!tag.contains(PANELS, Tag.TAG_LIST))
            return;

        List<PanelDefinition> was = new ArrayList<>(panels);
        panels.clear();
        for (Tag element : tag.getList(PANELS, Tag.TAG_COMPOUND)) {
            PanelDefinition panel = PanelDefinition.load((CompoundTag) element);
            if (panel != null && panels.size() < MAX_PANELS)
                panels.add(panel);
        }
        if (was.equals(panels))
            return;
        forget();
        if (clientPacket)
            redraw();
    }

    /**
     * Asks the client to draw this cell again, which takes two calls in this order and
     * neither of them alone.
     *
     * The plates are baked into the chunk mesh, so a client that has just been told
     * about different rectangles has to be made to build that mesh again, and it has
     * to have fresh model data to build it from. Those are two separate mechanisms in
     * Forge and each of them is useless without the other, which is why both are here
     * and why the order they are in is not arbitrary.
     *
     * {@code requestModelDataUpdate} does not compute anything. It puts this cell's
     * position into a set on the level's {@code ModelDataManager}, and that set is
     * drained by {@code getAt}, which nothing calls except a chunk section about to be
     * recompiled — so on its own it is a note left for a visit that never happens.
     * {@code sendBlockUpdated} is what arranges the visit. It goes to
     * {@code LevelRenderer.blockChanged}, which ignores both states entirely and marks
     * the section holding this block, and any section the block's immediate
     * neighbourhood reaches into, as needing to be rebuilt.
     *
     * The order matters because the drain happens on the main thread while the compile
     * task is being created, and takes whatever has been requested up to that moment.
     * Asking for the section first and the data afterwards would race a compile
     * scheduled in between, and that compile would bake the old plate.
     *
     * {@code setBlocksDirty} is the call that looks like it should do the marking and
     * is a trap worth naming so that nobody swaps it back. It puts the two states it
     * is given to {@code ModelManager.requiresRender} before it queues anything, and
     * that question opens by comparing them by reference, while {@code getBlockState}
     * hands back the same cached object both times. The answer is therefore always no
     * and no section is ever rebuilt.
     *
     * The flags are read on the client for one bit — whether to rebuild at once rather
     * than in turn — so sixteen means the ordinary queue. This is Create's own
     * {@code CopycatBlockEntity.redraw} less its poke at the light engine, which is
     * private and so cannot simply be borrowed. The light is left out deliberately: a
     * panel block is declared {@code noOcclusion}, so what it blocks is a property of
     * its state and not of where its plate happens to lie, and drilling a hole through
     * one changes no light the engine would have to be told about.
     *
     * Nothing noticed the missing rebuild for as long as it did because every other
     * change to a panel moves a block as well — building one, extending one, breaking
     * a cell — and a block update dirties the section by itself. Drilling a hole moves
     * no block, so it is the first change with nothing else to lean on.
     */
    private void redraw() {
        // isClientSide as well as the caller's clientPacket, because readClient is a
        // method and not a side: anything that hands a whole update tag to a block
        // entity gets here, and on a server that would broadcast a block update nobody
        // asked for
        if (level == null || !level.isClientSide)
            return;
        requestModelDataUpdate();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 16);
    }
}
