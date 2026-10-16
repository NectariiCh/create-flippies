package com.nectariich.create_flippies.content.gauge;

import net.minecraft.util.StringRepresentable;

/**
 * The look the universal gauge wears. Speedometer and stressometer borrow
 * Create's gauge partials; thermometer borrows Power Grid's.
 *
 * New entries go at the end — {@code ConfigureGaugePacket} sends themes by
 * ordinal.
 */
public enum GaugeTheme implements StringRepresentable {
    SPEEDOMETER("speedometer"),
    STRESSOMETER("stressometer"),
    THERMOMETER("thermometer");

    private final String name;

    GaugeTheme(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public GaugeTheme next() {
        GaugeTheme[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static GaugeTheme byName(String name) {
        for (GaugeTheme theme : values())
            if (theme.name.equals(name))
                return theme;
        return SPEEDOMETER;
    }
}
