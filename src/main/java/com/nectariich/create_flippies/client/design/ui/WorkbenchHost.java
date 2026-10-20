package com.nectariich.create_flippies.client.design.ui;

import net.minecraft.network.chat.Component;

/**
 * What the workbench's tabs and widgets need from the screen they are on: the state
 * they edit, and the two ways of showing a change, re-posing the preview (cheap, on
 * every keystroke) or rebuilding the widgets (when rows appear or go).
 */
public interface WorkbenchHost {

    WorkbenchState state();

    /** Something the preview shows changed: re-pose it. */
    void changed();

    /** Rows appear or go: rebuild the widgets (which also re-poses the preview). */
    void rebuild();

    /** Opens the file picker: a model, or in rack mode the CSVs a rack is described by. */
    void browse();

    /** The one-line status under the model name: what the last import, load or save did. */
    Component status();

    void setStatus(Component status);

    /** Opens the material editor on one material element of the model, in place of any already open. */
    void editMaterial(String part, String material);

    void closeMaterialEditor();
}
