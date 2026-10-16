package com.nectariich.create_flippies.content.relay;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One relay mount on a rail — 5-pin or 8-pin — and the relay in it if there is one.
 *
 * <h2>Fitting a relay</h2>
 *
 * The mount clips on empty. Right-clicking it with a relay pushes the relay in;
 * sneaking with an empty hand pulls it back out into the inventory, and so does a
 * left click — only the next left click takes the mount. Breaking the whole rail
 * gives back both. Only a relay with as many pins as the socket goes in.
 * The relay is state of this control and not a control of its own, so fitting or
 * pulling one changes no terminal and no wire: the pins are the mount's and stay
 * wired whatever is plugged into it.
 *
 * <h2>The circuit</h2>
 *
 * Fixed for the life of the control, in this order, because Power Grid streams
 * switched-wire states to the client positionally — fitting a relay re-values and
 * switches these rather than adding any:
 *
 * <ol>
 * <li>the <b>input</b>, A1 to A2: closed while a relay is fitted, at the relay's
 * {@linkplain RelayType#coilOhms() input resistance};
 * <li>then for each pole in turn, the <b>NO contact</b>, C to NO: closed while the
 * relay is energised, at its {@linkplain RelayType#onOhms() on-state resistance};
 * <li>and the <b>NC contact</b>, C to NC: closed while a changeover relay is
 * <em>not</em> energised. A solid state relay has no NC contact, so with one
 * fitted this never closes.
 * </ol>
 *
 * The 5-pin mount has one pole and the 8-pin two, both poles switching together.
 *
 * Open switches are Power Grid's 200 MΩ, so an empty mount is no load and no path.
 *
 * <h2>Switching</h2>
 *
 * Once a server tick it reads A1 − A2 off the last solve and switches on at
 * {@link RelayType#pickUpVolts()} and off at {@link RelayType#dropOutVolts()},
 * holding between them — a tick behind the solve, as the PSU and the PLC are. A
 * solid state relay's input is polarised: A2 above A1 never switches it on. An
 * electromechanical relay clicks as its armature pulls in and again as it drops out.
 *
 * The rated current is not enforced; the goggles say when the load is past it.
 *
 * <h2>What is saved</h2>
 *
 * Which relay is fitted, and whether it is energised. The host syncs one tag for
 * disk and network, and both are what the client draws and reports; {@link #changed()}
 * is only called when either changes, never per tick.
 */
public class RelayMountControl extends HostedControl {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".relay_mount.goggles.";

    /**
     * What the switches are valued at while nothing is fitted. They are open then,
     * so it hardly matters; these are a 24 V, 10 A relay's.
     */
    private static final double EMPTY_COIL_OHMS = 2400;
    private static final double EMPTY_CONTACT_OHMS = 0.16;

    private RelayType relay;
    private boolean energised;

    private final RelayMountGeometry geometry;

    private IElectricNode a1;
    private IElectricNode a2;
    private IElectricNode[] commons;
    private IElectricNode[] nos;
    private IElectricNode[] ncs;
    private SwitchedWire coil;
    private SwitchedWire[] noContacts;
    private SwitchedWire[] ncContacts;

    RelayMountControl(RelayMountType type) {
        super(type);
        this.geometry = type.geometry();
    }

    /** Whether this relay plugs into this mount's socket. */
    public boolean fits(RelayType relay) {
        return relay.pins() == geometry.pins();
    }

    /** The relay fitted, or null for an empty mount. */
    public RelayType relay() {
        return relay;
    }

    public boolean energised() {
        return relay != null && energised;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        int poles = geometry.poles();
        builder.setTerminalCount(geometry.terminals());
        a1 = builder.terminalNode(RelayMountGeometry.TERMINAL_A1);
        a2 = builder.terminalNode(RelayMountGeometry.TERMINAL_A2);
        commons = new IElectricNode[poles];
        nos = new IElectricNode[poles];
        ncs = new IElectricNode[poles];
        for (int pole = 0; pole < poles; pole++) {
            commons[pole] = builder.terminalNode(RelayMountGeometry.common(pole));
            nos[pole] = builder.terminalNode(RelayMountGeometry.no(pole));
            ncs[pole] = builder.terminalNode(RelayMountGeometry.nc(pole));
        }

        // The order below is the order Power Grid streams these to the client in;
        // see the class comment before changing it
        coil = builder.connectSwitch((float) coilOhms(), a1, a2, false);
        noContacts = new SwitchedWire[poles];
        ncContacts = new SwitchedWire[poles];
        for (int pole = 0; pole < poles; pole++) {
            noContacts[pole] = builder.connectSwitch((float) contactOhms(), commons[pole], nos[pole], false);
            ncContacts[pole] = builder.connectSwitch((float) contactOhms(), commons[pole], ncs[pole], false);
        }
        applyContacts();
    }

    private boolean built() {
        return coil != null;
    }

    private double coilOhms() {
        return relay == null ? EMPTY_COIL_OHMS : relay.coilOhms();
    }

    private double contactOhms() {
        return relay == null ? EMPTY_CONTACT_OHMS : relay.onOhms();
    }

    /** Puts every switch where the fitted relay and its state say. */
    private void applyContacts() {
        if (!built())
            return;
        if (coil.getResistance() != coilOhms())
            coil.setResistance(coilOhms());
        coil.setState(relay != null);
        boolean nc = relay != null && relay.changeover() && !energised;
        for (int pole = 0; pole < noContacts.length; pole++) {
            if (noContacts[pole].getResistance() != contactOhms()) {
                noContacts[pole].setResistance(contactOhms());
                ncContacts[pole].setResistance(contactOhms());
            }
            noContacts[pole].setState(energised());
            ncContacts[pole].setState(nc);
        }
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /** A1 − A2; on a client, from the node voltages Power Grid syncs. */
    public double coilVolts() {
        return built() ? voltage(a1) - voltage(a2) : 0;
    }

    /**
     * The current through the most loaded closed contact, worked out from the
     * voltage across it so a client can show it without being sent it.
     */
    public double loadAmps() {
        if (!built() || relay == null)
            return 0;
        boolean nc = relay.changeover() && !energised;
        double worst = 0;
        for (int pole = 0; pole < commons.length; pole++) {
            IElectricNode other = energised ? nos[pole] : nc ? ncs[pole] : null;
            if (other != null)
                worst = Math.max(worst, Math.abs(voltage(commons[pole]) - voltage(other)) / relay.onOhms());
        }
        return worst;
    }

    // ---------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide)
            return;
        serverTick();
    }

    /** One server tick, free of the level so it can be driven against a real network in a test. */
    void serverTick() {
        if (!built())
            return;
        boolean next = decide(relay, energised, coilVolts());
        if (next != energised) {
            energised = next;
            applyContacts();
            changed();
            // Asked only with a host: the sound's registry entry is not there to look up without a game
            if (!relay.solidState() && host() != null)
                // Pull-in a touch higher than drop-out, the way a real armature sounds
                play(ModSounds.RELAY_CLICK.get(), 0.6f, next ? 1.1f : 0.95f);
        }
    }

    /** Whether a relay seeing {@code volts} across its input is on, given whether it was. */
    static boolean decide(RelayType relay, boolean was, double volts) {
        if (relay == null)
            return false;
        double seen = relay.polarised() ? volts : Math.abs(volts);
        if (was)
            return seen > relay.dropOutVolts();
        return seen >= relay.pickUpVolts();
    }

    // -------------------------------------------------------- fitting it ---

    /**
     * A relay in hand pushes it in; an empty hand while sneaking pulls the fitted one
     * out. Called on the server only, as every click on a clipped control is.
     */
    @Override
    public InteractionResult use(Player player, InteractionHand hand, Vec3 local) {
        ItemStack held = player.getItemInHand(hand);
        RelayType offered = RelayType.forItem(held);
        if (offered != null) {
            if (relay != null)
                return InteractionResult.PASS;
            if (!fits(offered)) {
                player.displayClientMessage(Component.translatable(GOGGLE_PREFIX + "wrong_socket",
                        offered.pins(), geometry.pins()).withStyle(ChatFormatting.RED), true);
                return InteractionResult.CONSUME;
            }
            fit(offered);
            if (!player.isCreative())
                held.shrink(1);
            play(SoundEvents.STONE_BUTTON_CLICK_ON, 0.4f, 0.9f);
            return InteractionResult.CONSUME;
        }
        if (held.isEmpty() && player.isShiftKeyDown() && takeRelay(player))
            return InteractionResult.CONSUME;
        return InteractionResult.PASS;
    }

    /** A left click on a mount with a relay in it pulls the relay; the next one takes the mount. */
    @Override
    public boolean breakPart(Player player) {
        return takeRelay(player);
    }

    /** Pulls the fitted relay into the player's inventory, or nothing in creative. */
    private boolean takeRelay(Player player) {
        if (relay == null)
            return false;
        ItemStack removed = relay.asItem();
        fit(null);
        if (!player.isCreative() && !player.getInventory().add(removed))
            player.drop(removed, false);
        play(SoundEvents.STONE_BUTTON_CLICK_ON, 0.4f, 0.7f);
        return true;
    }

    /** Fits a relay, or empties the mount with null. A fresh relay starts off. */
    void fit(RelayType relay) {
        this.relay = relay;
        this.energised = false;
        applyContacts();
        changed();
    }

    private void play(SoundEvent sound, float volume, float pitch) {
        if (host() == null || host().level() == null)
            return;
        host().level().playSound(null, host().pos(), sound, SoundSource.BLOCKS, volume, pitch);
    }

    @Override
    public List<ItemStack> drops() {
        return relay == null ? List.of(type().asItem()) : List.of(type().asItem(), relay.asItem());
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header", geometry.pins())
                .withStyle(ChatFormatting.WHITE)));
        if (relay == null) {
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "empty").withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "relay",
                Component.translatable("item." + CreateFlippies.MOD_ID + "." + relay.id()))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "input", value(coilVolts(), "%.1f"),
                relay.coilVolts()).withStyle(ChatFormatting.GRAY)));
        Component state = energised()
                ? Component.translatable(GOGGLE_PREFIX + "on").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "off").withStyle(ChatFormatting.DARK_GRAY);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "output", state).withStyle(ChatFormatting.GRAY)));
        double amps = loadAmps();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "load", value(amps, "%.2f"), relay.ratedAmps())
                .withStyle(ChatFormatting.GRAY)));
        if (amps > relay.ratedAmps())
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "overloaded").withStyle(ChatFormatting.RED)));
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
        if (relay == null)
            return;
        tag.putString("Relay", relay.id());
        if (energised)
            tag.putBoolean("Energised", true);
    }

    @Override
    public void read(CompoundTag tag) {
        relay = tag.contains("Relay") ? RelayType.of(tag.getString("Relay")) : null;
        if (relay != null && !fits(relay))
            relay = null;
        energised = relay != null && tag.getBoolean("Energised");
        // A client is streamed the switch states by Power Grid and only wants the tag
        Level level = host() == null ? null : host().level();
        if (level == null || !level.isClientSide)
            applyContacts();
    }
}
