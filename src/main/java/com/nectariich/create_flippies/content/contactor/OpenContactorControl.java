package com.nectariich.create_flippies.content.contactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.din_rail.RailNeighbours;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.PressableControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One module of an open contactor on a rail; see {@link OpenContactorType}.
 *
 * <h2>The assembly</h2>
 *
 * Nothing records which modules belong together: each tick every module asks the
 * rail what is beside it ({@link RailNeighbours}). The module with no module on
 * its left leads its assembly. On the server, the leader alone decides whether the
 * assembly is in — so the coil's chance of letting go is rolled once, not once a
 * module — from whether any module's plunger is held by hand and the strongest
 * coil terminal at either end, by {@link ContactorCoil}'s rule, and sets every
 * module to that. A hand push counts as pulled in, so a coil holding at 30 % keeps
 * an assembly in once a hand has pushed it there.
 *
 * <h2>The circuit</h2>
 *
 * Four switches, in this order because Power Grid streams their states
 * positionally: C1–NO1, C2–NO2 (closed while in), C3–NC3, C4–NC4 (closed while
 * out).
 *
 * <h2>The hand</h2>
 *
 * Held with the use key, the indicator buttons' way ({@link PressableControl}): in
 * for as long as the key is down, and let go by the server itself if the holder
 * leaves. The holder is never saved.
 *
 * <h2>What is saved</h2>
 *
 * Whether it is pulled in, which is also what the client draws the plunger from.
 */
public class OpenContactorControl extends HostedControl implements PressableControl {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".contactor_open.goggles.";

    /** Further than an assembly could reasonably run, so a walk always ends. */
    static final int MAX_ASSEMBLY = 64;

    /** How far a holder may be before the plunger springs back, the indicator buttons' reach. */
    private static final double MAX_REACH = 8;

    /** How much of the remaining travel the plunger covers a tick, on the client. */
    private static final float PLUNGER_EASING = 0.7f;

    private boolean pulled;
    private UUID holder;

    /** Client-side, for the renderer. */
    private OpenContactorPiece piece = OpenContactorPiece.SINGLE;
    public float plunger;
    public float prevPlunger;

    private IElectricNode[] nodes;
    private SwitchedWire[] contacts;

    OpenContactorControl(OpenContactorType type) {
        super(type);
    }

    public boolean pulled() {
        return pulled;
    }

    public boolean held() {
        return holder != null;
    }

    public OpenContactorPiece piece() {
        return piece;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(OpenContactorGeometry.MODULE_TERMINALS);
        nodes = new IElectricNode[OpenContactorGeometry.MODULE_TERMINALS];
        for (int i = 0; i < nodes.length; i++)
            nodes[i] = builder.terminalNode(i);
        // The order below is the order Power Grid streams these to the client in
        contacts = new SwitchedWire[4];
        for (int pair = 0; pair < 4; pair++)
            contacts[pair] = builder.connectSwitch((float) OpenContactorType.CONTACT_OHMS, nodes[2 * pair],
                    nodes[2 * pair + 1], false);
        applyContacts();
    }

    private void applyContacts() {
        if (contacts == null)
            return;
        for (int pair = 0; pair < 4; pair++)
            contacts[pair].setState(OpenContactorGeometry.normallyOpen(pair) == pulled);
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /** The voltage across a pair, C minus its partner. */
    double across(int pair) {
        return nodes == null ? 0 : voltage(nodes[2 * pair]) - voltage(nodes[2 * pair + 1]);
    }

    /** The current through the most loaded closed pair, worked out from the voltage across it. */
    public double loadAmps() {
        double worst = 0;
        for (int pair = 0; pair < 4; pair++)
            if (pair < 2 == pulled)
                worst = Math.max(worst, Math.abs(across(pair)) / OpenContactorType.CONTACT_OHMS);
        return worst;
    }

    /** The highest voltage standing across an open pair. */
    public double openVolts() {
        double worst = 0;
        for (int pair = 0; pair < 4; pair++)
            if (pair < 2 != pulled)
                worst = Math.max(worst, Math.abs(across(pair)));
        return worst;
    }

    // ------------------------------------------------------------ the rail ---

    private Level level() {
        return host() == null ? null : host().level();
    }

    /** The control one module to {@code side} on the rail, or null. */
    HostedControl beside(int side) {
        Level level = level();
        return level == null ? null : RailNeighbours.beside(level, host().pos(), mount(), OpenContactorGeometry.MODULE_WIDTH, side);
    }

    /** This module and every one to its right, while they are modules. */
    List<OpenContactorControl> assemblyFrom() {
        List<OpenContactorControl> modules = new ArrayList<>();
        OpenContactorControl next = this;
        while (next != null && modules.size() < MAX_ASSEMBLY && !modules.contains(next)) {
            modules.add(next);
            next = next.beside(+1) instanceof OpenContactorControl module ? module : null;
        }
        return modules;
    }

    /** How many modules the assembly this one belongs to has, walking both ways. */
    int assemblySize() {
        OpenContactorControl first = this;
        for (int i = 0; i < MAX_ASSEMBLY && first.beside(-1) instanceof OpenContactorControl left; i++)
            first = left;
        return first.assemblyFrom().size();
    }

    // ---------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        Level level = level();
        if (level == null)
            return;
        boolean left = beside(-1) instanceof OpenContactorControl;
        if (level.isClientSide) {
            piece = OpenContactorPiece.of(left, beside(+1) instanceof OpenContactorControl);
            prevPlunger = plunger;
            plunger += ((pulled ? 1 : 0) - plunger) * PLUNGER_EASING;
            return;
        }
        if (holder != null && !holderPresent(level))
            holder = null;
        if (!left)
            lead(level);
    }

