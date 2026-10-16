package com.nectariich.create_flippies.content.procedural_gauge;

/**
 * What a renderer needs from a procedural gauge mounted in a host block, whichever
 * kind of gauge it is.
 *
 * The two mountable kinds differ only in where the needle's position comes from — a
 * voltmeter reads what is across its own terminals, a Square Procedural Gauge is
 * told by a display link — and not at all in how they are drawn. So the drawing is
 * written against this rather than against either of them, and one renderer serves
 * both.
 *
 * The needle is eased on both sides of the connection, which is why the previous
 * value is exposed as well as the current one: the renderer interpolates between
 * them, and that transient state is part of the reason a host must update its
 * controls in place on a sync rather than rebuild them.
 */
public interface MountedProceduralGauge {

    /** The dial face to print: whatever this gauge was customised to, else its default. */
    GaugeFaceDefinition faceDefinition();

    /** Where the needle is, from 0 at the low end of the scale to 1 at the high end. */
    float dialState();

    /** Where the needle was a tick ago, for interpolation. */
    float prevDialState();
}
