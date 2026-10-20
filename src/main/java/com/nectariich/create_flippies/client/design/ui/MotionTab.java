package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import com.nectariich.create_flippies.content.design_workbench.BreakerIndicator;
import com.nectariich.create_flippies.content.design_workbench.BreakerMode;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDraft;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeNeedleFit;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSpring;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The workbench's Motion tab: where the moving parts stand at each position.
 *
 * A switch has one row per position with its rotation about X, Y and Z in degrees
 * (applied Y first, then X, then Z, as {@link DesignMotion} does), and turns about a
 * pivot that is the mounting axis at the plate unless a custom one is set. A button
 * has a released and a pressed row, each a translation in pixels. The preview can
 * step through the positions or hold one.
 *
 * A switch also says here which of its positions spring back to rest: one checkbox
 * for a two-position switch, and one for each side of a three-position switch's
 * centre. The preview is left to step through every position regardless, since what
 * it is for is seeing how each pose looks.
 *
 * A breaker control switch always springs back from both sides, so both its boxes
 * are shown ticked and cannot be unticked. It has an Indicator section of its own as
 * well: what its contacts follow, the flag's two latched poses as rotations in the
 * same convention as the handle's, and the flag's own pivot.
 *
 * A gauge's needle has a section of its own ({@link #needle}): a rotary needle turns
 * about the pivot across the dial's sweep, so only the angle it is modelled at and
 * which way the face runs are set; an edgewise needle slides or swings between a
 * pose at the minimum and one at the maximum, which can be fitted to the ends of
 * the printed scale. The pivot is set as a switch's is.
 */
public final class MotionTab {

    private static final String[] AXES = { "x", "y", "z" };

    private MotionTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();
        if (state.kind.isGauge() && state.gauge != null) {
            needle(panel, host, state);
            return;
        }
        boolean button = state.kind.isButton();
        int positions = state.poses.length;

        // Which position the preview shows
        panel.label(t("preview"), 0xA0A0A0);
        for (int i = -1; i < positions; i++) {
            int position = i;
            Component label = i < 0 ? t("preview.animate")
                    : button ? t(i == 0 ? "preview.released" : "preview.pressed")
                    : Component.literal(Integer.toString(i + 1));
            Button choice = panel.button(label, i + 1, positions + 1, b -> {
                state.previewPosition = position;
                host.rebuild();
            });
            choice.active = state.previewPosition != position;
        }
        panel.row();

        for (int i = 0; i < positions; i++) {
            Component title = button ? t(i == 0 ? "pose.released" : "pose.pressed")
                    : t("pose.position", i + 1);
            panel.label(title, 0xE0E0E0);
            float[] pose = state.poses[i];
            for (int axis = 0; axis < 3; axis++) {
                int index = axis;
                panel.number(axis, 3, pose[axis], value -> {
                    pose[index] = value;
                    host.changed();
                }, t((button ? "pose.move." : "pose.turn.") + AXES[axis]));
            }
            panel.row();
        }

        if (!button)
            springs(panel, host, state);

        if (state.kind.isBreaker())
            indicator(panel, host, state);

        if (!button) {
            boolean custom = state.pivot != null;
            panel.add(new Checkbox(Panel.X, panel.y, Panel.WIDTH, 20, t("pivot.custom"), custom) {
                @Override
                public void onPress() {
                    super.onPress();
                    state.pivot = selected() ? DesignMotion.DEFAULT_PIVOT.clone() : null;
                    host.rebuild();
                }
            }).setTooltip(Tooltip.create(t("pivot.custom.tooltip")));
            panel.row();
            float[] pivot = custom ? state.pivot : DesignMotion.DEFAULT_PIVOT;
            for (int axis = 0; axis < 3; axis++) {
                int index = axis;
                NumberBox box = panel.number(axis, 3, pivot[axis], value -> {
                    if (state.pivot != null) {
                        state.pivot[index] = value;
                        host.changed();
                    }
                }, t("pivot." + AXES[axis]));
                box.setEditable(custom);
            }
            panel.row();
            Button pick = panel.button(t("pivot.pick"), 0, 1, t("pivot.pick.tooltip"), b -> {
                double[] moving = state.boxes().moving();
                state.pivot = new float[] { (float) (moving[0] + moving[3]) / 2, 0, (float) (moving[2] + moving[5]) / 2 };
                host.rebuild();
            });
            pick.active = state.info != null;
            panel.row();
        }

