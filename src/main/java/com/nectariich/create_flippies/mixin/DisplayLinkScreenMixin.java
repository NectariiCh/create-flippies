package com.nectariich.create_flippies.mixin;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.display.link.ElementAddressedTarget;
import com.nectariich.create_flippies.content.display.link.ElementAddressedTarget.LinkElementChoice;
import com.nectariich.create_flippies.content.display.link.LinkAddress;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkScreen;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;

import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's display link screen, given a second picker when the link is aimed at an
 * {@link ElementAddressedTarget}: Create's row selector picks the display, and a strip
 * added under the window picks which of its lettering elements the link writes to.
 *
 * <h2>How the choice gets back to the server</h2>
 *
 * Without a packet of our own. Create's {@code onClose} sends whatever state its
 * {@code targetLineSelector} holds as the link's line, an int it carries and saves
 * unchecked. So for an element-addressed target that field is swapped for a carrier:
 * a {@link ScrollInput} never added to the screen, ranged over every non-negative int,
 * whose state is the {@link LinkAddress} packing of the two pickers and is rewritten
 * whenever either moves. The visible pickers are ours and post nothing.
 *
 * <h2>Lifecycle</h2>
 *
 * Create rebuilds its options in {@code initGathererOptions}, from {@code init} and
 * again from {@code tick} whenever the source or target block changes, and does not
 * clear the screen first on the second path. So our widgets are removed at the head
 * and rebuilt at the tail, and what the player has picked but not yet sent is kept in
 * fields of our own: the link's {@code targetLine} on the client is the last synced
 * value and stays so until the screen closes, so restoring from it would undo the
 * player's scrolling. The pending choice is dropped when the target turns into a
 * different block, since its rows and elements are then someone else's.
 *
 * The window is made taller by the strip in {@code init}, so that {@code guiTop}
 * centres window and strip together and neither clips at small screen heights. If the
 * target changes kind while the screen is open (a host swapped for a lectern), the
 * screen is rebuilt so the window matches again.
 *
 * <h2>Names</h2>
 *
 * Create's fields and methods keep their names in production, so the mixin is not
 * remapped; {@code init} is vanilla's and is. Calls on vanilla's {@code Screen} from
 * the bodies here are ordinary code, which is why this extends Create's superclass.
 */
@Mixin(value = DisplayLinkScreen.class, remap = false)
public abstract class DisplayLinkScreenMixin extends AbstractSimiScreen {

    /** The strip's height, and where in Create's texture it is cut from (see {@link #create_flippies$strip}). */
    @Unique
    private static final int create_flippies$STRIP = 36, create_flippies$STRIP_V = 96, create_flippies$STRIP_WIDTH = 226;
    /** A stretch of the slider rail drawn over the strip's left edge to hide the slider knob it would copy. */
    @Unique
    private static final int create_flippies$RAIL_V = 40, create_flippies$RAIL_WIDTH = 26, create_flippies$RAIL_HEIGHT = 30;
    /**
     * Where Create's own picker and its label sit in a slot, relative to the slot's top;
     * Create's own slot starts {@link #create_flippies$STRIP_V} down the window, which
     * is why its picker is at 105 and its label at 109.
     */
    @Unique
    private static final int create_flippies$PICKER_X = 61, create_flippies$PICKER_Y = 9, create_flippies$LABEL_X = 65,
            create_flippies$LABEL_Y = 13, create_flippies$PICKER_WIDTH = 135;
    @Unique
    private static final int create_flippies$GREY = 0x999999;

    @Shadow
    private DisplayLinkBlockEntity blockEntity;
    @Shadow
    BlockState targetState;
    @Shadow
    List<DisplaySource> sources;
    @Shadow
    DisplayTarget target;
    @Shadow
    ScrollInput sourceTypeSelector;
    @Shadow
    ScrollInput targetLineSelector;
    @Shadow
    Label targetLineLabel;

    /** Whether {@code init} sized the window with the strip. */
    @Unique
    private boolean create_flippies$stripped;
    /** Everything we added, so the next rebuild can take it away again. */
    @Unique
    private final List<GuiEventListener> create_flippies$widgets = new ArrayList<>();
    @Unique
    private ScrollInput create_flippies$displayPicker;
    @Unique
    private ScrollInput create_flippies$elementPicker;
    @Unique
    private Label create_flippies$elementLabel;
    @Unique
    private List<LinkElementChoice> create_flippies$choices = List.of();

    /** The picked but unsent choice, and the target block it was picked on (null until read). */
    @Unique
    private int create_flippies$display, create_flippies$element, create_flippies$generation;
    @Unique
    private Block create_flippies$pickedOn;

    @ModifyArg(method = "init()V", remap = true,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkScreen;setWindowSize(II)V"),
            index = 1)
    private int create_flippies$roomForStrip(int height) {
        // init runs before initGathererOptions has looked the target up, so ask here
        create_flippies$stripped = minecraft != null && minecraft.level != null && DisplayTarget
                .get(minecraft.level, blockEntity.getTargetPosition()) instanceof ElementAddressedTarget;
        return create_flippies$stripped ? height + create_flippies$STRIP : height;
    }

