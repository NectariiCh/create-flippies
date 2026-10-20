package com.nectariich.create_flippies.content.crt;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.content.pc.PcDisplayView;
import com.nectariich.create_flippies.content.pc.PcPower;
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

/**
 * A CRT monitor in the world (spec 11.2.14): one VGA input (a SINK data port) and
 * a Power Grid socket. It holds no machine state: the picture is read from the
 * linked case's display each tick by {@link CrtSync} and streamed to the players
 * near it; what is saved is the link and whether it was on.
 *
 * The case's VGA port decides the display: `vga1` is display 0, `vga2` 1, `vga3` 2.
 */
public class CrtBlockEntity extends ElectricBlockEntity implements DataPortHost, IHaveGoggleInformation {

    /** Ticks after loading during which the saved on state stands while Power Grid solves (as the case's). */
    private static final int RESTORE_GRACE = 40;
    /** Ticks between supply figure syncs while they change. */
    private static final int FIGURES_SYNC = 20;

    // Saved

    private DataLink link;
    private boolean on;

    // Server only

    /** Not initialized where declared: Power Grid builds the circuit from the superclass constructor. */
    private PcPower.Circuit circuit;
    private double volts;
    private int sinceLoad;
    private int ticks;
    private final CrtStream stream = new CrtStream();

    // Synced to clients

    private boolean signal;
    private boolean socketWired;
    private float shownVolts;
    /** Client: the game time the screen last came on, for the warm-up. */
    private long onSince = Long.MIN_VALUE;
    /** Client: whether a sync tag was read before, so a CRT that is simply loaded does not warm up. */
    private boolean synced;

    public CrtBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRT.get(), pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        circuit = PcPower.buildCircuit(builder);
    }

    public Direction facing() {
        return getBlockState().getValue(CrtBlock.FACING);
    }

    public boolean on() {
        return on;
    }

    /** Whether a picture comes in: on, corded to a case port whose PC runs. Synced. */
    public boolean signal() {
        return signal;
    }

    public boolean socketWired() {
        return socketWired;
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
        if (!on || link == null || level == null || level.isClientSide || !level.isLoaded(link.peerPos()))
            return null;
        if (!(level.getBlockEntity(link.peerPos()) instanceof PcCaseBlockEntity pc))
            return null;
        int port = link.peerPort();
        if (port < PcCaseGeometry.VGA1 || port > PcCaseGeometry.VGA3
                || !link.pairsWith(worldPosition, CrtGeometry.VGA_IN, pc.link(port)))
            return null;
        return pc.display(port - PcCaseGeometry.VGA1);
    }

    // Ticking

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        ticks++;
        sinceLoad++;
        if (circuit != null)
            volts = circuit.volts();

        boolean next = CrtPower.nextOn(on, volts);
        // Just loaded: the network may not have solved yet and read 0 V, so a CRT that was on stays on
        if (sinceLoad < RESTORE_GRACE)
            next |= on;
        boolean nextSignal = next && source() != null;
        if (next != on || nextSignal != signal) {
            if (next != on)
                setChanged();
            on = next;
            signal = nextSignal;
            sendData();
        }

        if (circuit != null) {
            double ohms = CrtPower.loadOhms(volts, on, signal);
            if (PcPower.outsideDeadband(circuit.load().getResistance(), ohms))
                circuit.load().setResistance(ohms);
        }

        boolean wired = socketHasCord();
        if (wired != socketWired) {
            socketWired = wired;
            sendData();
        }
        if (ticks % FIGURES_SYNC == 0) {
            float v = (float) Math.round(volts);
            if (v != shownVolts) {
                shownVolts = v;
                sendData();
            }
        }
        if (ticks % 20 == 13)
            DataCords.validateLinks(this);
    }

    private boolean socketHasCord() {
        var connections = getElectricBehaviour().getConnections();
        return hasWire(connections.get(new BlockWireEndpoint(worldPosition, 0)))
                && hasWire(connections.get(new BlockWireEndpoint(worldPosition, 1)));
    }

    private static boolean hasWire(Set<BaseWireEntity> wires) {
        return wires != null && !wires.isEmpty();
    }

    // Loading and unloading

    @Override
    public void onLoad() {
        super.onLoad();
        sinceLoad = 0;
        if (level != null && !level.isClientSide && PcServer.get() != null)
            PcServer.get().crts().add(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    /** Removed or unloaded (Create calls this from {@code setRemoved}). */
    @Override
    public void invalidate() {
        super.invalidate();
        forget();
    }

    /** Off the server's list, or on a client, the texture freed. */
    private void forget() {
        if (level == null)
            return;
        if (level.isClientSide) {
            BlockPos pos = worldPosition;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.pc.CrtClient.release(pos));
        } else if (PcServer.get() != null) {
            PcServer.get().crts().remove(this);
        }
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putBoolean("On", on);
        if (clientPacket) {
            tag.putBoolean("Signal", signal);
            tag.putBoolean("Socket", socketWired);
            tag.putFloat("Volts", shownVolts);
            return;
        }
        if (link != null)
            tag.put("Link", link.write());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        boolean wasOn = on, hadSignal = signal;
        on = tag.getBoolean("On");
        if (clientPacket) {
            signal = tag.getBoolean("Signal");
            // Lost the picture: the texture goes. Not on gaining it, as the first frame may already be here
            if (hadSignal && !signal)
                forget();
            socketWired = tag.getBoolean("Socket");
            shownVolts = tag.getFloat("Volts");
            if (synced && on && !wasOn && level != null)
                onSince = level.getGameTime();
            synced = true;
            return;
        }
        link = tag.contains("Link") ? DataLink.read(tag.getCompound("Link")) : null;
    }

    // The port (spec 11.2.5)

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
        return Vec3.atLowerCornerOf(worldPosition)
                .add(CrtGeometry.toBlock(CrtGeometry.backFaceCentre(CrtGeometry.VGA_PORT), facing()));
    }

    @Override
    public AABB portBox(int port) {
        return CrtGeometry.toBlock(CrtGeometry.VGA_PORT, facing()).move(worldPosition);
    }

    @Override
    public Vec3 portNormal(int port) {
        return Vec3.atLowerCornerOf(CrtGeometry.back(facing()).getNormal());
    }

    @Override
    public DataLink link(int port) {
        return port == CrtGeometry.VGA_IN ? link : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port != CrtGeometry.VGA_IN)
            return;
        this.link = link;
        setChanged();
        sendData();
    }

    // Goggles

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.create_flippies.crt")));
        String state = !on ? "off" : signal ? "picture" : "no_signal";
        tooltip.add(Component.literal("    ").append(Component.translatable("gui.create_flippies.crt.goggles.state",
                Component.translatable("gui.create_flippies.crt.state." + state)).withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable("gui.create_flippies.pc.goggles.supply",
                String.format(Locale.ROOT, "%.0f", shownVolts),
                String.format(Locale.ROOT, "%.0f", CrtPower.inputWatts(on, signal))).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(0.25);
    }
}
