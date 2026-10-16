package com.nectariich.create_flippies.content.indicator;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenIndicatorMenuPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * An indicator mounted inside a host block: the same lamp across the same two
 * terminals, and the same three poles behind it, as {@link IndicatorBlockEntity}
 * keeps — held as an object among others rather than as one block's worth of state.
 *
 * The circuit is built through a view that numbers this unit's own terminals from
 * zero, so the body of {@link #buildCircuit} is the standalone unit's unchanged.
 * What differs is only where the state lives and who tells it to save: the kind
 * comes from the type rather than from a block state, which is the one thing a
 * control cannot read off a block of its own.
 *
 * The lamp needs no tick here either. Whether it is lit is worked out from the
 * voltage across it whenever it is drawn, and Power Grid syncs node voltages, so a
 * mounted lamp answers on the client exactly as the block form does. The button does
 * tick, on the server, for the reason {@link IndicatorButton#tick} gives.
 *
 * Neither the wrench nor a plain right click opens the configuration any more. The
 * plain click is the press — a button screwed into a panel has to be operable by a
 * player with nothing in their hand — and the configuration is a sneaking right
 * click with a screwdriver, which is also how it is offered as the unit goes on, so
 * a cap can be lettered before it is flush in a row of others.
 */
public class IndicatorControl extends HostedControl {

    private IElectricNode terminalA;
    private IElectricNode terminalB;

    private final IndicatorButton button = new IndicatorButton();

    private IndicatorColor color = IndicatorColor.defaultColor();
    private IndicatorLegend legend = IndicatorLegend.BLANK;

    IndicatorControl(IndicatorControlType type) {
        super(type);
    }

    public IndicatorKind kind() {
        return ((IndicatorControlType) type()).kind();
    }

    /** What this lamp is built for, fixed by which kind of unit it is. */
    public IndicatorRating rating() {
        return kind().rating();
    }

    public IndicatorSize size() {
        return kind().size();
    }

    public IndicatorColor color() {
        return color;
    }

    public IndicatorAction action() {
        return button.action();
    }

    public boolean isPressed() {
        return button.isPressed();
    }

    public IndicatorLegend legend() {
        return legend;
    }

    /** Server-side; see {@link IndicatorBlockEntity#configure}. */
    public void configure(IndicatorColor color, IndicatorAction action, IndicatorLegend legend) {
        button.setAction(action);
        this.color = IndicatorColor.sanitise(color, action);
        this.legend = legend;
        changed();
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(IndicatorLamp.TERMINALS);
        terminalA = builder.terminalNode(IndicatorLamp.TERMINAL_A);
        terminalB = builder.terminalNode(IndicatorLamp.TERMINAL_B);
        builder.connect(rating().resistance(), terminalA, terminalB);

        // Always three, always in this order; see the block form's buildCircuit for
        // why the count may not depend on what the unit has been set to do
        SwitchedWire[] poles = new SwitchedWire[IndicatorLamp.POLES];
        boolean made = button.isPressed();
        for (int pole = 0; pole < poles.length; pole++)
            poles[pole] = builder.connectSwitch(IndicatorLamp.CONTACT_RESISTANCE,
                    builder.terminalNode(IndicatorLamp.poleTerminal(pole, 0)),
                    builder.terminalNode(IndicatorLamp.poleTerminal(pole, 1)), made);
        button.setPoles(poles);
    }

    /** The cap is the footprint, and the two sizes differ in nothing else. */
    @Override
    public ControlFootprint footprint() {
        return IndicatorLamp.footprint(size());
    }

    /** Server-side: the use key has gone down on the cap. */
    public void press(Player player) {
        if (!button.press(player))
            return;
        click(button.isPressed());
    }

    /** Server-side: the use key has come up; only the holder's release counts. */
    public void release(Player player) {
        if (!button.release(player))
            return;
        click(false);
    }

    /** The watchdog on a held cap, and nothing else; see {@link IndicatorButton#tick}. */
    @Override
    public void tick() {
        if (host() == null)
            return;
        if (button.tick(host().level(), host().pos()))
            click(false);
    }

    /** Potential difference across the lamp, in volts; sign is not meaningful. */
    public float measuredVoltage() {
        return IndicatorBlockEntity.voltageBetween(terminalA, terminalB);
    }

    /** How hard the lamp is being driven, as a fraction of its rating. */
    public float brightness() {
        return IndicatorBlockEntity.brightness(measuredVoltage(), rating());
    }

    public boolean isLit() {
        return brightness() >= IndicatorBlockEntity.LIT_THRESHOLD;
    }

    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenIndicatorMenuPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putString("Color", color.id());
        button.write(tag);
        legend.write(tag);
    }

    @Override
    public void read(CompoundTag tag) {
        button.read(tag);
        color = IndicatorColor.sanitise(IndicatorColor.of(tag.getString("Color"), IndicatorColor.defaultColor()),
                action());
        legend = IndicatorLegend.read(tag);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return IndicatorBlockEntity.addLampTooltip(tooltip, rating(), measuredVoltage(), color, button);
    }

    /** The noise and the save that follow a cap moving, wherever it moved from. */
    private void click(boolean down) {
        if (host() != null)
            IndicatorButton.playClick(host().level(), host().pos(), down);
        changed();
    }
}