    @Inject(method = "initGathererOptions", at = @At("HEAD"))
    private void create_flippies$clear(CallbackInfo ci) {
        for (GuiEventListener widget : create_flippies$widgets)
            removeWidget(widget);
        create_flippies$widgets.clear();
        create_flippies$displayPicker = null;
        create_flippies$elementPicker = null;
        create_flippies$elementLabel = null;
    }

    @Inject(method = "initGathererOptions", at = @At("TAIL"))
    private void create_flippies$pickers(CallbackInfo ci) {
        boolean addressed = target instanceof ElementAddressedTarget;
        if (addressed != create_flippies$stripped) {
            // The target changed kind under an open screen; init sizes the window afresh
            rebuildWidgets();
            return;
        }
        if (!addressed)
            return;
        ElementAddressedTarget elements = (ElementAddressedTarget) target;
        DisplayLinkContext context = new DisplayLinkContext(minecraft.level, blockEntity);

        Block block = targetState.getBlock();
        if (create_flippies$pickedOn != block) {
            int line = blockEntity.targetLine;
            create_flippies$display = LinkAddress.row(line);
            create_flippies$element = LinkAddress.element(line);
            create_flippies$generation = LinkAddress.generation(line);
            create_flippies$pickedOn = block;
        }
        List<Component> names = elements.displayNames(context);
        create_flippies$display = Mth.clamp(create_flippies$display, 0, names.size() - 1);

        // Create's selector and label make way for ours in the same slot
        removeWidget(targetLineSelector);
        removeWidget(targetLineLabel);
        targetLineSelector = new ScrollInput(0, 0, 0, 0).withRange(0, Integer.MAX_VALUE);

        int x = guiLeft;
        int y = guiTop + create_flippies$STRIP_V;
        Label label = create_flippies$label(x, y, CommonComponents.EMPTY);
        if (names.size() > 1) {
            create_flippies$displayPicker = create_flippies$picker(x, y, names, label)
                    .calling(this::create_flippies$pickDisplay)
                    .setState(create_flippies$display);
        } else {
            label.text = names.get(0);
        }
        create_flippies$retitle(create_flippies$multiline(sourceTypeSelector == null ? 0 : sourceTypeSelector.getState()));
        create_flippies$buildElementPicker();
    }

    /** Create sets the carrier's title for the new source; the visible pickers get it instead. */
    @Inject(method = "initGathererSourceSubOptions", at = @At("TAIL"))
    private void create_flippies$retitleForSource(int index, CallbackInfo ci) {
        if (create_flippies$stripped)
            create_flippies$retitle(create_flippies$multiline(index));
    }

    /**
     * The strip under the window: the lower slot of Create's own background, so the
     * picker sits in the same frame as the one above it. That slice includes the
     * lower knob of the slider drawn down the left edge, so a plain stretch of the
     * rail is laid over it.
     */
    @Inject(method = "renderWindow", at = @At("TAIL"))
    private void create_flippies$strip(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
            CallbackInfo ci) {
        if (!create_flippies$stripped)
            return;
        AllGuiTextures background = AllGuiTextures.DATA_GATHERER;
        int x = guiLeft;
        int y = guiTop + background.getHeight();
        graphics.blit(background.location, x, y, 0, create_flippies$STRIP_V, create_flippies$STRIP_WIDTH,
                create_flippies$STRIP);
        graphics.blit(background.location, x, y, 0, create_flippies$RAIL_V, create_flippies$RAIL_WIDTH,
                create_flippies$RAIL_HEIGHT);
        // The square beside the slot holds the target's icon in Create's row; here, a mark for "element"
        graphics.drawString(font, "#", x + 45 - font.width("#") / 2, y + create_flippies$LABEL_Y, 0xFFFFFF, true);
    }

