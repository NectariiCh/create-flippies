package com.nectariich.create_flippies.client;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.nio.file.Path;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.blueprint.BlueprintBrowserScreen;
import com.nectariich.create_flippies.client.blueprint.ClientBlueprints;
import com.nectariich.create_flippies.client.design.ClientDesigns;
import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.design.DesignImporter;
import com.nectariich.create_flippies.client.design.DesignPreview;
import com.nectariich.create_flippies.client.design.screen.ScreenPattern;
import com.nectariich.create_flippies.client.design.ui.DialTab;
import com.nectariich.create_flippies.client.design.ui.ModelTab;
import com.nectariich.create_flippies.client.design.ui.MotionTab;
import com.nectariich.create_flippies.client.design.ui.Panel;
import com.nectariich.create_flippies.client.design.ui.RackTab;
import com.nectariich.create_flippies.client.design.ui.ScreenTab;
import com.nectariich.create_flippies.client.design.ui.MaterialEditor;
import com.nectariich.create_flippies.client.design.ui.SaveDialog;
import com.nectariich.create_flippies.client.design.ui.TextBoxEditor;
import com.nectariich.create_flippies.client.design.ui.TextTab;
import com.nectariich.create_flippies.client.design.ui.WindowsTab;
import com.nectariich.create_flippies.client.design.ui.WorkbenchHost;
import com.nectariich.create_flippies.client.design.ui.WorkbenchState;
import com.nectariich.create_flippies.client.display.ElementEditTarget;
import com.nectariich.create_flippies.client.display.LocalElementTarget;
import com.nectariich.create_flippies.client.display.editor.BenchGlassView;
import com.nectariich.create_flippies.client.display.editor.DisplayEditor;
import com.nectariich.create_flippies.client.display.editor.EditorHost;
import com.nectariich.create_flippies.client.display.editor.EditorScale;
import com.nectariich.create_flippies.client.display.editor.GlassView;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorMesh;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.MeshInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The Design Workbench's screen, in which a control is made from an imported model:
 * pick what it is built on (a two- or three-position switch, or a push-button),
 * import an {@code .obj} with its {@code .mtl} and textures, say which parts move and
 * which light up, size and place it, set how it moves, print text on it, and save it
 * as a blueprint, from which the control itself is made.
 *
 * The world is left visible: the model stands on the bench top as it would on a
 * panel, with its boxes outlined, and updates as the numbers change. The left panel
 * has three tabs:
 * <ul>
 * <li>Model: the kind, the import, size and place, a button's mode and rating,
 * the ambient occlusion, and the parts list with each part's materials, where the
 * moving and lit parts are picked ({@link ModelTab}).</li>
 * <li>Motion: where the moving parts stand at each position, and a switch's pivot
 * ({@link MotionTab}).</li>
 * <li>Text: the camera goes straight overhead with a flat projection, boxes are
 * drawn on the preview with the mouse ({@link TextBoxEditor}), and the panel edits
 * the selected one ({@link TextTab}).</li>
 * </ul>
 * What is being edited lives in a {@link WorkbenchState}, so the widgets can be
 * rebuilt at will (on scrolling, on changing tab) without losing anything.
 *
 * Above the tabs, the mode: a control built on an imported model, as above, or an
 * annunciator rack, whose model is generated from the rack it describes (a port of
 * the Unity rack builder). The rack mode's tabs are Rack (the layout and style),
 * Windows (each window's legend, colour and id, and CSV import) and the same Text
 * tab. Each mode keeps a state of its own, so switching between them loses nothing.
 * In rack mode the model is rebuilt whenever the rack changes, and installed like an
 * imported one, so the preview, the save and the item are the ordinary ones.
 *
 * The gauge mode is a control on an imported model again, rotary or edgewise, with
 * the same Model, Motion and Text tabs: the moving parts are the needle, turned
 * about its pivot across a rotary dial's sweep or slid or swung along an edgewise
 * scale. Its Dial tab ({@link DialTab}) sets what it measures and the settings of
 * the face generator its kind uses, whose printed face becomes the material of the
 * submesh given it with "D" in the parts list or in the material editor.
 *
 * The screen mode is a control on an imported model with nothing moving, a
 * procedural screen: its Model and Text tabs are the control's, and its Screen tab
 * ({@link ScreenTab}) says which submesh is the glass (given with "S" in the parts
 * list or in the material editor), where the viewport sits on it, and authors the
 * layout a placed screen starts with: its Edit layout button switches the whole
 * workbench into the screwdriver menu's own visual editor ({@link DisplayEditor}),
 * over the preview laid glass up on the bench, until Done or Esc.
 *
 * Double-clicking a material element in the parts list opens the
 * {@link MaterialEditor} over the preview, which edits how that element is drawn
 * while the rest of the workbench stays in use.
 *
 * Nothing leaves the workbench except through a blueprint: Save opens the
 * {@link SaveDialog}, and Blueprints opens the browser, from which a saved blueprint
 * can be made into an item or opened here again ({@link #openWith}).
 */
public class DesignWorkbenchScreen extends Screen implements WorkbenchHost {

    /** What is being made: a control on an imported model, an annunciator rack, a gauge, or a screen. */
    private enum Mode {
        CONTROL("mode.control"), RACK("mode.rack"), GAUGE("mode.gauge"), SCREEN("mode.screen");

        private final String key;

        Mode(String key) {
            this.key = key;
        }
    }

    /** The tabs across the top of the panel, each in the modes it names or, naming none, in all of them. */
    private enum Tab {
        MODEL("tab.model", Mode.CONTROL, Mode.GAUGE, Mode.SCREEN), MOTION("tab.motion", Mode.CONTROL, Mode.GAUGE),
        RACK("tab.rack", Mode.RACK), WINDOWS("tab.windows", Mode.RACK), DIAL("tab.dial", Mode.GAUGE),
        SCREEN("tab.screen", Mode.SCREEN), TEXT("tab.text");

        private final List<Mode> modes;
        private final String key;

        Tab(String key, Mode... modes) {
            this.modes = List.of(modes);
            this.key = key;
        }

        static List<Tab> of(Mode mode) {
            return java.util.Arrays.stream(values()).filter(tab -> tab.modes.isEmpty() || tab.modes.contains(mode))
                    .toList();
        }
    }

    /** Where the mode and the tabs sit, and the top of the scrolling area under them. */
    private static final int MODE_Y = 18;
    private static final int TABS_Y = MODE_Y + 22;
    private static final int TOP = TABS_Y + 24;

    /** The fixed row of buttons along the bottom of the panel. */
    private static final int FOOTER = 26;

    private final BlockPos bench;
    private final WorkbenchState controlState = new WorkbenchState();
    private final WorkbenchState rackState = WorkbenchState.forRack();
    private final WorkbenchState gaugeState = WorkbenchState.forGauge();
    private final WorkbenchState screenState = WorkbenchState.forScreen();
    private final TextBoxEditor textEditor = new TextBoxEditor(this);

    private Mode mode = Mode.CONTROL;
    /** The state of the mode shown. */
    private WorkbenchState state = controlState;
    private Tab tab = Tab.MODEL;
    private Component status = Component.empty();
    private SaveDialog dialog;
    /** The material editor's window over the preview, or null when it is closed. */
    private MaterialEditor materialEditor;
    private Panel panel;

    private int scroll;
    private int maxScroll;

    /**
     * Whether the preview was the last thing clicked, which is what makes Delete or
     * Backspace remove the selected text box. Asking whether a text field has focus
     * instead is not enough: every rebuild of the widgets (scrolling the panel, a
     * window resize, a model arriving) clears focus without the player noticing, and
     * the next Backspace meant for a typo would delete the whole box.
     */
    private boolean previewFocused;

    /**
     * The display editor over the screen's layout while the workbench is in editor
     * mode, which replaces the whole panel; null otherwise. While it is open, the
     * screen's drawing, input and ticking all go to it.
     */
    private DisplayEditor layoutEditor;

    /** A blueprint's model being fetched from the server, until it arrives. */
    private String downloading;

    public static void open(BlockPos bench) {
        Minecraft.getInstance().setScreen(new DesignWorkbenchScreen(bench));
    }

    /**
     * Opens the workbench with a saved blueprint loaded for editing, so Save
     * overwrites it. The model comes from this client's files if it has them, else
     * from the server, with the screen saying so until it arrives.
     *
     * @param section where the blueprint was opened from: {@link ClientBlueprints#LOCAL}
     *                makes the dialog offer saving locally again
     */
    public static void openWith(BlockPos bench, Blueprint blueprint, String section) {
        DesignWorkbenchScreen screen = new DesignWorkbenchScreen(bench);
        if (blueprint.design().kind().isRack()) {
            // A rack's model is built again from its rack, never fetched
            screen.mode = Mode.RACK;
            screen.state = screen.rackState;
            screen.tab = Tab.RACK;
            screen.state.load(blueprint, ClientBlueprints.LOCAL.equals(section));
            screen.status = t("loaded", blueprint.name());
            Minecraft.getInstance().setScreen(screen);
            return;
        }
        if (blueprint.design().kind().isGauge()) {
            // A gauge is a model like a control's, fetched the same way, in its own mode
            screen.mode = Mode.GAUGE;
            screen.state = screen.gaugeState;
        } else if (blueprint.design().kind().isScreen()) {
            screen.mode = Mode.SCREEN;
            screen.state = screen.screenState;
        }
        screen.state.load(blueprint, ClientBlueprints.LOCAL.equals(section));
        Minecraft.getInstance().setScreen(screen);
        screen.loadBlueprintModel();
    }

    private DesignWorkbenchScreen(BlockPos bench) {
        super(t("title"));
        this.bench = bench;
    }

    /**
     * Takes the camera every time this screen is shown, the first time and on coming
     * back from the blueprint browser, which gives it back while it is open.
     */
    @Override
    public void added() {
        super.added();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null)
            DesignCamera.enter(bench, minecraft.player.getDirection());
        DesignCamera.setTop(tab == Tab.TEXT);
    }

    // ------------------------------------------------------------------ host

    @Override
    public WorkbenchState state() {
        return state;
    }

    @Override
    public void changed() {
        refresh();
    }

    @Override
    public void rebuild() {
        rebuildWidgets();
    }

    @Override
    public void browse() {
        if (mode == Mode.RACK)
            DesignImporter.browseCsv(this::importFiles);
        else
            DesignImporter.browse(this::importFiles);
    }

    @Override
    public Component status() {
        return status;
    }

    @Override
    public void setStatus(Component status) {
        this.status = status;
    }

    @Override
    public void editMaterial(String part, String material) {
        materialEditor = new MaterialEditor(this, part, material);
        rebuildWidgets();
    }

    @Override
    public void closeMaterialEditor() {
        materialEditor = null;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- layout

    /** The bottom of the scrolling area, above the footer. */
    private int bottom() {
        return height - FOOTER - 4;
    }

    @Override
    protected void init() {
        // The editor runs at its own GUI scale, and the workbench at the game's
        if (layoutEditor != null)
            EditorScale.apply(this);
        else
            EditorScale.restore(this);
        super.init();
        if (layoutEditor != null) {
            layoutEditor.init(width, height);
            return;
        }
        if (dialog != null) {
            dialog.init(font, width, height, this::addRenderableWidget);
            return;
        }
        // The rack's model has to exist before Save can say whether there is one
        if (mode == Mode.RACK)
            generateModel();

        // The mode and the tabs, fixed above the scrolling column
        Mode[] modes = Mode.values();
        for (int i = 0; i < modes.length; i++) {
            Mode option = modes[i];
            Button button = Button.builder(t(option.key), b -> setMode(option))
                    .bounds(Panel.column(i, modes.length), MODE_Y, Panel.columnWidth(modes.length), 20)
                    .tooltip(Tooltip.create(t(option.key + ".tooltip"))).build();
            button.active = option != mode;
            addRenderableWidget(button);
        }
        List<Tab> tabs = Tab.of(mode);
        for (int i = 0; i < tabs.size(); i++) {
            Tab option = tabs.get(i);
            Button button = Button.builder(t(option.key), b -> setTab(option))
                    .bounds(Panel.column(i, tabs.size()), TABS_Y, Panel.columnWidth(tabs.size()), 20).build();
            button.active = option != tab;
            addRenderableWidget(button);
        }

        int firstScrolling = children().size();
        panel = new Panel(font, this::addRenderableWidget, TOP - scroll);
        switch (tab) {
        case MODEL -> ModelTab.build(panel, this);
        case MOTION -> MotionTab.build(panel, this);
        case RACK -> RackTab.build(panel, this);
        case WINDOWS -> WindowsTab.build(panel, this);
        case DIAL -> DialTab.build(panel, this);
        case SCREEN -> ScreenTab.build(panel, this, this::openLayoutEditor);
        case TEXT -> TextTab.build(panel, this);
        }
        int contentBottom = panel.y;
        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> scrolling = List
                .copyOf(children().subList(firstScrolling, children().size()));

        // The footer, fixed along the bottom of the panel
        int footerY = height - FOOTER;
        Button save = Button.builder(t("save"), b -> openSaveDialog())
                .bounds(Panel.column(0, 3), footerY, Panel.columnWidth(3), 20).build();
        save.active = state.canSave();
        save.setTooltip(Tooltip.create(t(save.active ? "save.tooltip"
                : state.kind.isRack() ? "save.needs_rack" : state.info == null ? "save.needs_model"
                        : state.kind.isScreen() ? state.screenError()
                        : state.moving.isEmpty() ? state.kind.isGauge() ? "needle.none" : "save.needs_moving"
                                : state.kind.isGauge() ? state.gaugeError() : "save.needs_moving")));
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(t("blueprints"), b -> BlueprintBrowserScreen.open(bench, this))
                .bounds(Panel.column(1, 3), footerY, Panel.columnWidth(3), 20)
                .tooltip(Tooltip.create(t("blueprints.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(Panel.column(2, 3), footerY, Panel.columnWidth(3), 20).build());

        // Input only: the window draws its own widgets, after everything else, so it is
        // on top of whatever it was dragged over
        if (materialEditor != null)
            materialEditor.init(font, width, height, this::addWidget);

        // The column scrolls when it is taller than the window, which a small window
        // or a large GUI scale makes it, more so with a long parts list
        maxScroll = Math.max(0, contentBottom + scroll - bottom());
        if (scroll > maxScroll) {
            scroll = maxScroll;
            rebuildWidgets();
            return;
        }
        for (var child : scrolling)
            if (child instanceof AbstractWidget widget)
                widget.visible = inView(widget.getY(), widget.getHeight());

        refresh();
    }

    /** Whether a row lies wholly in the scrolling area, between the tabs and the footer. */
    private boolean inView(int y, int rowHeight) {
        return y >= TOP - 2 && y + rowHeight <= bottom();
    }

    private boolean overPanel(double mouseX) {
        return mouseX < Panel.X + Panel.WIDTH + 6;
    }

    private void setMode(Mode mode) {
        if (mode == this.mode)
            return;
        this.mode = mode;
        state = switch (mode) {
        case RACK -> rackState;
        case GAUGE -> gaugeState;
        case SCREEN -> screenState;
        default -> controlState;
        };
        // The element it was editing belongs to the other mode's model
        materialEditor = null;
        status = Component.empty();
        // The other mode's model comes off the bench; refresh puts this one's on
        DesignPreview.hide();
        setTab(Tab.of(mode).get(0));
    }

    private void setTab(Tab tab) {
        this.tab = tab;
        previewFocused = false;
        scroll = 0;
        DesignCamera.setTop(tab == Tab.TEXT);
        rebuildWidgets();
    }

    private void openSaveDialog() {
        previewFocused = false;
        // The world's answer arrives later. The dialog closes itself on success; if it was
        // cancelled meanwhile, the answer only sets the status line
        dialog = new SaveDialog(this, bench, this::closeSaveDialog,
                blueprint -> status = t("saved", blueprint.name()),
                reason -> {
                    status = reason;
                    if (Minecraft.getInstance().screen == this && dialog == null)
                        rebuildWidgets();
                });
        rebuildWidgets();
    }

    private void closeSaveDialog() {
        if (dialog != null)
            dialog.closed();
        dialog = null;
        rebuildWidgets();
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (layoutEditor != null)
            return layoutEditor.mouseScrolled(mouseX, mouseY, delta);
        if (dialog != null)
            return super.mouseScrolled(mouseX, mouseY, delta);
        if (overPanel(mouseX)) {
            // The layout's element list scrolls itself before the panel does
            if (tab == Tab.SCREEN && super.mouseScrolled(mouseX, mouseY, delta))
                return true;
            if (maxScroll > 0) {
                int next = Mth.clamp(scroll - (int) Math.signum(delta) * Panel.ROW, 0, maxScroll);
                if (next != scroll) {
                    scroll = next;
                    rebuildWidgets();
                }
                return true;
            }
        } else if (materialEditor != null && materialEditor.contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        } else if (tab == Tab.TEXT && textEditor.mouseScrolled(mouseX, mouseY, delta, width, height)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (layoutEditor != null)
            return layoutEditor.mouseClicked(mouseX, mouseY, button);
        // Any click but one on the preview hands Delete back to the text fields
        previewFocused = false;
        // The window is on top, so a click on it is its own, whatever is under it
        if (dialog == null && materialEditor != null && materialEditor.contains(mouseX, mouseY)) {
            for (AbstractWidget widget : materialEditor.widgets())
                if (widget.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(widget);
                    if (button == 0)
                        setDragging(true);
                    return true;
                }
            setFocused(null);
            return materialEditor.mouseClicked(mouseX, mouseY, button);
        }
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;
        // The editor's picker is drawn rather than a widget, and a click anywhere on its
        // window stops there rather than reaching the preview behind it
        if (dialog == null && materialEditor != null && materialEditor.mouseClicked(mouseX, mouseY, button))
            return true;
        if (dialog == null && tab == Tab.TEXT && !overPanel(mouseX)) {
            // A click on the preview takes focus from any text field, so Delete
            // afterwards deletes the box rather than a character
            setFocused(null);
            previewFocused = true;
            return textEditor.mouseClicked(mouseX, mouseY, button, width, height);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (layoutEditor != null)
            return layoutEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (materialEditor != null && materialEditor.dragging())
            return materialEditor.mouseDragged(mouseX, mouseY);
        if (textEditor.dragging())
            return textEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY, width, height);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (layoutEditor != null)
            return layoutEditor.mouseReleased(mouseX, mouseY, button);
        if (materialEditor != null && materialEditor.mouseReleased())
            return true;
        if (textEditor.dragging())
            return textEditor.mouseReleased(mouseX, mouseY, button, width, height);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        // Every key, Esc included, which the editor takes as Done
        if (layoutEditor != null)
            return layoutEditor.keyPressed(key, scanCode, modifiers);
        if (dialog != null && key == GLFW.GLFW_KEY_ESCAPE) {
            closeSaveDialog();
            return true;
        }
        // Escape takes the editor's window down first, even from its hex field
        if (dialog == null && materialEditor != null && key == GLFW.GLFW_KEY_ESCAPE) {
            closeMaterialEditor();
            return true;
        }
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (dialog == null && tab == Tab.TEXT && previewFocused && !typing
                && (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && state.selectedText() != null) {
            state.texts.remove(state.selected);
            state.selected = -1;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ import

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (layoutEditor != null)
            return layoutEditor.charTyped(character, modifiers);
        return super.charTyped(character, modifiers);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        // A model dropped on the editor would swap the bench's model out from under it
        if (dialog == null && layoutEditor == null)
            importFiles(paths);
    }

    private void importFiles(List<Path> paths) {
        if (mode == Mode.RACK) {
            importCsv(paths);
            return;
        }
        try {
            DesignImporter.Imported imported = DesignImporter.load(paths);
            byte[] encoded = imported.bundle().encode();
            if (encoded.length > DesignBundle.MAX_BYTES)
                throw new IllegalStateException("model too large");
            String newHash = DesignBundle.hash(encoded);
            ResourceLocation newMesh = ClientDesigns.install(newHash, imported.bundle());
            MeshInfo newInfo = HypergraphicalAPI.inspectMesh(newMesh);
            if (newInfo == null || newInfo.triangleCount() == 0) {
                status = t("import.empty");
                return;
            }
            downloading = null;
            state.pendingScale = Float.NaN;
            state.setModel(newHash, encoded, imported.name(), newMesh, newInfo);
            state.unit = null;
            state.multiplier = 1;
            state.centre();
            status = imported.warnings().isEmpty() ? t("import.ok", state.parts.size())
                    : t("import.warnings", imported.warnings().size(), imported.warnings().get(0));
            imported.warnings().forEach(warning -> CreateFlippies.LOGGER.warn("Model import: {}", warning));
        } catch (java.nio.file.FileSystemException e) {
            // macOS answers a read in a protected folder this way when the process has
            // not been granted it, which the player can fix and should be told how
            CreateFlippies.LOGGER.warn("Model import failed", e);
            status = "Operation not permitted".equals(e.getReason()) ? t("import.denied", e.getFile())
                    : t("import.failed", e.getMessage());
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Model import failed", e);
            status = t("import.failed", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        rebuildWidgets();
    }

    /** Rack mode: the dropped or picked files are the CSVs the rack is described by. */
    private void importCsv(List<Path> paths) {
        try {
            WindowsTab.importTexts(this, DesignImporter.readTexts(paths));
        } catch (java.io.IOException e) {
            CreateFlippies.LOGGER.warn("Rack CSV import failed", e);
            status = t("csv.failed", e.getMessage() == null ? e.toString() : e.getMessage());
            rebuildWidgets();
        }
    }

    /**
     * Rack mode: builds the model the rack describes and installs it, if it changed
     * in a way the model shows. A legend is not model, so typing one costs nothing
     * here; the same rack always makes the same files and hash, so an unchanged one
     * is not installed again.
     *
     * @return whether there is a model for the rack as it stands
     */
    private boolean generateModel() {
        AnnunciatorRack rack = state.rack.build();
        if (rack.error() != null)
            return false;
        DesignBundle bundle = AnnunciatorMesh.build(rack);
        try {
            byte[] encoded = bundle.encode();
            String hash = DesignBundle.hash(encoded);
            if (hash.equals(state.hash) && state.mesh != null && state.info != null)
                return true;
            ResourceLocation mesh = ClientDesigns.install(hash, bundle);
            MeshInfo info = HypergraphicalAPI.inspectMesh(mesh);
            if (info == null || info.triangleCount() == 0)
                return false;
            state.setModel(hash, encoded, state.editing != null ? state.editing.name() : t("rack.name").getString(),
                    mesh, info);
            return true;
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Could not build the rack model", e);
            status = t("import.failed", e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        }
    }

    /**
     * Installs a loaded blueprint's model: from its bundle when this client has the
     * bytes (needed to upload it again on save), else from the model already
     * installed here, else fetched from the server, which calls back here.
     */
    private void loadBlueprintModel() {
        String hash = state.hash;
        if (!ControlDesign.isHash(hash))
            return;
        byte[] blob = ClientBlueprints.blob(hash);
        if (blob != null) {
            installBlueprintModel(hash, blob);
            return;
        }
        ResourceLocation installed = ClientDesigns.mesh(hash);
        MeshInfo info = installed == null ? null : HypergraphicalAPI.inspectMesh(installed);
        if (info != null) {
            state.setModel(hash, null, state.modelName, installed, info);
            status = t("loaded", state.modelName);
            rebuildWidgets();
            return;
        }
        downloading = hash;
        status = t("downloading");
        ClientBlueprints.fetch(hash, bytes -> {
            if (Minecraft.getInstance().screen == this && hash.equals(downloading))
                installBlueprintModel(hash, bytes);
        });
    }

    private void installBlueprintModel(String hash, byte[] blob) {
        downloading = null;
        try {
            if (!DesignBundle.hash(blob).equals(hash))
                throw new IllegalStateException("hash mismatch");
            ResourceLocation mesh = ClientDesigns.install(hash, DesignBundle.decode(blob));
            MeshInfo info = HypergraphicalAPI.inspectMesh(mesh);
            if (info == null)
                throw new IllegalStateException("unreadable model");
            state.setModel(hash, blob, state.modelName, mesh, info);
            status = t("loaded", state.modelName);
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Could not load blueprint model {}", hash, e);
            status = t("import.failed", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (Minecraft.getInstance().screen == this)
            rebuildWidgets();
    }

    /**
     * The Screen tab's Edit layout: the whole workbench becomes the display editor over
     * the screen's layout, with the preview showing the layout (never a test pattern)
     * and the camera taking the editor's top view.
     */
    private void openLayoutEditor() {
        if (layoutEditor != null || mode != Mode.SCREEN)
            return;
        previewFocused = false;
        materialEditor = null;
        layoutEditor = new DisplayEditor(new LayoutHost(), font);
        refresh();
        rebuildWidgets();
    }

    /** Done or Esc in the editor: back to the Screen tab, as it was. */
    private void closeLayoutEditor() {
        if (layoutEditor == null)
            return;
        DisplayEditor editor = layoutEditor;
        layoutEditor = null;
        editor.close();
        refresh();
        rebuildWidgets();
    }

    /**
     * The workbench as the display editor's host: the screen state's layout, which this
     * client owns outright (every edit re-poses the preview), seen from above on the
     * bench. No power to lose, and no Rotate, since the layout turns with the design.
     */
    private final class LayoutHost implements EditorHost {

        private final ElementEditTarget target = new LocalElementTarget(screenState.screen.layout,
                screenState::screenViewport, DesignWorkbenchScreen.this::refresh);
        private final GlassView view = new BenchGlassView();

        @Override
        public ElementEditTarget target() {
            return target;
        }

        @Override
        public GlassView view() {
            return view;
        }

        @Override
        public Object owner() {
            return DesignPreview.SCREEN_OWNER;
        }

        @Override
        public String sourceKey() {
            return "workbench@" + bench.asLong();
        }

        @Override
        public boolean canRotate() {
            return false;
        }

        @Override
        public void rotate() {}

        @Override
        public void close() {
            closeLayoutEditor();
        }

        @Override
        public Component title() {
            return Component.translatable("gui.create_flippies.display_editor.title");
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (layoutEditor != null)
            layoutEditor.tick();
        // A model the server already had in its design store may arrive through the
        // ordinary model download rather than the blueprint fetch
        if (downloading != null) {
            ResourceLocation installed = ClientDesigns.mesh(downloading);
            MeshInfo info = installed == null ? null : HypergraphicalAPI.inspectMesh(installed);
            if (info != null) {
                String hash = downloading;
                downloading = null;
                state.setModel(hash, null, state.modelName, installed, info);
                status = t("loaded", state.modelName);
                rebuildWidgets();
            }
        }
    }

    // ---------------------------------------------------------------- preview

    /** Re-poses the preview after anything changed. */
    private void refresh() {
        if (mode == Mode.RACK && !generateModel()) {
            DesignPreview.hide();
            return;
        }
        if (state.info == null || state.mesh == null) {
            DesignPreview.hide();
            return;
        }
        ControlDesign design = state.design(state.modelName);
        if (design == null) {
            DesignPreview.hide();
            return;
        }
        // Text is placed on a still model, and with the moving parts unposed: that is the
        // frame text boxes are stored in (one on the moving parts is carried by the motion
        // afterwards), and the frame the box editor's mouse mapping, outline and handles
        // work in. Any posed position would draw a moving part's text turned or shifted
        // away from the rectangle being clicked and dragged.
        int position = tab == Tab.TEXT ? DesignPreview.REST : state.previewPosition;
        DesignPreview.show(bench, design, state.mesh, state.hidden, position, tab == Tab.TEXT ? state.selected : -1,
                tab != Tab.TEXT);
        DesignPreview.highlight(tab == Tab.WINDOWS ? windowBox(design.rack(), state.selectedWindow) : null);
        // The editor works on the layout, so that is what the glass shows while it is open
        DesignPreview.pattern(mode == Mode.SCREEN && layoutEditor == null ? state.pattern : ScreenPattern.LAYOUT);
    }

    /** The box of one rack window, from the panel up to the face, for the preview to pick out. */
    private static double[] windowBox(AnnunciatorRack rack, int index) {
        if (rack == null || index < 0 || index >= rack.tiles.size())
            return null;
        int row = index / rack.cellsX, column = index % rack.cellsX;
        double left = rack.cellLeft(column), top = rack.cellTop(row);
        return new double[] { left, rack.lensY(), top, left + rack.cellWidth(), rack.depth,
                top + rack.cellHeight() };
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (layoutEditor != null) {
            layoutEditor.render(graphics, mouseX, mouseY, partialTicks);
            return;
        }
        if (dialog != null) {
            renderPanelBackground(graphics);
            dialog.renderBackground(graphics, font, width, height);
            super.render(graphics, mouseX, mouseY, partialTicks);
            return;
        }
        if (tab == Tab.TEXT)
            textEditor.render(graphics, font, width, height);

        // No darkened background: the preview on the bench is half of this screen
        renderPanelBackground(graphics);
        if (panel != null)
            panel.render(graphics, this::inView, mouseX, mouseY);
        // Nothing under the material editor's window shows as hovered
        boolean overEditor = materialEditor != null && materialEditor.contains(mouseX, mouseY);
        super.render(graphics, overEditor ? -1 : mouseX, overEditor ? -1 : mouseY, partialTicks);

        // A thin bar at the panel's right edge when there is more than fits
        if (maxScroll > 0) {
            int track = bottom() - TOP;
            int bar = Math.max(12, track * track / (track + maxScroll));
            int top = TOP + (track - bar) * scroll / maxScroll;
            graphics.fill(Panel.X + Panel.WIDTH + 3, top, Panel.X + Panel.WIDTH + 5, top + bar, 0x80FFFFFF);
        }

        // Last and raised, so it covers the panel's buttons and their text however
        // the GUI batches its drawing
        if (materialEditor != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            materialEditor.render(graphics, font);
            for (AbstractWidget widget : materialEditor.widgets())
                widget.render(graphics, mouseX, mouseY, partialTicks);
            graphics.pose().popPose();
        }
    }

    private void renderPanelBackground(GuiGraphics graphics) {
        graphics.fill(Panel.X - 6, 4, Panel.X + Panel.WIDTH + 6, height - 4, 0xC0101418);
        graphics.drawString(font, title, Panel.X, 7, 0xFFFFFF);
        // A rule between the scrolling column and the footer
        graphics.fill(Panel.X - 2, bottom() + 1, Panel.X + Panel.WIDTH + 2, bottom() + 2, 0x40FFFFFF);
    }

    @Override
    public void removed() {
        // A save still waiting for the world's answer must not rebuild a screen that is gone
        if (dialog != null)
            dialog.closed();
        if (layoutEditor != null) {
            layoutEditor.close();
            layoutEditor = null;
            EditorScale.restore(null);
        }
        DesignPreview.hide();
        DesignCamera.exit();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
