package com.nectariich.create_flippies.content.design_workbench.blueprint;

/**
 * What a client can ask of the world's blueprints, each carried by one
 * {@link com.nectariich.create_flippies.network.BlueprintOpPacket} with its
 * arguments in a compound.
 *
 * The argument keys are listed per op. Every op is answered with the whole world
 * section, so the asker's browser never has to work out what changed.
 *
 * Any op may also carry {@link #REQUEST}, a number the client picked; the answer
 * repeats it, so a screen that waits on one particular op (the workbench's save
 * dialog) can tell its own answer from the others.
 */
public enum BlueprintOp {

    /** {@code Blueprint}: insert, or overwrite the one with the same id. Its files must be uploaded first. */
    SAVE,
    /** {@code Id}, {@code Name}. */
    RENAME,
    /** {@code Id}: a copy called "<name> copy" beside it, owned by whoever asked. */
    COPY,
    /** {@code Id}. */
    DELETE,
    /** {@code Id}, {@code Folder}. */
    MOVE,
    /** {@code Folder}. */
    MKDIR,
    /** {@code From}, {@code To}: full paths; the folder's contents go with it. */
    RENAME_FOLDER,
    /** {@code Folder}: the folder, its subfolders and every blueprint in them. */
    DELETE_FOLDER,
    /** {@code Bench} (a packed block position), {@code Design}: an item made from a design, in creative. */
    MAKE,
    /** No arguments: start sending this player every change while the browser is open. */
    OPEN,
    /** No arguments: stop sending changes. */
    CLOSE,
    /** No arguments: send the world section once, for a dialog that needs its folders. */
    LIST,
    /** {@code Hash}: send one store file back, for a blueprint about to be kept locally. */
    FETCH;

    /** The optional argument, and the key of the answer, that pairs an op with its answer. */
    public static final String REQUEST = "Request";

    private static final BlueprintOp[] VALUES = values();

    /** The op with this ordinal, or null for one this version does not know. */
    public static BlueprintOp byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