    /**
     * The element picker for the picked display, in the strip. A display with no
     * elements at all (a label, a gauge) gets a note rather than a picker, and one with
     * elements but no lettering gets a greyed "No text elements". A link whose element
     * has been deleted, or whose slot has been taken by a new element since, keeps
     * pointing at it and is shown as "(deleted)", because that is what it does now:
     * write nothing. Scrolling away from it picks a live one.
     */
    @Unique
    private void create_flippies$buildElementPicker() {
        for (GuiEventListener widget : new GuiEventListener[] { create_flippies$elementPicker,
                create_flippies$elementLabel }) {
            removeWidget(widget);
            create_flippies$widgets.remove(widget);
        }
        create_flippies$elementPicker = null;

        int x = guiLeft;
        int y = guiTop + AllGuiTextures.DATA_GATHERER.getHeight();
        DisplayLinkContext context = new DisplayLinkContext(minecraft.level, blockEntity);
        List<LinkElementChoice> live = ((ElementAddressedTarget) target).elements(context, create_flippies$display);
        if (live == null) {
            create_flippies$element = 0;
            create_flippies$generation = 0;
            create_flippies$choices = List.of();
            create_flippies$elementLabel = create_flippies$label(x, y,
                    Component.translatable(create_flippies$key("whole_row"))).colored(create_flippies$GREY);
            create_flippies$send();
            return;
        }
        if (live.isEmpty()) {
            create_flippies$choices = List.of();
            create_flippies$elementLabel = create_flippies$label(x, y,
                    Component.translatable(create_flippies$key("no_text_elements"))).colored(create_flippies$GREY);
            create_flippies$send();
            return;
        }

        List<LinkElementChoice> choices = new ArrayList<>(live);
        int picked = create_flippies$indexOf(choices, create_flippies$element, create_flippies$generation);
        if (picked < 0) {
            picked = 0;
            while (picked < choices.size() && choices.get(picked).id() < create_flippies$element)
                picked++;
            choices.add(picked, new LinkElementChoice(create_flippies$element, create_flippies$generation,
                    Component.translatable(create_flippies$key("element_deleted"), create_flippies$element)));
        }
        create_flippies$choices = choices;
        List<Component> names = choices.stream().map(LinkElementChoice::name).toList();
        create_flippies$elementLabel = create_flippies$label(x, y, CommonComponents.EMPTY);
        create_flippies$elementPicker = create_flippies$picker(x, y, names, create_flippies$elementLabel)
                .calling(this::create_flippies$pickElement)
                .withShiftStep(10)
                .setState(picked);
        create_flippies$retitle(create_flippies$multiline(sourceTypeSelector == null ? 0 : sourceTypeSelector.getState()));
        create_flippies$send();
    }

    /** A new display starts at its first lettering element, or nothing when it has none. */
    @Unique
    private void create_flippies$pickDisplay(int display) {
        create_flippies$display = display;
        DisplayLinkContext context = new DisplayLinkContext(minecraft.level, blockEntity);
        List<LinkElementChoice> live = ((ElementAddressedTarget) target).elements(context, display);
        LinkElementChoice first = live == null || live.isEmpty() ? null : live.get(0);
        create_flippies$element = first == null ? 0 : first.id();
        create_flippies$generation = first == null ? 0 : first.generation();
        create_flippies$buildElementPicker();
    }

    @Unique
    private void create_flippies$pickElement(int index) {
        if (index < 0 || index >= create_flippies$choices.size())
            return;
        LinkElementChoice choice = create_flippies$choices.get(index);
        create_flippies$element = choice.id();
        create_flippies$generation = choice.generation();
        create_flippies$send();
    }

    /** Writes the pending choice into the carrier Create sends on close. */
    @Unique
    private void create_flippies$send() {
        if (targetLineSelector != null)
            targetLineSelector.setState(
                    LinkAddress.pack(create_flippies$display, create_flippies$element, create_flippies$generation));
    }

    /** Create's "display on" titles for the display picker, ours for the element picker. */
    @Unique
    private void create_flippies$retitle(boolean multiline) {
        if (create_flippies$displayPicker != null)
            create_flippies$displayPicker.titled(CreateLang.translateDirect(
                    multiline ? "display_link.display_on_multiline" : "display_link.display_on"));
        if (create_flippies$elementPicker != null)
            create_flippies$elementPicker.titled(Component.translatable(
                    create_flippies$key(multiline ? "element_on_multiline" : "element_on")));
    }

    /** Whether the source at that index offers several lines, as Create decides for its own title. */
    @Unique
    private boolean create_flippies$multiline(int index) {
        if (sources == null || index < 0 || index >= sources.size())
            return false;
        return !(sources.get(index) instanceof SingleLineDisplaySource);
    }

    @Unique
    private ScrollInput create_flippies$picker(int x, int slotY, List<Component> options, Label label) {
        ScrollInput picker = new SelectionScrollInput(x + create_flippies$PICKER_X, slotY + create_flippies$PICKER_Y,
                create_flippies$PICKER_WIDTH, 16).forOptions(options).writingTo(label);
        create_flippies$add(picker);
        return picker;
    }

    @Unique
    private Label create_flippies$label(int x, int slotY, Component text) {
        Label label = new Label(x + create_flippies$LABEL_X, slotY + create_flippies$LABEL_Y, text).withShadow();
        create_flippies$add(label);
        return label;
    }

    @Unique
    private void create_flippies$add(GuiEventListener widget) {
        if (widget instanceof Label label)
            addRenderableWidget(label);
        else if (widget instanceof ScrollInput input)
            addRenderableWidget(input);
        create_flippies$widgets.add(widget);
    }

    @Unique
    private static int create_flippies$indexOf(List<LinkElementChoice> choices, int id, int generation) {
        for (int i = 0; i < choices.size(); i++)
            if (choices.get(i).id() == id && choices.get(i).generation() == generation)
                return i;
        return -1;
    }

    @Unique
    private static String create_flippies$key(String name) {
        return "gui." + CreateFlippies.MOD_ID + ".display_link." + name;
    }
}
