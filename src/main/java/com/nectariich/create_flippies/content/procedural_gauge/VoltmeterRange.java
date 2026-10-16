package com.nectariich.create_flippies.content.procedural_gauge;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * The eight Square Procedural Voltmeter variants — each range is its own block.
 * Each carries the default dial face that variant ships with; the printed
 * face can be customized per gauge from its menu, the electrical range cannot.
 */
public enum VoltmeterRange implements StringRepresentable {
    V0_5("0_5", 0, 5, 1, 4, "0"),
    V0_12("0_12", 0, 12, 2, 3, "0"),
    V0_24("0_24", 0, 24, 4, 3, "0"),
    V0_1("0_1", 0, 1, 0.2f, 1, "0.0"),
    N1_1("n1_1", -1, 1, 0.5f, 4, "0.0"),
    N5_5("n5_5", -5, 5, 1, 4, "0"),
    V0_10("0_10", 0, 10, 2, 3, "0"),
    N10_10("n10_10", -10, 10, 5, 4, "0");

    private final String name;
    private final float min;
    private final float max;
    private final GaugeFaceDefinition face;

    VoltmeterRange(String name, float min, float max, float majorTick, int minorPerMajor, String labelFormat) {
        this.name = name;
        this.min = min;
        this.max = max;
        this.face = GaugeFaceDefinition.builder(min, max)
                .displayName("Square")
                .units("V")
                .majorTickInterval(majorTick)
                .minorTicksPerMajor(minorPerMajor)
                .labelFormat(labelFormat)
                .build();
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }

    public GaugeFaceDefinition face() {
        return face;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.voltmeter.range." + name);
    }

    /** Registry id of this variant's block. */
    public String blockId() {
        return "square_procedural_voltmeter_" + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static VoltmeterRange byName(String name) {
        for (VoltmeterRange range : values())
            if (range.name.equals(name))
                return range;
        return V0_12;
    }
}
