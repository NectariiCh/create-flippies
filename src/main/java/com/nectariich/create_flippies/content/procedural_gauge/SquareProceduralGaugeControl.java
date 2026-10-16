package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenGaugeMenuPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A Square Procedural Gauge mounted inside a host block: the link-driven gauge, with
 * the same supply requirement as {@link SquareProceduralGaugeBlockEntity} and the
 * same procedurally printed dial.
 *
 * The needle answers to a display link rather than to what is across its terminals,
 * and the terminals are what decide whether it answers at all — the gauge only reads
 * while it is fed roughly its rated 5, 12 or 24 volts, so an unpowered one sits at
 * zero however much a link tells it. That load is a real resistance in the circuit,
 * so switching the rated voltage retunes the wire in place rather than rebuilding
 * it: the run of terminal indices must never be renumbered, and rebuilding a circuit
 * to change one number would risk exactly that.
 *
 * Its line comes from the host's shared numbering over every
 * {@link LinkDrivenControl} on the block, which is what makes a mounted gauge
 * possible at all — see that interface. The dial's own scale minimum and maximum
 * double as the expected range of the data, exactly as the block form's do.
 */
public class SquareProceduralGaugeControl extends HostedControl
        implements MountedProceduralGauge, LinkDrivenControl {

    /**
     * How much text the link editor offers for a row that is a gauge. A gauge shows
     * no text at all, so this is not a width but a promise not to trim: the number
     * has to survive whatever formatting the source wrapped it in, and cutting the
     * line short could take the digits with it.
     */
    private static final int LINK_COLUMNS = 64;

    /** Null while this gauge still wears the printing it shipped with. */
    private GaugeFaceDefinition customFace;

    private SupplyVoltage supply = SupplyVoltage.V12;

    private float value;
    private boolean hasValue;
    private String lastText = "";

    private IElectricNode positive;
    private IElectricNode negative;
    private ElectricWire load;

    /** Read by the renderer, which interpolates between the two. */
    public float dialState;
    public float prevDialState;

    SquareProceduralGaugeControl(SquareProceduralGaugeControlType type) {
        super(type);
    }

    @Override
    public GaugeFaceDefinition faceDefinition() {
        return customFace != null ? customFace : SquareProceduralGaugeBlockEntity.defaultFace();
    }

    @Override
    public float dialState() {
        return dialState;
    }

    @Override
    public float prevDialState() {
        return prevDialState;
    }

    public SupplyVoltage getSupply() {
        return supply;
    }

    /** Called server-side from the editor; null puts the shipped printing back. */
    public void configure(GaugeFaceDefinition face) {
        customFace = face;
        changed();
    }

    /** Server-side: switch the rated supply voltage, retuning the load in place. */
    public void setSupply(SupplyVoltage supply) {
        this.supply = supply;
        if (load != null)
            load.setResistance(supply.loadResistance());
        changed();
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(MountedGaugeGeometry.TERMINALS);
        positive = builder.terminalNode(0);
        negative = builder.terminalNode(1);
        load = builder.connect(supply.loadResistance(), positive, negative);
    }

    /** Potential difference across the terminals, in volts. */
    public float measuredVoltage() {
        if (positive == null || negative == null)
            return 0;
        return (float) (positive.getVoltage() - negative.getVoltage());
    }

    /** Whether the terminals currently see roughly the rated supply voltage. */
    public boolean isPowered() {
        return supply.accepts(measuredVoltage());
    }

    /**
     * Takes one line from a link: the text for the goggle readout, and the number in
     * it for the needle. A line with no number in it leaves the needle where it was
     * rather than dropping it to zero — a source that formats "--" while it warms up
     * should not make the gauge look unpowered.
     */
    @Override
    public void acceptLine(String text) {
        Double number = LinkNumber.in(text);
        float incoming = number == null ? value : number.floatValue();
        // A link refreshing passively delivers the same line over and over, so
        // nothing may be saved or synced unless something actually moved
        if (text.equals(lastText) && incoming == value && (hasValue || number == null))
            return;
        lastText = text;
        if (number != null) {
            value = incoming;
            hasValue = true;
        }
        changed();
    }

    @Override
    public int linkColumns() {
        return LINK_COLUMNS;
    }

    @Override
    public Component linkRowName() {
        return Component.translatable("gui.create_flippies.control_host.row.gauge");
    }

    @Override
    public void tick() {
        prevDialState = dialState;
        dialState += (target() - dialState) * ProceduralGaugeBlockEntity.NEEDLE_EASING;
    }

    private float target() {
        if (!isPowered() || !hasValue)
            return 0;
        GaugeFaceDefinition face = faceDefinition();
        float span = face.maxValue - face.minValue;
        if (span == 0)
            return 0;
        return ProceduralGaugeBlockEntity.clampProgress((value - face.minValue) / span);
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
                new OpenGaugeMenuPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putString("Supply", supply.getSerializedName());
        tag.putFloat("Value", value);
        tag.putBoolean("HasValue", hasValue);
        tag.putString("LastText", lastText);
        if (customFace != null)
            tag.put("Face", customFace.toNbt());
    }

    @Override
    public void read(CompoundTag tag) {
        supply = SupplyVoltage.byName(tag.getString("Supply"));
        value = tag.getFloat("Value");
        hasValue = tag.getBoolean("HasValue");
        lastText = tag.getString("LastText");
        customFace = tag.contains("Face") ? GaugeFaceDefinition.fromNbt(tag.getCompound("Face")) : null;
        // The circuit may have been built before this ran, from the rated voltage
        // this control started at rather than the one it was saved with
        if (load != null)
            load.setResistance(supply.loadResistance());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String prefix = SquareProceduralGaugeBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(prefix + "header").withStyle(ChatFormatting.WHITE)));
        Component state = isPowered()
                ? Component.translatable(prefix + "supply_ok").withStyle(ChatFormatting.GREEN)
                : Component.translatable(prefix + "supply_missing").withStyle(ChatFormatting.RED);
        tooltip.add(indent(Component.translatable(prefix + "supply",
                supply.label().copy().withStyle(ChatFormatting.AQUA), state).withStyle(ChatFormatting.GRAY)));
        // The link's output verbatim, nothing else: no label, and no restating the
        // number pulled out of it or the range, both of which the dial already shows
        if (lastText.isEmpty()) {
            tooltip.add(indent(Component.translatable(prefix + "no_data").withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        tooltip.add(indent(Component.literal(lastText).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
