#!/bin/sh
# Builds the netinstall floppy (spec 12.5) into guest/netinstall/out/:
#   netinstall.bin   the flat program, boot sector first (at most 96 KiB)
#   netinstall.img   a 1,474,560-byte floppy image (mkfloppy.py)
#
# Runs inside the toolchain image (guest/docker/build.sh); started anywhere else it runs itself
# there.
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/netinstall/build.sh "$@"
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=$HERE/out
mkdir -p "$OUT"
CROSS=m68k-linux-gnu-
CFLAGS="-std=c17 -m68040 -O2 -ffreestanding -fno-builtin -nostdlib -fno-pic -fno-pie -fno-stack-protector \
    -fno-asynchronous-unwind-tables -fno-tree-loop-distribute-patterns -fno-delete-null-pointer-checks \
    -Wall -Wextra -Werror"

${CROSS}gcc $CFLAGS -c "$HERE/start.S" -o "$OUT/start.o"
${CROSS}gcc $CFLAGS -c "$HERE/netinstall.c" -o "$OUT/netinstall.o"
${CROSS}ld --no-warn-rwx-segments -T "$HERE/link.ld" -o "$OUT/netinstall.elf" "$OUT/start.o" "$OUT/netinstall.o" \
    "$(${CROSS}gcc -m68040 -print-libgcc-file-name)"
${CROSS}objcopy -O binary "$OUT/netinstall.elf" "$OUT/netinstall.bin"
python3 "$HERE/mkfloppy.py" "$OUT/netinstall.bin" "$OUT/netinstall.img"
${CROSS}size "$OUT/netinstall.elf"
