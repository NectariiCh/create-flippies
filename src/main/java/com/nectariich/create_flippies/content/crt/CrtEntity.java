package com.nectariich.create_flippies.content.crt;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortAddress;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceShape;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.content.pc.PcDisplayView;
import com.nectariich.create_flippies.content.pc.PcPower;
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.foundation.wire.entity.EntityCordEndpoint;
import com.nectariich.create_flippies.foundation.wire.entity.EntityElectricHolder;
import com.nectariich.create_flippies.foundation.wire.entity.EntityElectricHost;
import com.nectariich.create_flippies.mixin.CordItemAccessor;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.IWire;

/**
 * A CRT monitor in the world (spec 11.2.14): one VGA input (a SINK data port) and
 * a Power Grid socket. It holds no machine state: the picture is read from the
 * linked case's display each tick by {@link CrtSync} and streamed to the players
 * near it; what is saved is the link and whether it was on.
 *
 * A {@link DeviceEntity}, the model `crt-1.json` with its screen (+x) the front.
 * The socket takes Power Grid's copper cord with one click, as the case's does; the
 * cord ends on this entity through {@link EntityCordEndpoint}, and the supply's two
 * terminals and load are an {@link EntityElectricHolder}.
 *
 * The case's VGA port decides the display: `vga1` is display 0, `vga2` 1, `vga3` 2.
 */
public class CrtEntity extends DeviceEntity implements DataPortHost, EntityElectricHost {

    public static final DeviceShape SHAPE = new DeviceShape(new double[][] {CrtGeometry.BODY},
            new double[][] {CrtGeometry.VGA_PORT, CrtGeometry.SOCKET}, 1);

    /** Ticks after loading during which the saved on state stands while Power Grid solves (as the case's). */
    private static final int RESTORE_GRACE = 40;
    /** Ticks between supply figure syncs while they change. */
    private static final int FIGURES_SYNC = 20;

    private static final EntityDataAccessor<Boolean> ON =
            SynchedEntityData.defineId(CrtEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SIGNAL =
            SynchedEntityData.defineId(CrtEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SOCKET =
            SynchedEntityData.defineId(CrtEntity.class, EntityDataSerializers.BOOLEAN);

    // Saved (with ON)

    private DataLink link;

    // Server only

    private final EntityElectricHolder electric;
    private PcPower.Circuit circuit;
    private double volts;
    private int sinceLoad;
    private final CrtStream stream = new CrtStream();

    /** Client: the game time the screen last came on, for the warm-up. */
    private long onSince = Long.MIN_VALUE;

    public CrtEntity(EntityType<?> type, Level level) {
        super(type, level);
        electric = new EntityElectricHolder(this, PcPower.TERMINALS, builder -> circuit = PcPower.buildCircuit(builder));
    }

    @Override
    public DeviceShape shape() {
        return SHAPE;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.CRT.get());
    }

    @Override
    public float hardness() {
        return 1.5f;
    }

    @Override
    public SoundType sound() {
        return SoundType.METAL;
    }

    @Override
    //? if forge {
    protected void defineSynchedData() {
        entityData.define(ON, false);
        entityData.define(SIGNAL, false);
        entityData.define(SOCKET, false);
    }
    //?} else {
    /*protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ON, false);
        builder.define(SIGNAL, false);
        builder.define(SOCKET, false);
    }
    *///?}

    public boolean on() {
        return entityData.get(ON);
    }

    /** Whether a picture comes in: on, corded to a case port whose PC runs. Synced. */
    public boolean signal() {
        return entityData.get(SIGNAL);
    }

    public boolean socketWired() {
        return entityData.get(SOCKET);
    }

    /** Client: the game time the screen came on, or {@code Long.MIN_VALUE}. */
    public long onSince() {
        return onSince;
    }

    CrtStream stream() {
        return stream;
    }

    /**
     * The display this CRT shows, or null: off, no cord, the case not loaded or not
     * holding the link back, or its PC not running. Server only.
     */
    public PcDisplayView source() {
        if (!on() || level().isClientSide)
            return null;
        PcCaseBlockEntity pc = DataCords.peer(this, CrtGeometry.VGA_IN, PcCaseBlockEntity.class);
        if (pc == null)
            return null;
        int port = link.peerPort();
        if (port < PcCaseGeometry.VGA1 || port > PcCaseGeometry.VGA3)
            return null;
        return pc.display(port - PcCaseGeometry.VGA1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (!level().isClientSide)
            return;
        // Data that comes with the entity as it appears is not a change: a CRT that is simply seen does not warm up
        if (accessor == ON && on() && tickCount > 1)
            onSince = level().getGameTime();
        if (accessor == SIGNAL && !signal())
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.pc.CrtClient.release(getId()));
    }

    // Ticking

    @Override
    protected void deviceTick() {
        if (level().isClientSide)
            return;
        sinceLoad++;
        if (circuit != null)
            volts = circuit.volts();

        boolean on = on();
        boolean next = CrtPower.nextOn(on, volts);
        // Just loaded: the network may not have solved yet and read 0 V, so a CRT that was on stays on
        if (sinceLoad < RESTORE_GRACE)
            next |= on;
        entityData.set(ON, next);
        entityData.set(SIGNAL, next && source() != null);

        if (circuit != null) {
            double ohms = CrtPower.loadOhms(volts, next, signal());
            if (PcPower.outsideDeadband(circuit.load().getResistance(), ohms))
                circuit.load().setResistance(ohms);
        }
        entityData.set(SOCKET, electric.wired(PcPower.TERMINAL_A, PcPower.TERMINAL_B));
        if (tickCount % 20 == 13)
            DataCords.validateLinks(this);
    }

    // Loading, unloading, breaking

    @Override
    protected void added() {
        electric.added();
        sinceLoad = 0;
        if (!level().isClientSide && PcServer.get() != null)
            PcServer.get().crts().add(this);
    }

    @Override
    protected void removed(boolean destroyed) {
        if (destroyed)
            electric.remove();
        else
            electric.unload();
        if (level().isClientSide) {
            int id = getId();
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.pc.CrtClient.release(id));
        } else if (PcServer.get() != null) {
            PcServer.get().crts().remove(this);
        }
    }

