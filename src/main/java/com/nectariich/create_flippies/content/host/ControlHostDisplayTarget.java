package com.nectariich.create_flippies.content.host;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Lets a Create display link write onto the link-driven controls of one host block —
 * its panel labels and its Square Procedural Gauges alike.
 *
 * A link addresses a block position and a host block may carry several things worth
 * writing to, so something has to choose between them. The choice is the link's own
 * target line: the target reports as many rows as the block has link-driven
 * controls, and Create's link menu already offers a row selector and names each row
 * through {@link #getLineOptionText(int)}. That is the mechanism Create built for
 * exactly this shape of problem — a display board and a sign are both one position
 * with several addressable lines — and it is the only one that lets a player say
 * which control they meant. Guessing from geometry was the alternative and it cannot
 * be done well: a link stores a target position and nothing finer, so there is no
 * aimed point to be near, and picking whatever is nearest the link block itself
 * would silently re-target every link on a panel the moment another control was
 * mounted.
 *
 * Labels and gauges share one numbering rather than having one each. A row is
 * whatever is Nth down the face, whichever kind it is, so a panel carrying three
 * legends and two gauges offers five rows. The alternative — numbering each kind
 * separately — would mean a link's chosen row changing meaning depending on a
 * setting the link does not have, and there is only one target per block entity type
 * to hang such a setting off. The cost is that mounting a label above a gauge shifts
 * the gauge's row, exactly as inserting a line shifts the ones below it anywhere
 * else.
 *
 * The target is registered against the host block entity type, so every panel
 * advertises itself as a display target whether it carries anything writable or not.
 * A panel with none reports a single row and quietly ignores what it is sent, which
 * is better than the alternative of refusing to be a target at all: the registry is
 * keyed by block entity type, and what a particular block contains is not something
 * a registration can ask about.
 *
 * That key is also the one thing about this class that has to be kept in step by
 * hand. Everything below reads its rows off {@link ControlRackHolder}, so one
 * instance serves any block that can carry controls — but the registry does not know
 * that, and a holder nobody registered is not a target at all. A large panel cell is
 * a holder as much as a bare host is, and leaving it out is what made a gauge screwed
 * to a panel answer "not a valid target" while the same gauge on a bare host took a
 * link happily. Both are registered in {@code CreateFlippies.commonSetup}, and a new
 * kind of holder has to be added there with them.
 *
 * One point about aiming a link at a panel is worth knowing and is not this class's
 * to fix: a link addresses a block, and a control that straddles two panel cells is
 * held by the cell its centre is in. So a link has to be pointed at that cell, which
 * is not always the cell the crosshair would suggest for a control sitting over an
 * edge.
 */
public class ControlHostDisplayTarget extends DisplayTarget {

    private static final String LINE_KEY = "gui." + CreateFlippies.MOD_ID + ".control_host.line";
    private static final String NAMED_LINE_KEY = "gui." + CreateFlippies.MOD_ID + ".control_host.line_named";

    /**
     * What was on each row the last time the client asked for stats, so that
     * {@link #getLineOptionText(int)} can say. Written from the render thread and
     * read from it, and replaced wholesale rather than mutated, so a stale read is a
     * complete older array and never a half-filled one.
     */
    private static volatile Component[] lastRowNames;

    /**
     * What a row is when the host has none, so a link aimed at an empty panel still
     * has something to select.
     */
    private static final int NO_ROWS_WIDTH = 16;

    /**
     * Rows have to be numbered the same on both sides of the connection and the same
     * after a reload, and the mount is the only thing about an installed control that
     * never changes — so the order comes from that: face first, then down the face,
     * then across it. On any vertical face {@code v} is the world's y axis, so this is
     * top to bottom, which is the order a column of instruments is read in.
     */
    private static final Comparator<HostedControl> READING_ORDER = Comparator
            .<HostedControl>comparingInt(control -> control.mount().face().get3DDataValue())
            .thenComparingInt(control -> -control.mount().anchorV())
            .thenComparingInt(control -> control.mount().anchorU());

    /**
     * A source offering several lines fills consecutive rows from the chosen one,
     * which is what a sign does with its four rows and what makes a multi-line source
     * useful on a panel of instruments.
     */
    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        List<LinkDrivenControl> rows = rowsOf(context.getTargetBlockEntity());
        for (int i = 0; i < text.size(); i++) {
            int index = line + i;
            if (index < 0 || index >= rows.size())
                continue;
            rows.get(index).acceptLine(text.get(i).getString());
        }
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        List<LinkDrivenControl> rows = rowsOf(context.getTargetBlockEntity());
        if (context.level() != null && context.level().isClientSide)
            rememberRowNames(rows);
        return new DisplayTargetStats(Math.max(1, rows.size()), widthOf(rows), this);
    }

    /**
     * Refuses a line carrying a click event, exactly as the sign target does. A label
     * stores flat text and draws it through the font and a gauge reduces it to a
     * number, so a component that expects to be clicked is being sent somewhere that
     * can never honour it.
     */
    @Override
    public boolean requiresComponentSanitization() {
        return true;
    }

    /**
     * Names a row in the link's selector — "Row 2 — Gauge" rather than "Row 2".
     *
     * Create asks for this with nothing but the line number, so the only way to say
     * what is actually on that row is to remember the rows from the last time it
     * asked for stats. That is sound for the one caller that matters: the link screen
     * calls {@code provideStats} and then builds this label in the same method, and
     * the scroll input re-asks while that same screen is open. Only the client's own
     * call is remembered, because the server calls {@code provideStats} on every link
     * refresh and would otherwise overwrite the names with another panel's while a
     * screen was open on this one.
     *
     * A row whose name is not known falls back to the bare number rather than
     * guessing, which is what a link aimed at an empty panel gets.
     */
    @Override
    public Component getLineOptionText(int line) {
        Component[] names = lastRowNames;
        if (names != null && line >= 0 && line < names.length)
            return Component.translatable(NAMED_LINE_KEY, line + 1, names[line]);
        return Component.translatable(LINE_KEY, line + 1);
    }

    private static void rememberRowNames(List<LinkDrivenControl> rows) {
        Component[] names = new Component[rows.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = rows.get(i).linkRowName();
        lastRowNames = names;
    }

    /**
     * Every link-driven control in the block, in the order a link addresses them, and
     * empty for anything that is not a host — which is also what a link aimed at a
     * panel carrying nothing writable gets.
     */
    public static List<LinkDrivenControl> rowsOf(BlockEntity blockEntity) {
        if (!(blockEntity instanceof ControlRackHolder host))
            return List.of();
        List<HostedControl> writable = new ArrayList<>();
        for (HostedControl control : host.controls())
            if (control instanceof LinkDrivenControl)
                writable.add(control);
        writable.sort(READING_ORDER);
        List<LinkDrivenControl> rows = new ArrayList<>(writable.size());
        for (HostedControl control : writable)
            rows.add((LinkDrivenControl) control);
        return rows;
    }

    /**
     * How many characters a link is told it may send. One number has to stand for
     * every row on the panel and they may differ — a small label holds eight
     * characters, a gauge wants a whole line so its digits survive — so it is the most
     * generous of them. A link given the narrowest figure would trim text the row it
     * is actually driving could have taken in full, while anything too long for a
     * particular row is trimmed by that row as it is used.
     */
    private static int widthOf(List<LinkDrivenControl> rows) {
        if (rows.isEmpty())
            return NO_ROWS_WIDTH;
        int columns = 0;
        for (LinkDrivenControl row : rows)
            columns = Math.max(columns, row.linkColumns());
        return columns;
    }
}
