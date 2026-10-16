package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenAlphanumDisplayPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * An alphanumeric display mounted inside a host block: the same glass, barrel and
 * pins as {@link AlphanumDisplayBlockEntity}, on a panel rather than in a block of
 * its own.
 *
 * It is the same device in every respect that matters — two feeds sharing a return,
 * one line of text, and the same printing — so everything either form has to agree
 * about is {@link AlphanumDisplay}'s and everything geometric is
 * {@link DisplayPins}'s. What this adds is the one thing a block form does not need:
 * a line to be written to. A link addresses a block position and a line, while a host
 * block holds many controls, so the host numbers its link-driven controls as one list
 * in reading order, whichever kind each is — see {@link LinkDrivenControl}. A panel
 * carrying two labels and a display therefore offers a link three rows, and the
 * display is whichever of them it happens to be down the face.
 *
 * One row, not several. A display shows one line because the link model gives a
 * control one line, and a device that claimed several would renumber every control
 * below it on the panel by an amount that depended on its own settings.
 */
public class AlphanumDisplayControl extends HostedControl implements LinkDrivenControl {

    private String text = "";
    private DisplayPrint print;

    private IElectricNode vcc;
    private IElectricNode gnd;
    private IElectricNode backlight;

    AlphanumDisplayControl(AlphanumDisplayControlType type) {
        super(type);
        print = DisplayPrint.defaultFor(type.form());
    }

    public DisplayKind kind() {
        return ((AlphanumDisplayControlType) type()).kind();
    }

    public DisplayForm form() {
        return kind().form();
    }

    public SupplyVoltage supply() {
        return kind().supply();
    }

    /** How the line is set; see {@link DisplayPrint}. */
    public DisplayPrint print() {
        return print;
    }

    /** The line the device is holding, whether or not it is powered to show it. */
    public String text() {
        return text;
    }

    /** The line as it will be drawn, which is nothing at all without the supply. */
    public String displayedText() {
        return isPowered() ? text : "";
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(DisplayPins.TERMINALS);
        vcc = builder.terminalNode(DisplayPins.TERMINAL_VCC);
        gnd = builder.terminalNode(DisplayPins.TERMINAL_GND);
        backlight = builder.terminalNode(DisplayPins.TERMINAL_BL);
        builder.connect(AlphanumDisplay.logicResistance(supply()), vcc, gnd);
        builder.connect(AlphanumDisplay.backlightResistance(supply()), backlight, gnd);
    }

    public boolean isPowered() {
        return AlphanumDisplay.live(supply(), AlphanumDisplay.voltageBetween(vcc, gnd));
    }

    public boolean isBacklit() {
        return AlphanumDisplay.live(supply(), AlphanumDisplay.voltageBetween(backlight, gnd));
    }

    /**
     * Takes one line from a link.
     *
     * A link refreshing passively delivers the same string over and over, so an
     * implementation that saved and synced unconditionally would cost a packet a tick
     * for every panel in range of a redstone clock.
     */
    @Override
    public void acceptLine(String incoming) {
        String sanitised = AlphanumDisplay.sanitise(incoming);
        if (sanitised.equals(text))
            return;
        text = sanitised;
        changed();
    }

    @Override
    public int linkColumns() {
        return print.columns(form());
    }

    @Override
    public Component linkRowName() {
        return Component.translatable("gui.create_flippies.control_host.row.display");
    }

    /** Called server-side when an editor packet arrives. */
    public void configure(DisplayPrint print) {
        this.print = print;
        changed();
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
                new OpenAlphanumDisplayPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putString("Text", text);
        print.write(tag);
    }

    @Override
    public void read(CompoundTag tag) {
        text = AlphanumDisplay.sanitise(tag.getString("Text"));
        print = DisplayPrint.read(tag, form());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return AlphanumDisplay.addTooltip(tooltip, supply(), isPowered(), isBacklit(), text);
    }
}