        panel.button(t("pose.reset"), 0, 1, b -> {
            state.poses = ControlDesign.defaultPoses(state.kind);
            if (!button)
                state.pivot = null;
            if (state.kind.isBreaker()) {
                state.indicatorPoses = BreakerIndicator.defaultPoses();
                state.indicatorPivot = null;
            }
            host.rebuild();
        });
        panel.row();
    }

    /**
     * A gauge's needle: where the preview holds it, how it moves, and its pivot.
     * The needle itself is the moving parts, picked with "M" in the Model tab.
     */
    private static void needle(Panel panel, WorkbenchHost host, WorkbenchState state) {
        GaugeDraft gauge = state.gauge;
        panel.label(t("preview"), 0xA0A0A0);
        for (int i = -1; i < 2; i++) {
            int position = i;
            Component label = t(i < 0 ? "preview.animate" : i == 0 ? "needle.min" : "needle.max");
            Button choice = panel.button(label, i + 1, 3, b -> {
                state.previewPosition = position;
                host.rebuild();
            });
            choice.active = state.previewPosition != position;
        }
        panel.row();
        if (state.moving.isEmpty())
            panel.label(t("needle.none").withStyle(s -> s.withColor(0xFF8060)), 0xA0A0A0);

        boolean pivoted;
        if (!gauge.edgewise) {
            panel.label(t("needle.rotary"), 0xE0E0E0);
            panel.number(0, 2, gauge.needleZero, DialTab.any(host, value -> gauge.needleZero = value),
                    t("needle.zero"));
            panel.button(t(gauge.reverse ? "needle.reverse.on" : "needle.reverse.off"), 1, 2,
                    t("needle.reverse.tooltip"), b -> {
                        gauge.reverse = !gauge.reverse;
                        b.setMessage(t(gauge.reverse ? "needle.reverse.on" : "needle.reverse.off"));
                        host.changed();
                    });
            panel.row();
            pivoted = true;
        } else {
            panel.button(t(gauge.slide ? "needle.slide" : "needle.swing"), 0, 1, t("needle.motion.tooltip"), b -> {
                gauge.slide = !gauge.slide;
                // A slide's poses are distances and a swing's are angles
                state.poses = ControlDesign.defaultPoses(state.kind);
                host.rebuild();
            });
            panel.row();
            for (int i = 0; i < state.poses.length; i++) {
                panel.label(t(i == 0 ? "needle.at_min" : "needle.at_max"), 0xE0E0E0);
                float[] pose = state.poses[i];
                for (int axis = 0; axis < 3; axis++) {
                    int index = axis;
                    panel.number(axis, 3, pose[axis], value -> {
                        pose[index] = value;
                        host.changed();
                    }, t((gauge.slide ? "pose.move." : "pose.turn.") + AXES[axis]));
                }
                panel.row();
            }
            Button fit = panel.button(t("needle.fit"), 0, 1, t("needle.fit.tooltip"), b -> {
                double[] face = state.faceBox();
                double[] needle = state.boxes().moving();
                state.poses = gauge.slide ? GaugeNeedleFit.slide(gauge.build().edgewise, face, needle)
                        : GaugeNeedleFit.swing(gauge.build().edgewise, face, needle,
                                state.pivot != null ? state.pivot : DesignMotion.DEFAULT_PIVOT);
                host.rebuild();
            });
            fit.active = state.faceBox() != null && !state.moving.isEmpty();
            panel.row();
            pivoted = !gauge.slide;
        }

        if (pivoted) {
            boolean custom = state.pivot != null;
            panel.add(new Checkbox(Panel.X, panel.y, Panel.WIDTH, 20, t("pivot.custom"), custom) {
                @Override
                public void onPress() {
                    super.onPress();
                    state.pivot = selected() ? DesignMotion.DEFAULT_PIVOT.clone() : null;
                    host.rebuild();
                }
            }).setTooltip(Tooltip.create(t("needle.pivot.tooltip")));
            panel.row();
            float[] pivot = custom ? state.pivot : DesignMotion.DEFAULT_PIVOT;
            for (int axis = 0; axis < 3; axis++) {
                int index = axis;
                NumberBox box = panel.number(axis, 3, pivot[axis], value -> {
                    if (state.pivot != null) {
                        state.pivot[index] = value;
                        host.changed();
                    }
                }, t("pivot." + AXES[axis]));
                box.setEditable(custom);
            }
            panel.row();
            Button pick = panel.button(t("pivot.pick"), 0, 2, t("pivot.pick.tooltip"), b -> {
                double[] moving = state.boxes().moving();
                state.pivot = new float[] { (float) (moving[0] + moving[3]) / 2, 0,
                        (float) (moving[2] + moving[5]) / 2 };
                host.rebuild();
            });
            pick.active = state.info != null;
            Button dial = panel.button(t("needle.pivot.dial"), 1, 2, t("needle.pivot.dial.tooltip"), b -> {
                double[] face = state.faceBox();
                state.pivot = new float[] { (float) (face[0] + face[3]) / 2, (float) face[1],
                        (float) (face[2] + face[5]) / 2 };
                host.rebuild();
            });
            dial.active = state.faceBox() != null;
            panel.row();
        }

        panel.button(t("pose.reset"), 0, 1, b -> {
            state.poses = ControlDesign.defaultPoses(state.kind);
            state.pivot = null;
            gauge.needleZero = 0;
            gauge.reverse = false;
            host.rebuild();
        });
        panel.row();
    }

    /**
     * One checkbox per position that may spring back, labelled the way the switch's
     * own menu labels it, so a designed switch and a built-in one are set up in the
     * same words.
     */
    private static void springs(Panel panel, WorkbenchHost host, WorkbenchState state) {
        RotarySwitchKind kind = state.kind.rotary();
        if (kind == null)
            return;
        // A breaker springs back from both sides, whatever the mask says
        boolean forced = state.kind.isBreaker();
        for (int position : RotarySwitchSpring.springable(kind)) {
            boolean checked = forced || RotarySwitchSpring.springsFrom(kind, state.springs, position);
            Checkbox box = panel.add(new Checkbox(Panel.X, panel.y, Panel.WIDTH, 20,
                    RotarySwitchSpring.label(kind, position), checked) {
                @Override
                public void onPress() {
                    super.onPress();
                    state.springs = RotarySwitchSpring.with(kind, state.springs, position, selected());
                    host.changed();
                }
            });
            if (forced) {
                box.active = false;
                box.setTooltip(Tooltip.create(t("springs.breaker.tooltip")));
            }
            panel.row();
        }
    }

    /**
     * A breaker's flag: what the contacts follow, where the flag stands latched each
     * way, and what it turns about. Laid out like the handle's own rows above it.
     */
    private static void indicator(Panel panel, WorkbenchHost host, WorkbenchState state) {
        panel.gap(2);
        panel.label(t("indicator"), 0xE0E0E0);
        panel.button(t("breaker_mode", state.breakerMode.label()), 0, 1, modeTooltip(state.breakerMode), b -> {
            state.breakerMode = state.breakerMode.next();
            b.setMessage(t("breaker_mode", state.breakerMode.label()));
            b.setTooltip(Tooltip.create(modeTooltip(state.breakerMode)));
            host.changed();
        });
        panel.row();
        if (state.indicatorParts.isEmpty())
            panel.label(t("indicator.none").withStyle(s -> s.withColor(0xFF8060)), 0xA0A0A0);

        for (int i = 0; i < state.indicatorPoses.length; i++) {
            panel.label(t(i == BreakerIndicator.CW ? "indicator.pose.cw" : "indicator.pose.ccw"), 0xE0E0E0);
            float[] pose = state.indicatorPoses[i];
            for (int axis = 0; axis < 3; axis++) {
                int index = axis;
                panel.number(axis, 3, pose[axis], value -> {
                    pose[index] = value;
                    host.changed();
                }, t("pose.turn." + AXES[axis]));
            }
            panel.row();
        }

        boolean custom = state.indicatorPivot != null;
        panel.add(new Checkbox(Panel.X, panel.y, Panel.WIDTH, 20, t("pivot.custom"), custom) {
            @Override
            public void onPress() {
                super.onPress();
                state.indicatorPivot = selected() ? DesignMotion.DEFAULT_PIVOT.clone() : null;
                host.rebuild();
            }
        }).setTooltip(Tooltip.create(t("indicator.pivot.custom.tooltip")));
        panel.row();
        float[] pivot = custom ? state.indicatorPivot : DesignMotion.DEFAULT_PIVOT;
        for (int axis = 0; axis < 3; axis++) {
            int index = axis;
            NumberBox box = panel.number(axis, 3, pivot[axis], value -> {
                if (state.indicatorPivot != null) {
                    state.indicatorPivot[index] = value;
                    host.changed();
                }
            }, t("pivot." + AXES[axis]));
            box.setEditable(custom);
        }
        panel.row();
    }

    private static Component modeTooltip(BreakerMode mode) {
        return t("breaker_mode." + mode.id() + ".tooltip");
    }
}
