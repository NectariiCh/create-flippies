package com.nectariich.create_flippies.content.display;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.display.link.ElementAddressedTarget;
import com.nectariich.create_flippies.content.display.link.LinkAddress;
import com.nectariich.create_flippies.content.display.link.LinkRouting;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Lets a Create display link write onto an alphanumeric display placed as a block of
 * its own.
 *
 * One display, so one row: the link picks which of its lettering elements to start
 * at, packed into its line number by {@link LinkAddress} with the row always 0. A
 * source offering several lines fills the lettering elements in id order from the
 * chosen one, and lines beyond the last are ignored rather than wrapped, for the
 * reason {@link DisplayPrint} gives about cutting. The rule is {@link LinkRouting}'s,
 * the same one a display mounted in a panel follows, applied to a host of one row.
 *
 * A display mounted in a panel goes through
 * {@link com.nectariich.create_flippies.content.host.ControlHostDisplayTarget}
 * instead, which numbers the host's link-driven controls as one list; the string that
 * arrives is the same either way, which is the point of
 * {@link com.nectariich.create_flippies.foundation.control.LinkDrivenControl}.
 *
 * The width reported is what the chosen element will really show at the height the
 * player set, so the link's editor trims to roughly what can be read rather than to a
 * figure nothing enforces. Anything longer that arrives anyway is cut as it is drawn.
 */
public class AlphanumDisplayTarget extends DisplayTarget implements ElementAddressedTarget {

    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        List<String> lines = new ArrayList<>(text.size());
        for (MutableComponent component : text)
            lines.add(component.getString());
        LinkRouting.route(rowOf(context.getTargetBlockEntity()), line, lines);
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        LinkRouting.Stats stats = LinkRouting.stats(rowOf(context.getTargetBlockEntity()),
                context.blockEntity().targetLine);
        return new DisplayTargetStats(stats.rows(), stats.columns(), this);
    }

    @Override
    public List<Component> displayNames(DisplayLinkContext context) {
        return List.of(context.level().getBlockState(context.getTargetPos()).getBlock().getName());
    }

    @Override
    public List<LinkElementChoice> elements(DisplayLinkContext context, int display) {
        List<Row> row = rowOf(context.getTargetBlockEntity());
        return display != 0 || row.isEmpty() ? null : ElementAddressedTarget.choices(row.get(0));
    }

    /**
     * Refuses a line carrying a click event, exactly as the sign target does. A
     * display stores flat text and draws it through the font, so a component that
     * expects to be clicked is being sent somewhere that can never honour it.
     */
    @Override
    public boolean requiresComponentSanitization() {
        return true;
    }

    /** The display as {@link LinkRouting}'s one row, or no rows when it is not there. */
    private static List<Row> rowOf(BlockEntity blockEntity) {
        return blockEntity instanceof AlphanumDisplayBlockEntity display ? List.of(new Row(display)) : List.of();
    }

    /**
     * The block-form display seen as a link-driven row showing elements, which is all
     * {@link LinkRouting} asks of what it writes to. A view, not a second home for
     * anything: every call goes straight to the block entity.
     */
    private record Row(AlphanumDisplayBlockEntity display) implements LinkDrivenControl, ElementDisplay {

        @Override
        public void acceptLine(String text) {
            display.acceptLine(text);
        }

        @Override
        public int linkColumns() {
            return display.linkColumns();
        }

        @Override
        public Component linkRowName() {
            return display.getBlockState().getBlock().getName();
        }

        @Override
        public ScreenElements elements() {
            return display.elements();
        }

        @Override
        public Viewport viewport() {
            return display.viewport();
        }

        @Override
        public void elementsChanged() {
            display.elementsChanged();
        }

        @Override
        public boolean isPowered() {
            return display.isPowered();
        }

        @Override
        public boolean isBacklit() {
            return display.isBacklit();
        }
    }
}
