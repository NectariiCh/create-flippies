package com.nectariich.create_flippies.content.data_cord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.content.data_cord.PortCompatibility.End;
import com.nectariich.create_flippies.content.data_cord.PortCompatibility.Result;

import org.junit.jupiter.api.Test;

class PortCompatibilityTest {

    private static End end(String id, PortType type, PortRole role) {
        return new End(id, type, role, false);
    }

    @Test
    void sameTypeOnly() {
        assertEquals(Result.OK, PortCompatibility.check(end("a", PortType.VGA, PortRole.SOURCE),
                end("b", PortType.VGA, PortRole.SINK), 3));
        assertEquals(Result.DIFFERENT_TYPE, PortCompatibility.check(end("a", PortType.COM, PortRole.PEER),
                end("b", PortType.ETHERNET, PortRole.PEER), 3));
    }

    @Test
    void rolePairs() {
        assertEquals(Result.OK, PortCompatibility.check(end("a", PortType.ETHERNET, PortRole.PEER),
                end("b", PortType.ETHERNET, PortRole.PEER), 1));
        assertEquals(Result.OK, PortCompatibility.check(end("k", PortType.PS2, PortRole.DEVICE),
                end("c", PortType.PS2, PortRole.HOST), 1));
        // Two PS/2 host ports, or two VGA outputs, do not pair
        assertEquals(Result.ROLES, PortCompatibility.check(end("a", PortType.PS2, PortRole.HOST),
                end("b", PortType.PS2, PortRole.HOST), 1));
        assertEquals(Result.ROLES, PortCompatibility.check(end("a", PortType.VGA, PortRole.SOURCE),
                end("b", PortType.VGA, PortRole.SOURCE), 1));
    }

    @Test
    void occupiedPorts() {
        assertEquals(Result.OCCUPIED, PortCompatibility.check(new End("a", PortType.COM, PortRole.PEER, true),
                end("b", PortType.COM, PortRole.PEER), 1));
        assertEquals(Result.OCCUPIED, PortCompatibility.check(end("a", PortType.COM, PortRole.PEER),
                new End("b", PortType.COM, PortRole.PEER, true), 1));
    }

    @Test
    void samePort() {
        assertEquals(Result.SAME_PORT, PortCompatibility.check(end("a", PortType.COM, PortRole.PEER),
                end("a", PortType.COM, PortRole.PEER), 0));
    }

    @Test
    void lengthLimit() {
        assertEquals(Result.OK, PortCompatibility.check(end("a", PortType.COM, PortRole.PEER),
                end("b", PortType.COM, PortRole.PEER), PortCompatibility.MAX_CORD_BLOCKS));
        assertEquals(Result.TOO_LONG, PortCompatibility.check(end("a", PortType.COM, PortRole.PEER),
                end("b", PortType.COM, PortRole.PEER), PortCompatibility.MAX_CORD_BLOCKS + 0.01));
    }

    @Test
    void loopbackComOnOnePc() {
        // COM1 and COM2 of the same case: different ports, same block
        assertEquals(Result.OK, PortCompatibility.check(end("pc#3", PortType.COM, PortRole.PEER),
                end("pc#4", PortType.COM, PortRole.PEER), 0.1));
    }
}
