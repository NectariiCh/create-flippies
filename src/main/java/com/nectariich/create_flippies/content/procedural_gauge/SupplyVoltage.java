package com.nectariich.create_flippies.content.procedural_gauge;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * Rated supply of the Square Procedural Gauge (display link receiver). The gauge
 * only operates while its terminals see roughly the rated voltage.
 *
 * Also what an {@link com.nectariich.create_flippies.content.display.AlphanumDisplay
 * alphanumeric display} is rated for, which is why the tolerance and the three rails
 * are stated here rather than per instrument: a rail that runs a 12 V gauge has to
 * run a 12 V display, and two copies of {@link #TOLERANCE} would be two chances to
 * disagree about that. Only {@link #loadResistance} is the gauge's own — a display
 * draws differently, and has two feeds to draw with — so anything else sizing itself
 * from a rating works from {@link #rated()}.
 */
public enum SupplyVoltage implements StringRepresentable {
    V5("5v", 5),
    V12("12v", 12),
    V24("24v", 24);

    /** Accepted deviation from the rated voltage, as a fraction. */
    private static final float TOLERANCE = 0.25f;
    /** Rated power draw in watts; determines the load resistance. */
    private static final float RATED_POWER = 1.0f;

    private final String name;
    private final float rated;

    SupplyVoltage(String name, float rated) {
        this.name = name;
        this.rated = rated;
    }

    public float rated() {
        return rated;
    }

    public boolean accepts(float voltage) {
        float magnitude = Math.abs(voltage);
        return magnitude >= rated * (1 - TOLERANCE) && magnitude <= rated * (1 + TOLERANCE);
    }

    public float loadResistance() {
        return rated * rated / RATED_POWER;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.procedural_gauge.supply." + name);
    }

    public SupplyVoltage next() {
        SupplyVoltage[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static SupplyVoltage byName(String name) {
        for (SupplyVoltage supply : values())
            if (supply.name.equals(name))
                return supply;
        return V12;
    }
}
