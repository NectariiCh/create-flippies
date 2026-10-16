package com.nectariich.create_flippies.foundation.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * Which of a host block's Power Grid terminal indices belong to which installed
 * control, and which of them are dead.
 *
 * The table only ever grows. That is not tidiness, it is forced three times over
 * by Power Grid, and every way of getting it wrong is silent:
 *
 * <ul>
 * <li>A wire remembers the terminal it is screwed to as an integer —
 * {@code BlockWireEndpoint} persists {@code Pos} and {@code Terminal} — so
 * renumbering does not move a wire, it re-targets it onto whatever now sits at
 * that index.</li>
 * <li>{@code ElectricBehaviour.rebuildCircuit} snapshots each external node's
 * electrical network before the rebuild and afterwards re-attaches them
 * positionally, {@code joinNetwork(snapshot.get(i), i)}. An insertion therefore
 * hands the new node the network of the node that used to be at that index.</li>
 * <li>{@code CircuitBuilder.setTerminalCount}'s shrink path removes trailing
 * nodes unconditionally, while its {@code addExternalNode} early-returns when
 * {@code rebuildExternal} is false — and Power Grid calls
 * {@code rebuildCircuit(false)} routinely for ordinary state changes
 * (HvSwitchBlockEntity in six places, ContactorBlockEntity,
 * TransformerBlockEntity). A host that recomputed a smaller count during one of
 * those would drop nodes that live wires still point at, and could never get them
 * back.</li>
 * </ul>
 *
 * So removing a control leaves a tombstone: the indices stay, and their nodes
 * stay in the solver as isolated floating nodes. To stop a panel that has been
 * rearranged a few times from silting up, holes are recycled — but only by a
 * control of exactly the same terminal count, which is why the free list is
 * bucketed by width rather than being one list of offsets. Swapping a control for
 * another of the same shape then costs nothing at all.
 *
 * Uniform fixed-size slots were the obvious alternative and were rejected on
 * arithmetic: sized for the nine-terminal rotary switch they waste seven slots in
 * nine on a two-terminal control, and sixty-four eight-terminal indicators on an
 * 8x8 grid need 512 indices with slots cut to their own width but 576 with
 * nine-wide ones. Splitting an oversized hole was rejected for the opposite
 * reason: the remainder is a width nothing in the catalogue asks for, so a split
 * turns one reusable hole into permanent dust.
 */
public final class TerminalSlots {

    /**
     * Terminals per host block. The cap belongs to the block, not to the face, so
     * what it really bounds is a block wearing controls on all six of them: an 8x8
     * grid of eight-terminal indicators is 512 and fits eight times over, and nine
     * rotary switches on each of six faces is 486 and fits; 8x8 indicators on every
     * face is 3072 and does not, which is the case the refusal message exists for.
     *
     * Nothing downstream needs it to be this or any other number. Power Grid keeps a
     * block's external nodes in a plain list and persists a wire's terminal as a full
     * {@code putInt}, so the only thing that grows with the cap is cost, and it grows
     * with the block's {@link #capacity} rather than with how many controls are
     * really on it — the table never shrinks, so tombstones are paid for too.
     *
     * Two costs, both linear. {@code ElectricBehaviour.writeToSync} streams two
     * values per external node with no count at all, so a block at the cap puts
     * 2048 pairs into every state packet it sends — around 16 KB reduced, 32 KB at
     * full precision — whether one control changed or all of them. And every index,
     * live or tombstoned, is a floating node the solver carries. A panel that
     * actually reaches four figures of terminals is therefore a real load on the
     * server and on everyone looking at it; the cap is deliberately far above what a
     * panel needs so that it stops the absurd cases rather than the ambitious ones.
     */
    public static final int MAX_TERMINALS = 2048;

    /** Returned by {@link #allocate} when the block has no room left. */
    public static final int NO_SLOT = -1;

    private static final String CAPACITY = "Capacity";
    private static final String HOLES = "Holes";

    /**
     * How many indices this block has ever handed out, and the number the host
     * passes to {@code setTerminalCount}. Monotone for the life of the block.
     */
    private int capacity;

    /** Which indices belong to a control that is still installed. */
    private final BitSet occupied = new BitSet(MAX_TERMINALS);