    @Override
    protected void onBroken() {
        DataCords.breakAll(this);
    }

    // Using it: the data cord on the VGA port, Power Grid's cord on the socket

    @Override
    public InteractionResult interactAt(Player player, Vec3 local, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        Vec3 world = position().add(local);
        if (held.getItem() instanceof DataCordItem) {
            int port = portAt(world);
            return port < 0 ? InteractionResult.PASS : DataCordItem.usePort(level(), player, held, this, port);
        }
        if (IWire.isCord(level(), held.getItem())) {
            if (!CrtGeometry.onSocket(toModel(world)))
                return InteractionResult.PASS;
            // The server keeps the cord's first end in the stack and syncs it back
            if (level().isClientSide)
                return InteractionResult.SUCCESS;
            EntityCordEndpoint end = new EntityCordEndpoint(getUUID(), blockPosition(),
                    toWorld(CrtGeometry.backFaceCentre(CrtGeometry.POWER_PLUG)));
            UseOnContext context = new UseOnContext(player, hand,
                    new BlockHitResult(world, Direction.UP, blockPosition(), false));
            return CordItemAccessor.create_flippies$addEndpoint(context, end);
        }
        return InteractionResult.PASS;
    }

    // Power Grid

    @Override
    public EntityElectricHolder electric() {
        return electric;
    }

    /** The terminals are inside the cabinet, as the case's are; the cord ends at the plug. */
    @Override
    public Vec3 terminalPosition(int terminal) {
        double[] b = CrtGeometry.HIDDEN_TERMINAL;
        return toWorld(new Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
    }

    // The port (spec 11.2.5)

    @Override
    public PortAddress portAddress() {
        return PortAddress.entity(blockPosition(), getUUID());
    }

    @Override
    public Level hostLevel() {
        return level();
    }

    @Override
    public int dataPortCount() {
        return CrtGeometry.PORTS;
    }

    @Override
    public PortType portType(int port) {
        return PortType.VGA;
    }

    @Override
    public PortRole portRole(int port) {
        return PortRole.SINK;
    }

    @Override
    public Vec3 portOrigin(int port) {
        return toWorld(CrtGeometry.backFaceCentre(CrtGeometry.VGA_PORT));
    }

    @Override
    public AABB portBox(int port) {
        double[] b = CrtGeometry.VGA_PORT;
        return new AABB(toWorld(new Vec3(b[0], b[1], b[2])), toWorld(new Vec3(b[3], b[4], b[5])));
    }

    @Override
    public int portAt(Vec3 worldPoint) {
        return CrtGeometry.onVgaPort(toModel(worldPoint)) ? CrtGeometry.VGA_IN : -1;
    }

    @Override
    public Vec3 portNormal(int port) {
        return directionToWorld(new Vec3(-1, 0, 0));
    }

    @Override
    public DataLink link(int port) {
        return port == CrtGeometry.VGA_IN ? link : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port == CrtGeometry.VGA_IN)
            this.link = link;
    }

    // Saving

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(ON, tag.getBoolean("On"));
        link = tag.contains("Link") ? DataLink.read(tag.getCompound("Link")) : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("On", on());
        if (link != null)
            tag.put("Link", link.write());
    }
}
