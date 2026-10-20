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
 * An Edgewise Procedural Gauge mounted inside a host block: the link-driven edgewise
 * gauge, with the same supply requirement as
 * {@link EdgewiseProceduralGaugeBlockEntity}. It works exactly as
 * {@link SquareProceduralGaugeControl} does, only with an edgewise face: the pointer
 * answers to a display link, only while the terminals are fed roughly the rated
 * voltage, and its line comes from the host's shared numbering over every
 * {@link LinkDrivenControl} on the block.
 */
public class EdgewiseProceduralGaugeControl extends HostedControl
        implements MountedEdgewiseGauge, LinkDrivenControl {

    /** As {@link SquareProceduralGaugeControl}'s: a promise not to trim the number off the line. */
    private static final int LINK_COLUMNS = 64;

    /** Null while this gauge still wears the printing it shipped with. */
    private EdgewiseFaceDefinition customFace;

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

    EdgewiseProceduralGaugeControl(EdgewiseProceduralGaugeControlType type) {
        super(type);
    }

    @Override
    public EdgewiseFaceDefinition faceDefinition() {
        return customFace != null ? customFace : EdgewiseProceduralGaugeBlockEntity.defaultFace();
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
    public void configure(EdgewiseFaceDefinition face) {
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

    /** As {@link SquareProceduralGaugeControl#acceptLine}: nothing is saved unless something moved. */
    @Override
    public void acceptLine(String text) {
        Double number = LinkNumber.in(text);
        float incoming = number == null ? value : number.floatValue();
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
        float target = !isPowered() || !hasValue ? 0
                : EdgewiseProceduralGaugeBlockEntity.progress(faceDefinition(), value);
        dialState += (target - dialState) * ProceduralGaugeBlockEntity.NEEDLE_EASING;
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
        customFace = tag.contains("Face") ? EdgewiseFaceDefinition.fromNbt(tag.getCompound("Face")) : null;
        // The circuit may have been built before this ran, from the rated voltage
        // this control started at rather than the one it was saved with
        if (load != null)
            load.setResistance(supply.loadResistance());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String prefix = SquareProceduralGaugeBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(EdgewiseProceduralGaugeBlockEntity.GOGGLE_PREFIX + "header")
                .withStyle(ChatFormatting.WHITE)));
        Component state = isPowered()
                ? Component.translatable(prefix + "supply_ok").withStyle(ChatFormatting.GREEN)
                : Component.translatable(prefix + "supply_missing").withStyle(ChatFormatting.RED);
        tooltip.add(indent(Component.translatable(prefix + "supply",
                supply.label().copy().withStyle(ChatFormatting.AQUA), state).withStyle(ChatFormatting.GRAY)));
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
