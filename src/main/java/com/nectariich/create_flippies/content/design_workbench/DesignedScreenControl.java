package com.nectariich.create_flippies.content.design_workbench;

import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;
import com.nectariich.create_flippies.content.display.AlphanumDisplay;
import com.nectariich.create_flippies.content.display.DisplayPins;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.HostElementBudget;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenAlphanumDisplayPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;


import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A screen made at the Design Workbench, mounted on a panel: an imported model in
 * front, one submesh of which shows {@link ScreenElements}, on the alphanumeric
 * displays' barrel and pins behind.
 *
 * Electrically and to a display link it is an alphanumeric display in every respect:
 * the same three terminals in the same order ({@link DisplayPins}), a supply across
 * {@code VCC} and {@code GND} that decides whether anything shows and a backlight
 * feed across {@code BL} and {@code GND} that decides how it is lit, both drawing what
 * a display of its rating draws ({@link AlphanumDisplay}), and one row in its host's
 * list of link-driven controls whose lettering elements a link fills (see
 * {@link com.nectariich.create_flippies.content.display.link.LinkRouting}). The
 * screwdriver opens the same element editor a display's does. What differs is where
 * the elements are drawn, which is the design's business
 * ({@link ScreenSurface}), and what rating it has, which the design names rather than
 * the item.
 *
 * <h2>Its own elements</h2>
 *
 * A newly placed screen starts with a copy of the layout its design was authored
 * with ({@link #mint}), and from then on its elements are its own, saved beside the
 * design and edited and linked like a display's. They are read in place on every sync
 * so the editor and the renderer can hold on to them. The design, by contrast, is only
 * rebuilt when its tag changes, as for every other designed control.
 *
 * Common code: nothing here reaches a client class.
 */
public class DesignedScreenControl extends HostedControl implements DesignedControl, LinkDrivenControl, ElementDisplay {

    /** What a screen with no design is drawn on, so it always has a viewport. */
    private static final ScreenSurface NO_SURFACE = ScreenSurface.defaults();

    private ControlDesign design;
    private ControlFootprint footprint;

    /** Read in place on every sync rather than replaced; see {@link ScreenElements#read}. */
    private final ScreenElements elements = new ScreenElements();

    private IElectricNode vcc;
    private IElectricNode gnd;
    private IElectricNode backlight;
    private ElectricWire logic;
    private ElectricWire lamp;

    DesignedScreenControl(DesignedScreenControlType type) {
        super(type);
        footprint = type.footprint();
        NO_SURFACE.copyLayoutInto(elements);
    }

    @Override
    public ControlDesign design() {
        return design;
    }

    /**
     * Sets the design and the footprint its model implies, as a new object so the host
     * notices, fits the elements to its viewport and rates the circuit for its supply.
     */
    void setDesign(ControlDesign design) {
        this.design = design;
        this.footprint = DesignedFootprints.screen(design.front());
        elements.clampTo(viewport());
        // The circuit may have been built before the design arrived, at the default
        // rating's resistances
        if (logic != null)
            logic.setResistance(AlphanumDisplay.logicResistance(supply()));
        if (lamp != null)
            lamp.setResistance(AlphanumDisplay.backlightResistance(supply()));
    }

    /** A screen as it is first placed: its design, and a copy of the layout the design was authored with. */
    void mint(ControlDesign design) {
        setDesign(design);
        surface().copyLayoutInto(elements);
    }

    /** Where this screen's elements are drawn; the default surface while it has no design. */
    public ScreenSurface surface() {
        return design == null || design.screen() == null ? NO_SURFACE : design.screen();
    }

    /** The rating its design names. */
    public SupplyVoltage supply() {
        return surface().supply;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public ScreenElements elements() {
        return elements;
    }

    @Override
    public Viewport viewport() {
        return surface().viewport();
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
        logic = builder.connect(AlphanumDisplay.logicResistance(supply()), vcc, gnd);
        lamp = builder.connect(AlphanumDisplay.backlightResistance(supply()), backlight, gnd);
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
     * Takes one line from a link that names no element, into element 0, as a display
     * does; a link that names one goes through {@link LinkDrivenControl}'s element
     * route instead. Only syncs when the text changed.
     */
    @Override
    public void acceptLine(String incoming) {
        if (elements.acceptLinked(0, 0, incoming, HostElementBudget.textCap(this)))
            changed();
    }

    /** The first lettering element's estimate. */
    @Override
    public int linkColumns() {
        List<ScreenElement> texts = elements.texts();
        return texts.isEmpty() ? 1 : texts.get(0).columns();
    }

    /** The design's name, which is what tells two screens on one panel apart in a link's picker. */
    @Override
    public Component linkRowName() {
        String name = design == null ? "" : design.name();
        return name.isEmpty() ? Component.translatable("gui.create_flippies.control_host.row.screen")
                : Component.literal(name);
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
    public ItemStack asItem() {
        ItemStack stack = type().asItem();
        return design == null ? stack : design.applyTo(stack);
    }

    @Override
    public void write(CompoundTag tag) {
        if (design != null)
            tag.put(ControlDesign.TAG, design.write());
        elements.write(tag);
    }

    /**
     * The design first, rebuilt only when its tag changed, so the viewport is the
     * right one; then the elements in place. A tag with no elements is one saved
     * before it had any of its own, which starts from its design's layout.
     */
    @Override
    public void read(CompoundTag tag) {
        if (tag.contains(ControlDesign.TAG, Tag.TAG_COMPOUND)) {
            ControlDesign read = ControlDesign.read(tag.getCompound(ControlDesign.TAG));
            // The same design arriving again in a sync keeps its footprint object, so
            // the host does not rebuild its shapes for nothing
            if (design == null || !design.write().equals(read.write()))
                setDesign(read);
        }
        if (tag.contains("Elements", Tag.TAG_LIST))
            elements.read(tag, viewport(), null);
        else
            surface().copyLayoutInto(elements);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return AlphanumDisplay.addTooltip(tooltip, supply(), isPowered(), isBacklit(), elements);
    }
}
