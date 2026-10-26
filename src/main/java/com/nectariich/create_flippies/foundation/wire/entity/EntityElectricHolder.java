package com.nectariich.create_flippies.foundation.wire.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.world.entity.Entity;

import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.WorldNetworks;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.INetworkElement;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;

/**
 * The Power Grid circuit of an {@link EntityElectricHost}: what Power Grid's
 * {@code ElectricBehaviour} is to a block entity, cut down to what an entity with a
 * fixed circuit of terminals and wires between them needs.
 *
 * It talks to the world's networks the way the behaviour does, node by node: added
 * when the entity is ({@link #added}), its wires lifted out while unloaded
 * ({@link #unload}) and everything let go when the entity is destroyed
 * ({@link #remove}). The terminals are keyed by {@link EntityWireEndpoint}s, so the
 * network's saved cords find them again by the entity's UUID.
 *
 * The circuit is built once the entity's UUID and position are final, when it is
 * first added, through Power Grid's own {@link IElectricEntity.CircuitBuilder}
 * handed the terminal nodes ready made.
 */
public final class EntityElectricHolder {

    private final Entity owner;
    private final int terminals;
    private final Consumer<IElectricEntity.CircuitBuilder> circuit;
    private final List<OwnedFloatingNode> external = new ArrayList<>();
    private final List<INode> internalNodes = new ArrayList<>();
    private final List<AbstractElectricWire> internalWires = new ArrayList<>();
    private final Map<Integer, Set<BaseWireEntity>> connections = new HashMap<>();
    private boolean built;
    private boolean paused = true;
    private boolean removed;

    public EntityElectricHolder(Entity owner, int terminals, Consumer<IElectricEntity.CircuitBuilder> circuit) {
        this.owner = owner;
        this.terminals = terminals;
        this.circuit = circuit;
    }

    private WorldNetworks networks() {
        return GlobalElectricNetworks.getWorldNetworks(owner.level());
    }

    public boolean hasTerminal(int terminal) {
        return built && !removed && terminal >= 0 && terminal < external.size();
    }

    public OwnedFloatingNode terminal(int terminal) {
        return hasTerminal(terminal) ? external.get(terminal) : null;
    }

    private boolean hasInternals() {
        return !internalNodes.isEmpty() || !internalWires.isEmpty();
    }

    // Lifecycle

    /** The entity is in its level: build the circuit the first time, then join the networks as a block entity loads. */
    public void added() {
        if (removed)
            return;
        if (!built) {
            for (int i = 0; i < terminals; i++)
                external.add(new OwnedFloatingNode(new EntityWireEndpoint(owner.getUUID(), owner.blockPosition(), i)));
            circuit.accept(new IElectricEntity.CircuitBuilder(owner.blockPosition(), external, internalNodes,
                    internalWires));
            built = true;
        }
        EntityElectricHost.added(owner);
        WorldNetworks networks = networks();
        for (OwnedFloatingNode node : external)
            networks.nodeHolderAdded(node, hasInternals());
        unpause();
    }

    /** The entity left with its chunk (or, on a client, went out of view): its wires wait for it. */
    public void unload() {
        EntityElectricHost.removed(owner);
        if (removed || !built)
            return;
        pause();
        WorldNetworks networks = networks();
        for (OwnedFloatingNode node : external)
            networks.nodeHolderUnloaded(node);
    }

    /** The entity is destroyed: every cord on it comes off, dropping its items, and its terminals go. */
    public void remove() {
        EntityElectricHost.removed(owner);
        if (removed || !built)
            return;
        if (!owner.level().isClientSide)
            for (Set<BaseWireEntity> wires : connections.values())
                for (BaseWireEntity wire : List.copyOf(wires))
                    if (wire.isAlive())
                        wire.kill();
        connections.clear();
        pause();
        WorldNetworks networks = networks();
        for (OwnedFloatingNode node : external)
            networks.nodeHolderRemoved(node);
        removed = true;
    }

    private void pause() {
        if (paused)
            return;
        paused = true;
        internalWires.forEach(AbstractElectricWire::remove);
        for (int i = internalNodes.size() - 1; i >= 0; i--)
            internalNodes.get(i).remove();
    }

    private void unpause() {
        if (!paused)
            return;
        paused = false;
        if (!hasInternals())
            return;
        WorldNetworks networks = networks();
        for (OwnedFloatingNode node : external)
            networks.prepareUnpaused(node);
        tracedAdd(new LinkedList<>(external));
    }

    // Joining networks, as the behaviour does it

    /** A wire on terminal {@code terminal} joined {@code network}: the terminal, and what it reaches inside, go in. */
    public void joinNetwork(ElectricalNetwork network, int terminal) {
        if (!hasTerminal(terminal))
            return;
        OwnedFloatingNode node = external.get(terminal);
        addOrMerge(node, network);
        LinkedList<INode> scan = new LinkedList<>();
        scan.add(node);
        tracedAdd(scan);
    }

    private static void addOrMerge(IElectricNode node, ElectricalNetwork network) {
        if (node.getNetwork() == network)
            return;
        if (node.getNetwork() == null)
            network.addNode(node);
        else
            network.merge(node.getNetwork());
    }

    /** Spreads the networks of the scanned nodes over the internal wires they touch, and on to the far nodes. */
    private void tracedAdd(List<INode> scan) {
        if (paused)
            return;
        Set<INetworkElement> handled = new HashSet<>();
        while (!scan.isEmpty()) {
            INode node = scan.remove(0);
            ElectricalNetwork network = node.getNetwork();
            if (network == null)
                continue;
            handled.add(node);
            for (AbstractElectricWire wire : internalWires) {
                if (!wire.coupledNodes().contains(node) || !handled.add(wire))
                    continue;
                for (IElectricNode other : wire.coupledNodes()) {
                    addOrMerge(other, network);
                    if (handled.add(other))
                        scan.add(other);
                }
                network.addWire(wire);
            }
        }
    }

    // The cords on it

    void addConnection(int terminal, BaseWireEntity wire) {
        Set<BaseWireEntity> set = connections.computeIfAbsent(terminal, t -> new HashSet<>());
        set.removeIf(Entity::isRemoved);
        set.add(wire);
    }

    void removeConnection(int terminal, BaseWireEntity wire) {
        Set<BaseWireEntity> set = connections.get(terminal);
        if (set == null)
            return;
        set.remove(wire);
        if (set.isEmpty())
            connections.remove(terminal);
    }

    /** Whether a cord is on every one of these terminals. */
    public boolean wired(int... terminals) {
        for (int terminal : terminals) {
            Set<BaseWireEntity> set = connections.get(terminal);
            if (set == null || set.stream().noneMatch(Entity::isAlive))
                return false;
        }
        return true;
    }
}
