package com.nectariich.create_flippies.content.modem;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;
import com.nectariich.create_flippies.content.computer.net.Modem;
import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortAddress;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceShape;
import com.nectariich.create_flippies.content.pc.PcMotherboardItem;
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A network modem in the world (spec 12.4.5): one ethernet port, powered over its cord by the
 * PC at the other end (PoE), serving the server's content packs ({@link ModemPacks}) and the world's
 * file share ({@code localfilesys.com}, the folder {@code <world>/localfilesys/}).
 *
 * The modem lives on the server only, in a {@link ModemDriver}: made when first needed and dropped
 * on unload; it keeps nothing worth saving (DHCP leases are asked for again, TCP connections drop,
 * the downloader resumes with Range). The PC side's exchange phase drives it once per tick
 * ({@link #exchange}); a modem no running PC drove in a tick has its link taken down
 * ({@code PcServer}). The lights follow from that and sync on change only.
 *
 * A {@link DeviceEntity}, placed as the CRT and the keyboard are: its -x end (the lights)
 * the front, the port on its +x end, as a PC case.
 */
public class NetworkModemEntity extends DeviceEntity implements DataPortHost {

    public static final DeviceShape SHAPE = new DeviceShape(
            new double[][] {ModemGeometry.BODY, ModemGeometry.POWER_LED, ModemGeometry.LINK_LED},
            new double[][] {ModemGeometry.portBox()}, -1);

    private static final EntityDataAccessor<Boolean> POWER_LIT =
            SynchedEntityData.defineId(NetworkModemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> LINK_LIT =
            SynchedEntityData.defineId(NetworkModemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> ACTIVITY =
            SynchedEntityData.defineId(NetworkModemEntity.class, EntityDataSerializers.BOOLEAN);

    // Saved

    private long mac;
    private DataLink link;

    // Server only

    private final ModemDriver driver = new ModemDriver(
            () -> new Modem(mac, catalogue()),
            e -> CreateFlippies.LOGGER.error("Network modem at {} failed; restarting it in 5 s", blockPosition(), e));

    public NetworkModemEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public DeviceShape shape() {
        return SHAPE;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.NETWORK_MODEM.get());
    }

    @Override
    public float hardness() {
        return 1.0f;
    }

    @Override
    public SoundType sound() {
        return SoundType.METAL;
    }

    @Override
    //? if forge {
    protected void defineSynchedData() {
        entityData.define(POWER_LIT, false);
        entityData.define(LINK_LIT, false);
        entityData.define(ACTIVITY, false);
    }
    //?} else {
    /*protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWER_LIT, false);
        builder.define(LINK_LIT, false);
        builder.define(ACTIVITY, false);
    }
    *///?}

    /** The world's catalogue: its file share and the content packs; the jar's built-in pages alone with no server running. */
    private static Catalogue catalogue() {
        PcServer server = PcServer.get();
        return server != null ? server.catalogue() : new Catalogue(ClasspathContent.bundled());
    }

    public long macAddress() {
        return mac;
    }

    public boolean powerLit() {
        return entityData.get(POWER_LIT);
    }

    public boolean linkLit() {
        return entityData.get(LINK_LIT);
    }

    /** Frames moved in the last few ticks: the link light blinks. */
    public boolean activity() {
        return entityData.get(ACTIVITY);
    }

    // The exchange phase (spec 11.2.10, 12.4.2), server thread only

    /**
     * One tick of the link: {@code pc} is the linked PC's card while that PC is loaded and
     * running, else null (the modem then has no power and its link goes down). A throw from the
     * modem's stack stops this modem alone ({@link ModemDriver}); one from the card is the PC's.
     */
    public void exchange(FrameEndpoint pc) {
        if (!driver.exchange(pc, level().getGameTime()))
            return;
        // Power and link are one here: the link is up exactly while the PC powers the modem
        entityData.set(POWER_LIT, driver.powered());
        entityData.set(LINK_LIT, driver.powered());
        entityData.set(ACTIVITY, driver.active());
    }

    /** The modem itself, for tests and diagnostics; null until a PC first drove it. */
    public Modem modem() {
        return driver.modem();
    }

    // Life

    /** Loaded or placed, ticking or not: a PC may drive it from the next tick on. */
    @Override
    protected void added() {
        if (level().isClientSide)
            return;
        if (mac == 0) {
            // Locally administered, like a motherboard's (spec 12.4.1), fixed from placement on
            mac = PcMotherboardItem.randomMac(level().random.nextLong());
        }
        PcServer server = PcServer.get();
        if (server != null)
            server.modemLoaded(this);
    }

    @Override
    protected void deviceTick() {
        if (!level().isClientSide && level().getGameTime() % 20 == 11)
            DataCords.validateLinks(this);
    }

    /** Unloaded or broken: the modem and its connections go; nothing is saved of them. */
    @Override
    protected void removed(boolean destroyed) {
        if (level().isClientSide)
            return;
        PcServer server = PcServer.get();
        if (server != null)
            server.modemUnloaded(this);
        driver.close();
    }

    /** Broken: its cord drops and the PC forgets it. Server only. */
    @Override
    protected void onBroken() {
        DataCords.breakAll(this);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong("Mac", mac);
        if (link != null)
            tag.put("Link", link.write());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        mac = tag.getLong("Mac");
        link = tag.contains("Link") ? DataLink.read(tag.getCompound("Link")) : null;
    }

    // Using it: only the data cord, on the port

    @Override
    public InteractionResult interactAt(Player player, Vec3 local, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof DataCordItem))
            return InteractionResult.PASS;
        int port = portAt(position().add(local));
        return port < 0 ? InteractionResult.PASS : DataCordItem.usePort(level(), player, held, this, port);
    }

    // Data port (spec 11.2.5)

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
        return ModemGeometry.PORTS;
    }

    @Override
    public PortType portType(int port) {
        return PortType.ETHERNET;
    }

    @Override
    public PortRole portRole(int port) {
        return PortRole.PEER;
    }

    @Override
    public Vec3 portOrigin(int port) {
        return toWorld(ModemGeometry.portFaceCentre());
    }

    @Override
    public AABB portBox(int port) {
        double[] b = ModemGeometry.portBox();
        return new AABB(toWorld(new Vec3(b[0], b[1], b[2])), toWorld(new Vec3(b[3], b[4], b[5])));
    }

    /** The port, if the point is on it in the model's own frame (the box above is only its turned bounds). */
    @Override
    public int portAt(Vec3 worldPoint) {
        return ModemGeometry.onPort(toModel(worldPoint)) ? ModemGeometry.ETHERNET : -1;
    }

    @Override
    public Vec3 portNormal(int port) {
        return directionToWorld(new Vec3(1, 0, 0));
    }

    @Override
    public DataLink link(int port) {
        return port == ModemGeometry.ETHERNET ? link : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port != ModemGeometry.ETHERNET)
            return;
        this.link = link;
    }
}
