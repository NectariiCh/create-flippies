package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.api.Test;

/**
 * The exchange phase's data moves between real machines (docs/PC_SPEC.md 11.4),
 * driven from the host side the way the scheduler drives them: frames and bytes
 * are put on the wire through the devices' registers, then moved once per tick.
 */
class LinkExchangeTest {

    private static final int FRAME_AT = Rv32Map.RAM_BASE + 0x1000;
    private static final int RX_AT = Rv32Map.RAM_BASE + 0x4000;

    private static Machine machine(long mac) {
        Machine m = new Machine(MachineConfig.of(CpuModel.RV32I, 10_000_000, 1 << 20, null, false, mac),
                StandardCpus.INSTANCE);
        m.powerOn();
        return m;
    }

    private static byte[] frame(long dst, long src, int payload, int fill) {
        byte[] f = new byte[14 + payload];
        for (int i = 0; i < 6; i++) {
            f[i] = (byte) (dst >>> (40 - 8 * i));
            f[6 + i] = (byte) (src >>> (40 - 8 * i));
        }
        f[12] = 0x08;
        Arrays.fill(f, 14, f.length, (byte) fill);
        return f;
    }

    /** What the guest's driver would do: copy the frame into RAM and tell the card to send it. */
    private static int send(Machine m, byte[] frame) {
        m.loadRam(FRAME_AT, frame);
        Nic nic = m.nic();
        nic.write(Nic.TX_ADDR, 4, FRAME_AT);
        nic.write(Nic.TX_LEN, 4, frame.length);
        nic.write(Nic.TX_CMD, 4, Nic.TX_SEND);
        return nic.read(Nic.ERROR_CODE, 4);
    }

    private static byte[] receive(Machine m) {
        Nic nic = m.nic();
        int length = nic.read(Nic.RX_LEN, 4);
        nic.write(Nic.RX_ADDR, 4, RX_AT);
        nic.write(Nic.RX_CMD, 4, Nic.RX_COPY);
        int offset = RX_AT - Rv32Map.RAM_BASE;
        return Arrays.copyOfRange(m.board().ram(), offset, offset + length);
    }

    @Test
    void ethernetFramesAndLinkState() {
        long macA = 0x02_0000_0000_0AL, macB = 0x02_0000_0000_0BL;
        Machine a = machine(macA), b = machine(macB);
        b.nic().write(Nic.CTRL, 4, Nic.CTRL_RX_ENABLE);
        assertFalse(a.nic().link());

        // Link down: the card refuses at send time
        assertEquals(Nic.ERR_NO_LINK, send(a, frame(macB, macA, 32, 1)));

        PcLinkExchange.ethernet(a.nic(), b.nic());
        assertTrue(a.nic().link() && b.nic().link());

        byte[] first = frame(macB, macA, 46, 0x11), second = frame(macB, macA, 60, 0x22);
        assertEquals(Nic.ERR_NONE, send(a, first));
        assertEquals(Nic.ERR_NONE, send(a, second));
        PcLinkExchange.ethernet(a.nic(), b.nic());
        assertEquals(2, b.nic().read(Nic.RX_COUNT, 4));
        // In send order
        assertArrayEquals(first, receive(b));
        assertArrayEquals(second, receive(b));

        PcLinkExchange.ethernetDown(a.nic());
        assertFalse(a.nic().link());
    }

    @Test
    void comBytesWithTheLeftoverBuffer() {
        Machine a = machine(0), b = machine(0);
        Uart16550 tx = a.com(1), rx = b.com(1);
        PcLinkExchange.ComCable cable = new PcLinkExchange.ComCable();
        // Fill the far UART's host input so nothing more fits
        byte[] full = new byte[Uart16550.HOST_INPUT_CAPACITY];
        assertEquals(full.length, rx.hostWrite(full, 0, full.length));

        tx.write(Uart16550.THR, 1, 'h');
        a.runTick(Machine.TICK_NANOS, Long.MAX_VALUE);
        PcLinkExchange.com(tx, rx, cable);
        assertTrue(tx.hostConnected() && rx.hostConnected());
        // Not accepted: kept for the next tick
        assertEquals(1, cable.aToB.length());
        assertArrayEquals(new byte[] {'h'}, cable.aToB.contents());
    }

    @Test
    void comOverflowDropsTheOldest() {
        PcLinkExchange.ComBuffer buffer = new PcLinkExchange.ComBuffer();
        byte[] bytes = new byte[PcLinkExchange.COM_BUFFER + 100];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) i;
        buffer.offer(bytes, 50);
        buffer.offer(Arrays.copyOfRange(bytes, 50, bytes.length), bytes.length - 50);
        assertEquals(PcLinkExchange.COM_BUFFER, buffer.length());
        assertArrayEquals(Arrays.copyOfRange(bytes, 100, bytes.length), buffer.contents());
    }

    @Test
    void comDelivery() {
        Machine a = machine(0), b = machine(0);
        PcLinkExchange.ComCable cable = new PcLinkExchange.ComCable();
        a.com(2).write(Uart16550.THR, 1, 'x');
        a.runTick(Machine.TICK_NANOS, Long.MAX_VALUE);
        PcLinkExchange.com(a.com(2), b.com(2), cable);
        assertEquals(0, cable.aToB.length());
        b.runTick(Machine.TICK_NANOS, Long.MAX_VALUE);
        // The byte arrived in the far UART's receive register
        assertEquals('x', b.com(2).read(Uart16550.RBR, 1));
    }
}
