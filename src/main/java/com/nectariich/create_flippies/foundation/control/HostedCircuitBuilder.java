package com.nectariich.create_flippies.foundation.control;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import net.minecraft.core.BlockPos;

import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;

/**
 * The view of a host block's circuit that one installed control is allowed to
 * see: its own terminals numbered from zero, and nothing of anyone else's.
 *
 * A host owns one flat run of terminal indices for the whole block, so a control
 * that reached for {@code terminalNode(0)} would get the first terminal of the
 * panel rather than its own. Handing each control a builder that adds its slot
 * base to every index means an instrument's circuit code is the same whether it
 * runs in a block of its own or as one control among many — the rotary switch's
 * three sections already index this way by hand, so the arithmetic is only being
 * moved, not invented.
 *
 * This is modelled on Power Grid's own {@code ComponentCircuitBuilder}, which
 * does the same thing for a circuit board component, with one deliberate
 * difference: {@code setTerminalCount} is accepted rather than thrown as long as
 * the number matches what the control's kind declares. Power Grid forbids it
 * outright, but every instrument in this mod opens {@code buildCircuit} with that
 * call, and turning it into a check that the declaration and the circuit agree is
 * worth more than turning it into a crash — a control that quietly builds more
 * terminals than its kind admits to would read and write its neighbour's.
 *
 * Nodes and wires are staged in this builder's own lists and handed to the host
 * afterwards rather than being added to the host's collections as they are made.
 * That is what keeps them in a fixed order: Power Grid streams internal node
 * values and switched-wire states to the client positionally with no count and no
 * identity, so client and server must build them in exactly the same sequence or
 * every reading on the panel is not merely stale but wrong.
 */
public class HostedCircuitBuilder extends IElectricEntity.CircuitBuilder {

    private final IntFunction<FloatingNode> terminals;
    private final int terminalCount;
    private final List<INode> stagedNodes;
    private final List<AbstractElectricWire> stagedWires;

    /**
     * The superclass is given a null external-node list, exactly as
     * {@code ComponentCircuitBuilder} is: every route to it is overridden below, so
     * the field is never read. Do not call {@code clear()} or
     * {@code rebuildExternal(boolean)} on one of these; both touch that list.
     */
    public HostedCircuitBuilder(BlockPos pos, int terminalCount, IntFunction<FloatingNode> terminals) {
        this(pos, terminalCount, terminals, new ArrayList<>(), new ArrayList<>());
    }

    private HostedCircuitBuilder(BlockPos pos, int terminalCount, IntFunction<FloatingNode> terminals,
            List<INode> stagedNodes, List<AbstractElectricWire> stagedWires) {
        super(pos, null, stagedNodes, stagedWires);
        this.terminals = terminals;
        this.terminalCount = terminalCount;
        this.stagedNodes = stagedNodes;
        this.stagedWires = stagedWires;
    }

    @Override
    public FloatingNode terminalNode(int index) {
        if (index < 0 || index >= terminalCount)
            throw new IndexOutOfBoundsException(
                    "Control asked for terminal " + index + " of " + terminalCount);
        return terminals.apply(index);
    }

    /** Accepted as a declaration to check against, never as a request to resize. */
    @Override
    public void setTerminalCount(int count) {
        if (count != terminalCount)
            throw new IllegalArgumentException(
                    "Control built " + count + " terminals but its kind declares " + terminalCount);
    }

    @Override
    protected void addExternalNode() {
        throw new IllegalCallerException("A hosted control cannot add terminals to its host");
    }

    /** Moves what this control built into the host's circuit, in the order it was built. */
    public void handOver(IElectricEntity.CircuitBuilder host) {
        for (INode node : stagedNodes)
            host.add(node);
        for (AbstractElectricWire wire : stagedWires)
            host.add(wire);
    }
}
