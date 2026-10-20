package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;

import net.minecraft.network.chat.Component;

/**
 * What a design is built on: the rear of one of the existing controls, whose
 * contacts, circuit and behaviour it keeps, with its front replaced by an imported
 * model; or, for the annunciator rack, nothing at all, its model generated from the
 * rack it describes; or, for a gauge, the barrel and studs of the procedural gauges,
 * with an imported model in front whose needle follows the reading over a face
 * printed by one of the gauge face generators.
 */
public enum DesignKind {

    SWITCH_2P("switch_2p", RotarySwitchKind.TWO_POSITION, 4),
    SWITCH_3P("switch_3p", RotarySwitchKind.THREE_POSITION, 4),
    /**
     * A breaker control switch: a three-position switch whose handle always springs
     * back to the centre, with a flag that latches the way it was last thrown. See
     * {@link BreakerIndicator}.
     */
    BKR("bkr", RotarySwitchKind.THREE_POSITION, 4),
    BUTTON_2P("button_2p", null, 1.5f),
    /** Made in the workbench's rack mode, its model built from its rack rather than imported. No contacts yet. */
    ANNUNCIATOR("annunciator", null, 24),
    /**
     * Made in the workbench's gauge mode: an imported model with a rotary dial, as the
     * Square gauges print, on one of its submeshes and a needle that turns across it.
     * See {@link com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial}.
     */
    GAUGE("gauge", null, 8),
    /** The same with an edgewise face, as the edgewise voltmeters print, and a needle that slides or swings along it. */
    EDGEWISE_GAUGE("edgewise_gauge", null, 4);

    private final String id;
    private final RotarySwitchKind rotary;
    private final float frontWidth;

    DesignKind(String id, RotarySwitchKind rotary, float frontWidth) {
        this.id = id;
        this.rotary = rotary;
        this.frontWidth = frontWidth;
    }

    public String id() {
        return id;
    }

    /** The registry name of this kind's item, which is also its control kind's name. */
    public String itemId() {
        return "designed_" + id;
    }

    /** The switch this design turns like, or null for the button, the rack and the gauges. */
    public RotarySwitchKind rotary() {
        return rotary;
    }

    public boolean isButton() {
        return this == BUTTON_2P;
    }

    /** A breaker control switch: springs back from both sides, and has a latching flag. */
    public boolean isBreaker() {
        return this == BKR;
    }

    /** An annunciator rack: nothing moves, and its model and legends come from its rack. */
    public boolean isRack() {
        return this == ANNUNCIATOR;
    }

    /**
     * How wide the front this design replaces is, in pixels: the rotary switch's
     * plate, or the large indicator's cap. What "fit" scales a model to.
     */
    public float frontWidth() {
        return frontWidth;
    }

    public Component label() {
        return Component.translatable("gui." + CreateFlippies.MOD_ID + ".design_workbench.kind." + id);
    }

    /** A designed gauge, of either face. Its needle is its moving parts, moved by the reading. */
    public boolean isGauge() {
        return this == GAUGE || this == EDGEWISE_GAUGE;
    }

    /** A designed gauge with an edgewise face; the other is rotary. */
    public boolean isEdgewise() {
        return this == EDGEWISE_GAUGE;
    }

    /**
     * Whether the model is generated from what the design describes (a rack) rather
     * than imported: nothing of it moves, and it is never fetched to be edited, only
     * built again.
     */
    public boolean isGenerated() {
        return isRack();
    }

    public static DesignKind of(String id) {
        for (DesignKind kind : values())
            if (kind.id.equals(id))
                return kind;
        return SWITCH_2P;
    }
}
