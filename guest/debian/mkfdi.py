#!/usr/bin/env python3
"""Turns a raw disk image into the sparse .fdi format of spec 12.4.4, and optionally writes the
deflated chunk files the mod jar stores large files as (spec 12.4.3).

    mkfdi.py [--title TITLE] [--chunks DIR] IMAGE OUT.fdi

.fdi layout, every number big-endian unsigned 32 bits:
  0 magic "FDI1"; 4 image size in 512-byte sectors; 8 chunk size (65536); 12 chunks present;
  16 CRC32 of the chunk table; 20 the title, UTF-8, under 64 bytes, zero padded to 64;
  84 CRC32 of bytes 0 to 83; 88 the chunk table (per present chunk its index then the CRC32 of
  its data, indexes rising); then the present chunks' data in table order. Every chunk is
  65536 bytes except the image's last. All-zero chunks are left out. CRC32 is zlib's.

--chunks DIR writes DIR/<name of OUT>.z/ holding size.txt (the .fdi's size in decimal) and 0.z,
1.z, ... : the .fdi in 65536-byte pieces, each a zlib stream (RFC 1950), as the game's
DeflatedChunks reads them. Put that folder under the catalogue host's path in the jar.

Reads the image twice (once for the table, once for the data) so memory stays at one chunk.
"""
import argparse
import os
import struct
import sys
import zlib

CHUNK = 65536
SECTOR = 512
HEADER = 88
TITLE = 64


def chunks(path, size):
    """Yields (index, data) for every chunk of the image."""
    with open(path, 'rb') as f:
        index = 0
        while index * CHUNK < size:
            data = f.read(CHUNK)
            yield index, data
            index += 1


def header(sectors, title, table):
    title_bytes = title.encode('utf-8')
    if len(title_bytes) >= TITLE or '\0' in title:
        raise SystemExit('mkfdi: the title must be under 64 bytes of UTF-8 without NUL')
    if sectors > 0xFFFFFFFF:
        raise SystemExit('mkfdi: the image is too large for 32-bit sector counts')
    entries = b''.join(struct.pack('>II', index, crc) for index, crc in table)
    head = bytearray(HEADER)
    head[0:4] = b'FDI1'
    struct.pack_into('>IIII', head, 4, sectors, CHUNK, len(table), zlib.crc32(entries))
    head[20:20 + len(title_bytes)] = title_bytes
    struct.pack_into('>I', head, 84, zlib.crc32(bytes(head[:84])))
    return bytes(head) + entries


class ChunkWriter:
    """Splits a byte stream into 65536-byte zlib files: DIR/0.z, 1.z, ... and size.txt."""

    def __init__(self, folder):
        self.folder = folder
        os.makedirs(folder, exist_ok=True)
        for name in os.listdir(folder):
            os.remove(os.path.join(folder, name))
        self.buffer = bytearray()
        self.count = 0
        self.size = 0

    def write(self, data):
        self.buffer += data
        self.size += len(data)
        while len(self.buffer) >= CHUNK:
            self._flush(bytes(self.buffer[:CHUNK]))
            del self.buffer[:CHUNK]

    def close(self):
        if self.buffer:
            self._flush(bytes(self.buffer))
            self.buffer = bytearray()
        with open(os.path.join(self.folder, 'size.txt'), 'w') as f:
            f.write('%d\n' % self.size)

    def _flush(self, data):
        with open(os.path.join(self.folder, '%d.z' % self.count), 'wb') as f:
            f.write(zlib.compress(data, 9))
        self.count += 1


def main():
    parser = argparse.ArgumentParser(description='raw disk image to .fdi (spec 12.4.4)')
    parser.add_argument('--title', default='', help='the title, under 64 bytes of UTF-8')
    parser.add_argument('--chunks', metavar='DIR',
                        help='also write the deflated chunk files of the .fdi to DIR/<name>.z/')
    parser.add_argument('image')
    parser.add_argument('out')
    args = parser.parse_args()

    size = os.path.getsize(args.image)
    if size % SECTOR:
        raise SystemExit('mkfdi: %s is not whole 512-byte sectors' % args.image)
    title = args.title or os.path.basename(args.image)

    zero = bytes(CHUNK)
    table = []
    for index, data in chunks(args.image, size):
        if data != zero[:len(data)]:
            table.append((index, zlib.crc32(data)))
    head = header(size // SECTOR, title, table)

    writer = None
    if args.chunks:
        writer = ChunkWriter(os.path.join(args.chunks, os.path.basename(args.out) + '.z'))
    present = {index for index, _ in table}
    total = 0
    with open(args.out + '.part', 'wb') as out:
        out.write(head)
        if writer:
            writer.write(head)
        total += len(head)
        for index, data in chunks(args.image, size):
            if index in present:
                out.write(data)
                if writer:
                    writer.write(data)
                total += len(data)
    os.replace(args.out + '.part', args.out)
    if writer:
        writer.close()
    print('mkfdi: %s, %d of %d chunks present, %d bytes' %
          (args.out, len(table), (size + CHUNK - 1) // CHUNK, total), file=sys.stderr)


if __name__ == '__main__':
    main()
