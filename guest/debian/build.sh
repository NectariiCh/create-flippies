#!/bin/sh
# Builds the Debian desktop image of spec 12.8: a debian-ports m68k userland from a pinned snapshot
# with sysvinit, Xorg on the framebuffer, JWM and xterm, written as a raw disk image that boots
# from the boot ROM through flipboot (spec 12.7): guest/boot's mkdisk.py writes stage 1, stage 2,
# the partition table and the boot area with the installed kernel in slot A. Then the swap and
# the root file system go in, and mkfdi.py makes the .fdi (spec 12.4.4).
#
# Needs, built first: guest/linux/build.sh --deb (the kernel package) and guest/boot/build.sh
# (stage1.bin, stage2.bin and the flippies-boot package, which the image installs so that
# flipboot-update runs from the kernel's postinst hook), and guest/apps/build.sh (the desktop's
# own packages: the Start menu's Apps submenu, Flippies Apps, Shared files and System info).
#
#   guest/debian/build.sh                  out/debian.img (1 GiB) and out/debian.fdi
#   build.sh --size 768M                   another total disk size (K, M or G; 512M to 1G)
#   build.sh --kernel-deb FILE             the kernel package to install (default the one in
#                                          guest/linux/out/)
#   build.sh --cmdline TEXT                slot A's kernel command line (default below)
#   build.sh --chunks                      also the deflated chunk files for the jar (spec 12.4.3)
#                                          in out/chunks/debian.fdi.z/
#   build.sh --rebootstrap                 run mmdebstrap again even if its cached result fits
#   build.sh --out DIR                     write the results to DIR (under /build or the repo)
#
# Runs inside the toolchain image (guest/docker/build.sh --privileged: mmdebstrap runs the m68k
# maintainer scripts through qemu and needs the binfmt registration); started anywhere else it runs
# itself there. Nothing needs a loop mount: the root file system is filled by mkfs.ext4 -d and
# copied into the image with dd.
#
# Two stages. The bootstrap (mmdebstrap, three to four minutes under qemu) is cached in
# /build/debian as a tarball named by a hash of everything it depends on. The second stage unpacks
# it, lays the files of rootfs/ over it, edits inittab, rebuilds the font caches and writes the
# images; it takes one to two minutes.
#
# Partition layout (spec 12.7, written by guest/boot/mkdisk.py), in 512-byte sectors:
#   0           the MBR: stage 1, the disk signature "FLPC", the partition table
#   1..127      stage 2 (flipboot)
#   2048        partition 1, type 0xDA, 32 MiB: the boot area, the kernel in slot A
#   67584       partition 2, type 0x82, 128 MiB: swap (mkswap'd)
#   329728      partition 3, type 0x83, the rest: the root file system, ext4
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" --privileged /work/guest/debian/build.sh "$@"
fi

# The pinned userland (spec 12.8): debian-ports at this snapshot, unstable plus unreleased.
SNAPSHOT=20261025T063552Z
SNAPSHOT_TIME=2026-10-25T06:35:52Z
SNAPSHOT_URL=https://snapshot.debian.org/archive/debian-ports/$SNAPSHOT
KEYRING=/usr/share/keyrings/debian-ports-archive-keyring.gpg

# What the image holds beyond Debian's required set. Recommends are off. systemd-standalone-sysusers
# keeps apt from pulling the full systemd in for udev (spec 12.8). The DHCP client is busybox's
# udhcpc, which ifupdown uses on its own: dhcpcd 10.5.2 (dhcpcd-base) crashes on m68k at this
# snapshot (SIGSEGV in rb_tree_insert_node without seccomp, SIGSYS from its own filter with it;
# the same in qemu-system-m68k). e2fsprogs gives fsck.ext4 and mkfs for the user. python3 and
# python3-tk run our own desktop programs (guest/apps), so the package center that installs the
# rest is there without a terminal.
PACKAGES="
sysvinit-core systemd-standalone-sysusers udev
ifupdown udhcpc netbase iputils-ping wget
xserver-xorg-core xserver-xorg-video-fbdev xserver-xorg-input-evdev xinit xauth
jwm xterm xfonts-base x11-xserver-utils x11-utils
e2fsprogs sudo nano less procps debian-ports-archive-keyring
python3 python3-tk
"

