#!/bin/sh
# Makes the kernel tree the Linux fork builds from (spec 12.6): the pinned upstream longterm
# release plus our patch series. Downloads the tarball into the toolchain container's /build
# volume, refuses it unless its SHA-256 matches the pinned one, unpacks it under /build and applies
# guest/linux/patches/*.patch in order.
#
#   guest/linux/fetch.sh            prints the tree's path when done
#   fetch.sh --tree DIR             makes the tree at DIR (under /build) instead of the default
#   fetch.sh --patches "A B"        applies only the named files of patches/, in name order
#
# Runs inside the toolchain image (guest/docker/build.sh); started anywhere else it runs itself
# there. The tree is never unpacked in the repo: on a Mac the bind mount is case-insensitive and
# the tarball has paths that differ only in case.
#
# The tree is always exactly upstream plus the current series: a stamp in it records the series it
# was made with, and when the series has changed since, the tree is unpacked again from scratch.
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/linux/fetch.sh "$@"
fi

VERSION=6.18.58
SHA256=069b19f9032c4dac8149ae4b6520c35debca0f42a9f6d52ee55ba6ca9b818013
URL=https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-$VERSION.tar.xz

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${FLIPPIES_LINUX_WORK:-/build/linux}
TARBALL=$WORK/linux-$VERSION.tar.xz
TREE=$WORK/linux-$VERSION
ONLY=

while [ $# -gt 0 ]; do
    case "$1" in
        --tree) TREE=${2:?fetch: --tree needs a directory}; shift 2 ;;
        --patches) ONLY=${2?fetch: --patches needs a list}; shift 2 ;;
        *) echo "usage: fetch.sh [--tree DIR] [--patches \"NAME...\"]" >&2; exit 64 ;;
    esac
done
STAMP=$TREE/.flippies-series

# The patch files to apply: all of patches/, or the ones --patches names, in name order
PATCHES=$(cd "$HERE/patches" && for p in *.patch; do
    [ -e "$p" ] || continue
    if [ -n "$ONLY" ]; then
        case " $ONLY " in (*" $p "*) ;; (*) continue ;; esac
    fi
    echo "$p"
done)
for p in $ONLY; do
    [ -f "$HERE/patches/$p" ] || { echo "fetch: no patch $p" >&2; exit 64; }
done

mkdir -p "$WORK"

if ! echo "$SHA256  $TARBALL" | sha256sum -c --status 2>/dev/null; then
    echo "fetch: downloading $URL" >&2
    curl -fsSL --retry 3 -o "$TARBALL.part" "$URL"
    if ! echo "$SHA256  $TARBALL.part" | sha256sum -c --status; then
        echo "fetch: $URL does not match the pinned SHA-256 $SHA256; refused" >&2
        rm -f "$TARBALL.part"
        exit 1
    fi
    mv "$TARBALL.part" "$TARBALL"
fi

# The series the tree must carry: every patch's name and contents, in order.
SERIES=$(cd "$HERE/patches" && for p in $PATCHES; do
    sha256sum "$p"
done)
SERIES_SUM=$(printf '%s\n' "$VERSION" "$SERIES" | sha256sum | cut -d' ' -f1)

if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$SERIES_SUM" ]; then
    echo "$TREE"
    exit 0
fi

echo "fetch: unpacking linux-$VERSION into $TREE" >&2
rm -rf "$TREE" "$TREE.new"
mkdir "$TREE.new"
tar -xJf "$TARBALL" -C "$TREE.new" --strip-components=1
for p in $PATCHES; do
    echo "fetch: applying $p" >&2
    (cd "$TREE.new" && git apply --whitespace=nowarn "$HERE/patches/$p")
done
echo "$SERIES_SUM" > "$TREE.new/.flippies-series"
mv "$TREE.new" "$TREE"
echo "$TREE"
