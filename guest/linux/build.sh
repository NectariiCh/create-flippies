#!/bin/sh
# Builds the Create Flippies kernel (spec 12.6). Makes the tree with fetch.sh (the pinned release
# plus patches/), merges the fragments in config.d/ onto allnoconfig with the kernel's
# merge_config.sh (00-base.config first, then the rest in name order), builds vmlinux with the m68k
# cross compiler and copies it to guest/linux/out/ with System.map and the merged config.
#
#   guest/linux/build.sh          vmlinux, System.map, config into out/
#   guest/linux/build.sh --deb    the same, plus the Debian packages of make bindeb-pkg into out/
#
# Options for working on one part of the series in a tree of its own:
#   --tree DIR           the tree fetch.sh makes (fetch.sh --tree), objects in DIR-obj
#   --patches "A B"      only those files of patches/ (fetch.sh --patches)
#   --fragments "A B"    only those files of config.d/, in name order
#   --out DIR            copy the results to DIR (under /build or the repo) instead of out/
#
# Runs inside the toolchain image (guest/docker/build.sh); started anywhere else it runs itself
# there. Objects are kept in the /build volume next to the tree and thrown away whenever the tree
# was made again, so nothing stale survives a change of the patch series.
#
# FLIPPIES_LINUX_SRC=/build/... builds that tree instead (a git checkout to work on the series in;
# its objects go to <tree>-obj and are never thrown away).
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" env ${FLIPPIES_LINUX_SRC:+FLIPPIES_LINUX_SRC="$FLIPPIES_LINUX_SRC"} \
        /work/guest/linux/build.sh "$@"
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=$HERE/out
DEB=
FETCH_TREE=
FETCH_PATCHES=
FRAGMENTS=
while [ $# -gt 0 ]; do
    case "$1" in
        --deb) DEB=1; shift ;;
        --tree) FETCH_TREE=${2:?build: --tree needs a directory}; shift 2 ;;
        --patches) FETCH_PATCHES=${2?build: --patches needs a list}; shift 2 ;;
        --fragments) FRAGMENTS=${2?build: --fragments needs a list}; shift 2 ;;
        --out) OUT=${2:?build: --out needs a directory}; shift 2 ;;
        *) echo "usage: build.sh [--deb] [--tree DIR] [--patches LIST] [--fragments LIST] [--out DIR]" >&2
           exit 64 ;;
    esac
done

# The config fragments to merge: all of config.d/, or the ones --fragments names, in name order
CONFIGS=
for f in "$HERE"/config.d/*.config; do
    if [ -n "$FRAGMENTS" ]; then
        case " $FRAGMENTS " in (*" $(basename "$f") "*) ;; (*) continue ;; esac
    fi
    CONFIGS="$CONFIGS $f"
done
for f in $FRAGMENTS; do
    [ -f "$HERE/config.d/$f" ] || { echo "build: no fragment $f" >&2; exit 64; }
done

if [ -n "$FLIPPIES_LINUX_SRC" ]; then
    SRC=$FLIPPIES_LINUX_SRC
    OBJ=$SRC-obj
else
    SRC=$("$HERE/fetch.sh" ${FETCH_TREE:+--tree "$FETCH_TREE"} ${FETCH_PATCHES:+--patches "$FETCH_PATCHES"})
    OBJ=$SRC-obj
    # a tree made again (another patch series) starts from clean objects
    if [ "$(cat "$SRC/.flippies-series")" != "$(cat "$OBJ/.flippies-series" 2>/dev/null)" ]; then
        rm -rf "$OBJ"
    fi
fi
mkdir -p "$OBJ" "$OUT"
[ -f "$SRC/.flippies-series" ] && cp "$SRC/.flippies-series" "$OBJ/.flippies-series"

export ARCH=m68k CROSS_COMPILE=m68k-linux-gnu-
JOBS=$(nproc)

# GCC 14.2 (the image's m68k cross compiler) miscompiles lib/maple_tree.c with its straight-line
# strength reduction pass: in mas_wr_append the store to pivots[end + 1] lands on pivots[end], the
# tree gets corrupted and udev oopses in mas_split_final_node on every Debian boot. The kernel's
# own maple tree self-test fails the same way in qemu-system-m68k with an upstream virt kernel.
# The pass is turned off for the whole kernel, since other files may hit the same bug.
export KCFLAGS="-fno-tree-slsr${KCFLAGS:+ $KCFLAGS}"

# Every value a fragment asks for must reach the final .config: a misspelt or unreachable
# symbol stops the build instead of being dropped silently.
LOG=$OBJ/merge.log
(cd "$SRC" && scripts/kconfig/merge_config.sh -n -O "$OBJ" $CONFIGS) > "$LOG" 2>&1 || {
    cat "$LOG" >&2
    exit 1
}
if grep -q 'not in final .config' "$LOG"; then
    grep -A3 'not in final .config' "$LOG" >&2
    echo "build: config.d asks for values the kernel did not take" >&2
    exit 1
fi

if [ -n "$DEB" ]; then
    # bindeb-pkg links the kernel itself, so out/vmlinux is the packaged one
    rm -f "$OBJ"/../*.deb "$OUT"/*.deb
    make -C "$SRC" O="$OBJ" -j"$JOBS" bindeb-pkg
    cp "$OBJ"/../linux-image-*.deb "$OBJ"/../linux-libc-dev_*.deb "$OUT/"
else
    make -C "$SRC" O="$OBJ" -j"$JOBS" vmlinux
fi
cp "$OBJ/vmlinux" "$OBJ/System.map" "$OUT/"
cp "$OBJ/.config" "$OUT/config"

m68k-linux-gnu-size "$OUT/vmlinux" >&2
echo "build: $OUT/vmlinux" >&2
