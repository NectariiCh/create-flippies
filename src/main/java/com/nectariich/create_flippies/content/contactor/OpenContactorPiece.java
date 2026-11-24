package com.nectariich.create_flippies.content.contactor;

/**
 * Which model an open contactor module is drawn with, from whether there is
 * another module beside it: alone, the left or right end of an assembly, or one
 * between them. Left is authored −x.
 */
public enum OpenContactorPiece {
    SINGLE("single"),
    LEFT("left"),
    RIGHT("right"),
    CENTER("center");

    private final String name;

    OpenContactorPiece(String name) {
        this.name = name;
    }

    /** The left end has a module on its right, and so on. */
    public static OpenContactorPiece of(boolean moduleOnLeft, boolean moduleOnRight) {
        if (moduleOnLeft && moduleOnRight)
            return CENTER;
        if (moduleOnRight)
            return LEFT;
        if (moduleOnLeft)
            return RIGHT;
        return SINGLE;
    }

    /** The plate, drawn still; gen_contactor_open.py writes it. */
    public String bodyModel() {
        return "block/contactor_open_" + name + "_body";
    }

    /** The plunger, drawn moved towards the plate as it pulls in. */
    public String plungerModel() {
        return "block/contactor_open_" + name + "_plunger";
    }
}
