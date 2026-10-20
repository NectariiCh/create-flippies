package com.nectariich.create_flippies.content.procedural_gauge;

/**
 * What a renderer and the face menu need from an edgewise gauge mounted in a host
 * block, whichever kind it is: the edgewise counterpart of
 * {@link MountedProceduralGauge}. A voltmeter and the link-driven gauge differ only
 * in where the pointer's position comes from.
 */
public interface MountedEdgewiseGauge {

    /** The face to print: whatever this gauge was customised to, else its default. */
    EdgewiseFaceDefinition faceDefinition();

    /** Where the pointer is, from 0 at the low end of the scale to 1 at the high end. */
    float dialState();

    /** Where the pointer was a tick ago, for interpolation. */
    float prevDialState();
}
