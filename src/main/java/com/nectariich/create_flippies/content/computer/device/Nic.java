package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.DmaWindow;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * The network card (spec 11.1.7, 12.3): a simple card of our own, not a model of a real chip.
 * Frames are Ethernet II without preamble and FCS (destination MAC, source MAC, type, payload),
 * any length from {@link #MIN_FRAME} to {@link #MAX_FRAME}; the card neither pads nor checks them.
 *
 * TX_CMD copies a frame out of memory at once into the transmit queue, which the host drains
 * between ticks with {@link #takeOutgoing}; the host hands incoming frames to {@link #deliver},
 * which filters them into the receive queue. Each queue holds {@link #QUEUE_FRAMES} frames. No
 * device-internal timing: the card changes its line only on an access or a host call.
 *
 * Checksum offload for IPv4: RX_INFO reports whether the oldest received frame's IPv4 header and
 * TCP or UDP checksums are good, and CTRL.TX_CSUM makes TX_CMD fill them in. Both are worked out
 * from the frame's bytes when needed, so they add no state.
 */
public final class Nic implements BusDevice, FrameEndpoint {

    public static final int MIN_FRAME = 14, MAX_FRAME = 1514, QUEUE_FRAMES = 256;

    /** Register offsets (spec 11.1.7, 12.3). */
    public static final int CTRL = 0x00, STATUS = 0x04, MAC_LO = 0x08, MAC_HI = 0x0C, TX_ADDR = 0x10,
            TX_LEN = 0x14, TX_CMD = 0x18, RX_LEN = 0x20, RX_ADDR = 0x24, RX_CMD = 0x28, RX_COUNT = 0x2C,
            ERROR_CODE = 0x30, RX_DROPPED = 0x34, RX_INFO = 0x38;
    public static final int SIZE = 0x3C;

    /** CTRL bits. */
    public static final int CTRL_RX_ENABLE = 1, CTRL_PROMISC = 2, CTRL_IRQ_RX = 4, CTRL_IRQ_TX = 8,
            CTRL_IRQ_LINK = 16, CTRL_TX_CSUM = 32;
    private static final int CTRL_MASK = 0x3F;
    /** STATUS bits; TX_DONE, TX_ERROR, RX_OVERFLOW and LINK_CHANGED are write-1-to-clear. */
    public static final int STATUS_RX_READY = 1, STATUS_TX_DONE = 2, STATUS_TX_ERROR = 4,
            STATUS_RX_OVERFLOW = 8, STATUS_LINK = 16, STATUS_LINK_CHANGED = 32;
    /** TX_CMD and RX_CMD values. */
    public static final int TX_SEND = 1, RX_COPY = 1, RX_DROP = 2;
    /** ERROR_CODE values. */
    public static final int ERR_NONE = 0, ERR_BAD_LENGTH = 1, ERR_DMA_RANGE = 2, ERR_TX_FULL = 3,
            ERR_RX_EMPTY = 4, ERR_NO_LINK = 5;
    /**
     * RX_INFO bits for the oldest received frame: the IPv4 header checksum is good (and its
     * lengths fit the frame); the TCP or UDP checksum is good (IPv4, not a fragment, a UDP
     * checksum of 0 is not checked); the frame's type is IPv4 (0x0800).
     */
    public static final int RX_INFO_IP_OK = 1, RX_INFO_L4_OK = 2, RX_INFO_IPV4 = 4;

    private static final int ETH_HEADER = 14, TYPE_IPV4 = 0x0800, PROTO_TCP = 6, PROTO_UDP = 17;

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
    @Override
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
    @Override
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

    /** The receiver is on and its queue has room: a frame held back for now is not a dropped one. */
    @Override
    public boolean canReceive() {
        return (ctrl & CTRL_RX_ENABLE) != 0 && rxQueue.size() < QUEUE_FRAMES;
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
    @Override
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
            case RX_INFO -> rxQueue.isEmpty() ? 0 : rxInfo(rxQueue.peek());
            default -> 0;
        };
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        switch (offset & ~3) {
            case CTRL -> ctrl = Regs.merge(ctrl, offset, width, value) & CTRL_MASK;
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
        byte[] frame = Arrays.copyOfRange(w.array(), w.offset(), w.offset() + txLen);
        if ((ctrl & CTRL_TX_CSUM) != 0)
            fillChecksums(frame);
        txQueue.add(frame);
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

    // ---- checksum offload (spec 12.3) ----

    /** RX_INFO for frame {@code f}. */
    private static int rxInfo(byte[] f) {
        if (u16(f, 12) != TYPE_IPV4)
            return 0;
        int ihl = ipv4HeaderLength(f);
        if (ihl < 0 || fold(sum(f, ETH_HEADER, ihl, 0)) != 0xFFFF)
            return RX_INFO_IPV4;
        int l4 = l4Length(f, ihl);
        int at = ETH_HEADER + ihl;
        // A UDP checksum of 0 means the sender computed none (RFC 768): nothing to check
        if (l4 < 0 || (f[23] & 0xFF) == PROTO_UDP && u16(f, at + 6) == 0
                || fold(pseudoSum(f, l4) + sum(f, at, l4, 0)) != 0xFFFF)
            return RX_INFO_IPV4 | RX_INFO_IP_OK;
        return RX_INFO_IPV4 | RX_INFO_IP_OK | RX_INFO_L4_OK;
    }

    /**
     * TX_CSUM: the IPv4 header checksum of an IPv4 frame, then its TCP or UDP checksum, each
     * computed with the field zeroed. Anything else is left as it is.
     */
    private static void fillChecksums(byte[] f) {
        int ihl = ipv4HeaderLength(f);
        if (ihl < 0)
            return;
        put16(f, 24, 0);
        put16(f, 24, ~fold(sum(f, ETH_HEADER, ihl, 0)));
        int l4 = l4Length(f, ihl);
        if (l4 < 0)
            return;
        boolean udp = (f[23] & 0xFF) == PROTO_UDP;
        int at = ETH_HEADER + ihl + (udp ? 6 : 16);
        put16(f, at, 0);
        int c = ~fold(pseudoSum(f, l4) + sum(f, ETH_HEADER + ihl, l4, 0)) & 0xFFFF;
        // UDP sends a computed 0 as all ones, since 0 means "no checksum" (RFC 768)
        put16(f, at, udp && c == 0 ? 0xFFFF : c);
    }

    /**
     * The IPv4 header length in bytes when {@code f} holds IPv4 with version 4, a header of at
     * least 20 bytes and a total length that covers the header and fits the frame (padding after
     * it allowed); otherwise -1.
     */
    private static int ipv4HeaderLength(byte[] f) {
        if (f.length < ETH_HEADER + 20 || u16(f, 12) != TYPE_IPV4 || (f[14] & 0xF0) != 0x40)
            return -1;
        int ihl = (f[14] & 0x0F) * 4;
        int total = u16(f, 16);
        return ihl < 20 || total < ihl || ETH_HEADER + total > f.length ? -1 : ihl;
    }

    /**
     * The length the TCP or UDP checksum covers (TCP: the IPv4 payload; UDP: its length field),
     * or -1 when there is none to work out: another protocol, a fragment, or a segment too short.
     */
    private static int l4Length(byte[] f, int ihl) {
        if ((u16(f, 20) & 0x3FFF) != 0)
            return -1;
        int length = u16(f, 16) - ihl;
        return switch (f[23] & 0xFF) {
            case PROTO_TCP -> length >= 20 ? length : -1;
            case PROTO_UDP -> {
                if (length < 8)
                    yield -1;
                int udp = u16(f, ETH_HEADER + ihl + 4);
                yield udp >= 8 && udp <= length ? udp : -1;
            }
            default -> -1;
        };
    }

    /** The unfolded sum of the pseudo-header: source and destination addresses, protocol, length. */
    private static int pseudoSum(byte[] f, int l4) {
        return sum(f, 26, 8, 0) + (f[23] & 0xFF) + l4;
    }

    /** {@code acc} plus the big-endian 16-bit words of {@code length} bytes from {@code at}, an odd last byte padded with 0. */
    private static int sum(byte[] f, int at, int length, int acc) {
        int end = at + (length & ~1);
        for (int i = at; i < end; i += 2)
            acc += u16(f, i);
        if ((length & 1) != 0)
            acc += (f[end] & 0xFF) << 8;
        return acc;
    }

    /** A sum folded to 16 bits with end-around carry (one's complement addition, RFC 1071). */
    private static int fold(int s) {
        while (s >>> 16 != 0)
            s = (s & 0xFFFF) + (s >>> 16);
        return s;
    }

    private static int u16(byte[] f, int at) {
        return (f[at] & 0xFF) << 8 | f[at + 1] & 0xFF;
    }

    private static void put16(byte[] f, int at, int v) {
        f[at] = (byte) (v >>> 8);
        f[at + 1] = (byte) v;
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
        ctrl = in.readInt() & CTRL_MASK;
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

    /** Up to {@link #QUEUE_FRAMES} frames, so states saved with the old 32-frame queues load too. */
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
