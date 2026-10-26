package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
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
 * The device behind an {@link AlphanumDisplayBlock}: what is on its glass, as
 * {@link ScreenElements}, and the two feeds that decide whether it can be seen.
 *
 * There is nothing to tick and nothing to work out. The text is whatever a display
 * link last sent, the way a panel label's is; whether the device is powered and
 * whether its backlight is on are questions about node voltages, which Power Grid
 * syncs, so both sides answer them at draw time from what they already have. A
 * display is in that sense the simplest instrument in the mod — it measures nothing,
 * which is exactly the difference between it and a gauge.
 *
 * The elements are saved as well as synced, text included. A reload would otherwise
 * blank every display in the world until each link next fired, which for a link on a
 * redstone pulse could be never. A display saved as one line, before elements, loads
 * as element 0 showing that line exactly as it did; see {@link ScreenElements#read}.
 */
public class AlphanumDisplayBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation,
        ElementDisplay {

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

    /**
     * Read in place on every sync rather than replaced, so a renderer holding this
     * object always sees the latest. Starts as the shipped default (one line over the
     * whole glass at the form's default height); a loaded tag replaces it, migrating a
     * single-line save.
     */
    private final ScreenElements elements = new ScreenElements();

    public AlphanumDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ALPHANUM_DISPLAY.get(), pos, state);
        // An empty tag read as a single-line save is the line a display always shipped
        // with: DisplayPrint's per-form default height, not ScreenElements.defaults'
        // fitted one, so a newly placed display prints and tells links its columns as
        // before. The form (so the viewport) is known from here: the state is the block's
        elements.read(new CompoundTag(), viewport(), getForm());
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

    /** What the glass shows, back to front. */
    @Override
    public ScreenElements elements() {
        return elements;
    }

    /** The usable glass, in the frame its lettering prints in at no turn. */
    @Override
    public Viewport viewport() {
        return Viewport.of(getForm());
    }

    @Override
    public void elementsChanged() {
        notifyUpdate();
    }

    /** Characters a display link is told will fit: the first lettering element's estimate. */
    public int linkColumns() {
        List<ScreenElement> texts = elements.texts();
        return texts.isEmpty() ? 1 : texts.get(0).columns();
    }

    @Override
    public boolean isPowered() {
        return AlphanumDisplay.live(getSupply(), AlphanumDisplay.voltageBetween(vcc, gnd));
    }

    @Override
    public boolean isBacklit() {
        return AlphanumDisplay.live(getSupply(), AlphanumDisplay.voltageBetween(backlight, gnd));
    }

    /**
     * Called server-side when a display link delivers a line, which goes to element 0
     * as the one line always did.
     *
     * A link refreshing passively delivers the same string over and over, so an
     * unconditional save and sync would be a packet a tick for every display in range
     * of a redstone clock; {@link ScreenElements#acceptLinked} says whether anything
     * changed.
     */
    public void acceptLine(String incoming) {
        if (elements.acceptLinked(0, 0, incoming))
            notifyUpdate();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        elements.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        elements.read(tag, viewport(), getForm());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return AlphanumDisplay.addTooltip(tooltip, getSupply(), isPowered(), isBacklit(), elements());
    }
}
