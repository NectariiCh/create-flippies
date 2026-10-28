package com.nectariich.create_flippies.content.label;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenPanelLabelPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;


/**
 * A panel label mounted inside a host block: one line of text on a strip of the
 * panel, either written by hand or driven by a Create display link.
 *
 * The strip is this label's own rather than its kind's. It is the rectangle the
 * player drew as they placed it, in the authored frame — {@code width} along x and
 * {@code height} along z, whichever way the legend on it happens to run — and it is
 * fixed from then on, for the reason
 * {@link HostedControl} gives about mounts: a change of size is a change of
 * footprint and of everything that was checked against it, so it is a new label
 * rather than an edit to this one. What the editor changes is only what is printed
 * on the strip, never how much of the panel it covers.
 *
 * The two sources of text are two fields rather than one, and the link wins while
 * it has anything to say. A single field the link overwrote would destroy the
 * legend underneath it the first time a link fired, with nothing to restore it
 * from; keeping them apart means a link can put a live reading on a label that
 * still remembers what it was called, and unhooking the link brings the legend
 * back. Writing a legend by hand clears the link text for the same reason in
 * reverse: taking a label back from a link has to be possible without breaking the
 * link.
 *
 * Both fields are saved and both are synced, because the client draws them and the
 * host writes a control's state once for the disk and the network alike. The link
 * text in particular is worth persisting: a reload would otherwise blank every
 * driven label until each link next fired, which for a link on a redstone pulse
 * could be never.
 */
public class PanelLabelControl extends HostedControl implements LinkDrivenControl {

    /** White, because the panel behind a label is whatever block the builder chose. */
    private static final int DEFAULT_COLOUR = 0xFFFFFFFF;

    private String text = PanelLabel.PLACEHOLDER;
    private String linkText = "";
    private int colour = DEFAULT_COLOUR;

    /**
     * The plate the legend is printed on, separately from the legend.
     *
     * Two colours rather than one, because a legend is two things: lettering, and the
     * card it is printed on. A panel that names its groups by colour wants the card
     * coloured and the lettering left legible — black on yellow for a warning, white
     * on the panel's own grey for everything else — and a single colour can only ever
     * give one of those.
     */
    private int plateColour = PanelLabel.DEFAULT_PLATE_COLOUR;
    private PanelLabelPrint print = PanelLabelPrint.DEFAULT;

    private int width;
    private int height;

    /**
     * How many labels deep this one sits, which is how far it stands off the panel:
     * see {@link PanelLabel#STACK_STEP}. Settled once, as the label goes on, from
     * whatever it was laid over: a label underneath that is later taken away leaves
     * this one where it is, which is a tenth of a pixel nobody will see, rather than
     * a stack that shuffles every time one of it is removed.
     */
    private int layer;

    /**
     * The footprint, kept rather than rebuilt. Every overlap test, hitbox and
     * outline goes through it, so it is asked for several times a click and once a
     * frame, and the boxes behind it never change once the label is made.
     */
    private ControlFootprint footprint;

    PanelLabelControl(PanelLabelControlType type, int width, int height) {
        super(type);
        resize(width, height, 0);
    }

    /** The legend as written by hand, which is what the editor starts from. */
    public String text() {
        return text;
    }

    public int colour() {
        return colour;
    }

    /** The colour of the plate the legend is printed on; see the field. */
    public int plateColour() {
        return plateColour;
    }

    /** Which face the legend is set in, and how large; see {@link PanelLabelPrint}. */
    public PanelLabelPrint print() {
        return print;
    }

    /**
     * How long the strip is along the authored x axis, in the half-pixel steps a
     * drawn rectangle is measured in — see
     * {@link SpannedControlType#SPANS_PER_PIXEL}.
     * Which way the legend runs across it is the print's business, not the strip's.
     */
    public int width() {
        return width;
    }

    /** How tall the strip is across the authored x axis, in the same steps. */
    public int height() {
        return height;
    }

    /** How many labels deep this one sits; zero for one laid over nothing. */
    public int layer() {
        return layer;
    }

    /** How far the strip stands off the panel, in pixels. */
    public double standOff() {
        return PanelLabel.standOff(layer);
    }

    /** The middle of this strip's outer surface, which is where its legend is printed. */
    public Vec3 textAnchor() {
        return PanelLabel.textAnchor(layer);
    }

    /**
     * Puts this label on top of the labels its strip is laid over. Only for a label
     * that has not gone on yet: once mounted, its footprint is part of what the host
     * has checked and built, for the reason {@link HostedControl} gives about mounts.
     */
    public void stackOn(int layer) {
        resize(width, height, layer);
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    /** What is actually printed on the panel: the link while it is driving, the legend otherwise. */
    public String displayedText() {
        return linkText.isEmpty() ? text : linkText;
    }

    /** Nothing to build, and saying so is what checks the declaration against the circuit. */
    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(0);
    }

