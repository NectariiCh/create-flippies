#!/usr/bin/env python3
"""Writes a raw Create Flippies PC disk image with the flipboot boot chain (spec 12.7).

    mkdisk.py create OUT --stage1 stage1.bin --stage2 stage2.bin [--size 1G]
              [--kernel-a vmlinux] [--cmdline-a TEXT] [--kernel-b vmlinux] [--cmdline-b TEXT]
              [--active a|b] [--root rootfs.ext4]
    mkdisk.py show IMAGE

Layout (sectors of 512 bytes):
    0              stage 1 (code bytes 0..439), disk signature "FLPC", the MBR partition table, 0x55 0xAA
    1..127         stage 2
    2048..67583    partition 1, type 0xDA, 32 MiB: the boot area (header copies at bytes 0 and 4096,
                   slot A's kernel at 1 MiB, slot B's at 16 MiB; format in flipboot.c)
    67584..329727  partition 2, type 0x82, 128 MiB: swap (left empty; the Debian image runs mkswap)
    329728..end    partition 3, type 0x83: the root file system (--root copies an ext4 image there)

The file is created sparse at --size (default 1 GiB, at most 1 GiB, the PC's largest disk).
The FlipbootDisk class in the Java tests writes the same bytes; a test compares the two.
"""

import argparse
import struct
import sys

SECTOR = 512
STAGE1_MAX = 440
STAGE2_MAX = 127 * SECTOR
P1_START, P1_SECTORS = 2048, 65536
P2_START, P2_SECTORS = P1_START + P1_SECTORS, 262144
P3_START = P2_START + P2_SECTORS
MIN_P3_SECTORS = 2048
MAX_SIZE = 1 << 30
HEADER = 4096
SLOT_RECORD, SLOT_SIZE, CMDLINE_FIELD = 32, 544, 512
SLOT_OFFSETS = (1 << 20, 16 << 20)
COMMAND_LINE_MAX = 239  # stage 2 appends " flipboot.slot=A" within the kernel's 255
DEFAULT_CMDLINE = "root=/dev/fhda3 rw console=tty0 console=ttyS0,115200"
SIGNATURE = b"FLPC"