    /** Server-side, on the leader: decide for the whole assembly. */
    private void lead(Level level) {
        List<OpenContactorControl> modules = assemblyFrom();
        boolean hand = false;
        for (OpenContactorControl module : modules)
            hand |= module.held();
        double fraction = Math.max(coilFraction(beside(-1)),
                coilFraction(modules.get(modules.size() - 1).beside(+1)));
        boolean next = hand || ContactorCoil.decide(pulled, fraction, level.random.nextDouble());
        boolean moved = false;
        for (OpenContactorControl module : modules)
            moved |= module.set(next);
        if (moved)
            level.playSound(null, host().pos(), ModSounds.RELAY_CLICK.get(), SoundSource.BLOCKS, 1.0f,
                    next ? 0.6f : 0.5f);
    }

    private static double coilFraction(HostedControl control) {
        return control instanceof OpenContactorCoilControl coil ? Math.abs(coil.coilFraction()) : 0;
    }

    /** @return whether it moved */
    boolean set(boolean pulled) {
        if (this.pulled == pulled)
            return false;
        this.pulled = pulled;
        applyContacts();
        changed();
        return true;
    }

    // ------------------------------------------------------------ the hand ---

    @Override
    public boolean pressableAt(Vec3 local) {
        if (mount() == null)
            return false;
        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        for (double[] box : OpenContactorGeometry.moduleBody()) {
            double[] b = mount().box(box);
            if (new AABB(b[0] / scale, b[1] / scale, b[2] / scale, b[3] / scale, b[4] / scale, b[5] / scale)
                    .inflate(1.0e-3).contains(local))
                return true;
        }
        return false;
    }

    @Override
    public void press(Player player) {
        holder = player.getUUID();
    }

    @Override
    public void release(Player player) {
        if (player.getUUID().equals(holder))
            holder = null;
    }

    private boolean holderPresent(Level level) {
        if (!(level instanceof ServerLevel server))
            return false;
        ServerPlayer player = server.getServer().getPlayerList().getPlayer(holder);
        BlockPos pos = host().pos();
        return player != null && player.level() == level && !player.isRemoved() && player.isAlive()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_REACH * MAX_REACH;
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header", assemblySize())
                .withStyle(ChatFormatting.WHITE)));
        Component state = pulled
                ? Component.translatable(GOGGLE_PREFIX + "in").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "out").withStyle(ChatFormatting.DARK_GRAY);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "state", state).withStyle(ChatFormatting.GRAY)));
        double amps = loadAmps();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "load", value(amps, "%.1f"),
                OpenContactorType.RATED_AMPS, OpenContactorType.MAX_AMPS).withStyle(ChatFormatting.GRAY)));
        if (amps > OpenContactorType.MAX_AMPS)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "over_max").withStyle(ChatFormatting.RED)));
        else if (amps > OpenContactorType.RATED_AMPS)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "over_rated").withStyle(ChatFormatting.GOLD)));
        if (openVolts() > OpenContactorType.MAX_VOLTS)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "over_volts", OpenContactorType.RATED_VOLTS)
                    .withStyle(ChatFormatting.RED)));
        return true;
    }

    private static Component value(double value, String format) {
        return Component.literal(String.format(Locale.ROOT, format, value)).withStyle(ChatFormatting.AQUA);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }

    // ------------------------------------------------------------ save/load ---

    @Override
    public void write(CompoundTag tag) {
        if (pulled)
            tag.putBoolean("Pulled", true);
    }

    @Override
    public void read(CompoundTag tag) {
        pulled = tag.getBoolean("Pulled");
        Level level = level();
        if (level == null || !level.isClientSide)
            applyContacts();
    }
}
