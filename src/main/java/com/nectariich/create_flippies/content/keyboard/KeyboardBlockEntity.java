package com.nectariich.create_flippies.content.keyboard;

import java.util.List;

import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.data_cord.DataPortHost;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A keyboard (spec 11.2.12): one PS/2 port, DEVICE end, and nothing else of its
 * own. It has no power: it is bus powered from the port, so typing reaches a PC
 * only while a cord joins it to a case's PS/2 port.
 *
 * The model `keyboard-1.json` is turned like the case's: its -x edge (the near
 * side) faces {@code facing}, the port is on its +x side.
 */
public class KeyboardBlockEntity extends SmartBlockEntity implements DataPortHost {

    /** The model's `ps/2` cube, px. */
    static final double[] PORT = {7, 0.25, 0.25, 7.25, 0.75, 0.75};
    /** The body, px. */
    static final double[] BODY = {0, 0, 0, 7, 1, 16};

    private DataLink link;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.KEYBOARD.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    public Direction facing() {
        return getBlockState().getValue(KeyboardBlock.FACING);
    }

    /** The port's cube grown 0.375 px outward, as the case's ports are. */
    static double[] portBox() {
        double[] b = PORT.clone();
        b[3] += PcCaseGeometry.PORT_GROW;
        return b;
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide && level.getGameTime() % 20 == 11)
            DataCords.validateLinks(this);
    }

    // The port

    @Override
    public int dataPortCount() {
        return 1;
    }

    @Override
    public PortType portType(int port) {
        return PortType.PS2;
    }

    @Override
    public PortRole portRole(int port) {
        return PortRole.DEVICE;
    }

    @Override
    public Vec3 portOrigin(int port) {
        Vec3 face = new Vec3(PORT[3], (PORT[1] + PORT[4]) / 2, (PORT[2] + PORT[5]) / 2);
        return Vec3.atLowerCornerOf(worldPosition).add(PcCaseGeometry.toBlock(face, facing()));
    }

    @Override
    public AABB portBox(int port) {
        return PcCaseGeometry.toBlock(portBox(), facing()).move(worldPosition);
    }

    @Override
    public Vec3 portNormal(int port) {
        return Vec3.atLowerCornerOf(facing().getOpposite().getNormal());
    }

    @Override
    public DataLink link(int port) {
        return port == 0 ? link : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port != 0)
            return;
        this.link = link;
        setChanged();
        sendData();
    }

    /** The case this keyboard's cord leads to, if it is loaded and holds the link back. */
    public PcCaseBlockEntity linkedCase() {
        if (link == null || level == null || !level.isLoaded(link.peerPos()))
            return null;
        if (!(level.getBlockEntity(link.peerPos()) instanceof PcCaseBlockEntity pc))
            return null;
        return link.pairsWith(worldPosition, 0, pc.link(link.peerPort())) ? pc : null;
    }

    /** The case's PS/2 controller port this keyboard is on: case `ps/2 1` is 0, `ps/2 2` is 1. */
    public int linkedPs2Port() {
        return link == null ? -1 : link.peerPort() - PcCaseGeometry.PS2_1;
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (link != null)
            tag.put("Link", link.write());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        link = tag.contains("Link") ? DataLink.read(tag.getCompound("Link")) : null;
    }
}
