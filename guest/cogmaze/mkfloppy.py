"""Floppy tools for Cog Maze.

  mkfloppy.py <program.bin> <out.img>
      Pads the flat program (boot sector first) to a 1,474,560-byte floppy image.

  mkfloppy.py install <image.img> <world dir> [disk uuid]
      Writes the image into a world's disk store as a floppy the PC can read
      (<world>/create_flippies/disks/<uuid>/<chunk>.bin, zlib-deflated 64 KiB
      chunks; an all-zero chunk gets no file) and prints the /give command for
      the matching floppy item. Run it with the world closed.
"""
import os
import sys
import uuid
import zlib

FLOPPY_BYTES = 2880 * 512
CHUNK = 64 * 1024


def make_image(program_path, out_path):
    data = open(program_path, "rb").read()
    if len(data) < 512 or data[510:512] != b"\x55\xaa":
        sys.exit("program does not start with a boot sector ending in 55 AA")
    # the last sector holds the high score
    if len(data) > FLOPPY_BYTES - 512:
        sys.exit("program is %d bytes, too big for the floppy" % len(data))
    open(out_path, "wb").write(data + bytes(FLOPPY_BYTES - len(data)))
    print("%s: %d bytes of program, %d sectors" % (out_path, len(data), (len(data) + 511) // 512))


def uuid_int_array(u):
    # Minecraft's NbtUtils.createUUID: four signed ints, most significant first
    b = u.int.to_bytes(16, "big")
    ints = [int.from_bytes(b[i:i + 4], "big", signed=True) for i in range(0, 16, 4)]
    return "[I;%s]" % ",".join(str(i) for i in ints)


def install(image_path, world_dir, disk_id=None):
    data = open(image_path, "rb").read()
    if len(data) != FLOPPY_BYTES:
        sys.exit("not a 1.44 MB image")
    u = uuid.UUID(disk_id) if disk_id else uuid.uuid4()
    root = os.path.join(world_dir, "create_flippies", "disks", str(u))
    os.makedirs(root, exist_ok=True)
    for name in os.listdir(root):
        if name.endswith(".bin"):
            os.remove(os.path.join(root, name))
    written = 0
    for n in range(0, (len(data) + CHUNK - 1) // CHUNK):
        chunk = data[n * CHUNK:(n + 1) * CHUNK]
        if not any(chunk):
            continue
        open(os.path.join(root, "%d.bin" % n), "wb").write(zlib.compress(chunk, 1))
        written += 1
    print("disk %s: %d chunk files in %s" % (u, written, root))
    print("in game: /give @p create_flippies:floppy_disk{DiskId:%s,display:{Name:'{\"text\":\"Cog Maze\"}'}}"
          % uuid_int_array(u))


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "install":
        install(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
    elif len(sys.argv) == 3:
        make_image(sys.argv[1], sys.argv[2])
    else:
        sys.exit(__doc__)
