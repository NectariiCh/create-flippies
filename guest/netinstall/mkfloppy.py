"""Pads the netinstall program to a floppy image (spec 12.5, 11.1.5).

  mkfloppy.py <program.bin> <out.img>

The program is a flat binary that starts with its boot sector (0x55 0xAA at 510); the boot ROM
loads sector 0 to 0x1000 and the boot sector loads the rest. The image is 2880 sectors of 512
bytes (1,474,560 bytes), the game's only floppy size.
"""
import sys

FLOPPY_BYTES = 2880 * 512
MAX_PROGRAM = 96 * 1024


def main(program_path, out_path):
    data = open(program_path, "rb").read()
    if len(data) < 512 or data[510:512] != b"\x55\xaa":
        sys.exit("mkfloppy: the program does not start with a boot sector ending in 55 AA")
    if len(data) > MAX_PROGRAM:
        sys.exit("mkfloppy: the program is %d bytes, more than %d" % (len(data), MAX_PROGRAM))
    with open(out_path, "wb") as f:
        f.write(data + bytes(FLOPPY_BYTES - len(data)))
    print("%s: %d bytes of program, %d sectors" % (out_path, len(data), (len(data) + 511) // 512))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
