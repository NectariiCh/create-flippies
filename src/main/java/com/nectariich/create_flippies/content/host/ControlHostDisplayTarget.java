package com.nectariich.create_flippies.content.host;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.link.ElementAddressedTarget;
import com.nectariich.create_flippies.content.display.link.LinkAddress;
import com.nectariich.create_flippies.content.display.link.LinkRouting;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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
 *
 * <h2>Elements</h2>
 *
 * A row that shows {@link ElementDisplay elements} (an alphanumeric display, a
 * designed screen) has more than one thing on it a link could mean, so the link also
 * names one of its lettering elements. Both travel in the link's one line number,
 * packed by {@link LinkAddress}; a link saved before elements existed reads as its
 * row's element 0, which is the display's one line migrated. Where the lines then go
 * is {@link LinkRouting}'s rule, and the second picker in Create's screen is fed by
 * {@link ElementAddressedTarget}.
 */
public class ControlHostDisplayTarget extends DisplayTarget implements ElementAddressedTarget {

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
     * useful on a panel of instruments; on a row showing elements it fills that row's
     * lettering elements first. See {@link LinkRouting}.
     */
    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        BlockEntity target = context.getTargetBlockEntity();
        List<LinkDrivenControl> rows = rowsOf(target);
        List<String> lines = new ArrayList<>(text.size());
        for (MutableComponent component : text)
            lines.add(component.getString());
        if (!CreateFlippies.LOGGER.isDebugEnabled() || target == null) {
            LinkRouting.route(rows, line, lines);
            return;
        }
        CompoundTag before = target.getUpdateTag();
        LinkRouting.route(rows, line, lines);
        logSync(target, before);
    }

    /**
     * How many lines will land and how wide, from where the link is aimed; see
     * {@link LinkRouting#stats}. A link aimed at a label or gauge is told what it always
     * was: every row, as wide as the widest of them, since one number has to stand
     * for rows that differ (a small label holds eight characters, a gauge wants a
     * whole line so its digits survive) and anything too long for a particular row is
     * trimmed by that row as it is used. A link aimed at a lettering element is told
     * that element's width, because that is the one its single line goes to.
     */
    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        List<LinkDrivenControl> rows = rowsOf(context.getTargetBlockEntity());
        if (context.level() != null && context.level().isClientSide)
            rememberRowNames(rows);
        LinkRouting.Stats stats = LinkRouting.stats(rows, context.blockEntity().targetLine);
        return new DisplayTargetStats(stats.rows(), stats.columns(), this);
    }

    @Override
    public List<Component> displayNames(DisplayLinkContext context) {
        List<LinkDrivenControl> rows = rowsOf(context.getTargetBlockEntity());
        if (rows.isEmpty())
            return List.of(Component.translatable(LINE_KEY, 1));
        List<Component> names = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++)
            names.add(Component.translatable(NAMED_LINE_KEY, i + 1, rows.get(i).linkRowName()));
        return names;
    }

    @Override
    public List<LinkElementChoice> elements(DisplayLinkContext context, int display) {
        List<LinkDrivenControl> rows = rowsOf(context.getTargetBlockEntity());
        if (display < 0 || display >= rows.size() || !rows.get(display).isElementDisplay())
            return null;
        return ElementAddressedTarget.choices((ElementDisplay) rows.get(display));
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
     *
     * Only a fallback now: Create's screen gets {@link #displayNames} and the element
     * picker instead (see {@code DisplayLinkScreenMixin}), so this is for anything
     * else that asks with a packed line. It names the row, and the element after a
     * slash when the link names one other than the first slot.
     */
    @Override
    public Component getLineOptionText(int line) {
        int row = LinkAddress.row(line);
        Component[] names = lastRowNames;
        MutableComponent text = names != null && row < names.length
                ? Component.translatable(NAMED_LINE_KEY, row + 1, names[row])
                : Component.translatable(LINE_KEY, row + 1);
        int element = LinkAddress.element(line);
        return element == 0 ? text : text.append(" / #" + element);
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
     * Debug only: how big the host's full sync is when a link transfer changed it.
     *
     * Link text reaches the client the same way a menu edit does, as the whole host's
     * update tag, so a clock source on a dense panel resends every control on it once
     * a second. Whether that is worth a delta packet (slice 5 of the screen elements
     * work) is a question of how big those tags really get, which this answers in
     * the log rather than by guessing. Comparing the tag before and after is the
     * only way to know a sync happened without asking every kind of control to say;
     * it costs two tag builds per transfer, which is why it runs only with debug
     * logging on.
     */
    private static void logSync(BlockEntity target, CompoundTag before) {
        CompoundTag after = target.getUpdateTag();
        if (after.equals(before))
            return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(after, out);
        } catch (IOException e) {
            return;
        }
        CreateFlippies.LOGGER.debug("Display link synced host {} at {}: update tag {} bytes uncompressed",
                target.getType(), target.getBlockPos(), bytes.size());
    }
}
