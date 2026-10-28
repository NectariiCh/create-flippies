package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.HostElementBudget;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenAlphanumDisplayPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;


import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * An alphanumeric display mounted inside a host block: the same glass, barrel and
 * pins as {@link AlphanumDisplayBlockEntity}, on a panel rather than in a block of
 * its own.
 *
 * It is the same device in every respect that matters (two feeds sharing a return,
 * the same elements on the glass), so everything either form has to agree
 * about is {@link AlphanumDisplay}'s and everything geometric is
 * {@link DisplayPins}'s. What this adds is the one thing a block form does not need:
 * a line to be written to. A link addresses a block position and a line, while a host
 * block holds many controls, so the host numbers its link-driven controls as one list
 * in reading order, whichever kind each is — see {@link LinkDrivenControl}. A panel
 * carrying two labels and a display therefore offers a link three rows, and the
 * display is whichever of them it happens to be down the face.
 *
 * One row, not several. What the glass shows is {@link ScreenElements}, and a link
 * aimed at this row also names which lettering element it writes (see
 * {@link com.nectariich.create_flippies.content.display.link.LinkRouting}); an old
 * link names element 0, which is the one line every display used to be. A device that
 * claimed a row per element would renumber every control below it on the panel by an
 * amount that depended on its own settings.
 */
public class AlphanumDisplayControl extends HostedControl implements LinkDrivenControl, ElementDisplay {

    /** Read in place on every sync rather than replaced; see {@link ScreenElements#read}. */
    private final ScreenElements elements;

    private IElectricNode vcc;
    private IElectricNode gnd;
    private IElectricNode backlight;

    AlphanumDisplayControl(AlphanumDisplayControlType type) {
        super(type);
        // The shipped single line at DisplayPrint's per-form default height, as an
        // empty single-line save migrates, not ScreenElements.defaults' fitted one
        elements = new ScreenElements();
        elements.read(new CompoundTag(), Viewport.of(type.form()), type.form());
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

    @Override
    public ScreenElements elements() {
        return elements;
    }

    /** The usable glass, in the frame its lettering prints in at no turn. */
    @Override
    public Viewport viewport() {
        return Viewport.of(form());
    }

    @Override
    public void elementsChanged() {
        changed();
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

    @Override
    public boolean isPowered() {
        return AlphanumDisplay.live(supply(), AlphanumDisplay.voltageBetween(vcc, gnd));
    }

    @Override
    public boolean isBacklit() {
        return AlphanumDisplay.live(supply(), AlphanumDisplay.voltageBetween(backlight, gnd));
    }

    /**
     * Takes one line from a link, into element 0.
     *
     * A link refreshing passively delivers the same string over and over, so an
     * implementation that saved and synced unconditionally would cost a packet a tick
     * for every panel in range of a redstone clock; {@link ScreenElements#acceptLinked}
     * says whether anything changed.
     */
    @Override
    public void acceptLine(String incoming) {
        if (elements.acceptLinked(0, 0, incoming, HostElementBudget.textCap(this)))
            changed();
    }

    /** The first lettering element's estimate, which is element 0's on every display so far. */
    @Override
    public int linkColumns() {
        List<ScreenElement> texts = elements.texts();
        return texts.isEmpty() ? 1 : texts.get(0).columns();
    }

    @Override
    public Component linkRowName() {
        return Component.translatable("gui.create_flippies.control_host.row.display");
    }

    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        ModPackets.sendToPlayer(player,
                new OpenAlphanumDisplayPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        elements.write(tag);
    }

    @Override
    public void read(CompoundTag tag) {
        elements.read(tag, viewport(), form());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return AlphanumDisplay.addTooltip(tooltip, supply(), isPowered(), isBacklit(), elements);
    }
}
