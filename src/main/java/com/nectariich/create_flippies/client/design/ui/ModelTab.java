package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignOcclusion;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;

import dev.nectariich.hypergraphical.api.MeshUnit;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The workbench's Model tab: what the control is built on, the model and how it is
 * sized and placed, how a button behaves, how the model shows its ambient
 * occlusion, and the parts list where the moving and lit parts are picked.
 *
 * The gauge mode has the same tab, with the choice of a rotary or an edgewise gauge
 * in place of the controls; there the moving parts are the needle, and the parts
 * list is also where the dial face is given to a submesh.
 */
public final class ModelTab {

    private ModelTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();

        // What the control is built on. The rack and the gauges are modes of their own, so
        // a gauge chooses only between the gauges, and a control never offers them
        boolean gauge = state.kind.isGauge();
        List<DesignKind> kinds = Arrays.stream(DesignKind.values())
                .filter(kind -> !kind.isGenerated() && kind.isGauge() == gauge).toList();
        for (int i = 0; i < kinds.size(); i++) {
            DesignKind option = kinds.get(i);
            Button choice = panel.button(option.label(), i, kinds.size(), b -> {
                state.setKind(option);
                host.rebuild();
            });
            choice.active = option != state.kind;
        }
        panel.row();

        panel.button(t("browse"), 0, 1, t("browse.tooltip"), b -> host.browse());
        panel.row();
        panel.label(() -> state.info == null ? t("no_model")
                : Component.literal(state.modelName == null ? "" : state.modelName).append(Component
                        .literal("  " + state.info.triangleCount() + " tris").withStyle(s -> s.withColor(0x808080))),
                0xE0E0E0);
        panel.label(host::status, 0xC0C0C0);
        panel.gap(3);

        Button units = panel.button(unitLabel(state), 0, 2, t("units.tooltip"), b -> {
            state.unit = nextUnit(state.unit);
            b.setMessage(unitLabel(state));
            host.changed();
        });
        units.active = state.info != null;
        panel.number(1, 2, state.multiplier, value -> {
            if (value > 0) {
                state.multiplier = value;
                host.changed();
            }
        }, t("scale"));
        panel.row();

        Button fit = panel.button(t("fit"), 0, 2, t("fit.tooltip"), b -> {
            state.fit();
            host.rebuild();
        });
        fit.active = state.info != null;
        Button centre = panel.button(t("centre"), 1, 2, t("centre.tooltip"), b -> {
            state.centre();
            host.rebuild();
        });
        centre.active = state.info != null;
        panel.row();

        panel.label(t("offset"), 0xA0A0A0);
        panel.number(0, 3, state.offsetX, value -> {
            state.offsetX = value;
            host.changed();
        }, t("offset.x"));
        panel.number(1, 3, state.offsetY, value -> {
            state.offsetY = value;
            host.changed();
        }, t("offset.y"));
        panel.number(2, 3, state.offsetZ, value -> {
            state.offsetZ = value;
            host.changed();
        }, t("offset.z"));
        panel.row();

        if (state.kind.isButton()) {
            panel.button(state.action.label(), 0, 2, actionTooltip(state.action), b -> {
                state.action = state.action.next();
                b.setMessage(state.action.label());
                b.setTooltip(Tooltip.create(actionTooltip(state.action)));
                host.changed();
            });
            panel.button(state.rating.label(), 1, 2, t("rating.tooltip"), b -> {
                state.rating = IndicatorRating.values()[(state.rating.ordinal() + 1) % IndicatorRating.values().length];
                b.setMessage(state.rating.label());
            });
            panel.row();
        }

        occlusion(panel, host, state);

        panel.label(() -> {
            if (state.info == null)
                return null;
            if (state.moving.isEmpty())
                return t(gauge ? "needle.none" : "moving.none").withStyle(s -> s.withColor(0xFF8060));
            double[] front = state.boxes().front();
            return t("hitbox", NumberBox.format((float) (front[3] - front[0])),
                    NumberBox.format((float) (front[4] - front[1])), NumberBox.format((float) (front[5] - front[2])));
        }, 0xA0A0A0);
        panel.gap(2);

