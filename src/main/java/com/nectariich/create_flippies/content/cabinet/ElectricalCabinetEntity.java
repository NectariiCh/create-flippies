package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceItem;
import com.nectariich.create_flippies.content.device.DeviceShape;
import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An electrical cabinet hung on a wall: a sheet-metal box of any size up to three
 * blocks across and up and one deep, to the pixel, with a door that swings open
 * about its hinge. Its {@link CabinetSpec} comes off the item and never changes once
 * it is up; only the door does.
 *
 * A {@link DeviceEntity} at a quarter turn, its back plate's outer face on the wall
 * ({@link CabinetGeometry}). It is hollow: the walls and the door are solid, the room
 * inside is not, so a big one can be climbed into and shut. A right click on the door
 * (not sneaking) opens or closes it, unless something is where it would swing to.
 * Gear inside may be deeper than the room and poke through the door or walls; that
 * is allowed, and nothing here or on the item refuses a cabinet for its depth.
 *
 * It takes three seconds of holding the attack button to break, by hand in survival
 * and, with the {@code slowBreaking} game rule on, in creative too ({@link #slowBreaks}).
 *
 * The spec and the door come with the entity as it appears (spawn data), so its shape
 * is right from the first frame; after that the door travels as synced entity data,
 * and both sides make their shape again when it changes.
 *
 * <h2>Rails</h2>
 *
 * DIN rails go on the inside of the back plate ({@link CabinetRail}), laid by two
 * clicks with a rail in hand while the door is open ({@link CabinetRailService}). The
 * list here is what the rails are: their geometry, what is drawn, and what a new one
 * is checked against. It travels as synced entity data, a tag of its own, so a client
 * sees a rail the moment it goes on and the placement preview tests against the same
 * list the server will. The gear on the rails, and the rails' share of the world's
 * blocks, belong to the invisible host blocks in the cells they run through
 * ({@link CabinetRailHostBlock}), because Power Grid, wiring, goggles and every
 * control packet address a block. Breaking the cabinet takes all of it down
 * ({@link CabinetRailService#dismantle}).
 *
 * <h2>Holes</h2>
 *
 * The screwdriver's cabinet mode drills and cuts holes through any wall and the door,
 * and fills them back in ({@link CabinetCutService}). The strokes are kept in order
 * ({@link CabinetCut}) and travel as synced entity data of their own; both sides make
 * the walls again from them when they change ({@link CabinetCuts}), and the shape with
 * them, so what is hit, stood on and drawn is the cabinet with its holes.
 *
 * <h2>Controls</h2>
 *
 * Controls go on the outside of any wall and of the door with the screwdriver, the way
 * they go on a block face ({@link CabinetMounting}): in ordinary control hosts in the
 * cells in front. A door's controls swing with it. They are hosted where they are with
 * the door open, which is where they are wired, and drawn and clicked on the shut door
 * through its {@link CabinetDoorLink}; this keeps the hosts it put them in
 * ({@link #doorHosts}), tells them when the door moves, and takes them down with it.
 */
public class ElectricalCabinetEntity extends DeviceEntity {

    private static final EntityDataAccessor<Boolean> DOOR_OPEN =
            SynchedEntityData.defineId(ElectricalCabinetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<CompoundTag> RAILS =
            SynchedEntityData.defineId(ElectricalCabinetEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> CUTS =
            SynchedEntityData.defineId(ElectricalCabinetEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> DOOR_HOSTS =
            SynchedEntityData.defineId(ElectricalCabinetEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Null only while the entity is being constructed, when {@link #shape} is already asked for. */
    private CabinetSpec spec = CabinetSpec.DEFAULT;

    /** The rails on the back plate, read back out of {@link #RAILS} whenever it changes. */
    private List<CabinetRail> rails = List.of();
    /** The id the next rail gets; never reused while the cabinet stands. Server only. */
    private int nextRailId;
    /** The holes cut in its walls and filled back in, in order, read back out of {@link #CUTS}. */
    private List<CabinetCut> cuts = List.of();
    /** Its walls as the cuts leave them, made when first asked for after a change. */
    private CabinetCuts.Built walls;
    /** Its shape closed and open with the cuts made, likewise. */
    private final DeviceShape[] cutShapes = new DeviceShape[2];
    /** Who broke it, for {@link #onBroken}; null for nobody. */
    /** The hosts carrying controls on the door; synced, so a client can find them to pick while it is shut. */
    private List<BlockPos> doorHosts = List.of();
    private Player breaker;
    /** Whether its rails and gear have been taken down already. */
    private boolean dismantled;

    public ElectricalCabinetEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public CabinetSpec spec() {
        return spec == null ? CabinetSpec.DEFAULT : spec;
    }

    /** Sets what it is; before it is added to the level. */
    public void setSpec(CabinetSpec spec) {
        CabinetSpec clamped = spec.clamp();
        if (clamped.equals(this.spec))
            return;
        this.spec = clamped;
        shapeChanged();
    }

    public boolean doorOpen() {
        return entityData.get(DOOR_OPEN);
    }

    /** The inside face of its back plate, model px: where rails go. */
    public CabinetGeometry.BackPlane backPlane() {
        return CabinetGeometry.backPlane(spec());
    }

    // Rails

    public List<CabinetRail> rails() {
        return rails;
    }

    /** The way its front looks: straight out of the wall it hangs on. */
    public Direction out() {
        Vec3 front = directionToWorld(new Vec3(1, 0, 0));
        return Direction.getNearest(front.x, front.y, front.z);
    }

    /** Its rails in the world, to the pixel. */
    public CabinetRailFrame railFrame() {
        return CabinetRailFrame.of(position(), out());
    }

    /** The id {@link #addRail} will give the next rail. Server only. */
    public int nextRailId() {
        return nextRailId;
    }

    /** Puts {@code rail} on under a fresh id and returns it as it went on. Server only. */
    public CabinetRail addRail(CabinetRail rail) {
        CabinetRail placed = rail.withId(nextRailId++);
        List<CabinetRail> next = new ArrayList<>(rails);
        next.add(placed);
        setRails(next);
        return placed;
    }

    /** Takes rail {@code id} off; whether it was there. Server only. */
    public boolean removeRail(int id) {
        List<CabinetRail> next = new ArrayList<>(rails);
        if (!next.removeIf(rail -> rail.id() == id))
            return false;
        setRails(next);
        return true;
    }

    private void setRails(List<CabinetRail> next) {
        rails = Collections.unmodifiableList(next);
        entityData.set(RAILS, saveRails(rails));
    }

    private static CompoundTag saveRails(List<CabinetRail> rails) {
        ListTag list = new ListTag();
        for (CabinetRail rail : rails)
            list.add(rail.save());
        CompoundTag tag = new CompoundTag();
        tag.put("Rails", list);
        return tag;
    }

    private static List<CabinetRail> loadRails(CompoundTag tag) {
        List<CabinetRail> rails = new ArrayList<>();
        for (Tag entry : tag.getList("Rails", Tag.TAG_COMPOUND))
            rails.add(CabinetRail.load((CompoundTag) entry));
        return Collections.unmodifiableList(rails);
    }

    // Controls on the door

    public List<BlockPos> doorHosts() {
        return doorHosts;
    }

    /** How the door's open pose is carried to its shut one. */
    public CabinetDoorLink doorLink() {
        return CabinetDoorLink.of(this);
    }

    /** {@code pos} hosts controls on the door. Server only. */
    public void addDoorHost(BlockPos pos) {
        if (doorHosts.contains(pos))
            return;
        List<BlockPos> next = new ArrayList<>(doorHosts);
        next.add(pos.immutable());
        setDoorHosts(next);
    }

    private void setDoorHosts(List<BlockPos> next) {
        doorHosts = Collections.unmodifiableList(next);
        entityData.set(DOOR_HOSTS, saveDoorHosts(doorHosts));
    }

    /**
     * Tells every host of the door's controls whether the door is shut, and forgets
     * those that have gone. Server only; on every swing and now and then besides, so a
     * host that was unloaded when the door moved catches up.
     */
    private void syncDoorHosts() {
        if (level().isClientSide || doorHosts.isEmpty())
            return;
        CabinetDoorLink link = doorLink();
        boolean shut = !doorOpen();
        List<BlockPos> kept = new ArrayList<>(doorHosts.size());
        for (BlockPos pos : doorHosts) {
            if (!level().isLoaded(pos)) {
                kept.add(pos);
                continue;
            }
            if (level().getBlockEntity(pos) instanceof ControlHostBlockEntity host && host.door() != null
                    && host.door().cabinet().equals(getUUID())) {
                host.setDoor(link, shut);
                kept.add(pos);
            }
        }
        if (kept.size() != doorHosts.size())
            setDoorHosts(kept);
    }

    /** Whether {@code pos} holds a host of this cabinet's door. */
    private boolean isDoorHost(BlockPos pos) {
        return level().getBlockEntity(pos) instanceof ControlHostBlockEntity host && host.door() != null
                && host.door().cabinet().equals(getUUID());
    }

    private static CompoundTag saveDoorHosts(List<BlockPos> hosts) {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Hosts", hosts.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    private static List<BlockPos> loadDoorHosts(CompoundTag tag) {
        List<BlockPos> hosts = new ArrayList<>();
        for (long packed : tag.getLongArray("Hosts"))
            hosts.add(BlockPos.of(packed));
        return Collections.unmodifiableList(hosts);
    }

    @Override
    protected void deviceTick() {
        if (!level().isClientSide && (level().getGameTime() + getId()) % 40 == 0)
            syncDoorHosts();
    }

    // Holes

    public List<CabinetCut> cuts() {
        return cuts;
    }

    /** Its walls as the cuts leave them. */
    public CabinetCuts.Built walls() {
        CabinetCuts.Built built = walls;
        if (built == null)
            walls = built = CabinetCuts.build(spec(), cuts);
        return built;
    }

    /** Makes {@code cut}, or says why not. Server only. */
    public CabinetCuts.Problem addCut(CabinetCut cut) {
        CabinetCuts.Change change = CabinetCuts.add(spec(), cuts, cut);
        if (change.problem().ok())
            setCuts(change.cuts());
        return change.problem();
    }

    private void setCuts(List<CabinetCut> next) {
        cutsChanged(next);
        entityData.set(CUTS, saveCuts(cuts));
    }

    private void cutsChanged(List<CabinetCut> next) {
        cuts = next;
        walls = null;
        cutShapes[0] = cutShapes[1] = null;
        shapeChanged();
    }

    private static CompoundTag saveCuts(List<CabinetCut> cuts) {
        ListTag list = new ListTag();
        for (CabinetCut cut : cuts)
            list.add(cut.save());
        CompoundTag tag = new CompoundTag();
        tag.put("Cuts", list);
        return tag;
    }

    private static List<CabinetCut> loadCuts(CompoundTag tag) {
        List<CabinetCut> cuts = new ArrayList<>();
        for (Tag entry : tag.getList("Cuts", Tag.TAG_COMPOUND)) {
            CabinetCut cut = CabinetCut.load((CompoundTag) entry);
            if (cut != null)
                cuts.add(cut);
        }
        return Collections.unmodifiableList(cuts);
    }

    @Override
    public DeviceShape shape() {
        if (cuts == null || cuts.isEmpty())
            return CabinetGeometry.shape(spec(), doorOpen());
        int open = doorOpen() ? 1 : 0;
        DeviceShape shape = cutShapes[open];
        if (shape == null) {
            CabinetCuts.Built built = walls();
            cutShapes[open] = shape = new DeviceShape(built.boxes(open == 1, false).toArray(double[][]::new),
                    new double[][] {}, built.boxes(open == 1, true).toArray(double[][]::new), 1, 1);
        }
        return shape;
    }

    @Override
    public ItemStack asItem() {
        ItemStack stack = new ItemStack(
                (spec().standing() ? ModItems.STANDING_ELECTRICAL_CABINET : ModItems.ELECTRICAL_CABINET).get());
        spec().apply(stack);
        return stack;
    }

    @Override
    public float hardness() {
        // Three seconds by hand: a block's 30 ticks per point of hardness
        return 2;
    }

    @Override
    public boolean slowBreaks() {
        return true;
    }

    /** Another cabinet, or any device, may stand on its top. */
    @Override
    public boolean carriesOnTop() {
        return true;
    }

    @Override
    public SoundType sound() {
        return SoundType.METAL;
    }

    /** Lit from the room inside rather than from the wall block its position is on. */
    @Override
    public Vec3 getLightProbePosition(float partialTicks) {
        return toWorld(CabinetGeometry.interiorCentre(spec()));
    }

    @Override
    //? if forge {
    protected void defineSynchedData() {
        entityData.define(DOOR_OPEN, false);
        entityData.define(RAILS, new CompoundTag());
        entityData.define(CUTS, new CompoundTag());
        entityData.define(DOOR_HOSTS, new CompoundTag());
    }
    //?} else {
    /*protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DOOR_OPEN, false);
        builder.define(RAILS, new CompoundTag());
        builder.define(CUTS, new CompoundTag());
        builder.define(DOOR_HOSTS, new CompoundTag());
    }
    *///?}

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (accessor == DOOR_OPEN)
            shapeChanged();
        if (accessor == RAILS)
            rails = loadRails(entityData.get(RAILS));
        if (accessor == CUTS)
            cutsChanged(loadCuts(entityData.get(CUTS)));
        if (accessor == DOOR_HOSTS)
            doorHosts = loadDoorHosts(entityData.get(DOOR_HOSTS));
    }

    // The door

    @Override
    public InteractionResult interactAt(Player player, Vec3 local, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof DeviceItem item) {
            BlockHitResult top = DeviceItem.topHit(this, position().add(local));
            if (top != null) {
                InteractionResult placed = item.placeAt(level(), player, top, held);
                if (placed != InteractionResult.PASS)
                    return placed;
            }
        }
        if (player.isShiftKeyDown())
            return InteractionResult.PASS;
        Vec3 model = toModel(position().add(local));
        if (CabinetGeometry.partAt(spec(), doorOpen(), model) != CabinetGeometry.Part.DOOR)
            return InteractionResult.PASS;
        boolean next = !doorOpen();
        if (!doorClear(next))
            return InteractionResult.FAIL;
        if (level().isClientSide)
            return InteractionResult.SUCCESS;
        entityData.set(DOOR_OPEN, next);
        syncDoorHosts();
        Vec3 at = toWorld(CabinetGeometry.interiorCentre(spec()));
        level().playSound(null, at.x, at.y, at.z, next ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                SoundSource.BLOCKS, 1, level().random.nextFloat() * 0.1f + 0.9f);
        return InteractionResult.CONSUME;
    }

    /**
     * Whether nothing solid, block or other device, is where the door would be
     * {@code open} or not.
     *
     * The cells its rails run through are left out: gear on the rails may stand
     * deeper than the room and poke through the closed door, which is allowed, and
     * would otherwise stop the door ever closing on it.
     */
    private boolean doorClear(boolean open) {
        double[] door = CabinetGeometry.door(spec(), open);
        AABB box = new AABB(toWorld(new Vec3(door[0], door[1], door[2])), toWorld(new Vec3(door[3], door[4], door[5])))
                .deflate(1e-6);
        if (!level().getEntityCollisions(this, box).isEmpty())
            return false;
        VoxelShape door3d = Shapes.create(box);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level().getBlockState(pos);
            // Its own rails, and the hosts of its door's controls, which go with the door
            if (state.getBlock() instanceof CabinetRailHostBlock || isDoorHost(pos))
                continue;
            VoxelShape shape = state.getCollisionShape(level(), pos);
            if (!shape.isEmpty()
                    && Shapes.joinIsNotEmpty(shape.move(pos.getX(), pos.getY(), pos.getZ()), door3d, BooleanOp.AND))
                return false;
        }
        return true;
    }

    // Breaking

    @Override
    public void breakBy(Player player) {
        breaker = player;
        super.breakBy(player);
    }

    /** Everything on its rails comes down with it, and the rails as items unless broken in creative. */
    @Override
    protected void onBroken() {
        dismantle(breaker == null || !breaker.getAbilities().instabuild);
    }

    /** Destroyed some other way than by breaking, a command say, it still lets go of its rails. */
    @Override
    protected void removed(boolean destroyed) {
        super.removed(destroyed);
        if (destroyed)
            dismantle(true);
    }

    private void dismantle(boolean dropRails) {
        if (dismantled || level().isClientSide)
            return;
        dismantled = true;
        CabinetRailService.dismantle(this, dropRails);
        // The door's controls come off with it: each host drops what it carries as it goes
        for (BlockPos pos : doorHosts)
            if (level().isLoaded(pos) && isDoorHost(pos))
                level().removeBlock(pos, false);
    }

    // Saving and spawning

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // Before the step, which makes the bounding box from the shape
        spec = CabinetSpec.load(tag.getCompound(CabinetSpec.TAG));
        entityData.set(DOOR_OPEN, tag.getBoolean("DoorOpen"));
        rails = loadRails(tag.getCompound("Rails"));
        entityData.set(RAILS, saveRails(rails));
        nextRailId = tag.getInt("NextRailId");
        cuts = loadCuts(tag.getCompound("Cuts"));
        entityData.set(CUTS, saveCuts(cuts));
        doorHosts = loadDoorHosts(tag.getCompound("DoorHosts"));
        entityData.set(DOOR_HOSTS, saveDoorHosts(doorHosts));
        walls = null;
        cutShapes[0] = cutShapes[1] = null;
        for (CabinetRail rail : rails)
            nextRailId = Math.max(nextRailId, rail.id() + 1);
        super.readAdditionalSaveData(tag);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put(CabinetSpec.TAG, spec().save());
        tag.putBoolean("DoorOpen", doorOpen());
        tag.put("Rails", saveRails(rails));
        tag.putInt("NextRailId", nextRailId);
        if (!cuts.isEmpty())
            tag.put("Cuts", saveCuts(cuts));
        if (!doorHosts.isEmpty())
            tag.put("DoorHosts", saveDoorHosts(doorHosts));
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        spec().write(buffer);
        buffer.writeBoolean(doorOpen());
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        spec = CabinetSpec.read(buffer);
        entityData.set(DOOR_OPEN, buffer.readBoolean());
        walls = null;
        cutShapes[0] = cutShapes[1] = null;
        shapeChanged();
    }
}
