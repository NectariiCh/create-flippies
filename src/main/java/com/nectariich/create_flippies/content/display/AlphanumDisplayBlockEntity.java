package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * The device behind an {@link AlphanumDisplayBlock}: one line of text, how it is set,
 * and the two feeds that decide whether it can be seen.
 *
 * There is nothing to tick and nothing to work out. The line is whatever a display
 * link last sent, the way a panel label's is; whether the device is powered and
 * whether its backlight is on are questions about node voltages, which Power Grid
 * syncs, so both sides answer them at draw time from what they already have. A
 * display is in that sense the simplest instrument in the mod — it measures nothing,
 * which is exactly the difference between it and a gauge.
 *
 * The line is saved as well as synced. A reload would otherwise blank every display
 * in the world until each link next fired, which for a link on a redstone pulse could
 * be never.
 */
public class AlphanumDisplayBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {

    /**
     * The three terminals. Deliberately declared without initialisers and assigned in
     * {@link #buildCircuit}, for the reason the other instruments' are: SmartBlockEntity
     * builds its behaviours from its own constructor, so Power Grid asks for the circuit
     * while this class is still a superclass under construction and an initialiser here
     * would run afterwards and wipe it.
     */
    private IElectricNode vcc;
    private IElectricNode gnd;
    private IElectricNode backlight;

    private String text = "";
    private DisplayPrint print;

    public AlphanumDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ALPHANUM_DISPLAY.get(), pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(DisplayPins.TERMINALS);
        vcc = builder.terminalNode(DisplayPins.TERMINAL_VCC);
        gnd = builder.terminalNode(DisplayPins.TERMINAL_GND);
        backlight = builder.terminalNode(DisplayPins.TERMINAL_BL);
        // Two loads sharing the return, which is the whole of the device
        // electrically; see AlphanumDisplay for why they are separate feeds
        builder.connect(AlphanumDisplay.logicResistance(getSupply()), vcc, gnd);
        builder.connect(AlphanumDisplay.backlightResistance(getSupply()), backlight, gnd);
    }

    /**
     * The whole device, not the cell it stands in.
     *
     * Everything is drawn from partials by a block entity renderer, so the inherited
     * box — this block alone — would cull the display the moment its own cell left the
     * view frustum, while the barrel hanging four pixels through the panel was still
     * plainly in shot. The box comes from the mounted form's footprint at the mount a
     * standalone display sits on, which is the centre of its own face.
     */
    @Override
    protected AABB createRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof AlphanumDisplayBlock))
            return super.createRenderBoundingBox();
        ControlMount mount = ControlMount.centred(state.getValue(AlphanumDisplayBlock.FACING),
                state.getValue(AlphanumDisplayBlock.ROTATION));
        return DisplayPins.footprint(getForm()).bounds(mount).move(worldPosition);
    }

    /** A turn spins the device and keeps this block entity, so the box moves. */
    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        invalidateRenderBoundingBox();
    }

    /**
     * What this device is. Read off the block, which is what the items place; falls
     * back for the same reason {@link #createRenderBoundingBox} guards, that this is
     * asked while the circuit is built and a block entity can outlive its block.
     */
    public DisplayKind kind() {
        return getBlockState().getBlock() instanceof AlphanumDisplayBlock block
                ? block.getKind()
                : DisplayKind.BRDRL_1P5_SQ_12V;
    }

    public DisplayForm getForm() {
        return kind().form();
    }

    public SupplyVoltage getSupply() {
        return kind().supply();
    }

    /** How the line is set; the form's own default until a screwdriver says otherwise. */
    public DisplayPrint print() {
        if (print == null)
            print = DisplayPrint.defaultFor(getForm());
        return print;
    }

    /** The line as it will be drawn, which is nothing at all without the supply. */
    public String displayedText() {
        return isPowered() ? text : "";
    }

    /** The line the device is holding, whether or not it is powered to show it. */
    public String text() {
        return text;
    }

    public boolean isPowered() {
        return AlphanumDisplay.live(getSupply(), AlphanumDisplay.voltageBetween(vcc, gnd));
    }

    public boolean isBacklit() {
        return AlphanumDisplay.live(getSupply(), AlphanumDisplay.voltageBetween(backlight, gnd));
    }

    /**
     * Called server-side when a display link delivers a line.
     *
     * A link refreshing passively delivers the same string over and over, so an
     * unconditional save and sync would be a packet a tick for every display in range
     * of a redstone clock.
     */
    public void acceptLine(String incoming) {
        String sanitised = AlphanumDisplay.sanitise(incoming);
        if (sanitised.equals(text))
            return;
        text = sanitised;
        notifyUpdate();
    }

    /** Server-side: everything the configuration menu decides, applied together. */
    public void configure(DisplayPrint print) {
        this.print = print;
        notifyUpdate();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putString("Text", text);
        print().write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        text = AlphanumDisplay.sanitise(tag.getString("Text"));
        print = DisplayPrint.read(tag, getForm());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return AlphanumDisplay.addTooltip(tooltip, getSupply(), isPowered(), isBacklit(), text);
    }
}
