package com.nectariich.create_flippies.content.design_workbench;

/**
 * A mounted control whose front is an imported model. Implemented by the designed
 * switch and the designed button, which otherwise share nothing but the control
 * each is built on.
 */
public interface DesignedControl {

    /** Null only for a control loaded from a save that lost its design. */
    ControlDesign design();
}
