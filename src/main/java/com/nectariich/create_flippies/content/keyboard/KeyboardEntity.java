package com.nectariich.create_flippies.content.keyboard;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
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
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.nbt.CompoundTag;
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
 * A keyboard (spec 11.2.12): one PS/2 port, DEVICE end, and nothing else of its
 * own. It has no power: it is bus powered from the port, so typing reaches a PC
 * only while a cord joins it to a case's PS/2 port. Right-clicking it with an empty
 * hand asks to type on it.
 *
 * A {@link DeviceEntity}: the model `keyboard-1.json`, its near (-x) edge the front,
 * towards the player who placed it; the port is on its +x side. It needs something
 * under it, as the block did, and drops as an item when that goes.
 *
 * {@link KeyboardMouseEntity} adds a second port for a mouse; the ports here are
 * written for any count, each cube from {@link #portCubes()}.
 */
public class KeyboardEntity extends DeviceEntity implements DataPortHost {

    /** The model's `ps/2` cube, px. */
    static final double[] PORT = KeyboardGeometry.PORT;
    private static final double[][] PORTS = {PORT};
    /** The body, px. */
    static final double[] BODY = KeyboardGeometry.BODY;

    public static final DeviceShape SHAPE = new DeviceShape(new double[][] {BODY}, new double[][] {portBox()}, -1);

    /** Ticks between the checks that it still stands on something. */
    private static final int SUPPORT_CHECK = 20;

    private final DataLink[] links = new DataLink[portCubes().length];

    public KeyboardEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    static double[] portBox(double[] cube) {
        return KeyboardGeometry.portBox(cube);
    }

    static double[] portBox() {
        return portBox(PORT);
    }

    /** The model's port cubes, px, by port number. */
    protected double[][] portCubes() {
        return PORTS;
    }

    /** What sits behind port {@code port}: a keyboard here. */
    public Ps2DeviceKind deviceKind(int port) {
        return port == 0 ? Ps2DeviceKind.KEYBOARD : Ps2DeviceKind.NONE;
    }

    @Override
    public DeviceShape shape() {
        return SHAPE;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.KEYBOARD.get());
    }

    @Override
    public float hardness() {
        return 0.8f;
    }

    @Override
    public SoundType sound() {
        return SoundType.STONE;
    }

    @Override
    protected void deviceTick() {
        if (level().isClientSide)
            return;
        if (tickCount % 20 == 11)
            DataCords.validateLinks(this);
        if (tickCount % SUPPORT_CHECK == 0 && !supported())
            breakBy(null);
    }

    /** Whether anything solid is right under it. */
    private boolean supported() {
        for (AABB box : collisionShape().toAabbs()) {
            AABB below = new AABB(box.minX, box.minY - 1 / 32.0, box.minZ, box.maxX, box.minY, box.maxZ).deflate(1e-4,
                    0, 1e-4);
            if (!level().noCollision(this, below))
                return true;
        }
        return false;
    }

    // Using it

    @Override
    public InteractionResult interactAt(Player player, Vec3 local, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] interactAt {} client={} hand={} held={}", getType(), level().isClientSide, hand, held);  // DEBUG kbm
        Vec3 world = position().add(local);
        if (held.getItem() instanceof DataCordItem) {
            int port = portAt(world);
            return port < 0 ? InteractionResult.PASS : DataCordItem.usePort(level(), player, held, this, port);
        }
        if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        if (level().isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.pc.KeyboardClient.requestCapture(getId()));
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    protected void onBroken() {
        DataCords.breakAll(this);
    }

    @Override
    protected void removed(boolean destroyed) {
        PcServer server = PcServer.get();
        if (!level().isClientSide && server != null)
            server.captures().endAt(this, destroyed ? "removed" : "unloaded");
    }

    // The port

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
        return links.length;
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
        double[] c = portCubes()[port];
        return toWorld(new Vec3(c[3], (c[1] + c[4]) / 2, (c[2] + c[5]) / 2));
    }

    @Override
    public AABB portBox(int port) {
        double[] b = portBox(portCubes()[port]);
        return new AABB(toWorld(new Vec3(b[0], b[1], b[2])), toWorld(new Vec3(b[3], b[4], b[5])));
    }

    /** The port, if the point is on it in the model's own frame (the box above is only its turned bounds). */
    @Override
    public int portAt(Vec3 worldPoint) {
        Vec3 m = toModel(worldPoint);
        double[][] cubes = portCubes();
        double e = 1e-2;
        for (int port = 0; port < cubes.length; port++) {
            double[] b = portBox(cubes[port]);
            if (m.x >= b[0] - e && m.x <= b[3] + e && m.y >= b[1] - e && m.y <= b[4] + e && m.z >= b[2] - e
                    && m.z <= b[5] + e)
                return port;
        }
        return -1;
    }

    @Override
    public Vec3 portNormal(int port) {
        return directionToWorld(new Vec3(1, 0, 0));
    }

    @Override
    public DataLink link(int port) {
        return port >= 0 && port < links.length ? links[port] : null;
    }

    @Override
    public void setLink(int port, DataLink link) {
        if (port >= 0 && port < links.length)
            links[port] = link;
    }

    /** The case this keyboard's cord leads to, if it is loaded and holds the link back. */
    public PcCaseBlockEntity linkedCase() {
        return linkedCase(0);
    }

    /** The case port {@code port}'s cord leads to, if it is loaded and holds the link back. */
    public PcCaseBlockEntity linkedCase(int port) {
        return DataCords.peer(this, port, PcCaseBlockEntity.class);
    }

    /** The case's PS/2 controller port this keyboard is on: case `ps/2 1` is 0, `ps/2 2` is 1. */
    public int linkedPs2Port() {
        return linkedPs2Port(0);
    }

    /** The case's PS/2 controller port that port {@code port}'s cord is on, or -1. */
    public int linkedPs2Port(int port) {
        DataLink link = link(port);
        return link == null ? -1 : link.peerPort() - PcCaseGeometry.PS2_1;
    }

    /** Whether any of its ports leads to a case. */
    public boolean linkedToAnyCase() {
        for (int port = 0; port < links.length; port++)
            if (linkedCase(port) != null)
                return true;
        return false;
    }

    // Saving

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        for (int port = 0; port < links.length; port++) {
            String key = linkKey(port);
            links[port] = tag.contains(key) ? DataLink.read(tag.getCompound(key)) : null;
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        for (int port = 0; port < links.length; port++)
            if (links[port] != null)
                tag.put(linkKey(port), links[port].write());
    }

    /** Port 0 keeps the keyboard's original "Link", so saved keyboards load. */
    private static String linkKey(int port) {
        return port == 0 ? "Link" : "Link" + port;
    }
}