# Slot A's kernel command line. "quiet" keeps the boot log off the serial console, which holds
# interrupts off about 7 ms per line (spec 12.15.3).
CMDLINE="root=/dev/fhda3 rw console=tty0 console=ttyS0,115200 quiet"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BOOT=$REPO/guest/boot
OUT=$HERE/out
WORK=${FLIPPIES_DEBIAN_WORK:-/build/debian}
SIZE=1G
KDEB=
CHUNKS=
REBOOTSTRAP=
while [ $# -gt 0 ]; do
    case "$1" in
        --size) SIZE=${2:?build: --size needs a size}; shift 2 ;;
        --kernel-deb) KDEB=${2:?build: --kernel-deb needs a file}; shift 2 ;;
        --cmdline) CMDLINE=${2:?build: --cmdline needs a value}; shift 2 ;;
        --chunks) CHUNKS=1; shift ;;
        --rebootstrap) REBOOTSTRAP=1; shift ;;
        --out) OUT=${2:?build: --out needs a directory}; shift 2 ;;
        *) echo "usage: build.sh [--size SIZE] [--kernel-deb FILE] [--cmdline TEXT] [--chunks] [--rebootstrap] [--out DIR]" >&2
           exit 64 ;;
    esac
done

if [ -z "$KDEB" ]; then
    set -- "$REPO"/guest/linux/out/linux-image-*.deb
    [ $# -eq 1 ] && [ -f "$1" ] || { echo "build: need exactly one guest/linux/out/linux-image-*.deb (guest/linux/build.sh --deb)" >&2; exit 1; }
    KDEB=$1
fi
[ -f "$KDEB" ] || { echo "build: no kernel package $KDEB" >&2; exit 1; }
set -- "$BOOT"/out/flippies-boot_*_all.deb
[ $# -eq 1 ] && [ -f "$1" ] && [ -f "$BOOT/out/stage1.bin" ] && [ -f "$BOOT/out/stage2.bin" ] \
    || { echo "build: need guest/boot/out/stage1.bin, stage2.bin and one flippies-boot_*_all.deb (guest/boot/build.sh)" >&2; exit 1; }
BOOTDEB=$1
# The desktop's own packages, installed in stage 2 (so changing them needs no new bootstrap)
APPDEBS=
for p in flippies-desktop flippies-apps flippies-sharedfiles flippies-sysinfo; do
    set -- "$REPO"/guest/apps/out/${p}_*_all.deb
    [ $# -eq 1 ] && [ -f "$1" ] || { echo "build: need one guest/apps/out/${p}_*_all.deb (guest/apps/build.sh)" >&2; exit 1; }
    APPDEBS="$APPDEBS $1"
done

# Sizes in sectors
case "$SIZE" in
    *[Kk]) BYTES=$(( ${SIZE%?} * 1024 )) ;;
    *[Mm]) BYTES=$(( ${SIZE%?} * 1024 * 1024 )) ;;
    *[Gg]) BYTES=$(( ${SIZE%?} * 1024 * 1024 * 1024 )) ;;
    *[!0-9]*|'') echo "build: --size must be a number with K, M or G: $SIZE" >&2; exit 64 ;;
    *) BYTES=$SIZE ;;
