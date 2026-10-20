package com.nectariich.create_flippies.foundation;

/**
 * Marks a block whose interaction shape reaches outside its own block space —
 * the Square gauges, whose barrel and contact studs stand in the block behind the
 * panel they are mounted on.
 *
 * Vanilla ray tracing only ever tests a block's shape while the ray is inside
 * that block's own cell, so such geometry is normally unpickable. Blocks
 * carrying this marker are additionally tested by
 * {@link ProtrudingHitboxPicker} while the ray passes through any of the six
 * neighbouring cells.
 *
 * The protruding geometry must stay within
 * {@link com.nectariich.create_flippies.foundation.control.ControlFootprint#REACH_BUDGET}
 * of its own cell, which is as far as the picker looks.
 */
public interface IProtrudingHitbox {
}
