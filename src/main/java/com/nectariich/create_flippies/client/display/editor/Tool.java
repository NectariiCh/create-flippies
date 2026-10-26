package com.nectariich.create_flippies.client.display.editor;

import com.nectariich.create_flippies.content.display.element.ElementKind;

import org.lwjgl.glfw.GLFW;

/** The tool strip's tools: what a drag on the glass does. */
public enum Tool {
    SELECT(null, GLFW.GLFW_KEY_V, "V"),
    TEXT(ElementKind.TEXT, GLFW.GLFW_KEY_T, "T"),
    RECTANGLE(ElementKind.FILL, GLFW.GLFW_KEY_R, "R"),
    ELLIPSE(ElementKind.ELLIPSE, GLFW.GLFW_KEY_E, "E"),
    LINE(ElementKind.LINE, GLFW.GLFW_KEY_L, "L"),
    BAR(ElementKind.BAR, GLFW.GLFW_KEY_B, "B"),
    /** Picks a file first; then a drag places it. */
    SVG(ElementKind.SVG, -1, "SVG");

    /** The kind a drag with this tool draws; null for Select. */
    public final ElementKind kind;
    /** The key that picks it, or -1. */
    public final int key;
    /** What its button says. */
    public final String label;

    Tool(ElementKind kind, int key, String label) {
        this.kind = kind;
        this.key = key;
        this.label = label;
    }
}