esac
TOTAL=$(( BYTES / 512 ))
# mkdisk.py's layout; checked against the partition table it writes, below
P1_START=2048; P1_SIZE=65536
P2_START=$(( P1_START + P1_SIZE )); P2_SIZE=262144
P3_START=$(( P2_START + P2_SIZE )); P3_SIZE=$(( TOTAL - P3_START ))
# Partition 3 starts at 161 MiB and the root file system needs about 280 MiB (October 2026), so
# 512M (351 MiB for the root) is the floor; mkfs.ext4 below still says when the files do not fit.
MIN_BYTES=$(( 512 * 1024 * 1024 ))
if [ $(( BYTES % 512 )) -ne 0 ] || [ "$BYTES" -lt "$MIN_BYTES" ] || [ "$BYTES" -gt $(( 1024 * 1024 * 1024 )) ]; then
    echo "build: --size must be whole sectors, at least 512M and at most 1G (the PC's largest HDD): $SIZE" >&2
    exit 64
fi

mkdir -p "$WORK" "$OUT"

# ---- stage 1: the bootstrap, cached by its inputs ----

KEY=$( {
    echo "$SNAPSHOT"
    echo $PACKAGES
    sha256sum < "$KDEB"
    sha256sum < "$BOOTDEB"
    sed -n '/^# ---- stage 1/,/^# ---- stage 2/p' "$0"
} | sha256sum | cut -c1-16)
BASE=$WORK/base-$KEY.tar

if [ -n "$REBOOTSTRAP" ] || [ ! -f "$BASE" ]; then
    rm -f "$WORK"/base-*.tar
    cp "$KDEB" "$WORK/kernel.deb"
    cp "$BOOTDEB" "$WORK/flippies-boot.deb"
    echo "build: mmdebstrap from debian-ports $SNAPSHOT" >&2
    # Documentation, manual pages and translations are left out (dpkg path-exclude, which stays in
    # the image for later installs); copyright files are kept.
    mmdebstrap --mode=root --arch=m68k --variant=minbase --format=tar \
        --keyring="$KEYRING" \
        --aptopt='APT::Install-Recommends "false"' \
        --aptopt='Acquire::Check-Valid-Until "false"' \
        --dpkgopt='path-exclude=/usr/share/man/*' \
        --dpkgopt='path-exclude=/usr/share/info/*' \
        --dpkgopt='path-exclude=/usr/share/locale/*' \
        --dpkgopt='path-include=/usr/share/locale/locale.alias' \
        --dpkgopt='path-exclude=/usr/share/doc/*' \
        --dpkgopt='path-include=/usr/share/doc/*/copyright' \
        --dpkgopt='path-exclude=/usr/share/lintian/*' \
        --include="$(echo $PACKAGES | tr ' ' ',')" \
        --customize-hook='copy-in "'"$WORK/flippies-boot.deb"'" "'"$WORK/kernel.deb"'" /tmp' \
        --customize-hook='chroot "$1" dpkg -i /tmp/flippies-boot.deb' \
        --customize-hook='chroot "$1" dpkg -i /tmp/kernel.deb' \
        --customize-hook='rm -f "$1/tmp/kernel.deb" "$1/tmp/flippies-boot.deb"' \
        --customize-hook='chroot "$1" useradd -m -u 1000 -s /bin/bash -c "Flippies user" -G video,input,tty,dialout,sudo,audio,plugdev flip' \
        --customize-hook='chroot "$1" passwd -d flip' \
        unstable "$BASE.part" \
        "deb [check-valid-until=no] $SNAPSHOT_URL unstable main" \
        "deb [check-valid-until=no] $SNAPSHOT_URL unreleased main"
    mv "$BASE.part" "$BASE"
fi

# ---- stage 2: configuration and the images ----

ROOT=$WORK/root
rm -rf "$ROOT"
mkdir "$ROOT"
tar -C "$ROOT" --numeric-owner -xpf "$BASE"

# Our files over the bootstrap: root's, except the desktop user's home
cp -R "$HERE/rootfs/." "$ROOT/"
chown -R 0:0 "$ROOT/etc/hostname" "$ROOT/etc/hosts" "$ROOT/etc/fstab" "$ROOT/etc/network" \
    "$ROOT/etc/apt/sources.list" "$ROOT/etc/apt/apt.conf.d/50flippies" "$ROOT/etc/X11/xorg.conf.d" \
    "$ROOT/etc/sudoers.d" "$ROOT/etc/sysctl.d/50-ping.conf"