def _table():
    table = []
    for i in range(256):
        c = i << 24
        for _ in range(8):
            c = ((c << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
        table.append(c)
    return table


_TABLE = _table()


def cksum(data):
    """The POSIX cksum CRC of data, as cksum prints it."""
    c = 0
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ _TABLE[(c >> 24) ^ b]
    n = len(data)
    while n:
        c = ((c << 8) & 0xFFFFFFFF) ^ _TABLE[(c >> 24) ^ (n & 0xFF)]
        n >>= 8
    return ~c & 0xFFFFFFFF


def parse_size(text):
    t = text.strip().upper().removesuffix("IB").removesuffix("B")
    shift = {"K": 10, "M": 20, "G": 30}.get(t[-1:], 0)
    if shift:
        t = t[:-1]
    return int(t) << shift


def partition_entry(boot, kind, start, count):
    # CHS fields set to the LBA-only marker (0xFE 0xFF 0xFF); Linux reads only the LBA fields.
    return struct.pack("<B3sB3sII", 0x80 if boot else 0, b"\xFE\xFF\xFF", kind, b"\xFE\xFF\xFF", start, count)


def mbr(stage1, size_sectors):
    if len(stage1) > STAGE1_MAX:
        raise SystemExit(f"mkdisk: stage 1 is {len(stage1)} bytes, more than {STAGE1_MAX}")
    sector = bytearray(SECTOR)
    sector[:len(stage1)] = stage1
    sector[440:444] = SIGNATURE
    entries = (partition_entry(True, 0xDA, P1_START, P1_SECTORS)
               + partition_entry(False, 0x82, P2_START, P2_SECTORS)
               + partition_entry(False, 0x83, P3_START, size_sectors - P3_START))
    sector[446:446 + len(entries)] = entries
    sector[510:512] = b"\x55\xAA"
    return bytes(sector)


def header(active, slots, generation=1):
    """slots: two (offset, kernel bytes or None, command line) tuples."""
    h = bytearray(HEADER)
    h[0:4] = b"FLBT"
    struct.pack_into(">III", h, 4, 1, active, 0)
    struct.pack_into(">I", h, 16, generation)
    for i, (offset, kernel, cmdline) in enumerate(slots):
        if kernel is None:
            continue
        r = SLOT_RECORD + SLOT_SIZE * i
        struct.pack_into(">III", h, r, offset, len(kernel), cksum(kernel))
        cmd = cmdline.encode("ascii")
        if len(cmd) > COMMAND_LINE_MAX:
            raise SystemExit(f"mkdisk: the command line of slot {'AB'[i]} is longer than {COMMAND_LINE_MAX} bytes")
        h[r + 32:r + 32 + len(cmd)] = cmd
    struct.pack_into(">I", h, 12, cksum(bytes(h)))
    return bytes(h)


def create(args):
    size = parse_size(args.size)
    if size % SECTOR or size > MAX_SIZE or size // SECTOR < P3_START + MIN_P3_SECTORS:
        raise SystemExit(f"mkdisk: --size must be a multiple of 512 from {(P3_START + MIN_P3_SECTORS) * SECTOR} to {MAX_SIZE} bytes")
    stage1 = open(args.stage1, "rb").read()
    stage2 = open(args.stage2, "rb").read()
    if len(stage2) > STAGE2_MAX:
        raise SystemExit(f"mkdisk: stage 2 is {len(stage2)} bytes, more than {STAGE2_MAX}")
    kernels = [open(k, "rb").read() if k else None for k in (args.kernel_a, args.kernel_b)]
    limits = (SLOT_OFFSETS[1] - SLOT_OFFSETS[0], P1_SECTORS * SECTOR - SLOT_OFFSETS[1])
    for i, k in enumerate(kernels):
        if k is not None and len(k) > limits[i]:
            raise SystemExit(f"mkdisk: the kernel for slot {'AB'[i]} ({len(k)} bytes) is larger than the slot ({limits[i]} bytes)")
    root = open(args.root, "rb").read() if args.root else None
    if root is not None and len(root) > size - P3_START * SECTOR:
        raise SystemExit("mkdisk: the root image is larger than partition 3")
    active = "ab".index(args.active)
    h = header(active, [(SLOT_OFFSETS[0], kernels[0], args.cmdline_a), (SLOT_OFFSETS[1], kernels[1], args.cmdline_b)])

    with open(args.out, "wb") as f:
        f.truncate(size)
        f.write(mbr(stage1, size // SECTOR))
        f.write(stage2)
        f.seek(P1_START * SECTOR)
        f.write(h)
        f.write(h)
        for i, k in enumerate(kernels):
            if k is not None:
                f.seek(P1_START * SECTOR + SLOT_OFFSETS[i])
                f.write(k)
        if root is not None:
            f.seek(P3_START * SECTOR)
            f.write(root)
    print(f"mkdisk: {args.out}: {size >> 20} MiB, slot {'AB'[active]} active"
          + "".join(f", slot {'AB'[i]} {len(k)} bytes" for i, k in enumerate(kernels) if k is not None))


def show(args):
    with open(args.image, "rb") as f:
        sector = f.read(SECTOR)
        if sector[510:512] != b"\x55\xAA":
            raise SystemExit("mkdisk: no MBR signature")
        for i in range(4):
            boot, _, kind, _, start, count = struct.unpack_from("<B3sB3sII", sector, 446 + 16 * i)
            if kind:
                print(f"partition {i + 1}: type 0x{kind:02X}, sectors {start}..{start + count - 1}")
        for copy in range(2):
            f.seek(P1_START * SECTOR + copy * HEADER)
            h = f.read(HEADER)
            zeroed = h[:12] + bytes(4) + h[16:]
            magic, version, active, crc, generation = struct.unpack_from(">4sIIII", h, 0)
            ok = magic == b"FLBT" and version == 1 and active <= 1 and cksum(zeroed) == crc
            print(f"header {copy}: {'ok' if ok else 'BAD'}, generation {generation}, active slot {'AB'[active & 1]}")
            if not ok:
                continue
            for s in range(2):
                r = SLOT_RECORD + SLOT_SIZE * s
                offset, length, kcrc = struct.unpack_from(">III", h, r)
                cmd = h[r + 32:r + 32 + CMDLINE_FIELD].split(b"\0")[0].decode("ascii", "replace")
                state = ""
                if length:
                    f.seek(P1_START * SECTOR + offset)
                    state = ", kernel ok" if cksum(f.read(length)) == kcrc else ", kernel CRC BAD"
                print(f"  slot {'AB'[s]}: offset {offset}, {length} bytes, crc {kcrc}{state}, command line \"{cmd}\"")


def main(argv):
    p = argparse.ArgumentParser(description="flipboot disk images (spec 12.7)")
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("create", help="write a new raw disk image")
    c.add_argument("out")
    c.add_argument("--stage1", required=True)
    c.add_argument("--stage2", required=True)
    c.add_argument("--size", default="1G")
    c.add_argument("--kernel-a")
    c.add_argument("--cmdline-a", default=DEFAULT_CMDLINE)
    c.add_argument("--kernel-b")
    c.add_argument("--cmdline-b", default=DEFAULT_CMDLINE)
    c.add_argument("--active", choices=("a", "b"), default="a")
    c.add_argument("--root")
    s = sub.add_parser("show", help="print an image's partitions and boot area")
    s.add_argument("image")
    args = p.parse_args(argv)
    (create if args.command == "create" else show)(args)


if __name__ == "__main__":
    main(sys.argv[1:])
