#!/bin/sh
# Builds the step-one initramfs (spec 12.6, 12.10 step 3): Debian's static busybox for m68k from
# the debian-ports snapshot of spec 12.8 plus initramfs/init, as an uncompressed newc cpio in
# guest/linux/out/initramfs.cpio for the headless runner's --initrd.
#
#   guest/linux/mkinitramfs.sh
#   mkinitramfs.sh --net          adds /etc/udhcpc/default.script, so udhcpc configures eth0
#   mkinitramfs.sh --out FILE     writes FILE (under /build or the repo) instead of out/
#
# Runs inside the toolchain image (guest/docker/build.sh); started anywhere else it runs itself
# there. The archive is written by the kernel's own gen_init_cpio, built from the tree fetch.sh
# makes, so /dev/console is in it without root; every entry gets the snapshot's time, so the
# same inputs give the same bytes.
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/linux/mkinitramfs.sh "$@"
fi

# busybox-static 1:1.38.0-3 for m68k at snapshot 20261025T063552Z. The hash is the one in that
# snapshot's unstable/main/binary-m68k Packages index, whose own hash is in the snapshot's
# InRelease, signed by the Debian Ports 2026 key (checked with debian-ports-archive-keyring).
SNAPSHOT=https://snapshot.debian.org/archive/debian-ports/20261025T063552Z
SNAPSHOT_TIME=2026-10-25T06:35:52Z
DEB=busybox-static_1.38.0-3_m68k.deb
DEB_URL=$SNAPSHOT/pool-m68k/main/b/busybox/$DEB
DEB_SHA256=4fb7f78eb996019f2a484ec6b4ec5b5ccf2103961c18d678eb1091ff85ed9bc5

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${FLIPPIES_LINUX_WORK:-/build/linux}/initramfs
CPIO=$HERE/out/initramfs.cpio
NET=
while [ $# -gt 0 ]; do
    case "$1" in
        --net) NET=1; shift ;;
        --out) CPIO=${2:?mkinitramfs: --out needs a file}; shift 2 ;;
        *) echo "usage: mkinitramfs.sh [--net] [--out FILE]" >&2; exit 64 ;;
    esac
done
mkdir -p "$WORK" "$(dirname "$CPIO")"

if ! echo "$DEB_SHA256  $WORK/$DEB" | sha256sum -c --status 2>/dev/null; then
    echo "mkinitramfs: downloading $DEB_URL" >&2
    curl -fsSL --retry 3 -o "$WORK/$DEB.part" "$DEB_URL"
    if ! echo "$DEB_SHA256  $WORK/$DEB.part" | sha256sum -c --status; then
        echo "mkinitramfs: $DEB_URL does not match the pinned SHA-256 $DEB_SHA256; refused" >&2
        rm -f "$WORK/$DEB.part"
        exit 1
    fi
    mv "$WORK/$DEB.part" "$WORK/$DEB"
fi
rm -rf "$WORK/deb"
dpkg-deb -x "$WORK/$DEB" "$WORK/deb"

SRC=$("$HERE/fetch.sh")
cc -O2 -o "$WORK/gen_init_cpio" "$SRC/usr/gen_init_cpio.c"

cat > "$WORK/list" <<LIST
dir /dev 0755 0 0
nod /dev/console 0600 0 0 c 5 1
dir /bin 0755 0 0
dir /sbin 0755 0 0
dir /proc 0755 0 0
dir /sys 0755 0 0
dir /tmp 1777 0 0
dir /root 0700 0 0
file /bin/busybox $WORK/deb/usr/bin/busybox 0755 0 0
slink /bin/sh busybox 0777 0 0
file /init $HERE/initramfs/init 0755 0 0
LIST
# udhcpc's default script, busybox's own example from the same package
if [ -n "$NET" ]; then
    cat >> "$WORK/list" <<LIST
dir /etc 0755 0 0
dir /etc/udhcpc 0755 0 0
file /etc/udhcpc/default.script $WORK/deb/usr/share/doc/busybox-static/examples/udhcp/simple.script 0755 0 0
LIST
fi
"$WORK/gen_init_cpio" -t "$(date -u -d "$SNAPSHOT_TIME" +%s)" -o "$CPIO" "$WORK/list"
chmod 644 "$CPIO"
ls -l "$CPIO" >&2