chmod 0440 "$ROOT/etc/sudoers.d/flip"
chown -R 1000:1000 "$ROOT/home/flip"
chmod 0755 "$ROOT/home/flip/.xinitrc"

# The key of the deb.flip package archive (guest/apt/build.sh signs it), so apt trusts the archive
# from the first boot. Without it the image still builds; apt then needs the key added by hand
# (the command is in guest/apt/build.sh).
APTKEY=$REPO/guest/apt/out/archive/flippies-archive.gpg
if [ -f "$APTKEY" ]; then
    install -m 0644 -o 0 -g 0 "$APTKEY" "$ROOT/etc/apt/trusted.gpg.d/flippies-archive.gpg"
else
    echo "build: no $APTKEY (guest/apt/build.sh); apt in this image will not trust deb.flip" >&2
fi

# inittab: tty1 logs the desktop user in (its .profile starts X), tty2 a plain login, and a login
# on COM1 (spec 12.11's "login:" on ttyS0). The other consoles are not started.
sed -i \
    -e 's|^1:2345:respawn:.*|1:2345:respawn:/sbin/agetty --autologin flip --noclear tty1 linux|' \
    -e '/^[3-6]:23:respawn:/d' \
    "$ROOT/etc/inittab"
echo 'T0:23:respawn:/sbin/agetty -L 115200 ttyS0 vt100' >> "$ROOT/etc/inittab"
grep -q '^1:2345:respawn:/sbin/agetty --autologin flip' "$ROOT/etc/inittab" \
    || { echo "build: inittab has no tty1 line to replace" >&2; exit 1; }

# The desktop's own packages (guest/apps): their postinst scripts compile the Python modules and
# make the Apps menu's cached copy (m68k python3 and sh through qemu). Their dependencies are all
# in the bootstrap.
mkdir -p "$ROOT/tmp/apps"
cp $APPDEBS "$ROOT/tmp/apps/"
chroot "$ROOT" sh -c 'dpkg -i /tmp/apps/*.deb'
rm -rf "$ROOT/tmp/apps"

# fontconfig rejects bitmap fonts by default; JWM draws with Xft, and the only fonts here are the
# bitmap ones of xfonts-base (fixed), which also look best at 640x480 in 256 colours.
rm -f "$ROOT"/etc/fonts/conf.d/70-no-bitmaps*.conf

# fontconfig's caches are only used while each font folder's mtime matches the one recorded in
# the cache, to the nanosecond. mkfs.ext4 -d keeps whole seconds only, so without this every
# program that opens a font (JWM, xterm with Xft) rescans all fonts, which takes minutes at
# 25 MHz. Round the folders' times to whole seconds, then build the caches again (m68k fc-cache
# through qemu).
find "$ROOT/usr/share/fonts" -type d | while read -r d; do
    touch -m -d "@$(stat -c %Y "$d")" "$d"
done
chroot "$ROOT" /usr/bin/fc-cache -s -f

# The bootstrap's sources point at the snapshot; the image's at deb.flip (rootfs/). Clean up what
# mmdebstrap and the build host left.
rm -f "$ROOT/etc/apt/sources.list.d/"* "$ROOT/etc/resolv.conf" "$ROOT/etc/machine-id"
: > "$ROOT/etc/resolv.conf"
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/var/cache/apt/"*.bin "$ROOT/var/log/"*.log

# ---- the disk image ----

IMG=$WORK/debian.img
rm -f "$IMG" "$WORK/swap.img" "$WORK/root.img"