        PartsHierarchy.build(panel, host);
    }

    /**
     * The design's ambient occlusion: whether its model shows any, how strongly, how far
     * a crevice looks for what shades it, and with how many rays. Strength shows on the
     * preview at once; radius and quality re-bake it, which the preview holds off until
     * the slider stops ({@code DesignPreview}).
     */
    private static void occlusion(Panel panel, WorkbenchHost host, WorkbenchState state) {
        DesignOcclusion occlusion = state.occlusion;
        panel.gap(2);
        panel.label(t("ao"), 0xE0E0E0);
        panel.add(new Checkbox(Panel.X, panel.y, Panel.WIDTH, 20, t("ao.enabled"), occlusion.enabled()) {
            @Override
            public void onPress() {
                super.onPress();
                state.occlusion = state.occlusion.withEnabled(selected());
                host.rebuild();
            }
        }).setTooltip(Tooltip.create(t("ao.enabled.tooltip")));
        panel.row();

        AbstractSliderButton strength = panel.add(new AbstractSliderButton(Panel.X, panel.y, Panel.WIDTH, 20,
                strengthLabel(occlusion.strength()), occlusion.strength() / DesignOcclusion.MAX_STRENGTH) {
            @Override
            protected void updateMessage() {
                setMessage(strengthLabel(strength()));
            }

            @Override
            protected void applyValue() {
                state.occlusion = state.occlusion.withStrength(strength());
                host.changed();
            }

            private float strength() {
                return (float) value * DesignOcclusion.MAX_STRENGTH;
            }
        });
        strength.setTooltip(Tooltip.create(t("ao.strength.tooltip")));
        strength.active = occlusion.enabled();
        panel.row();

        float range = DesignOcclusion.MAX_RADIUS - DesignOcclusion.MIN_RADIUS;
        AbstractSliderButton radius = panel.add(new AbstractSliderButton(Panel.X, panel.y, Panel.WIDTH, 20,
                radiusLabel(occlusion.radius()), (occlusion.radius() - DesignOcclusion.MIN_RADIUS) / range) {
            @Override
            protected void updateMessage() {
                setMessage(radiusLabel(radius()));
            }

            @Override
            protected void applyValue() {
                state.occlusion = state.occlusion.withRadius(radius());
                host.changed();
            }

            private float radius() {
                return DesignOcclusion.MIN_RADIUS + (float) value * range;
            }
        });
        radius.setTooltip(Tooltip.create(t("ao.radius.tooltip")));
        radius.active = occlusion.enabled();
        panel.row();

        Button quality = panel.button(qualityLabel(occlusion), 0, 2, t("ao.quality.tooltip"), b -> {
            state.occlusion = state.occlusion.nextQuality();
            b.setMessage(qualityLabel(state.occlusion));
            host.changed();
        });
        quality.active = occlusion.enabled();
        // Always active: the sliders change the settings without rebuilding, so whether
        // they are at the defaults is not known when this row is laid out
        panel.button(t("ao.reset"), 1, 2, t("ao.reset.tooltip"), b -> {
            state.occlusion = DesignOcclusion.DEFAULT;
            host.rebuild();
        });
        panel.row();
    }

    private static Component strengthLabel(float strength) {
        return t("ao.strength", Math.round(strength * 100));
    }

    private static Component radiusLabel(float radius) {
        return t("ao.radius", Math.round(radius * 100));
    }

    private static Component qualityLabel(DesignOcclusion occlusion) {
        return t("ao.quality", t("ao.quality." + occlusion.quality()));
    }

    private static Component actionTooltip(IndicatorAction action) {
        return t("action." + action.id() + ".tooltip");
    }

    private static Component unitLabel(WorkbenchState state) {
        Component name = t("unit." + state.effectiveUnit().name().toLowerCase(Locale.ROOT));
        return state.unit == null ? t("units.auto", name) : t("units", name);
    }

    private static MeshUnit nextUnit(MeshUnit unit) {
        if (unit == null)
            return MeshUnit.values()[0];
        int next = unit.ordinal() + 1;
        return next < MeshUnit.values().length ? MeshUnit.values()[next] : null;
    }
}