    /**
     * Opening the editor is the server's decision, so the click is answered by
     * telling this one player's client to open it rather than by opening it here.
     * See {@link HostedControl#openEditor} for why there is no other way round.
     */
    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        ModPackets.sendToPlayer(player,
                new OpenPanelLabelPacket(host().pos(), mount()));
        return true;
    }

    /** Called server-side when an editor packet arrives; the text arrives sanitised. */
    public void configure(String text, int colour, int plateColour, PanelLabelPrint print) {
        this.text = text;
        this.colour = colour;
        this.plateColour = plateColour;
        this.print = print;
        // A hand-written legend takes the label back from whatever link was driving
        // it, until that link fires again
        this.linkText = "";
        changed();
    }

    /**
     * Called server-side when a display link delivers a line to this label.
     *
     * A link that is refreshing passively delivers the same string over and over, so
     * an unconditional save and sync of the whole host would be a packet a tick for
     * every panel in range of a redstone clock.
     */
    @Override
    public void acceptLine(String incoming) {
        String sanitised = PanelLabel.sanitise(incoming);
        if (sanitised.equals(linkText))
            return;
        linkText = sanitised;
        changed();
    }

    @Override
    public int linkColumns() {
        return print.columns(width, height);
    }

    @Override
    public Component linkRowName() {
        return Component.translatable("gui.create_flippies.control_host.row.label");
    }

    /**
     * The whole legend, which is worth showing because the strip may not be showing
     * all of it: a label drawn narrow, or set to a fixed height too large for what is
     * written on it, prints only as much as fits and keeps the rest.
     *
     * Nothing else goes in it. What a label is is the line written on it, and a
     * header naming the thing the player is plainly looking at is noise.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String legend = displayedText();
        if (legend.isEmpty())
            return false;
        tooltip.add(Component.literal("    ").append(Component.literal(legend).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putString("Text", text);
        tag.putString("LinkText", linkText);
        tag.putInt("Colour", colour);
        tag.putInt("PlateColour", plateColour);
        tag.putInt("Spans", width);
        tag.putInt("SpansHigh", height);
        if (layer != 0)
            tag.putInt("Layer", layer);
        print.write(tag);
    }

    @Override
    public void read(CompoundTag tag) {
        text = PanelLabel.sanitise(tag.getString("Text"));
        linkText = PanelLabel.sanitise(tag.getString("LinkText"));
        colour = tag.contains("Colour") ? PanelLabel.sanitiseColour(tag.getInt("Colour")) : DEFAULT_COLOUR;
        plateColour = tag.contains("PlateColour") ? PanelLabel.sanitiseColour(tag.getInt("PlateColour"))
                : PanelLabel.DEFAULT_PLATE_COLOUR;
        print = PanelLabelPrint.read(tag);
        // A label saved before labels could stack carries no layer and sits where it did
        resize(span(tag, "Spans", "Width", PanelLabel.DEFAULT_WIDTH),
                span(tag, "SpansHigh", "Height", PanelLabel.DEFAULT_HEIGHT), tag.getInt("Layer"));
    }

    /**
     * One dimension of the strip, in half pixels, from whichever of the two keys the
     * tag carries.
     *
     * The strip used to be counted in whole pixels and is now counted in half ones,
     * so a label saved under the old key is worth twice what it says — and one saved
     * before it could be drawn to size at all carries neither key and is the strip
     * the kind describes, which is the size it was placed at. Reading both is the
     * whole of the migration: nothing rewrites the old key, because the next save
     * writes the new one and the old is simply never read again.
     */
    private static int span(CompoundTag tag, String key, String legacyKey, int fallback) {
        if (tag.contains(key))
            return tag.getInt(key);
        if (tag.contains(legacyKey))
            return tag.getInt(legacyKey) * SpannedControlType.SPANS_PER_PIXEL;
        return fallback;
    }

    /** The strip, and the footprint that follows from it, in one place. */
    /**
     * A strip that is already this size keeps the footprint it has, rather than being
     * handed an identical new one.
     *
     * It is read from a tag that always carries the spans, so this runs on every sync
     * of the block this label is in — which is every sync caused by anything else in
     * that block as well, since a cell syncs whole. Building a fresh
     * {@link ControlFootprint} each time would be a small waste on its own; what makes
     * it worth guarding is that the host takes a footprint staying the same object as
     * its evidence that nothing in the cell has moved, and so that the shape it has
     * already built is still the right one. A new footprint for an unchanged strip
     * would retract that evidence a few times a second and have a panel of two dozen
     * instruments rebuild its whole shape for every reading written onto a label.
     */
    private void resize(int width, int height, int layer) {
        int spanned = PanelLabel.span(width);
        int high = PanelLabel.span(height);
        int stacked = PanelLabel.layer(layer);
        if (footprint != null && spanned == this.width && high == this.height && stacked == this.layer)
            return;
        this.width = spanned;
        this.height = high;
        this.layer = stacked;
        this.footprint = PanelLabel.footprint(spanned, high, stacked);
    }
}
