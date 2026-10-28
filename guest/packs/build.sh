#!/bin/sh
# Builds the content packs a server imports for the network modem's made-up internet (spec 12.4):
# zip files of the hosts they serve, outside the mod jar. mkpack.py has the format.
#
#   guest/packs/build.sh            both packs, after the tests (tests/, Python 3)
#   build.sh os                     only debian-os.flippack
#   build.sh apps                   only debian-apps.flippack
#   build.sh --test                 only the tests
#
# Output, guest/packs/out/, each with a .sha256 beside it (sha256sum's format):
#   debian-os.flippack      get.flip: the Debian image as deflated chunks (debian.fdi.z/, from
#                           guest/debian/build.sh --chunks) and index.txt with its line for the
#                           netinstall floppy's menu
#   debian-apps.flippack    deb.flip: the package archive of guest/apt/build.sh (signed indexes,
#                           the .debs, the archive's public key flippies-archive.gpg, index.html)
#
# Runs on the host (any Python 3), or in the toolchain image. The zips are deterministic (fixed
# times and modes, sorted entries), so the same inputs give the same SHA-256 with the same zlib.
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT=$HERE/out
# The debian-ports snapshot both packs come from (guest/debian/build.sh, guest/apt/build.sh)
SNAPSHOT=20261025T063552Z
CHUNKS=$REPO/guest/debian/out/chunks/debian.fdi.z
ARCHIVE=$REPO/guest/apt/out/archive

WHICH="os apps"
case "$1" in
    os|apps) WHICH=$1 ;;
    --test|"") ;;
    *) echo "usage: build.sh [os | apps | --test]" >&2; exit 64 ;;
esac

PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s "$HERE/tests" -t "$HERE/tests"
[ "$1" = --test ] && exit 0

mkdir -p "$OUT"
for w in $WHICH; do
    case "$w" in
    os)
        [ -f "$CHUNKS/size.txt" ] && [ -f "$CHUNKS/0.z" ] \
            || { echo "build: no $CHUNKS (guest/debian/build.sh --chunks)" >&2; exit 1; }
        INDEX=$(mktemp)
        {
            echo "# kind path size title (debian-os pack, guest/packs/build.sh)"
            python3 -B "$HERE/mkpack.py" index-line "$CHUNKS"
        } > "$INDEX"
        python3 -B "$HERE/mkpack.py" "$OUT/debian-os.flippack" --id debian-os --version "$SNAPSHOT" \
            --title "Debian GNU/Linux m68k desktop" \
            --add "get.flip/index.txt=$INDEX" --add "get.flip/debian.fdi.z=$CHUNKS" \
            || { rm -f "$INDEX"; exit 1; }
        rm -f "$INDEX"
        ;;
    apps)
        [ -f "$ARCHIVE/dists/unstable/InRelease" ] && [ -f "$ARCHIVE/flippies-archive.gpg" ] \
            || { echo "build: no signed archive in $ARCHIVE (guest/apt/build.sh)" >&2; exit 1; }
        python3 -B "$HERE/mkpack.py" "$OUT/debian-apps.flippack" --id debian-apps --version "$SNAPSHOT" \
            --title "Debian m68k programs and the Flippies desktop apps" \
            --add "deb.flip/=$ARCHIVE"
        ;;
    esac
done
