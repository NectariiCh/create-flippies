#!/bin/sh
# Builds the boot chain (spec 12.7) into guest/boot/out/:
#   stage1.bin            the MBR's code part (at most 440 bytes)
#   stage2.bin            flipboot, for sectors 1..127 (at most 65,024 bytes)
#   flippies-boot_<v>_all.deb   flipboot-update and its kernel hook
#   testroot.ext4         with --testroot: guest/linux/out/initramfs.cpio as an ext4 root file
#                         system, for the boot chain's end-to-end test (FlipbootE2eTest)
#
# Runs inside the toolchain image (guest/docker/build.sh); started anywhere else it runs itself
# there. The disk image tool, mkdisk.py, needs only Python 3.
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/boot/build.sh "$@"
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=$HERE/out
TESTROOT=
case "$1" in
    --testroot) TESTROOT=1 ;;
    "") ;;
    *) echo "usage: build.sh [--testroot]" >&2; exit 64 ;;
esac
mkdir -p "$OUT"
CROSS=m68k-linux-gnu-
CFLAGS="-m68040 -Os -ffreestanding -fno-builtin -nostdlib -fno-pic -fno-pie -fno-stack-protector \
    -fno-asynchronous-unwind-tables -fno-tree-loop-distribute-patterns -Wall -Wextra -Werror"

${CROSS}gcc -m68040 -c "$HERE/stage1.S" -o "$OUT/stage1.o"
${CROSS}ld --no-warn-rwx-segments -T "$HERE/stage1.ld" -o "$OUT/stage1.elf" "$OUT/stage1.o"
${CROSS}objcopy -O binary "$OUT/stage1.elf" "$OUT/stage1.bin"

${CROSS}gcc $CFLAGS -c "$HERE/start.S" -o "$OUT/start.o"
${CROSS}gcc $CFLAGS -c "$HERE/flipboot.c" -o "$OUT/flipboot.o"
${CROSS}ld --no-warn-rwx-segments -T "$HERE/stage2.ld" -o "$OUT/stage2.elf" "$OUT/start.o" "$OUT/flipboot.o" \
    "$(${CROSS}gcc -m68040 -print-libgcc-file-name)"
${CROSS}objcopy -O binary "$OUT/stage2.elf" "$OUT/stage2.bin"

s1=$(stat -c %s "$OUT/stage1.bin")
s2=$(stat -c %s "$OUT/stage2.bin")
[ "$s1" -le 440 ] || { echo "build: stage1.bin is $s1 bytes, more than 440" >&2; exit 1; }
[ "$s2" -le 65024 ] || { echo "build: stage2.bin is $s2 bytes, more than 65024" >&2; exit 1; }
echo "stage1.bin $s1 bytes, stage2.bin $s2 bytes"

# The Debian package: arch all, a shell script, so no m68k userland compiler is needed.
PKG=/tmp/flippies-boot
rm -rf "$PKG"
cp -r "$HERE/flippies-boot" "$PKG"
chmod 755 "$PKG/usr/sbin/flipboot-update" "$PKG/etc/kernel/postinst.d/zz-flipboot"
chmod 644 "$PKG/etc/default/flipboot" "$PKG/DEBIAN/control" "$PKG/DEBIAN/conffiles"
VERSION=$(sed -n 's/^Version: //p' "$PKG/DEBIAN/control")
rm -f "$OUT"/flippies-boot_*_all.deb
dpkg-deb --root-owner-group -Zxz --build "$PKG" "$OUT/flippies-boot_${VERSION}_all.deb" > /dev/null
echo "flippies-boot_${VERSION}_all.deb"

if [ -n "$TESTROOT" ]; then
    CPIO=$HERE/../linux/out/initramfs.cpio
    [ -f "$CPIO" ] || { echo "build: $CPIO is not built (guest/linux/mkinitramfs.sh)" >&2; exit 1; }
    ROOT=/build/boot-testroot
    rm -rf "$ROOT"
    mkdir -p "$ROOT"
    (cd "$ROOT" && cpio -id --quiet < "$CPIO")
    cp "$HERE/../linux/initramfs/init" "$ROOT/init"
    chmod 755 "$ROOT/init"
    rm -f "$OUT/testroot.ext4"
    mke2fs -q -t ext4 -L testroot -E root_owner=0:0 -d "$ROOT" "$OUT/testroot.ext4" 16M
    rm -rf "$ROOT"
    echo "testroot.ext4 $(stat -c %s "$OUT/testroot.ext4") bytes"
fi
