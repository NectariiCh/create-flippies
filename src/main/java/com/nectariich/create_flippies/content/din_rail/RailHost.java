package com.nectariich.create_flippies.content.din_rail;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;

/**
 * Marks a {@link ControlRackHolder} whose controls are DIN rail gear seated on a
 * rail: a rail block, or a cell a cabinet's rail runs through.
 *
 * Such a host seats gear only by hand, through its block's own {@code use}, which
 * keeps out kinds that are not {@link DinRailMountable} and snaps the rest to
 * module positions along the rail. Anything that would install into a host some
 * other way — the screwdriver, which rules grids and mounts anything anywhere —
 * asks this first and keeps out, because installing there would skip both.
 */
public interface RailHost {
}
