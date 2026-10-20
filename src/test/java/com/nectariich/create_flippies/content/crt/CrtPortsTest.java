package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.nectariich.create_flippies.content.data_cord.PortCompatibility;
import com.nectariich.create_flippies.content.data_cord.PortCompatibility.End;
import com.nectariich.create_flippies.content.data_cord.PortCompatibility.Result;
import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;

import org.junit.jupiter.api.Test;

/** The CRT's `vga in` pairs with the case's three VGA ports and nothing else. */
class CrtPortsTest {

    /** The CRT's one port, as {@code CrtBlockEntity} declares it. */
    private static final End CRT = new End("crt", PortType.VGA, PortRole.SINK, false);

    private static End casePort(int port) {
        return new End("case#" + port, PcCaseGeometry.portType(port), PcCaseGeometry.portRole(port), false);
    }

    @Test
    void onlyTheCaseVgaPorts() {
        for (int port = 0; port < PcCaseGeometry.PORTS; port++) {
            Result r = PortCompatibility.check(CRT, casePort(port), 2);
            if (port >= PcCaseGeometry.VGA1 && port <= PcCaseGeometry.VGA3)
                assertEquals(Result.OK, r, "case port " + port);
            else
                assertNotEquals(Result.OK, r, "case port " + port);
            // The same either way round
            assertEquals(r, PortCompatibility.check(casePort(port), CRT, 2));
        }
    }

    @Test
    void notAnotherMonitorOrAKeyboard() {
        assertEquals(Result.ROLES, PortCompatibility.check(CRT, new End("crt2", PortType.VGA, PortRole.SINK, false), 1));
        assertEquals(Result.DIFFERENT_TYPE,
                PortCompatibility.check(CRT, new End("kb", PortType.PS2, PortRole.DEVICE, false), 1));
    }

    @Test
    void oneCordPerPortAndTheLengthLimit() {
        End taken = new End("case#6", PortType.VGA, PortRole.SOURCE, true);
        assertEquals(Result.OCCUPIED, PortCompatibility.check(CRT, taken, 1));
        assertEquals(Result.TOO_LONG, PortCompatibility.check(CRT, casePort(PcCaseGeometry.VGA2),
                PortCompatibility.MAX_CORD_BLOCKS + 1));
    }
}
