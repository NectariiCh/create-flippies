package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import com.nectariich.create_flippies.client.blueprint.ClientBlueprints;
import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The Save blueprint dialog, drawn over the workbench: a name, where to save it (the
 * world, or the local collection and the world), a folder, and Save, Save and Make,
 * or Cancel. When the workbench was opened from a blueprint, Save overwrites that one
 * and Save as new keeps it and adds a copy.
 *
 * A control only comes out of the workbench by way of a blueprint: saving builds the
 * final design (boxes worked out, named after the blueprint) and hands it, with the
 * model and any font files, to {@link ClientBlueprints}, which uploads what the server
 * lacks; Save and Make then asks for the item, which only creative players get.
 *
 * Only the server knows whether the world took the blueprint (it refuses to let
 * someone overwrite another player's, or a save whose files did not arrive), so the
 * dialog stays open saying it is saving until the answer comes back. A refusal is
 * shown in the dialog and nothing is made; only a save the world accepted counts as
 * saved, becomes the blueprint being edited, and goes on to Save and Make. Overwrite
 * is offered only for a blueprint this player may change; for anyone else's, Save as
 * new is the way to keep the edit.
 *
 * The dialog lives inside the workbench screen rather than as a screen of its own,
 * so the preview and camera stay as they are behind it.
 */
public final class SaveDialog {

    public static final int WIDTH = 220;
    private static final int HEIGHT = 178;

    private final WorkbenchHost host;
    private final BlockPos bench;
    private final Runnable close;
    private final Consumer<Blueprint> saved;

    private String name;
    private boolean local;
    private String folder;
    private Component error;

    /** Whether a save was sent and its answer has not come back yet; the buttons wait meanwhile. */
    private boolean pending;

    private int left, top;

    /** Told of a refusal that came back after the dialog was closed, for the workbench's status line. */
    private final Consumer<Component> refused;

    /** Whether the dialog was closed (cancelled), so a late answer goes to the workbench rather than here. */
    private boolean closed;

    /**
     * The workbench took the dialog down (Cancel, Escape, or the screen going away). An
     * answer still on its way then only sets the workbench's status line, and never
     * closes whatever dialog may have been opened since.
     */
    public void closed() {
        closed = true;
    }

    /**
     * @param close   takes the dialog down; the workbench then calls {@link #closed()}
     * @param saved   called once the world accepted the blueprint, before the dialog closes itself
     * @param refused called with the reason when the world refused it after the dialog was closed
     */
    public SaveDialog(WorkbenchHost host, BlockPos bench, Runnable close, Consumer<Blueprint> saved,
            Consumer<Component> refused) {
        this.host = host;
        this.bench = bench;
        this.close = close;
        this.saved = saved;
        this.refused = refused;
        WorkbenchState state = host.state();
        Blueprint editing = state.editing;
        name = editing != null ? editing.name() : state.modelName == null ? "" : state.modelName;
        name = Blueprint.cleanName(name);
        local = editing == null || state.editingLocal;
        folder = editing != null ? editing.folder() : "";
        // The world's folders come with a sync; ask for one so the picker has them
        ClientBlueprints.refresh();
    }

    /** Lays out the dialog's widgets, centred over the preview side of the screen. */
    public void init(Font font, int width, int height, Consumer<AbstractWidget> add) {
        left = Math.max(4, (width - WIDTH) / 2);
        top = Math.max(4, (height - HEIGHT) / 2);
        int x = left + 8, w = WIDTH - 16, half = (w - 4) / 2;
        int y = top + 20;

        EditBox nameBox = new EditBox(font, x + 1, y + 11, w - 2, 18, t("save.name"));
        nameBox.setMaxLength(Blueprint.MAX_NAME);
        nameBox.setValue(name);
        nameBox.setResponder(value -> name = value);
        add.accept(nameBox);
        y += 34;

        add.accept(Button.builder(t(local ? "save.section.local" : "save.section.world"), b -> {
            local = !local;
            b.setMessage(t(local ? "save.section.local" : "save.section.world"));
        }).bounds(x, y, w, 20).tooltip(Tooltip.create(t("save.section.tooltip"))).build());
        y += 24;

        EditBox folderBox = new EditBox(font, x + 1, y + 11, w - 46, 18, t("save.folder"));
        folderBox.setMaxLength(Blueprint.MAX_DEPTH * (Blueprint.MAX_SEGMENT + 1));
        folderBox.setValue(folder);
        folderBox.setResponder(value -> folder = value);
        folderBox.setTooltip(Tooltip.create(t("save.folder.tooltip")));
        add.accept(folderBox);
        add.accept(Button.builder(Component.literal("<"), b -> {
            folder = stepFolder(-1);
            folderBox.setValue(folder);
        }).bounds(x + w - 42, y + 10, 20, 20).tooltip(Tooltip.create(t("save.folder.pick"))).build());
        add.accept(Button.builder(Component.literal(">"), b -> {
            folder = stepFolder(1);
            folderBox.setValue(folder);
        }).bounds(x + w - 20, y + 10, 20, 20).tooltip(Tooltip.create(t("save.folder.pick"))).build());
        y += 36;

        Blueprint edited = host.state().editing;
        boolean editing = edited != null;
        // Overwriting someone else's blueprint is refused by the server, so it is not
        // offered; Save as new keeps the edit instead, and Save and Make makes a copy
        boolean overwrite = editing && ClientBlueprints.mayManage(edited);
        Button save = Button.builder(t(editing ? "save.overwrite" : "save.save"), b -> save(false, false))
                .bounds(x, y, half, 20).tooltip(Tooltip.create(editing && !overwrite
                        ? Component.translatable("gui.create_flippies.blueprints.not_yours.tooltip")
                        : t(editing ? "save.overwrite.tooltip" : "save.save.tooltip"))).build();
        save.active = !pending && (!editing || overwrite);
        add.accept(save);
        Button make = Button.builder(t("save.make"), b -> save(true, editing && !overwrite))
                .bounds(x + half + 4, y, half, 20).build();
        boolean creative = Minecraft.getInstance().player != null && Minecraft.getInstance().player.isCreative();
        make.active = creative && !pending;
        make.setTooltip(Tooltip.create(t(creative ? "save.make.tooltip" : "save.make.survival")));
        add.accept(make);
        y += 24;
        if (editing) {
            Button asNew = Button.builder(t("save.as_new"), b -> save(false, true)).bounds(x, y, half, 20)
                    .tooltip(Tooltip.create(t("save.as_new.tooltip"))).build();
            asNew.active = !pending;
            add.accept(asNew);
            add.accept(Button.builder(Component.translatable("gui.cancel"), b -> close.run())
                    .bounds(x + half + 4, y, half, 20).build());
        } else {
            add.accept(Button.builder(Component.translatable("gui.cancel"), b -> close.run()).bounds(x, y, w, 20)
                    .build());
        }
    }

    /** The folders of the chosen section, the top folder first; both sections' for Local and World. */
    private List<String> folders() {
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(ClientBlueprints.worldFolders());
        if (local)
            all.addAll(ClientBlueprints.localFolders());
        all.remove("");
        List<String> out = new ArrayList<>();
        out.add("");
        out.addAll(all);
        return out;
    }

    private String stepFolder(int step) {
        List<String> folders = folders();
        String current = Blueprint.normalizeFolder(folder);
        int at = current == null ? -1 : folders.indexOf(current);
        int next = at < 0 ? (step > 0 ? 0 : folders.size() - 1) : Math.floorMod(at + step, folders.size());
        return folders.get(next);
    }

    /**
     * Builds the blueprint and saves it; with {@code make}, also asks for the item.
     * {@code asNew} keeps the blueprint being edited and saves a copy under a new id.
     */
    private void save(boolean make, boolean asNew) {
        WorkbenchState state = host.state();
        String clean = Blueprint.cleanName(name);
        if (clean.isEmpty()) {
            error = t("save.error.name");
            return;
        }
        String path = Blueprint.normalizeFolder(folder);
        if (path == null) {
            error = t("save.error.folder", Blueprint.MAX_SEGMENT, Blueprint.MAX_DEPTH);
            return;
        }
        if (state.kind.isRack() && !state.canSave()) {
            error = t("save.needs_rack");
            return;
        }
        if (state.kind.isGauge() && state.gaugeError() != null) {
            error = t(state.gaugeError());
            return;
        }
        if (state.moving.isEmpty() && !state.kind.isGenerated()) {
            error = t("save.error.moving");
            return;
        }
        ControlDesign design = state.design(clean);
        if (design == null) {
            error = t("save.error.model");
            return;
        }

        long now = System.currentTimeMillis();
        Blueprint editing = state.editing;
        Blueprint blueprint;
        if (editing != null && !asNew)
            blueprint = new Blueprint(editing.id(), clean, path, editing.author(), editing.created(), now, design);
        else
            blueprint = new Blueprint(UUID.randomUUID(), clean, path, author(), now, now, design);

        // The model's bundle and every font file the text uses, as far as this client
        // has them; the server already has any it lacks here, since they came from it
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        byte[] model = state.blob != null ? state.blob : ClientBlueprints.blob(design.model());
        if (model != null)
            blobs.put(design.model(), model);
        for (String font : design.fontHashes()) {
            byte[] bytes = DesignFonts.bytes(font);
            if (bytes != null)
                blobs.put(font, bytes);
        }

        // Wait for the world's answer: only a save it accepted is saved, becomes the
        // blueprint being edited, and is made into an item
        boolean savedLocal = local;
        pending = true;
        error = t("save.pending");
        host.rebuild();
        ClientBlueprints.save(blueprint, savedLocal, blobs, reply -> {
            pending = false;
            if (!reply.ok()) {
                Component reason = reply.status() != null ? reply.status() : t("save.refused");
                if (closed) {
                    refused.accept(reason);
                } else {
                    error = reason;
                    host.rebuild();
                }
                return;
            }
            error = null;
            if (make)
                ClientBlueprints.make(bench, blueprint);
            state.editing = blueprint;
            state.editingLocal = savedLocal;
            saved.accept(blueprint);
            if (!closed)
                close.run();
        });
    }

    private static String author() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? "" : minecraft.player.getUUID().toString();
    }

    /** The dialog's frame and labels, drawn under its widgets. */
    public void renderBackground(GuiGraphics graphics, Font font, int width, int height) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xF0181C22);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF606870);
        graphics.drawString(font, t(host.state().editing != null ? "save.title.edit" : "save.title"), left + 8,
                top + 8, 0xFFFFFF);
        int x = left + 8;
        graphics.drawString(font, t("save.name"), x, top + 21, 0xA0A0A0);
        graphics.drawString(font, t("save.folder"), x, top + 79, 0xA0A0A0);
        if (error != null)
            graphics.drawString(font, error, x, top + HEIGHT - 13, pending ? 0xA0A0A0 : 0xFF8060);
    }
}
