#!/bin/sh
# Builds the desktop's own packages (spec 12.8) into guest/apps/out/, all Architecture: all, so
# no m68k compiler is needed:
#   flippies-desktop_<v>_all.deb       the Start menu's "Apps" submenu from the installed
#                                      .desktop files (flippies-apps-menu, a JWM dynamic menu)
#   flippies-apps_<v>_all.deb          Flippies Apps, the package center for http://deb.flip/
#   flippies-sharedfiles_<v>_all.deb   Shared files, for http://localfilesys.com/
#   flippies-sysinfo_<v>_all.deb       System info
# The image build (guest/debian/build.sh) installs all four; the archive serves them too.
#
#   guest/apps/build.sh            run the tests (tests/, Python 3, sh and awk), then the packages
#   guest/apps/build.sh --test     only the tests
#
# Runs on the host when it has dpkg-deb, otherwise in the toolchain image (guest/docker/build.sh).
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
if ! command -v dpkg-deb > /dev/null && [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$HERE/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/apps/build.sh "$@"
fi

OUT=$HERE/out
PACKAGES="flippies-desktop flippies-apps flippies-sharedfiles flippies-sysinfo"
TEST_ONLY=
case "$1" in
    --test) TEST_ONLY=1 ;;
    "") ;;
    *) echo "usage: build.sh [--test]" >&2; exit 64 ;;
esac

# -B: no __pycache__ in the source tree (it would go into the packages)
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s "$HERE/tests" -t "$HERE/tests"
[ -n "$TEST_ONLY" ] && exit 0

mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
for p in $PACKAGES; do
    cp -R "$HERE/$p" "$WORK/$p"
    PKG=$WORK/$p
    find "$PKG" -name __pycache__ -prune -exec rm -rf {} + -o -name .DS_Store -exec rm -f {} +
    find "$PKG" -type d -exec chmod 755 {} +
    find "$PKG" -type f -exec chmod 644 {} +
    chmod 755 "$PKG"/usr/bin/* "$PKG"/DEBIAN/postinst "$PKG"/DEBIAN/prerm "$PKG"/DEBIAN/postrm 2>/dev/null || true
    # Installed-Size (KiB) for apt and Flippies Apps; dpkg-deb does not add it
    echo "Installed-Size: $(du -sk --exclude=DEBIAN "$PKG" | cut -f1)" >> "$PKG/DEBIAN/control"
    VERSION=$(sed -n 's/^Version: //p' "$PKG/DEBIAN/control")
    rm -f "$OUT/${p}"_*_all.deb
    dpkg-deb --root-owner-group -Zxz --build "$PKG" "$OUT/${p}_${VERSION}_all.deb" > /dev/null
    echo "${p}_${VERSION}_all.deb"
done
