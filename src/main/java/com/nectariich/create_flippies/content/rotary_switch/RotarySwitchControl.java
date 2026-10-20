package com.nectariich.create_flippies.content.rotary_switch;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenRotarySwitchMenuPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A rotary switch mounted inside a host block: the same contact stack as
 * {@link RotarySwitchBlockEntity} keeps, held as an object among others rather
 * than as one block's worth of state.
 *
 * The circuit is built through a view that numbers this switch's own terminals
 * from zero, so the body of {@link #buildCircuit} is the standalone
 * switch's unchanged. What differs is only where the state lives and who tells it
 * to save.
 *
 * The internal topology is fixed and the handle is expressed by switching wires
 * rather than by adding and removing them, exactly as the block form does. That is
 * not a stylistic echo: Power Grid streams a block's switched-wire states to the
 * client positionally with no count, so a control whose wire count varied with its
 * own position would shift every later control's readings on the same panel.
 */
public class RotarySwitchControl extends HostedControl {

    /** Which detent the handle sits in; 0 is the first marking on the front. */
    private int position;

    /** Front, paint and handle; the mounted form of the same per-switch state. */
    private RotarySwitchAppearance appearance;

    /**
     * Which detents spring back to rest, as a {@link RotarySwitchSpring} mask set
     * from the menu. Read through {@link #springs()}, which a designed switch answers
     * from its design instead.
     */
    private int springs;

    /** Who is holding the handle over in a springing detent; never saved. */
    private final RotarySwitchSpring spring = new RotarySwitchSpring();

    /**
     * Per section, one wire from the common to each stud, in the order
     * {@link RotarySwitchKind#studs} lists them. Null until the host builds the
     * circuit, which it does before anything can turn the handle.
     */
    private SwitchedWire[][] contacts;

    public float handleAngle;
    public float prevHandleAngle;

    protected RotarySwitchControl(RotarySwitchControlType type) {
        super(type);
        appearance = RotarySwitchAppearance.defaultFor(kind());
        position = kind().defaultPosition();
        // The handle starts where the detent is rather than easing over to it from
        // upright, which on a switch that does not start upright is a control that
        // appears to be thrown by whoever placed it
        handleAngle = kind().handleAngle(position);
        prevHandleAngle = handleAngle;
    }

    public RotarySwitchKind kind() {
        return ((RotarySwitchControlType) type()).kind();
    }

    public RotarySwitchAppearance appearance() {
        return appearance;
    }

    /** Server-side: re-dress the switch. Nothing electrical or geometric changes. */
    public void setAppearance(RotarySwitchAppearance appearance) {
        this.appearance = appearance.forKind(kind());
        changed();
    }

    public int getPosition() {
        return position;
    }

    /** Which detents spring back to rest, as a {@link RotarySwitchSpring} mask. */
    public int springs() {
        return springs;
    }

    /**
     * Server-side: change which detents spring back. A handle left in a detent that
     * now springs, with nobody holding it there, goes back on the next tick.
     */
    public void setSprings(int springs) {
        this.springs = RotarySwitchSpring.sanitise(kind(), springs);
        changed();
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        RotarySwitchKind kind = kind();
        int sections = RotarySwitchBlock.SECTIONS.length;
        int studs = kind.studs().length;
        contacts = new SwitchedWire[sections][studs];
        builder.setTerminalCount(kind.terminalCount());
        int made = madeStud();
        for (int section = 0; section < sections; section++) {
            int base = section * kind.contactsPerSection();
            IElectricNode common = builder.terminalNode(base + kind.commonIndex());
            for (int stud = 0; stud < studs; stud++)
                contacts[section][stud] = builder.connectSwitch(RotarySwitchBlockEntity.CONTACT_RESISTANCE, common,
                        builder.terminalNode(base + kind.studIndex(stud)), stud == made);
        }
    }

    /**
     * The appearance menu, asked for on this player's behalf. A mounted control
     * cannot open its own screen; see {@link HostedControl#openEditor}. Reached both
     * by wrenching the panel and by the screwdriver as the switch goes on, so a
     * switch can be dressed before it is buried in a grid of others.
     */
    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenRotarySwitchMenuPacket(host().pos(), mount()));
        return true;
    }

    /**
     * A push on one half of the front turns the switch that way, exactly as the
     * block form's does — see {@link RotarySwitchSide}. The click arrives in the
     * host block's space, so it goes back through this control's own mount to be
     * read as a place on the switch.
     */
    @Override
    public InteractionResult use(Player player, InteractionHand hand, Vec3 local) {
        RotarySwitchSide side = RotarySwitchAim.sideAt(local, mount());
        if (side == null)
            return InteractionResult.PASS;
        // turn() is what makes the noise, so a push into a stop is silent
        turn(side, player);
        return InteractionResult.CONSUME;
    }

    /**
     * Stops at the ends, and remembers who turned it into a detent that springs
     * back; see {@link RotarySwitchBlockEntity#turn}.
     */
    public boolean turn(RotarySwitchSide side, Player player) {
        int target = Mth.clamp(position + side.detentStep(), 0, kind().positions() - 1);
        if (target == position)
            return false;
        setPosition(target);
        spring.turned(kind(), springs(), position, player);
        if (host() != null)
            RotarySwitchBlockEntity.playTurnSound(host().level(), host().pos());
        return true;
    }

    /** Server-side: this player has let go; see {@link RotarySwitchBlockEntity#release}. */
    public void release(Player player) {
        if (spring.release(kind(), springs(), position, player))
            springBack();
    }

    /** The handle returning to rest under its spring, with the same clack as a turn. */
    private void springBack() {
        spring.clear();
        setPosition(kind().defaultPosition());
        if (host() != null)
            RotarySwitchBlockEntity.playTurnSound(host().level(), host().pos());
    }

    public void setPosition(int position) {
        this.position = Math.floorMod(position, kind().positions());
        applyContacts();
        changed();
    }

    @Override
    public void tick() {
        // Lets go for a holder who is no longer there to hold it, and returns a
        // handle that was loaded over with nobody holding it at all
        if (host() != null && spring.tick(host().level(), host().pos(), kind(), springs(), position))
            springBack();
        prevHandleAngle = handleAngle;
        float target = kind().handleAngle(position);
        handleAngle += (target - handleAngle) * RotarySwitchBlockEntity.HANDLE_EASING;
        if (Math.abs(target - handleAngle) < RotarySwitchBlockEntity.HANDLE_SNAP_DEGREES)
            handleAngle = target;
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putInt("Position", position);
        appearance.write(tag);
        tag.putInt("Springs", springs);
    }

    @Override
    public void read(CompoundTag tag) {
        position = Math.floorMod(tag.getInt("Position"), kind().positions());
        appearance = RotarySwitchAppearance.read(tag, kind());
        // Absent on a switch saved before there were springs, which reads as none
        springs = RotarySwitchSpring.sanitise(kind(), tag.getInt("Springs"));
        // The circuit may have been built before this ran, from the position this
        // control started at rather than the one it was saved in
        applyContacts();
    }

    /** Says which way the handle springs back, if it does; see the block form. */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return RotarySwitchBlockEntity.addSpringTooltip(tooltip, kind(), springs());
    }

    /**
     * Which of the kind's studs every section's common is on now, or -1 for none: the
     * one the handle's detent makes. A designed breaker whose contacts follow its flag
     * rather than its handle answers differently.
     */
    protected int madeStud() {
        return kind().madeStud(position);
    }

    /** Point every section's common at whichever stud {@link #madeStud()} names. */
    protected void applyContacts() {
        if (contacts == null)
            return;
        int made = madeStud();
        for (SwitchedWire[] section : contacts)
            for (int stud = 0; stud < section.length; stud++)
                section[stud].setState(stud == made);
    }
}
