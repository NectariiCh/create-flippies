package com.nectariich.create_flippies.content.display;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * Everything about an alphanumeric display that both forms it comes in have to agree
 * on: what its two feeds cost the circuit, when each of them counts as live, what it
 * is allowed to be sent, and what a pair of goggles says about one.
 *
 * <h2>Two feeds, one return</h2>
 *
 * A display is not one load but two that happen to share a ground. {@code VCC} runs
 * the device: with it the glass shows whatever was last sent and without it the glass
 * is blank, however much is on the other pin. {@code BL} runs the backlight, which is
 * a lamp and nothing else — it does not decide what is shown, only whether it can be
 * read. Wiring the two separately is the whole point of the arrangement: a panel can
 * leave its readouts live and switch the backlights off with the room lights, or
 * drive a backlight from the circuit being watched so that a display lights up when
 * the thing it is reporting on does.
 *
 * Both are plain resistances rather than anything that reads a voltage, so both are a
 * real cost to whatever is feeding them, and neither is stored: whether either is
 * live is a question about the potential across two nodes, and Power Grid syncs node
 * voltages, so both sides can answer it at draw time from what they already have.
 * That is the same arrangement the indicators rely on for their lamps and the gauges
 * for their needles.
 *
 * The backlight draws the more of the two. A screen is a trickle and a lamp behind it
 * is not, which is also what makes switching the backlights off worth doing.
 */
public final class AlphanumDisplay {

    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alphanum_display.goggles.";

    /** What the device itself draws at exactly its rated voltage, in watts. */
    public static final float LOGIC_WATTS = 0.5f;

    /** What the backlight draws at exactly its rated voltage, in watts. */
    public static final float BACKLIGHT_WATTS = 1.5f;

    private AlphanumDisplay() {}

    /** Ohms across {@code VCC} and {@code GND} on a display of this rating. */
    public static float logicResistance(SupplyVoltage supply) {
        return resistance(supply, LOGIC_WATTS);
    }

    /** Ohms across {@code BL} and {@code GND} on a display of this rating. */
    public static float backlightResistance(SupplyVoltage supply) {
        return resistance(supply, BACKLIGHT_WATTS);
    }

    /** Potential difference between two nodes, in volts; null-safe while a circuit builds. */
    public static float voltageBetween(IElectricNode a, IElectricNode b) {
        if (a == null || b == null)
            return 0;
        return (float) (a.getVoltage() - b.getVoltage());
    }

    /**
     * Whether a feed at this voltage counts as live on a display of this rating.
     *
     * The gauges' own tolerance, through {@link SupplyVoltage#accepts}, so that a rail
     * which runs a 12 V gauge runs a 12 V display. The sign is dropped because neither
     * feed cares which way round it was wired — the device is a resistance and a lamp,
     * and refusing to work when a player reversed a pair would be a puzzle with no
     * clue in it.
     */
    public static boolean live(SupplyVoltage supply, float volts) {
        return supply.accepts(volts);
    }

    /**
     * The display mounted at exactly this mount, or null if there is none.
     *
     * A mount identifies a control within its host because no two controls may occupy
     * the same space, which makes it the one handle a packet can carry that does not
     * depend on the order the host happens to hold its controls in.
     */
    public static AlphanumDisplayControl at(BlockEntity blockEntity, ControlMount mount) {
        if (!(blockEntity instanceof ControlRackHolder host))
            return null;
        for (HostedControl control : host.controls())
            if (control instanceof AlphanumDisplayControl display && mount.equals(display.mount()))
                return display;
        return null;
    }

    /**
     * What a pair of goggles says about a display, in either form.
     *
     * The state of the two feeds, and then each lettering element's text verbatim and
     * nothing else: no label on it, and nothing derived from it. The glass already
     * shows the text, so the tooltip exists for the part the glass cannot: whether the
     * device is powered, whether the backlight is on, and the whole of a line the glass
     * has cut short.
     *
     * @return whether anything was added, which is always
     */
    public static boolean addTooltip(List<Component> tooltip, SupplyVoltage supply, boolean powered, boolean backlit,
            ScreenElements elements) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "supply",
                supply.label().copy().withStyle(ChatFormatting.AQUA), state(powered))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "backlight", state(backlit))
                .withStyle(ChatFormatting.GRAY)));
        // Nothing is shown at all without the supply, so there is no line to report —
        // saying what a dark display would be showing invents a reading
        if (!powered) {
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "off").withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        // Every piece of lettering in the order a link fills them, one to a line;
        // shapes have nothing to read out
        boolean any = false;
        for (ScreenElement element : elements.texts())
            if (!element.text().isEmpty()) {
                tooltip.add(indent(Component.literal(element.text()).withStyle(ChatFormatting.WHITE)));
                any = true;
            }
        if (!any)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "no_data").withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    private static Component state(boolean on) {
        return on ? Component.translatable(GOGGLE_PREFIX + "on").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "off_state").withStyle(ChatFormatting.RED);
    }

    private static float resistance(SupplyVoltage supply, float watts) {
        return supply.rated() * supply.rated() / watts;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
