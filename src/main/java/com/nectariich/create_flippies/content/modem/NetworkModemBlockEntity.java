package com.nectariich.create_flippies.content.modem;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;
import com.nectariich.create_flippies.content.computer.net.Modem;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortAddress;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.content.pc.PcMotherboardItem;
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A network modem in the world (spec 12.4.5): one ethernet port, powered over its cord by the
 * PC at the other end (PoE), serving the catalogue bundled in the jar and the world's file share
 * ({@code localfilesys.com}, the folder {@code <world>/localfilesys/}).
 *
 * The modem lives on the server only, in a {@link ModemDriver}: made when first needed and dropped
 * on unload; it keeps nothing worth saving (DHCP leases are asked for again, TCP connections drop,
 * the downloader resumes with Range). The PC side's exchange phase drives it once per tick
 * ({@link #exchange}); a modem no running PC drove in a tick has its link taken down
 * ({@code PcServer}). The lights follow from that and sync on change only.
 */
public class NetworkModemBlockEntity extends SmartBlockEntity implements DataPortHost {

    // Saved

    private long mac;
    private DataLink link;

    // Server only

    private final ModemDriver driver = new ModemDriver(
            () -> new Modem(mac, catalogue()),
            e -> CreateFlippies.LOGGER.error("Network modem at {} failed; restarting it in 5 s", worldPosition, e));

    // Synced to clients

    private boolean powerLit;
    private boolean linkLit;
    private boolean activity;

    public NetworkModemBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.NETWORK_MODEM.get(), pos, state);
    }

    protected NetworkModemBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** The world's catalogue: its file share and the bundled hosts; the bundled ones alone with no server running. */
    private static Catalogue catalogue() {
        PcServer server = PcServer.get();
        return server != null ? server.catalogue() : new Catalogue(ClasspathContent.bundled());
    }

    public Direction facing() {
        return getBlockState().getValue(NetworkModemBlock.FACING);
    }

    public long macAddress() {
        return mac;
    }

    public boolean powerLit() {
        return powerLit;
    }

    public boolean linkLit() {
        return linkLit;
    }

    /** Frames moved in the last few ticks: the link light blinks. */
    public boolean activity() {
        return activity;
    }

    // The exchange phase (spec 11.2.10, 12.4.2), server thread only

    /**
     * One tick of the link: {@code pc} is the linked PC's card while that PC is loaded and
     * running, else null (the modem then has no power and its link goes down). A throw from the
     * modem's stack stops this modem alone ({@link ModemDriver}); one from the card is the PC's.
     */
    public void exchange(FrameEndpoint pc) {
        if (!driver.exchange(pc, level == null ? 0 : level.getGameTime()))
            return;
        // Power and link are one here: the link is up exactly while the PC powers the modem
        powerLit = driver.powered();
        linkLit = driver.powered();
        activity = driver.active();
        sendData();
    }

    /** The modem itself, for tests and diagnostics; null until a PC first drove it. */
    public Modem modem() {
        return driver.modem();
    }

    // Life

    /** Loaded or placed, ticking or not: a PC may drive it from the next tick on. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide)
            return;
        if (mac == 0) {
            // Locally administered, like a motherboard's (spec 12.4.1), fixed from placement on
            mac = PcMotherboardItem.randomMac(level.random.nextLong());
            setChanged();
        }
        PcServer server = PcServer.get();
        if (server != null)
            server.modemLoaded(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide && level.getGameTime() % 20 == 11)
            DataCords.validateLinks(this);
    }

    /** Unloaded or broken: the modem and its connections go; nothing is saved of them. */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    @Override
    public void remove() {
        super.remove();
        forget();
    }

    private void forget() {
        if (level == null || level.isClientSide)
            return;
        PcServer server = PcServer.get();
        if (server != null)
            server.modemUnloaded(this);
        driver.close();
    }

    /** Broken: its cord drops and the PC forgets it. Server only. */
    void dropContents() {
        DataCords.breakAll(this);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (clientPacket) {
            tag.putBoolean("PowerLit", powerLit);
            tag.putBoolean("LinkLit", linkLit);
            tag.putBoolean("Activity", activity);
            return;
        }
        tag.putLong("Mac", mac);
        if (link != null)
            tag.put("Link", link.write());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        if (clientPacket) {
            powerLit = tag.getBoolean("PowerLit");
            linkLit = tag.getBoolean("LinkLit");
            activity = tag.getBoolean("Activity");
            return;
        }
        mac = tag.getLong("Mac");
        link = tag.contains("Link") ? DataLink.read(tag.getCompound("Link")) : null;
    }

    // Data port (spec 11.2.5)

    @Override
    public PortAddress portAddress() {
        return PortAddress.block(worldPosition);
    }

    @Override
    public Level hostLevel() {
        return level;
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
        return Vec3.atLowerCornerOf(worldPosition).add(PcCaseGeometry.toBlock(ModemGeometry.portFaceCentre(), facing()));
    }

    @Override
    public AABB portBox(int port) {
        return PcCaseGeometry.toBlock(ModemGeometry.portBox(), facing()).move(worldPosition);
    }

    @Override
    public Vec3 portNormal(int port) {
        return Vec3.atLowerCornerOf(PcCaseGeometry.back(facing()).getNormal());
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
        setChanged();
        sendData();
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(0.25);
    }
}
