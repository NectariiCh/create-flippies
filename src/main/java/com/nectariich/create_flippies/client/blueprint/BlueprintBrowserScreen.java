package com.nectariich.create_flippies.client.blueprint;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.lwjgl.glfw.GLFW;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.DesignWorkbenchScreen;
import com.nectariich.create_flippies.compat.EditBoxes;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintIndex;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintService;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Favorites;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Browsing and managing blueprints: the world's, this installation's, and the
 * starred ones, each a tab.
 *
 * The world and local tabs are folder trees shown one folder at a time, with a
 * breadcrumb and a ".." row to go back up; folders are listed before blueprints,
 * each by name. The favourites tab is a flat list of every starred blueprint, from
 * whichever section and world it came from. A pane to the right describes the
 * selection and holds the actions, which follow what is selected: a folder can be
 * opened, renamed and deleted; a blueprint can be made, edited, renamed, copied,
 * moved (into another folder or the other section), deleted and starred.
 *
 * Everything that asks for a name or could lose something opens a small dialog
 * over the screen. Enter confirms one and Escape closes it, and while it is open
 * nothing behind it reacts. Without a dialog the list takes the keyboard: arrows
 * move the selection, Enter opens or edits, Backspace goes up, Delete deletes and
 * F2 renames.
 *
 * Laid out to fit 320 by 180 (GUI scale 4 at 1280 by 720): the list takes what the
 * pane leaves, the pane's buttons sit in two columns at its foot, and the row
 * columns that do not fit (kind, date) are dropped rather than squeezed.
 *
 * World ops go to the server and come back as a sync, which bumps
 * {@link ClientBlueprints#revision()}; the screen lists again whenever that moves.
 */
public class BlueprintBrowserScreen extends FlippiesScreen {

    private static final String LANG = "gui." + CreateFlippies.MOD_ID + ".blueprints.";

    private static final int MARGIN = 6, ROW = 12, BUTTON_H = 16, GAP = 2, TOP_LIST = 46;
    private static final int PANE_MIN = 124;

    private static final int PANEL_BG = 0xC0101418, LIST_BG = 0x80000000, ROW_SELECTED = 0x60FFFFFF,
            ROW_HOVER = 0x28FFFFFF, TEXT = 0xFFFFFF, MUTED = 0xA0A0A0, FOLDER = 0xFFE0B040, BLUEPRINT = 0xFF5090D0,
            STAR_ON = 0xFFD040, STAR_OFF = 0x707070, OK = 0x80E080, BAD = 0xFF8080;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Where the browser was last left, so reopening it lands in the same place. */
    private static String lastSection = ClientBlueprints.WORLD;
    private static final Map<String, String> LAST_FOLDER = new HashMap<>();

    private final BlockPos bench;
    private final Screen parent;

    private String section = lastSection;
    private final List<Row> rows = new ArrayList<>();
    /** What is selected, by a key that survives a refresh: see {@link Row#key}. */
    private String selected;
    private int scroll;
    private boolean draggingScrollbar;
    private long lastClick;
    private String lastClickKey;
    private int seenRevision = -1;

    private Dialog dialog;

    private final List<AbstractWidget> mainWidgets = new ArrayList<>();
    private final List<AbstractWidget> dialogWidgets = new ArrayList<>();

    private Button makeButton, editButton, renameButton, copyButton, moveButton, deleteButton, starButton,
            showButton, upButton, newFolderButton;

    // Layout, worked out in init
    private int listX, listY, listW, listH, paneX, paneW, buttonsTop;

    public BlueprintBrowserScreen(BlockPos bench, Screen parent) {
        super(Component.translatable(LANG + "title"));
        this.bench = bench;
        this.parent = parent;
    }

    public static void open(BlockPos bench, Screen parent) {
        Minecraft.getInstance().setScreen(new BlueprintBrowserScreen(bench, parent));
    }

    // Rows

    private enum RowType { UP, FOLDER, BLUEPRINT }

    /**
     * One line of the list. A favourite row also names its entry, and its blueprint
     * is the one the entry resolves to.
     */
    private record Row(RowType type, String folder, Blueprint blueprint, Favorites.Entry favorite) {

        String key() {
            return switch (type) {
                case UP -> "up";
                case FOLDER -> "f:" + folder;
                case BLUEPRINT -> favorite != null
                        ? "v:" + favorite.section() + ":" + favorite.worldKey() + ":" + favorite.id()
                        : "b:" + blueprint.id();
            };
        }
    }

    private boolean favoritesTab() {
        return ClientBlueprints.FAVORITES.equals(section);
    }

    private String folder() {
        return LAST_FOLDER.getOrDefault(section, "");
    }

    private void setFolder(String folder) {
        LAST_FOLDER.put(section, folder);
    }

    /** Lists the current folder (or the favourites) again, keeping the selection if it is still there. */
    private void refreshRows() {
        seenRevision = ClientBlueprints.revision();
        rows.clear();
        if (favoritesTab()) {
            List<Row> favorites = new ArrayList<>();
            for (Favorites.Entry entry : ClientBlueprints.favorites().entries())
                favorites.add(new Row(RowType.BLUEPRINT, "", ClientBlueprints.resolve(entry), entry));
            favorites.sort(Comparator.comparing((Row r) -> r.blueprint().name(), BlueprintIndex.NAME_ORDER));
            rows.addAll(favorites);
        } else {
            BlueprintIndex index = ClientBlueprints.section(section);
            // A folder deleted or renamed from under the browser sends it to the nearest one still there
            String folder = folder();
            while (!folder.isEmpty() && !index.folderExists(folder))
                folder = BlueprintIndex.parent(folder);
            setFolder(folder);
            if (!folder.isEmpty())
                rows.add(new Row(RowType.UP, BlueprintIndex.parent(folder), null, null));
            for (String child : index.subfolders(folder))
                rows.add(new Row(RowType.FOLDER, child, null, null));
            for (Blueprint blueprint : index.in(folder))
                rows.add(new Row(RowType.BLUEPRINT, folder, blueprint, null));
        }
        if (selected != null && selectedRow() == null)
            selected = null;
        clampScroll();
        updateButtons();
    }

    private Row selectedRow() {
        if (selected == null)
            return null;
        for (Row row : rows)
            if (row.key().equals(selected))
                return row;
        return null;
    }

    private int selectedIndex() {
        Row row = selectedRow();
        return row == null ? -1 : rows.indexOf(row);
    }

    private void select(int index) {
        if (rows.isEmpty())
            return;
        int clamped = Math.max(0, Math.min(rows.size() - 1, index));
        selected = rows.get(clamped).key();
        // Keep the selection in view
        int top = clamped * ROW, bottom = top + ROW;
        if (top < scroll)
            scroll = top;
        else if (bottom > scroll + listH)
            scroll = bottom - listH;
        clampScroll();
        updateButtons();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW - listH);
    }

    // Layout and widgets

    @Override
    public void added() {
        super.added();
        ClientBlueprints.subscribe(true);
    }

    @Override
    public void removed() {
        ClientBlueprints.subscribe(false);
        super.removed();
    }

    @Override
    protected void init() {
        mainWidgets.clear();
        dialogWidgets.clear();

        int doneW = 46;
        int tabW = Math.max(40, Math.min(80, (width - 2 * MARGIN - doneW - 8) / 3));
        String[] sections = { ClientBlueprints.WORLD, ClientBlueprints.LOCAL, ClientBlueprints.FAVORITES };
        for (int i = 0; i < sections.length; i++) {
            String target = sections[i];
            Button tab = Button.builder(Component.translatable(LANG + "tab." + target), b -> switchSection(target))
                    .bounds(MARGIN + i * (tabW + 2), MARGIN, tabW, BUTTON_H).build();
            tab.active = !target.equals(section);
            main(tab);
        }
        main(Button.builder(Component.translatable(LANG + "done"), b -> onClose())
                .bounds(width - MARGIN - doneW, MARGIN, doneW, BUTTON_H).build());

        paneW = Math.max(PANE_MIN, Math.min(200, (width - 2 * MARGIN) * 2 / 5));
        listX = MARGIN;
        listY = TOP_LIST;
        listW = Math.max(100, width - 2 * MARGIN - paneW - 6);
        paneX = listX + listW + 6;
        paneW = width - MARGIN - paneX;
        int statusY = height - MARGIN - 9;
        listH = Math.max(ROW * 3, statusY - 4 - listY);

        // Breadcrumb row: up, the path, and a new folder
        int crumbY = MARGIN + BUTTON_H + 4;
        upButton = main(Button.builder(Component.literal(".."), b -> goUp()).bounds(listX, crumbY, 20, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable(LANG + "up"))).build());
        int newW = Math.min(76, listW / 3);
        newFolderButton = main(Button.builder(Component.translatable(LANG + "new_folder"), b -> openNewFolder())
                .bounds(listX + listW - newW, crumbY, newW, BUTTON_H).build());

        // The pane's buttons: two columns at its foot
        int colW = (paneW - GAP) / 2;
        buttonsTop = statusY - 4 - 4 * (BUTTON_H + GAP) + GAP;
        makeButton = main(paneButton(0, 0, colW, "make", b -> make()));
        editButton = main(paneButton(1, 0, colW, "edit", b -> edit()));
        renameButton = main(paneButton(0, 1, colW, "rename", b -> openRename()));
        copyButton = main(paneButton(1, 1, colW, "copy", b -> copy()));
        moveButton = main(paneButton(0, 2, colW, "move", b -> openMove()));
        deleteButton = main(paneButton(1, 2, colW, "delete", b -> openDelete()));
        starButton = main(paneButton(0, 3, colW, "favorite", b -> toggleStar()));
        showButton = main(paneButton(1, 3, colW, "open", b -> {
            Row row = selectedRow();
            if (favoritesTab() && row != null && row.favorite() != null)
                show(row);
            else
                activate();
        }));

        if (dialog != null)
            dialog.build();
        refreshRows();
    }

    private Button paneButton(int column, int row, int w, String key, Button.OnPress press) {
        return Button.builder(Component.translatable(LANG + key), press)
                .bounds(paneX + column * (w + GAP), buttonsTop + row * (BUTTON_H + GAP), w, BUTTON_H).build();
    }

    private <T extends AbstractWidget> T main(T widget) {
        mainWidgets.add(widget);
        addWidget(widget);
        return widget;
    }

    private <T extends AbstractWidget> T modal(T widget) {
        dialogWidgets.add(widget);
        addWidget(widget);
        return widget;
    }

    /** Sets what each action button does and whether it may, for the current selection. */
    private void updateButtons() {
        if (makeButton == null)
            return;
        Row row = selectedRow();
        boolean blueprint = row != null && row.type() == RowType.BLUEPRINT;
        boolean folder = row != null && row.type() == RowType.FOLDER;
        boolean favorite = blueprint && row.favorite() != null;
        LocalPlayer player = Minecraft.getInstance().player;
        boolean creative = player != null && player.isCreative();
        boolean mayManage = blueprint && !favorite && mayManage(row.blueprint());

        makeButton.active = blueprint && creative && bench != null;
        makeButton.setTooltip(Tooltip.create(Component.translatable(creative
                ? LANG + "make.tooltip" : BlueprintService.SURVIVAL_KEY)));
        editButton.active = blueprint && bench != null;
        editButton.setTooltip(Tooltip.create(Component.translatable(LANG + "edit.tooltip")));

        renameButton.active = folder || mayManage;
        deleteButton.active = folder || mayManage;
        moveButton.active = mayManage;
        copyButton.active = blueprint && !favorite;
        Tooltip manageTip = favorite ? Tooltip.create(Component.translatable(LANG + "favorite_only.tooltip"))
                : blueprint && !mayManage ? Tooltip.create(Component.translatable(LANG + "not_yours.tooltip"))
                : null;
        renameButton.setTooltip(folder ? Tooltip.create(Component.translatable(LANG + "rename_folder")) : manageTip);
        deleteButton.setTooltip(folder ? Tooltip.create(Component.translatable(LANG + "delete_folder")) : manageTip);
        moveButton.setTooltip(manageTip);
        copyButton.setTooltip(favorite ? Tooltip.create(Component.translatable(LANG + "favorite_only.tooltip"))
                : null);

        boolean starred = blueprint && isStarred(row);
        starButton.active = blueprint;
        starButton.setMessage(Component.translatable(LANG + (starred ? "unfavorite" : "favorite")));

        if (favoritesTab()) {
            showButton.setMessage(Component.translatable(LANG + "show"));
            showButton.setTooltip(Tooltip.create(Component.translatable(LANG + "show.tooltip")));
            showButton.active = favorite && ClientBlueprints.live(row.favorite()) != null;
        } else {
            showButton.setMessage(Component.translatable(LANG + "open"));
            showButton.setTooltip(null);
            showButton.active = folder || row != null && row.type() == RowType.UP;
        }
        upButton.active = !favoritesTab() && !folder().isEmpty();
        upButton.visible = newFolderButton.visible = !favoritesTab();
        newFolderButton.active = !favoritesTab();

        if (dialog != null)
            for (AbstractWidget widget : mainWidgets)
                widget.active = false;
    }

    private boolean mayManage(Blueprint blueprint) {
        if (ClientBlueprints.LOCAL.equals(section))
            return true;
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && (blueprint.author().equals(player.getStringUUID()) || player.hasPermissions(2));
    }

    private boolean isStarred(Row row) {
        return row.favorite() != null || ClientBlueprints.isFavorite(section, row.blueprint().id());
    }

    // Actions

    private void switchSection(String target) {
        lastSection = section = target;
        selected = null;
        scroll = 0;
        if (ClientBlueprints.WORLD.equals(target))
            ClientBlueprints.refresh();
        rebuildWidgets();
    }

    private void goUp() {
        if (favoritesTab() || folder().isEmpty())
            return;
        String from = folder();
        setFolder(BlueprintIndex.parent(from));
        selected = "f:" + from;
        scroll = 0;
        refreshRows();
        int index = selectedIndex();
        if (index >= 0)
            select(index);
    }

    private void enter(String folder) {
        setFolder(folder);
        selected = null;
        scroll = 0;
        refreshRows();
    }

    /** What a double click or Enter does: go into a folder or up, or open a blueprint in the workbench. */
    private void activate() {
        Row row = selectedRow();
        if (row == null)
            return;
        switch (row.type()) {
            case UP -> goUp();
            case FOLDER -> enter(row.folder());
            case BLUEPRINT -> edit();
        }
    }

    /** Goes to where a favourite is kept and selects it there. */
    private void show(Row row) {
        Blueprint live = ClientBlueprints.live(row.favorite());
        if (live == null)
            return;
        lastSection = section = row.favorite().section();
        setFolder(live.folder());
        selected = "b:" + live.id();
        scroll = 0;
        rebuildWidgets();
        int index = selectedIndex();
        if (index >= 0)
            select(index);
    }

    /** The section a selected blueprint is edited or made as: its own, or local for a favourite from elsewhere. */
    private String sourceSection(Row row) {
        if (row.favorite() == null)
            return section;
        return ClientBlueprints.live(row.favorite()) != null ? row.favorite().section() : ClientBlueprints.LOCAL;
    }

    private void make() {
        Row row = selectedRow();
        if (row != null && row.type() == RowType.BLUEPRINT && bench != null)
            ClientBlueprints.make(bench, row.blueprint());
    }

    private void edit() {
        Row row = selectedRow();
        if (row != null && row.type() == RowType.BLUEPRINT && bench != null)
            DesignWorkbenchScreen.openWith(bench, row.blueprint(), sourceSection(row));
    }

    private void copy() {
        Row row = selectedRow();
        if (row == null || row.type() != RowType.BLUEPRINT || row.favorite() != null)
            return;
        if (ClientBlueprints.LOCAL.equals(section))
            ClientBlueprints.copyLocal(row.blueprint().id());
        else
            ClientBlueprints.copyWorld(row.blueprint().id());
    }

    private void toggleStar() {
        Row row = selectedRow();
        if (row == null || row.type() != RowType.BLUEPRINT)
            return;
        toggleStar(row);
    }

    private void toggleStar(Row row) {
        if (row.favorite() != null)
            ClientBlueprints.removeFavorite(row.favorite());
        else
            ClientBlueprints.toggleFavorite(section, row.blueprint());
        refreshRows();
    }

    private void openNewFolder() {
        if (favoritesTab())
            return;
        String parent = folder();
        openDialog(new TextDialog(Component.translatable(LANG + "dialog.new_folder"),
                Component.translatable(LANG + "new_folder"), Blueprint.MAX_SEGMENT, name -> {
                    String path = BlueprintIndex.child(parent, name);
                    if (path == null)
                        return Component.translatable(LANG + "dialog.bad_folder");
                    if (ClientBlueprints.section(section).folderExists(path))
                        return Component.translatable(LANG + "dialog.folder_taken");
                    if (ClientBlueprints.LOCAL.equals(section))
                        ClientBlueprints.mkdirLocal(path);
                    else
                        ClientBlueprints.mkdirWorld(path);
                    selected = "f:" + path;
                    return null;
                }));
    }

    private void openRename() {
        Row row = selectedRow();
        if (row == null || !renameButton.active)
            return;
        boolean local = ClientBlueprints.LOCAL.equals(section);
        if (row.type() == RowType.FOLDER) {
            String from = row.folder();
            openDialog(new TextDialog(Component.translatable(LANG + "dialog.rename_folder"),
                    Component.literal(BlueprintIndex.leaf(from)), Blueprint.MAX_SEGMENT, name -> {
                        String to = BlueprintIndex.child(BlueprintIndex.parent(from), name);
                        if (to == null)
                            return Component.translatable(LANG + "dialog.bad_folder");
                        if (to.equals(from))
                            return null;
                        if (ClientBlueprints.section(section).folderExists(to))
                            return Component.translatable(LANG + "dialog.folder_taken");
                        if (local)
                            ClientBlueprints.renameFolderLocal(from, to);
                        else
                            ClientBlueprints.renameFolderWorld(from, to);
                        selected = "f:" + to;
                        return null;
                    }));
        } else if (row.type() == RowType.BLUEPRINT) {
            Blueprint blueprint = row.blueprint();
            openDialog(new TextDialog(Component.translatable(LANG + "dialog.rename"),
                    Component.literal(blueprint.name()), Blueprint.MAX_NAME, name -> {
                        if (Blueprint.cleanName(name).isEmpty())
                            return Component.translatable(LANG + "dialog.empty_name");
                        if (local)
                            ClientBlueprints.renameLocal(blueprint.id(), name);
                        else
                            ClientBlueprints.renameWorld(blueprint.id(), name);
                        return null;
                    }));
        }
    }

    private void openDelete() {
        Row row = selectedRow();
        if (row == null || !deleteButton.active)
            return;
        boolean local = ClientBlueprints.LOCAL.equals(section);
        if (row.type() == RowType.FOLDER) {
            String folder = row.folder();
            int count = ClientBlueprints.section(section).under(folder).size();
            openDialog(new ConfirmDialog(Component.translatable(LANG + "dialog.delete_folder"),
                    Component.translatable(LANG + "dialog.delete_folder.message", BlueprintIndex.leaf(folder), count),
                    () -> {
                        if (local)
                            ClientBlueprints.deleteFolderLocal(folder);
                        else
                            ClientBlueprints.deleteFolderWorld(folder);
                    }));
        } else if (row.type() == RowType.BLUEPRINT) {
            Blueprint blueprint = row.blueprint();
            openDialog(new ConfirmDialog(Component.translatable(LANG + "dialog.delete"),
                    Component.translatable(LANG + (local ? "dialog.delete.local" : "dialog.delete.world"),
                            blueprint.name()),
                    () -> {
                        if (local)
                            ClientBlueprints.deleteLocal(blueprint.id());
                        else
                            ClientBlueprints.deleteWorld(blueprint.id());
                    }));
        }
    }

    private void openMove() {
        Row row = selectedRow();
        if (row == null || row.type() != RowType.BLUEPRINT || !moveButton.active)
            return;
        openDialog(new MoveDialog(section, row.blueprint()));
    }

    // Dialogs

    private void openDialog(Dialog dialog) {
        this.dialog = dialog;
        rebuildWidgets();
    }

    private void closeDialog() {
        dialog = null;
        rebuildWidgets();
    }

    /**
     * A box over the screen, centred, with its own widgets. Built again on every
     * {@link #init}, so it keeps whatever the player typed in fields of its own
     * rather than in the widgets.
     */
    private abstract class Dialog {

        final Component title;
        int x, y, w, h;
        Component error;

        Dialog(Component title) {
            this.title = title;
        }

        abstract int preferredHeight();

        /** Adds the dialog's widgets; {@link #x}, {@link #y}, {@link #w} and {@link #h} are set. */
        abstract void widgets();

        /** Enter or OK; true once the dialog has done its job and may close. */
        abstract boolean confirm();

        void build() {
            w = Math.min(240, width - 2 * MARGIN);
            h = Math.min(preferredHeight(), height - 2 * MARGIN);
            x = (width - w) / 2;
            y = (height - h) / 2;
            widgets();
        }

        /** OK and Cancel along the bottom. */
        void okCancel(Component ok) {
            int bw = (w - 3 * MARGIN) / 2;
            modal(Button.builder(ok, b -> submit()).bounds(x + MARGIN, y + h - MARGIN - BUTTON_H, bw, BUTTON_H)
                    .build());
            modal(Button.builder(Component.translatable(LANG + "dialog.cancel"), b -> closeDialog())
                    .bounds(x + w - MARGIN - bw, y + h - MARGIN - BUTTON_H, bw, BUTTON_H).build());
        }

        void submit() {
            if (confirm())
                closeDialog();
        }

        void render(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.fill(x, y, x + w, y + h, 0xF0181C24);
            graphics.renderOutline(x, y, w, h, 0xFF606878);
            graphics.drawString(font, title, x + MARGIN, y + MARGIN, TEXT, false);
            if (error != null)
                graphics.drawString(font, font.plainSubstrByWidth(error.getString(), w - 2 * MARGIN), x + MARGIN,
                        y + h - MARGIN - BUTTON_H - 11, BAD, false);
        }

        boolean mouseClicked(double mouseX, double mouseY) {
            return false;
        }

        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            return false;
        }
    }

    /** One line of text to type, checked by {@code accept}, which returns an error or null once done. */
    private class TextDialog extends Dialog {

        private final int maxLength;
        private final Function<String, Component> accept;
        private String value;
        private EditBox box;

        TextDialog(Component title, Component initial, int maxLength, Function<String, Component> accept) {
            super(title);
            this.maxLength = maxLength;
            this.accept = accept;
            this.value = initial.getString();
        }

        @Override
        int preferredHeight() {
            return 2 * MARGIN + 10 + 20 + 12 + BUTTON_H;
        }

        @Override
        void widgets() {
            box = modal(new EditBox(font, x + MARGIN, y + MARGIN + 12, w - 2 * MARGIN, 16, title));
            box.setMaxLength(maxLength);
            box.setValue(value);
            box.setResponder(text -> {
                value = text;
                error = null;
            });
            EditBoxes.cursorToEnd(box);
            box.setHighlightPos(0);
            setFocused(box);
            okCancel(Component.translatable(LANG + "dialog.ok"));
        }

        @Override
        boolean confirm() {
            error = accept.apply(value);
            return error == null;
        }
    }

    /** A question with Yes and No, for anything that cannot be taken back. */
    private class ConfirmDialog extends Dialog {

        private final Component message;
        private final Runnable yes;

        ConfirmDialog(Component title, Component message, Runnable yes) {
            super(title);
            this.message = message;
            this.yes = yes;
        }

        private int lines() {
            return font.split(message, Math.max(60, Math.min(240, width - 2 * MARGIN) - 2 * MARGIN)).size();
        }

        @Override
        int preferredHeight() {
            return 2 * MARGIN + 14 + lines() * 10 + 6 + BUTTON_H;
        }

        @Override
        void widgets() {
            okCancel(Component.translatable(LANG + "dialog.yes_delete"));
            // Nothing to type, so the confirm button does not take focus: Enter still confirms
            setFocused(null);
        }

        @Override
        boolean confirm() {
            yes.run();
            return true;
        }

        @Override
        void render(GuiGraphics graphics, int mouseX, int mouseY) {
            super.render(graphics, mouseX, mouseY);
            int lineY = y + MARGIN + 14;
            for (var line : font.split(message, w - 2 * MARGIN)) {
                graphics.drawString(font, line, x + MARGIN, lineY, 0xE0E0E0, false);
                lineY += 10;
            }
        }
    }

    /**
     * Where to move a blueprint: a section, a folder from that section's list, or a
     * new path typed in (made on the way).
     */
    private class MoveDialog extends Dialog {

        private final String from;
        private final Blueprint blueprint;
        private String target;
        private String path;
        private int listScroll;
        private EditBox box;
        private int folderListY, folderListH;

        MoveDialog(String from, Blueprint blueprint) {
            super(Component.translatable(LANG + "dialog.move", blueprint.name()));
            this.from = from;
            this.blueprint = blueprint;
            this.target = from;
            this.path = blueprint.folder();
        }

        @Override
        int preferredHeight() {
            return 170;
        }

        private List<String> choices() {
            return ClientBlueprints.section(target).folderChoices();
        }

        @Override
        void widgets() {
            int rowY = y + MARGIN + 12;
            Button sectionButton = modal(Button.builder(Component.translatable(LANG + "dialog.move.section",
                    Component.translatable(LANG + "tab." + target)), b -> {
                        target = ClientBlueprints.LOCAL.equals(target) ? ClientBlueprints.WORLD
                                : ClientBlueprints.LOCAL;
                        listScroll = 0;
                        rebuildWidgets();
                    }).bounds(x + MARGIN, rowY, w - 2 * MARGIN, BUTTON_H).build());
            sectionButton.setTooltip(Tooltip.create(Component.translatable(LANG + "dialog.move.section.tooltip")));
            box = modal(new EditBox(font, x + MARGIN, rowY + BUTTON_H + 4, w - 2 * MARGIN, 16,
                    Component.translatable(LANG + "dialog.move.path")));
            box.setMaxLength((Blueprint.MAX_SEGMENT + 1) * Blueprint.MAX_DEPTH);
            box.setValue(path);
            box.setResponder(text -> {
                path = text;
                error = null;
            });
            box.setHint(Component.translatable(LANG + "dialog.move.path"));
            setFocused(box);
            folderListY = rowY + BUTTON_H + 4 + 20;
            folderListH = Math.max(ROW, y + h - MARGIN - BUTTON_H - 14 - folderListY);
            okCancel(Component.translatable(LANG + "dialog.move.ok"));
        }

        @Override
        boolean confirm() {
            String folder = Blueprint.normalizeFolder(path);
            if (folder == null) {
                error = Component.translatable(LANG + "dialog.bad_folder");
                return false;
            }
            if (target.equals(from) && folder.equals(blueprint.folder()))
                return true;
            ClientBlueprints.moveBetween(from, blueprint, target, folder);
            return true;
        }

        @Override
        void render(GuiGraphics graphics, int mouseX, int mouseY) {
            super.render(graphics, mouseX, mouseY);
            int listX = x + MARGIN, listW = w - 2 * MARGIN;
            graphics.fill(listX, folderListY, listX + listW, folderListY + folderListH, LIST_BG);
            graphics.enableScissor(listX, folderListY, listX + listW, folderListY + folderListH);
            List<String> choices = choices();
            String current = Blueprint.normalizeFolder(path);
            for (int i = 0; i < choices.size(); i++) {
                int rowY = folderListY + i * ROW - listScroll;
                if (rowY + ROW < folderListY || rowY > folderListY + folderListH)
                    continue;
                String choice = choices.get(i);
                boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW
                        && mouseY >= folderListY && mouseY < folderListY + folderListH;
                if (choice.equals(current))
                    graphics.fill(listX, rowY, listX + listW, rowY + ROW, ROW_SELECTED);
                else if (hovered)
                    graphics.fill(listX, rowY, listX + listW, rowY + ROW, ROW_HOVER);
                drawFolderIcon(graphics, listX + 2, rowY + 2);
                graphics.drawString(font, font.plainSubstrByWidth("/" + choice, listW - 16), listX + 14, rowY + 2,
                        TEXT, false);
            }
            graphics.disableScissor();
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY) {
            int listX = x + MARGIN, listW = w - 2 * MARGIN;
            if (mouseX < listX || mouseX >= listX + listW || mouseY < folderListY
                    || mouseY >= folderListY + folderListH)
                return false;
            int index = (int) ((mouseY - folderListY + listScroll) / ROW);
            List<String> choices = choices();
            if (index >= 0 && index < choices.size()) {
                path = choices.get(index);
                box.setValue(path);
            }
            return true;
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            int max = Math.max(0, choices().size() * ROW - folderListH);
            listScroll = Math.max(0, Math.min(max, listScroll - (int) (delta * ROW * 2)));
            return true;
        }
    }

    // Input

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (dialog != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closeDialog();
                return true;
            }
            // Enter confirms, unless Tab has put the focus on one of the dialog's buttons, which Enter then presses
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !(getFocused() instanceof Button)) {
                dialog.submit();
                return true;
            }
            return super.keyPressed(key, scanCode, modifiers);
        }
        int index = selectedIndex();
        int page = Math.max(1, listH / ROW - 1);
        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> select(index < 0 ? 0 : index + 1);
            case GLFW.GLFW_KEY_UP -> select(index < 0 ? rows.size() - 1 : index - 1);
            case GLFW.GLFW_KEY_PAGE_DOWN -> select(Math.max(0, index) + page);
            case GLFW.GLFW_KEY_PAGE_UP -> select(Math.max(0, index) - page);
            case GLFW.GLFW_KEY_HOME -> select(0);
            case GLFW.GLFW_KEY_END -> select(rows.size() - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> activate();
            case GLFW.GLFW_KEY_BACKSPACE -> goUp();
            case GLFW.GLFW_KEY_DELETE -> openDelete();
            case GLFW.GLFW_KEY_F2 -> openRename();
            default -> {
                return super.keyPressed(key, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dialog != null) {
            if (super.mouseClicked(mouseX, mouseY, button))
                return true;
            dialog.mouseClicked(mouseX, mouseY);
            // Nothing behind a dialog reacts, wherever the click lands
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !overList(mouseX, mouseY))
            return false;
        if (maxScroll() > 0 && mouseX >= listX + listW - 5) {
            draggingScrollbar = true;
            dragScrollbar(mouseY);
            return true;
        }
        int index = (int) ((mouseY - listY + scroll) / ROW);
        if (index < 0 || index >= rows.size()) {
            selected = null;
            updateButtons();
            return true;
        }
        Row row = rows.get(index);
        if (row.type() == RowType.BLUEPRINT && mouseX >= starX() - 1) {
            selected = row.key();
            toggleStar(row);
            return true;
        }
        long now = Util.getMillis();
        boolean twice = row.key().equals(lastClickKey) && now - lastClick < 400;
        lastClick = now;
        lastClickKey = row.key();
        select(index);
        if (twice)
            activate();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && dialog == null) {
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragScrollbar(double mouseY) {
        double fraction = (mouseY - listY) / Math.max(1, listH);
        scroll = (int) Math.round(fraction * maxScroll());
        clampScroll();
    }

    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        if (dialog != null)
            return dialog.mouseScrolled(mouseX, mouseY, delta);
        if (overList(mouseX, mouseY)) {
            scroll -= (int) (delta * ROW * 3);
            clampScroll();
            return true;
        }
        return super.onScroll(mouseX, mouseY, delta);
    }

    private boolean overList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH;
    }

    @Override
    public void tick() {
        if (seenRevision != ClientBlueprints.revision())
            refreshRows();
        for (AbstractWidget widget : dialogWidgets)
            if (widget instanceof EditBox box)
                EditBoxes.tick(box);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawBackground(graphics, mouseX, mouseY, partialTick);
        boolean modal = dialog != null;
        int mx = modal ? -1 : mouseX, my = modal ? -1 : mouseY;

        graphics.fill(listX - 2, MARGIN + BUTTON_H + 2, width - MARGIN + 2, height - MARGIN + 2, PANEL_BG);
        drawBreadcrumb(graphics);
        drawList(graphics, mx, my);
        drawPane(graphics);
        drawStatus(graphics);
        for (AbstractWidget widget : mainWidgets)
            widget.render(graphics, mx, my, partialTick);

        if (modal) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 300);
            graphics.fill(0, 0, width, height, 0xA0000000);
            dialog.render(graphics, mouseX, mouseY);
            for (AbstractWidget widget : dialogWidgets)
                widget.render(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
        }
    }

    private void drawBreadcrumb(GuiGraphics graphics) {
        int crumbY = MARGIN + BUTTON_H + 4;
        String text;
        if (favoritesTab())
            text = Component.translatable(LANG + "favorites.heading").getString();
        else
            text = Component.translatable(LANG + "tab." + section).getString() + " /"
                    + (folder().isEmpty() ? "" : " " + folder().replace("/", " / "));
        int x = favoritesTab() ? listX : listX + 24;
        int room = listX + listW - (favoritesTab() ? 0 : newFolderButton.getWidth() + 4) - x;
        // A deep path keeps its end, which is where the player is
        String shown = text;
        if (font.width(shown) > room) {
            String ellipsis = "...";
            while (shown.length() > 1 && font.width(ellipsis + shown) > room)
                shown = shown.substring(1);
            shown = ellipsis + shown;
        }
        graphics.drawString(font, shown, x, crumbY + 4, TEXT, false);
    }

    private int starX() {
        return listX + listW - (maxScroll() > 0 ? 6 : 0) - 11;
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(listX, listY, listX + listW, listY + listH, LIST_BG);
        if (!favoritesTab() && ClientBlueprints.WORLD.equals(section) && !ClientBlueprints.worldKnown()) {
            graphics.drawCenteredString(font, Component.translatable(LANG + "loading"), listX + listW / 2,
                    listY + listH / 2 - 4, MUTED);
            return;
        }
        if (rows.isEmpty() || rows.size() == 1 && rows.get(0).type() == RowType.UP) {
            Component empty = Component.translatable(LANG + (favoritesTab() ? "empty.favorites" : "empty"));
            int lineY = listY + Math.max(ROW + 4, listH / 2 - 8);
            for (var line : font.split(empty, listW - 12)) {
                graphics.drawCenteredString(font, line, listX + listW / 2, lineY, MUTED);
                lineY += 10;
            }
        }

        boolean showKind = listW >= 190, showDate = listW >= 290;
        int right = starX() - 4;
        int dateW = font.width("0000-00-00");
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < rows.size(); i++) {
            int rowY = listY + i * ROW - scroll;
            if (rowY + ROW <= listY || rowY >= listY + listH)
                continue;
            Row row = rows.get(i);
            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= Math.max(rowY, listY)
                    && mouseY < Math.min(rowY + ROW, listY + listH);
            if (row.key().equals(selected))
                graphics.fill(listX, rowY, listX + listW, rowY + ROW, ROW_SELECTED);
            else if (hovered)
                graphics.fill(listX, rowY, listX + listW, rowY + ROW, ROW_HOVER);

            int textY = rowY + 2;
            int nameRight = right;
            switch (row.type()) {
                case UP -> {
                    drawFolderIcon(graphics, listX + 3, rowY + 2);
                    graphics.drawString(font, "..", listX + 16, textY, MUTED, false);
                }
                case FOLDER -> {
                    drawFolderIcon(graphics, listX + 3, rowY + 2);
                    graphics.drawString(font, ellipsize(BlueprintIndex.leaf(row.folder()), right - listX - 16),
                            listX + 16, textY, TEXT, false);
                }
                case BLUEPRINT -> {
                    Blueprint blueprint = row.blueprint();
                    drawBlueprintIcon(graphics, listX + 3, rowY + 2);
                    if (showDate) {
                        String date = row.favorite() != null ? sourceLabel(row.favorite())
                                : DAY.format(Instant.ofEpochMilli(blueprint.modified()));
                        graphics.drawString(font, date, right - dateW, textY, MUTED, false);
                        nameRight = right - dateW - 6;
                    }
                    if (showKind) {
                        String kind = blueprint.design().kind().label().getString();
                        int kindW = font.width(kind);
                        graphics.drawString(font, kind, nameRight - kindW, textY, MUTED, false);
                        nameRight -= kindW + 6;
                    }
                    graphics.drawString(font, ellipsize(blueprint.name(), nameRight - listX - 16), listX + 16, textY,
                            TEXT, false);
                    boolean starred = isStarred(row);
                    graphics.drawString(font, starred ? "★" : "☆", starX(), textY,
                            starred ? STAR_ON : STAR_OFF, false);
                }
            }
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            int barX = listX + listW - 4;
            int thumbH = Math.max(10, listH * listH / (rows.size() * ROW));
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll();
            graphics.fill(barX, listY, barX + 4, listY + listH, 0x40FFFFFF);
            graphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xC0FFFFFF);
        }
    }

    private String sourceLabel(Favorites.Entry entry) {
        if (entry.isLocal())
            return Component.translatable(LANG + "tab.local").getString();
        return Component.translatable(LANG + (entry.worldKey().equals(ClientBlueprints.worldKey())
                ? "tab.world" : "elsewhere")).getString();
    }

    /** The details of the selection, in the room above the pane's buttons. */
    private void drawPane(GuiGraphics graphics) {
        int x = paneX, y = listY, w = paneW;
        int bottom = buttonsTop - 4;
        List<Component> lines = new ArrayList<>();
        Row row = selectedRow();
        Component heading;
        if (row == null || row.type() == RowType.UP) {
            heading = Component.translatable(LANG + "details.none");
            lines.add(Component.translatable(LANG + "details.hint"));
        } else if (row.type() == RowType.FOLDER) {
            BlueprintIndex index = ClientBlueprints.section(section);
            heading = Component.literal(BlueprintIndex.leaf(row.folder()));
            lines.add(Component.translatable(LANG + "details.folder", index.subfolders(row.folder()).size(),
                    index.in(row.folder()).size()));
        } else {
            Blueprint blueprint = row.blueprint();
            ControlDesign design = blueprint.design();
            heading = Component.literal(blueprint.name());
            lines.add(design.kind().label());
            if (design.rack() != null)
                lines.add(Component.translatable(LANG + "details.windows", design.rack().cellsX,
                        design.rack().cellsY));
            else
                lines.add(Component.translatable(LANG + "details.parts", design.positions(),
                        design.movingParts().size()));
            lines.add(Component.translatable(LANG + "details.texts", design.texts().size(), design.lights().size()));
            if (blueprint.modified() > 0)
                lines.add(Component.translatable(LANG + "details.modified",
                        MINUTE.format(Instant.ofEpochMilli(blueprint.modified()))));
            if (row.favorite() != null)
                lines.add(Component.translatable(LANG + "details.from", sourceLabel(row.favorite())));
            else
                lines.add(Component.translatable(LANG + "details.folder_path", "/" + blueprint.folder()));
        }
        graphics.drawString(font, ellipsize(heading.getString(), w), x, y, TEXT, false);
        int lineY = y + 12;
        for (Component line : lines)
            for (var wrapped : font.split(line, w)) {
                if (lineY + 9 > bottom)
                    return;
                graphics.drawString(font, wrapped, x, lineY, MUTED, false);
                lineY += 10;
            }
    }

    private void drawStatus(GuiGraphics graphics) {
        Component status = ClientBlueprints.status();
        if (status == null)
            return;
        graphics.drawString(font, ellipsize(status.getString(), width - 2 * MARGIN), MARGIN, height - MARGIN - 9,
                ClientBlueprints.statusOk() ? OK : BAD, false);
    }

    private String ellipsize(String text, int room) {
        if (room <= 0)
            return "";
        if (font.width(text) <= room)
            return text;
        return font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
    }

    /** A folder: a body with a tab on its top left. */
    private static void drawFolderIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 4, y + 2, FOLDER);
        graphics.fill(x, y + 1, x + 9, y + 8, FOLDER);
        graphics.fill(x + 1, y + 3, x + 8, y + 7, 0xFFF0C860);
    }

    /** A blueprint: a blue sheet with pale lines on it. */
    private static void drawBlueprintIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 9, y + 8, BLUEPRINT);
        graphics.fill(x + 2, y + 2, x + 7, y + 3, 0xFFD0E4FF);
        graphics.fill(x + 2, y + 5, x + 6, y + 6, 0xFFD0E4FF);
    }
}