    /** Free ranges by width, newest first, so a swap reuses the hole it just made. */
    private final Map<Integer, Deque<Integer>> holes = new HashMap<>();

    public int capacity() {
        return capacity;
    }

    /** Whether {@link #allocate} would succeed, asked without consuming anything. */
    public boolean hasRoom(int width) {
        if (width <= 0)
            return true;
        Deque<Integer> bucket = holes.get(width);
        if (bucket != null)
            for (int base : bucket)
                if (isFree(base, width))
                    return true;
        return capacity + width <= MAX_TERMINALS;
    }

    /**
     * Reserves {@code width} consecutive indices, preferring a hole of exactly
     * that width over the end of the table.
     *
     * @return the first index, or {@link #NO_SLOT} if the block is full
     */
    public int allocate(int width) {
        if (width <= 0)
            return capacity;

        Deque<Integer> bucket = holes.get(width);
        while (bucket != null && !bucket.isEmpty()) {
            // A hole recorded in a save can have been overrun by a control from
            // that same save, so a popped hole is only a candidate
            int base = bucket.pop();
            if (isFree(base, width))
                return take(base, width);
        }

        if (capacity + width > MAX_TERMINALS)
            return NO_SLOT;
        return take(capacity, width);
    }

    /**
     * Tombstones a range. The capacity deliberately does not fall even when the
     * range is the last one in the table: {@code setTerminalCount} would then
     * delete those nodes on the next rebuild, wires and all.
     */
    public void release(int base, int width) {
        if (width <= 0)
            return;
        occupied.clear(base, base + width);
        holes.computeIfAbsent(width, key -> new ArrayDeque<>()).push(base);
    }

    /**
     * Re-reserves a range read back from a save, growing the capacity to cover it
     * if the saved capacity was short.
     *
     * @return false if the range is out of bounds or already taken, in which case
     *         the caller must drop the control rather than let two of them answer
     *         for one terminal
     */
    public boolean claim(int base, int width) {
        if (base < 0 || width < 0 || base + width > MAX_TERMINALS)
            return false;
        if (!isFree(base, width))
            return false;
        take(base, width);
        forgetHolesOverlapping(base, width);
        return true;
    }

    public void save(CompoundTag tag) {
        tag.putInt(CAPACITY, capacity);
        List<Integer> flat = new ArrayList<>();
        holes.forEach((width, bases) -> bases.forEach(base -> {
            flat.add(base);
            flat.add(width);
        }));
        int[] pairs = new int[flat.size()];
        for (int i = 0; i < pairs.length; i++)
            pairs[i] = flat.get(i);
        tag.putIntArray(HOLES, pairs);
    }

    /**
     * Restores the table. The widths a hole may be reused at are part of the save
     * because they cannot be recovered from the occupancy alone — two adjacent
     * holes of two and three read back as one unusable five.
     *
     * Holes are taken on trust only so far: one that runs past the capacity, or
     * overlaps a hole already accepted, is dropped. That leaks the indices but can
     * never hand the same index to two controls. The caller then {@link #claim}s
     * the range of every control it goes on to load.
     */
    public void load(CompoundTag tag) {
        capacity = Mth.clamp(tag.getInt(CAPACITY), 0, MAX_TERMINALS);
        occupied.clear();
        holes.clear();

        BitSet accepted = new BitSet(MAX_TERMINALS);
        int[] pairs = tag.getIntArray(HOLES);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            int base = pairs[i];
            int width = pairs[i + 1];
            if (base < 0 || width <= 0 || base + width > capacity)
                continue;
            int clash = accepted.nextSetBit(base);
            if (clash >= 0 && clash < base + width)
                continue;
            accepted.set(base, base + width);
            holes.computeIfAbsent(width, key -> new ArrayDeque<>()).push(base);
        }
    }

    private int take(int base, int width) {
        occupied.set(base, base + width);
        capacity = Math.max(capacity, base + width);
        return base;
    }

    private boolean isFree(int base, int width) {
        int next = occupied.nextSetBit(base);
        return next < 0 || next >= base + width;
    }

    private void forgetHolesOverlapping(int base, int width) {
        holes.forEach((holeWidth, bases) -> bases
                .removeIf(holeBase -> holeBase < base + width && base < holeBase + holeWidth));
        holes.values().removeIf(Deque::isEmpty);
    }
}
