package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.DmaWindow;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * The network card (spec 11.1.7): a simple card of our own, not a model of a real chip. Frames
 * are Ethernet II without preamble and FCS (destination MAC, source MAC, type, payload), any
 * length from {@link #MIN_FRAME} to {@link #MAX_FRAME}; the card neither pads nor checks them.
 *
 * TX_CMD copies a frame out of memory at once into the transmit queue, which the host drains
 * between ticks with {@link #takeOutgoing}; the host hands incoming frames to {@link #deliver},
 * which filters them into the receive queue. Each queue holds {@link #QUEUE_FRAMES} frames. No
 * device-internal timing: the card changes its line only on an access or a host call.
 */
public final class Nic implements BusDevice {

    public static final int MIN_FRAME = 14, MAX_FRAME = 1514, QUEUE_FRAMES = 32;

    /** Register offsets (spec 11.1.7). */
    public static final int CTRL = 0x00, STATUS = 0x04, MAC_LO = 0x08, MAC_HI = 0x0C, TX_ADDR = 0x10,
            TX_LEN = 0x14, TX_CMD = 0x18, RX_LEN = 0x20, RX_ADDR = 0x24, RX_CMD = 0x28, RX_COUNT = 0x2C,
            ERROR_CODE = 0x30, RX_DROPPED = 0x34;
    public static final int SIZE = 0x38;

    /** CTRL bits. */
    public static final int CTRL_RX_ENABLE = 1, CTRL_PROMISC = 2, CTRL_IRQ_RX = 4, CTRL_IRQ_TX = 8,
            CTRL_IRQ_LINK = 16;
    /** STATUS bits; TX_DONE, TX_ERROR, RX_OVERFLOW and LINK_CHANGED are write-1-to-clear. */
    public static final int STATUS_RX_READY = 1, STATUS_TX_DONE = 2, STATUS_TX_ERROR = 4,
            STATUS_RX_OVERFLOW = 8, STATUS_LINK = 16, STATUS_LINK_CHANGED = 32;
    /** TX_CMD and RX_CMD values. */
    public static final int TX_SEND = 1, RX_COPY = 1, RX_DROP = 2;
    /** ERROR_CODE values. */
    public static final int ERR_NONE = 0, ERR_BAD_LENGTH = 1, ERR_DMA_RANGE = 2, ERR_TX_FULL = 3,
            ERR_RX_EMPTY = 4, ERR_NO_LINK = 5;

    private static final int STATE_VERSION = 1;

    private final SystemBus bus;
    private final IrqSink irq;
    private final long mac;
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private final ArrayDeque<byte[]> rxQueue = new ArrayDeque<>();

    private int ctrl;
    private boolean txDone;
    private boolean txError;
    private boolean rxOverflow;
    private boolean link;
    private boolean linkChanged;
    private int txAddr;
    private int txLen;
    private int rxAddr;
    private int errorCode;
    private int rxDropped;
    private boolean irqLevel;

    /** {@code mac} is 48 bits, byte 0 (the first on the wire) in bits 47..40. */
    public Nic(SystemBus bus, IrqSink irq, long mac) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.irq = Objects.requireNonNull(irq, "irq");
        if (mac < 0 || mac >>> 48 != 0)
            throw new IllegalArgumentException("MAC address must fit 48 bits: " + Long.toHexString(mac));
        this.mac = mac;
    }

    // ---- host side ----

    public long macAddress() {
        return mac;
    }

    /** Cable connected and the peer powered; LINK_CHANGED on a change. The queues are kept. */
    public void setLink(boolean up) {
        if (up != link) {
            link = up;
            linkChanged = true;
            updateIrq();
        }
    }

    public boolean link() {
        return link;
    }

    /**
     * An incoming frame, copied. Dropped and counted while RX_ENABLE is off, for a length out of
     * range, or with the receive queue full (RX_OVERFLOW set); dropped silently when the filter
     * rejects it. True if it was queued.
     */
    public boolean deliver(byte[] frame, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, frame.length);
        if ((ctrl & CTRL_RX_ENABLE) == 0 || length < MIN_FRAME || length > MAX_FRAME) {
            rxDropped++;
            return false;
        }
        if (!accepts(frame, offset))
            return false;
        if (rxQueue.size() >= QUEUE_FRAMES) {
            rxOverflow = true;
            rxDropped++;
            updateIrq();
            return false;
        }
        rxQueue.add(Arrays.copyOfRange(frame, offset, offset + length));
        updateIrq();
        return true;
    }

    /** Ours, broadcast, multicast (bit 0 of byte 0), or everything in promiscuous mode. */
    private boolean accepts(byte[] frame, int offset) {
        if ((ctrl & CTRL_PROMISC) != 0 || (frame[offset] & 1) != 0)
            return true;
        for (int i = 0; i < 6; i++)
            if ((frame[offset + i] & 0xFF) != macByte(i))
                return false;
        return true;
    }

    /** The oldest transmitted frame, exact length, or null when empty. */
    public byte[] takeOutgoing() {
        return txQueue.poll();
    }

    public int outgoingCount() {
        return txQueue.size();
    }

    /** Frames waiting for the guest. */
    public int incomingCount() {
        return rxQueue.size();
    }

    private int macByte(int i) {
        return (int) (mac >>> (40 - 8 * i)) & 0xFF;
    }

    // ---- registers ----

    private int status() {
        return (rxQueue.isEmpty() ? 0 : STATUS_RX_READY) | (txDone ? STATUS_TX_DONE : 0)
                | (txError ? STATUS_TX_ERROR : 0) | (rxOverflow ? STATUS_RX_OVERFLOW : 0)
                | (link ? STATUS_LINK : 0) | (linkChanged ? STATUS_LINK_CHANGED : 0);
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        int v = switch (offset & ~3) {
            case CTRL -> ctrl;
            case STATUS -> status();
            case MAC_LO -> macByte(0) | macByte(1) << 8 | macByte(2) << 16 | macByte(3) << 24;
            case MAC_HI -> macByte(4) | macByte(5) << 8;
            case TX_ADDR -> txAddr;
            case TX_LEN -> txLen;
            case RX_LEN -> rxQueue.isEmpty() ? 0 : rxQueue.peek().length;
            case RX_ADDR -> rxAddr;
            case RX_COUNT -> rxQueue.size();
            case ERROR_CODE -> errorCode;
            case RX_DROPPED -> rxDropped;
            default -> 0;
        };
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        switch (offset & ~3) {
            case CTRL -> ctrl = Regs.merge(ctrl, offset, width, value) & 0x1F;
            case STATUS -> {
                int bits = Regs.written(offset, width, value);
                if ((bits & STATUS_TX_DONE) != 0)
                    txDone = false;
                if ((bits & STATUS_TX_ERROR) != 0)
                    txError = false;
                if ((bits & STATUS_RX_OVERFLOW) != 0)
                    rxOverflow = false;
                if ((bits & STATUS_LINK_CHANGED) != 0)
                    linkChanged = false;
            }
            case TX_ADDR -> txAddr = Regs.merge(txAddr, offset, width, value);
            case TX_LEN -> txLen = Regs.merge(txLen, offset, width, value);
            case TX_CMD -> {
                if (Regs.touchesByte0(offset) && (value & 0xFF) == TX_SEND)
                    send();
            }
            case RX_ADDR -> rxAddr = Regs.merge(rxAddr, offset, width, value);
            case RX_CMD -> {
                if (Regs.touchesByte0(offset))
                    receive(value & 0xFF);
            }
            default -> { }
        }
        updateIrq();
    }

    private void send() {
        int err = ERR_NONE;
        DmaWindow w = null;
        if (txLen < MIN_FRAME || txLen > MAX_FRAME)
            err = ERR_BAD_LENGTH;
        else if ((w = bus.dma(txAddr, txLen)) == null)
            err = ERR_DMA_RANGE;
        else if (txQueue.size() >= QUEUE_FRAMES)
            err = ERR_TX_FULL;
        else if (!link)
            err = ERR_NO_LINK;
        errorCode = err;
        if (err != ERR_NONE) {
            txError = true;
            return;
        }
        txQueue.add(Arrays.copyOfRange(w.array(), w.offset(), w.offset() + txLen));
        txDone = true;
    }

    private void receive(int cmd) {
        if (cmd != RX_COPY && cmd != RX_DROP)
            return;
        byte[] frame = rxQueue.peek();
        if (frame == null) {
            errorCode = ERR_RX_EMPTY;
            return;
        }
        if (cmd == RX_COPY) {
            DmaWindow w = bus.dma(rxAddr, frame.length);
            if (w == null) {
                errorCode = ERR_DMA_RANGE;
                return;
            }
            System.arraycopy(frame, 0, w.array(), w.offset(), frame.length);
            w.onWrite().run();
        }
        rxQueue.poll();
        errorCode = ERR_NONE;
    }

    /** Registers, counters and both queues cleared; the link and the MAC are the host's and stay. */
    @Override
    public void reset() {
        ctrl = 0;
        txDone = false;
        txError = false;
        rxOverflow = false;
        linkChanged = false;
        txAddr = 0;
        txLen = 0;
        rxAddr = 0;
        errorCode = ERR_NONE;
        rxDropped = 0;
        txQueue.clear();
        rxQueue.clear();
        updateIrq();
    }

    private void updateIrq() {
        boolean level = (ctrl & CTRL_IRQ_RX) != 0 && !rxQueue.isEmpty()
                || (ctrl & CTRL_IRQ_TX) != 0 && (txDone || txError)
                || (ctrl & CTRL_IRQ_LINK) != 0 && linkChanged;
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }

    // ---- saved state (spec 1.5, 11.1.7) ----

    /** The MAC, every register and flag, the counters and both queues oldest first. */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeLong(mac);
        out.writeInt(ctrl);
        out.writeBoolean(txDone);
        out.writeBoolean(txError);
        out.writeBoolean(rxOverflow);
        out.writeBoolean(link);
        out.writeBoolean(linkChanged);
        out.writeInt(txAddr);
        out.writeInt(txLen);
        out.writeInt(rxAddr);
        out.writeInt(errorCode);
        out.writeInt(rxDropped);
        out.writeBoolean(irqLevel);
        writeQueue(out, txQueue);
        writeQueue(out, rxQueue);
    }

    /** StateFormatException if the saved MAC is not this card's. */
    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "network card", STATE_VERSION);
        long saved = in.readLong();
        StateIo.check(saved == mac, "saved network card has MAC " + Long.toHexString(saved) + ", this one "
                + Long.toHexString(mac));
        ctrl = in.readInt() & 0x1F;
        txDone = in.readBoolean();
        txError = in.readBoolean();
        rxOverflow = in.readBoolean();
        link = in.readBoolean();
        linkChanged = in.readBoolean();
        txAddr = in.readInt();
        txLen = in.readInt();
        rxAddr = in.readInt();
        errorCode = in.readInt();
        StateIo.check(errorCode >= 0 && errorCode <= ERR_NO_LINK, "network card error code " + errorCode + " does not exist");
        rxDropped = in.readInt();
        irqLevel = in.readBoolean();
        readQueue(in, txQueue, "transmit");
        readQueue(in, rxQueue, "receive");
    }

    private static void writeQueue(DataOutput out, ArrayDeque<byte[]> queue) throws IOException {
        out.writeInt(queue.size());
        for (byte[] f : queue) {
            out.writeShort(f.length);
            out.write(f);
        }
    }

    private static void readQueue(DataInput in, ArrayDeque<byte[]> queue, String what) throws IOException {
        int n = StateIo.readCount(in, QUEUE_FRAMES, what + " queue length");
        queue.clear();
        for (int i = 0; i < n; i++) {
            int length = in.readUnsignedShort();
            StateIo.check(length >= MIN_FRAME && length <= MAX_FRAME, what + " frame of " + length + " bytes");
            byte[] f = new byte[length];
            in.readFully(f);
            queue.add(f);
        }
    }
}
