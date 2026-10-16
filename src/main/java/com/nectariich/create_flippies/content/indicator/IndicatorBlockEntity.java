package com.nectariich.create_flippies.content.indicator;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * The unit behind an {@link IndicatorBlock}: a lamp across two terminals, three
 * pairs of button contacts behind it, a lens colour and whatever is printed on the
 * cap.
 *
 * The lamp itself has nothing to tick. It has no animation to ease and no state that
 * follows the circuit — whether it is lit is a question about the voltage across its
 * own two terminals, and Power Grid syncs node voltages to the client, so both sides
 * can answer it at draw time from what they already have. That is the same
 * arrangement the gauges rely on for their needles, minus the easing.
 *
 * What does tick is the button, and only on the server: a spring-return cap is held
 * down by one player, and {@link IndicatorButton#tick} is what notices when that
 * player is no longer there to be holding it.
 *
 * Everything else here is saved because nothing else knows it: unlike the rating and
 * the cap size, which are what block this is, the colour, the action and the legend
 * are what a screwdriver set afterwards.
 */
public class IndicatorBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {

    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator.goggles.";

    /**
     * How much of its rated voltage a lamp needs across it before it reads as on.
     *
     * A lens does not fade in evenly from nothing, and there are two models rather
     * than a continuum, so the question this answers is where the cut is. Low enough
     * that a lamp on a sagging line still shows, high enough that leakage through a
     * neighbouring circuit does not light a panel up.
     */
    public static final float LIT_THRESHOLD = 0.4f;

    /**
     * The lamp's two terminals. Deliberately declared without initialisers and
     * assigned in {@link #buildCircuit}, for the reason the rotary switch's contacts
     * are: SmartBlockEntity builds its behaviours from its own constructor, so Power
     * Grid asks for the circuit while this class is still a superclass under
     * construction and an initialiser here would run afterwards and wipe it.
     */
    private IElectricNode terminalA;
    private IElectricNode terminalB;

    /**
     * The plunger and its three poles. Made on demand for the same reason, one step
     * worse: the circuit is built before any field of this class exists, and the
     * builder is where the poles come from, so the button has to be there to take
     * them — see {@link #button()}.
     */
    private IndicatorButton button;

    private IndicatorColor color;
    private IndicatorLegend legend;

    public IndicatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INDICATOR.get(), pos, state);
        color = IndicatorColor.defaultColor();
        legend = IndicatorLegend.BLANK;
    }

    /**
     * The plunger, made if it is not there yet.
     *
     * Not a field initialiser and not set in the constructor, because both run after
     * {@code super()} and {@link #buildCircuit} runs inside it. Every caller goes
     * through here rather than touching the field, so there is no order in which one
     * of them can find a null.
     */
    public IndicatorButton button() {
        if (button == null)
            button = new IndicatorButton();
        return button;
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(IndicatorLamp.TERMINALS);
        terminalA = builder.terminalNode(IndicatorLamp.TERMINAL_A);
        terminalB = builder.terminalNode(IndicatorLamp.TERMINAL_B);
        builder.connect(rating().resistance(), terminalA, terminalB);

        // One switched wire per pole, always three of them and always in this order,
        // whatever the action says. Power Grid streams a block's switched-wire states
        // to the client positionally with no count, so a unit whose wire count
        // depended on its own settings would shift every later control's readings on
        // the same panel — the same reason the rotary switch switches wires rather
        // than adding and removing them
        SwitchedWire[] poles = new SwitchedWire[IndicatorLamp.POLES];
        boolean made = button().isPressed();
        for (int pole = 0; pole < poles.length; pole++)
            poles[pole] = builder.connectSwitch(IndicatorLamp.CONTACT_RESISTANCE,
                    builder.terminalNode(IndicatorLamp.poleTerminal(pole, 0)),
                    builder.terminalNode(IndicatorLamp.poleTerminal(pole, 1)), made);
        button().setPoles(poles);
    }

    /**
     * The whole unit, not the cell it stands in.
     *
     * Everything is drawn from partials by a block entity renderer, so the inherited
     * box — this block alone — would cull the unit the moment its own cell left the
     * view frustum, while the barrel hanging four pixels through the panel was still
     * plainly in shot. The box comes from the mounted form's footprint at the mount a
     * standalone unit sits on, which is the centre of its own face.
     */
    @Override
    protected AABB createRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof IndicatorBlock))
            return super.createRenderBoundingBox();
        ControlMount mount = ControlMount.centred(state.getValue(IndicatorBlock.FACING),
                state.getValue(IndicatorBlock.ROTATION));
        return IndicatorLamp.footprint(size()).bounds(mount).move(worldPosition);
    }

    /** A wrench turn spins the unit and keeps this block entity, so the box moves. */
    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        invalidateRenderBoundingBox();
    }

    /**
     * What this unit is. Read off the block, which is what the ten items place; falls
     * back to the smallest for the same reason {@link #createRenderBoundingBox}
     * guards, that this is asked while the circuit is built and a block entity can
     * outlive its block.
     */
    public IndicatorKind kind() {
        return getBlockState().getBlock() instanceof IndicatorBlock block
                ? block.getKind()
                : IndicatorKind.MED_6V;
    }

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
        return button().action();
    }

    public boolean isPressed() {
        return button().isPressed();
    }

    public IndicatorLegend legend() {
        return legend;
    }

    /**
     * Server-side: everything the configuration menu decides, applied together.
     *
     * One call rather than a setter apiece because the colour and the action are not
     * independent — a black cap is only allowed on a button — so setting them one at
     * a time would pass through a state that is not allowed and would have to be
     * corrected afterwards. Nothing here is electrical or geometric except the action,
     * and even that only opens contacts; the circuit's shape never changes.
     */
    public void configure(IndicatorColor color, IndicatorAction action, IndicatorLegend legend) {
        button().setAction(action);
        this.color = IndicatorColor.sanitise(color, action);
        this.legend = legend;
        notifyUpdate();
    }

    /** Server-side: the use key has gone down on the cap. */
    public void press(Player player) {
        if (!button().press(player))
            return;
        IndicatorButton.playClick(level, worldPosition, button().isPressed());
        notifyUpdate();
    }

    /** Server-side: the use key has come up; only the holder's release counts. */
    public void release(Player player) {
        if (!button().release(player))
            return;
        IndicatorButton.playClick(level, worldPosition, false);
        notifyUpdate();
    }

    /**
     * Nothing about the lamp needs a tick. This is the watchdog on a held cap, and it
     * does nothing at all on the client and nothing on a unit that is not a
     * spring-return button standing pressed.
     */
    @Override
    public void tick() {
        super.tick();
        if (button().tick(level, worldPosition)) {
            IndicatorButton.playClick(level, worldPosition, false);
            notifyUpdate();
        }
    }

    /** Potential difference across the lamp, in volts; sign is not meaningful. */
    public float measuredVoltage() {
        return voltageBetween(terminalA, terminalB);
    }

    /** How hard the lamp is being driven, as a fraction of its rating. */
    public float brightness() {
        return brightness(measuredVoltage(), rating());
    }

    public boolean isLit() {
        return brightness() >= LIT_THRESHOLD;
    }

    /**
     * Shared with {@link IndicatorControl}, so a unit screwed into a panel lights at
     * exactly the same point as one placed as a block of its own.
     */
    public static float voltageBetween(IElectricNode a, IElectricNode b) {
        if (a == null || b == null)
            return 0;
        return (float) (a.getVoltage() - b.getVoltage());
    }

    /** As above: the lamp is bipolar, so it is the size of the difference that lights it. */
    public static float brightness(float volts, IndicatorRating rating) {
        if (rating.volts() <= 0 || Float.isNaN(volts))
            return 0;
        return Math.abs(volts) / rating.volts();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putString("Color", color.id());
        button().write(tag);
        legend.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        button().read(tag);
        color = IndicatorColor.sanitise(IndicatorColor.of(tag.getString("Color"), IndicatorColor.defaultColor()),
                action());
        legend = IndicatorLegend.read(tag);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return addLampTooltip(tooltip, rating(), measuredVoltage(), color, button());
    }

    /** Shared with the mounted form, which says exactly the same things. */
    static boolean addLampTooltip(List<Component> tooltip, IndicatorRating rating, float volts,
            IndicatorColor color, IndicatorButton button) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "voltage",
                Component.literal(String.format("%.2f", Math.abs(volts))).withStyle(ChatFormatting.AQUA),
                rating.label().copy().withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "lens",
                color.label().copy().withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        // Only worth a line on a unit that clicks: on a plain lamp it would say that
        // the thing the player is looking at does nothing, once per lamp on the panel
        if (button.action().clickable())
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "contacts",
                    button.action().label().copy().withStyle(ChatFormatting.AQUA),
                    Component.translatable(GOGGLE_PREFIX + (button.isPressed() ? "closed" : "open"))
                            .withStyle(button.isPressed() ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                    .withStyle(ChatFormatting.GRAY)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
