package com.nectariich.create_flippies.client.display.editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * What the parts of one open display editor share: the session and its history, the
 * host, the camera, the selection, which elements are hidden in the editor, the tool,
 * the bars' preview values, and the few services the {@link DisplayEditor} gives them
 * (a context menu, a status message, the SVG picker). Made by the editor; the menus,
 * the glass tools and the commands each hold the one context.
 */
public final class EditorContext {

    public final EditorHost host;
    public final EditSession session;
    public final GlassView view;
    public final Font font;
    public final Selection selection = new Selection();
    /** Elements not drawn in the editor's view; never saved, forgotten on close. */
    public final Set<Integer> hidden = new HashSet<>();
    /** Each bar's editor-only preview value, by slot; never saved. */
    public final Map<Integer, Float> barPreview = new HashMap<>();

    public Tool tool = Tool.SELECT;
    /** Whether the tool stays picked after a shape (a double-click on its button). */
    public boolean toolLocked;
    /** The picture an SVG drag will place, once picked; null until then. */
    public String pendingArt;
    /** Whether the inspector's fill section is open, which is when the glass shows gradient handles. */
    public boolean fillOpen;

    private final Services services;
    private EditorCommands commands;

    /** What the editor itself provides to its parts. */
    public interface Services {
        void openContextMenu(double x, double y, List<ContextMenu.Entry> entries);

        void status(Component message);

        /** Opens the system file picker for an SVG; {@code done} gets the stored picture's hash once imported. */
        void importSvg(Consumer<String> done);
    }

    public EditorContext(EditorHost host, Font font, Services services) {
        this.host = host;
        this.session = new EditSession(host.target());
        this.view = host.view();
        this.font = font;
        this.services = services;
    }

    public EditorCommands commands() {
        if (commands == null)
            commands = new EditorCommands(this);
        return commands;
    }

    public void openContextMenu(double x, double y, List<ContextMenu.Entry> entries) {
        services.openContextMenu(x, y, entries);
    }

    public void status(Component message) {
        services.status(message);
    }

    public void importSvg(Consumer<String> done) {
        services.importSvg(done);
    }

    /** Picks a tool for one shape, or locked until Select is picked again. */
    public void pickTool(Tool tool, boolean locked) {
        this.tool = tool;
        this.toolLocked = locked && tool != Tool.SELECT;
    }

    /** After a shape: back to Select unless the tool is locked. */
    public void shapeDone() {
        if (!toolLocked)
            tool = Tool.SELECT;
        pendingArt = toolLocked ? pendingArt : null;
    }
}