# The boot chain: stage 1 and 2, the partition table and the boot area, with the kernel the
# package installed (/boot/vmlinuz-*, byte for byte the build's vmlinux) in slot A. Slot B stays
# empty, so a boot has no Esc prompt until the first kernel upgrade (the postinst hook) fills it.
set -- "$ROOT"/boot/vmlinuz-*
[ $# -eq 1 ] && [ -f "$1" ] || { echo "build: need exactly one /boot/vmlinuz-* in the image" >&2; exit 1; }
python3 "$BOOT/mkdisk.py" create "$IMG" --size "$BYTES" --stage1 "$BOOT/out/stage1.bin" \
    --stage2 "$BOOT/out/stage2.bin" --kernel-a "$1" --cmdline-a "$CMDLINE" >&2
TABLE=$(sfdisk -d "$IMG")
for want in "start= *$P1_START, size= *$P1_SIZE, type=da, bootable" "start= *$P2_START, size= *$P2_SIZE, type=82" \
        "start= *$P3_START, size= *$P3_SIZE, type=83"; do
    echo "$TABLE" | grep -q "$want" \
        || { echo "$TABLE" >&2; echo "build: mkdisk.py's partitions are not where this script expects them" >&2; exit 1; }
done

truncate -s $(( P2_SIZE * 512 )) "$WORK/swap.img"
mkswap -q -L swap -U 464c4950-0000-4000-8000-000000000002 "$WORK/swap.img"
dd if="$WORK/swap.img" of="$IMG" bs=4096 count=1 oflag=seek_bytes seek=$(( P2_START * 512 )) conv=notrunc status=none

truncate -s $(( P3_SIZE * 512 )) "$WORK/root.img"
if ! E2FSPROGS_FAKE_TIME=$(date -u -d "$SNAPSHOT_TIME" +%s) \
    mkfs.ext4 -q -F -L root -U 464c4950-0000-4000-8000-000000000003 \
        -E hash_seed=464c4950-0000-4000-8000-000000000004,lazy_itable_init=0 \
        -m 1 -J size=16 -d "$ROOT" "$WORK/root.img"; then
    echo "build: the root file system ($(du -sm "$ROOT" | cut -f1) MiB of files) does not fit partition 3" \
        "($(( P3_SIZE / 2048 )) MiB); give a larger --size" >&2
    exit 1
fi
dd if="$WORK/root.img" of="$IMG" bs=1M oflag=seek_bytes seek=$(( P3_START * 512 )) conv=notrunc,sparse status=none
rm -f "$WORK/swap.img"

# Report: used blocks of the root file system and the package count
BLOCKS=$(dumpe2fs -h "$WORK/root.img" 2>/dev/null | sed -n 's/^Block count: *//p')
FREE=$(dumpe2fs -h "$WORK/root.img" 2>/dev/null | sed -n 's/^Free blocks: *//p')
BSIZE=$(dumpe2fs -h "$WORK/root.img" 2>/dev/null | sed -n 's/^Block size: *//p')
rm -f "$WORK/root.img"
python3 "$BOOT/mkdisk.py" show "$IMG" >&2
NPKG=$(grep -c '^Package: ' "$ROOT/var/lib/dpkg/status")
echo "build: root file system $(( (BLOCKS - FREE) * BSIZE / 1048576 )) MiB used of $(( BLOCKS * BSIZE / 1048576 )) MiB; $NPKG packages" >&2

# ---- the .fdi ----

python3 "$HERE/mkfdi.py" --title "Debian GNU/Linux m68k desktop ($SNAPSHOT)" \
    ${CHUNKS:+--chunks "$WORK/chunks"} "$IMG" "$WORK/debian.fdi"

cp --sparse=always "$IMG" "$OUT/debian.img"
cp "$WORK/debian.fdi" "$OUT/debian.fdi"
if [ -n "$CHUNKS" ]; then
    rm -rf "$OUT/chunks"
    cp -R "$WORK/chunks" "$OUT/chunks"
fi
echo "build: $OUT/debian.img, $OUT/debian.fdi" >&2
