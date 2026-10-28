#!/bin/sh
# Builds the package archive the network modem serves at http://deb.flip/ (spec 12.4, 12.8): the
# programs of PACKAGES below with every package they need that a Debian disk of guest/debian
# does not already hold, taken from the debian-ports snapshot the image was built from, plus our
# own packages in local/ (flippies-video) and the desktop's packages in guest/apps/out/. Every
# installed image's /etc/apt/sources.list already reads
# "deb [check-valid-until=no] http://deb.flip/ unstable main".
#
#   guest/apt/build.sh                 out/archive/, which guest/packs/build.sh puts into the
#                                      debian-apps content pack (hosts/deb.flip/)
#   build.sh --image FILE              resolve against a disk image's installed packages instead
#                                      of oldest-image.status
#
# What a disk holds already: oldest-image.status is the dpkg status of the first image that was
# served (October 25, 2026: no python3, no desktop packages), cut to the fields apt resolves
# with. Images built since hold more (python3-tk and guest/apps), all from the same snapshot, so
# an archive that completes the oldest disk completes every later one too, and a disk installed
# from that first image can still install Flippies Apps with its Python and Tk.
#
# Output, guest/apt/out/:
#   archive/pool/..., pool-m68k/...    the .debs, at their debian-ports pool paths; local ones
#                                      under pool/local/
#   archive/dists/unstable/            Release, InRelease and Release.gpg, and
#                                      main/binary-m68k/Packages, Packages.gz and Packages.xz
#   archive/flippies-archive.gpg       the archive's public key (binary), for
#                                      /etc/apt/trusted.gpg.d/ of an image built before it existed
#   archive/index.html                 a page about the archive for a browser in the guest
#   signing/                           the signing key's GnuPG home: the private key. Made once and
#                                      kept, since the image trusts its public key. out/ is ignored
#                                      by git; delete signing/ only together with an image rebuild.
#
# Signing: apt in the image checks the archive's signature as usual. The archive is our own, so
# it is signed with our own Ed25519 key; guest/debian/build.sh puts the public key into the image's
# /etc/apt/trusted.gpg.d/ when this has run first, and on a disk installed before that one command
# adds it:
#   sudo wget -qO /etc/apt/trusted.gpg.d/flippies-archive.gpg http://deb.flip/flippies-archive.gpg
# Release is dated at the snapshot and signed with the clock faked to that time; Ed25519
# signatures are deterministic, so the same inputs and key give the same archive byte for byte.
#
# Runs inside the toolchain image (guest/docker/build.sh; no privileges: nothing m68k runs here).
# Started anywhere else it runs itself there. The .debs are cached in /build/apt/cache.
set -e

if [ ! -f /.dockerenv ] && [ -z "$FLIPPIES_IN_CONTAINER" ]; then
    REPO="$(cd "$(dirname "$0")/../.." && pwd)"
    exec "$REPO/guest/docker/build.sh" /work/guest/apt/build.sh "$@"
fi

# The image's userland (guest/debian/build.sh): debian-ports at this snapshot, unstable plus
# unreleased. Everything here comes from the same snapshot, so it matches what is installed.
SNAPSHOT=20261025T063552Z
SNAPSHOT_TIME=2026-10-25T06:35:52Z
SNAPSHOT_URL=https://snapshot.debian.org/archive/debian-ports/$SNAPSHOT
KEYRING=/usr/share/keyrings/debian-ports-archive-keyring.gpg

# What the archive offers, for a 25 MHz 68040 with 128 MiB, 640x480 in 256 colours and X with JWM.
# Recommends are off, as in the image. Not here, and why:
#   GTK 3 programs (galculator, mousepad, gpicview, pcmanfm): GTK 3 pulls dbus, dconf and at-spi,
#     and its dbus-user-session choice pulls systemd and openrc beside sysvinit; about 78 MiB
#     installed for the toolkit alone, and slow to start at 25 MHz. Worker (its own Xlib toolkit),
#     Motif (nedit, xpdf), FLTK (dillo), Tk and Athena (x11-apps) are used instead. pcmanfm is not
#     installable.
#   xfe: FOX 1.6 on big-endian m68k draws no text on its buttons, menus and headers and pink
#     frames at 256 colours; worker is the file manager instead.
#   mpv, mplayer, ffmpeg (ffplay), vlc, xine-ui: not installable on m68k at this snapshot
#     (libsdl2, libbluray and libavdevice are missing). GStreamer decodes Ogg Theora instead, but
#     its X output (ximagesink, so gst-play-1.0) cannot draw on the 8-bit PseudoColor display:
#     local/flippies-video shows GStreamer's frames through Tk.
#   man-db for manual pages: the image leaves manual pages out (dpkg path-exclude). x11-apps pulls
#     man-db in anyway.
#   featherpad (Qt 6, libicu76 missing), mupdf (libgumbo2 missing), tcc and leafpad (not built).
PACKAGES="
vim nedit
python3 python3-tk idle perl lua5.4 tk8.6 bc
gcc make libc6-dev
openjdk-17-jdk-headless
x11-apps worker feh dillo xpdf
gstreamer1.0-tools gstreamer1.0-plugins-base
htop mc unzip zip curl file tmux tree sqlite3 bzip2 git
bsdgames xbill xjump xgalaga xmahjongg ace-of-penguins xboard gnuchess
"
# Named so apt does not choose systemd for "systemd | systemd-standalone-tmpfiles" (xjump): the
# systemd package would replace the image's systemd-standalone-sysusers (spec 12.8).
STEER="systemd-standalone-tmpfiles"
# Never installed by this archive: a plan that holds one of these is a build error.
FORBIDDEN="systemd openrc systemd-sysv"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT=$HERE/out
WORK=${FLIPPIES_APT_WORK:-/build/apt}
IMAGE=
LOCAL=$REPO/guest/apps/out
while [ $# -gt 0 ]; do
    case "$1" in
        --image) IMAGE=${2:?build: --image needs a file}; shift 2 ;;
        *) echo "usage: build.sh [--image FILE]" >&2; exit 64 ;;
    esac
done
[ -z "$IMAGE" ] || [ -f "$IMAGE" ] || { echo "build: no disk image $IMAGE" >&2; exit 1; }

APTDIR=$WORK/apt
CACHE=$WORK/cache
mkdir -p "$OUT" "$CACHE" "$APTDIR/etc/apt/apt.conf.d" "$APTDIR/etc/apt/preferences.d" \
    "$APTDIR/etc/apt/sources.list.d" "$APTDIR/var/lib/apt/lists/partial" \
    "$APTDIR/var/cache/apt/archives/partial" "$APTDIR/var/lib/dpkg"

# ---- what a disk already holds ----

if [ -n "$IMAGE" ]; then
    # The root file system is partition 3 (guest/boot/mkdisk.py's layout); debugfs reads its dpkg
    # status in place.
    START=$(sfdisk -d "$IMAGE" | sed -n 's/.*start= *\([0-9]*\),.*type=83.*/\1/p' | head -n 1)
    [ -n "$START" ] || { echo "build: $IMAGE has no Linux partition (type 83)" >&2; exit 1; }
    debugfs -R "cat /var/lib/dpkg/status" "$IMAGE?offset=$(( START * 512 ))" 2>/dev/null > "$APTDIR/var/lib/dpkg/status"
    FROM=$IMAGE
else
    cp "$HERE/oldest-image.status" "$APTDIR/var/lib/dpkg/status"
    FROM=$HERE/oldest-image.status
fi
INSTALLED=$(grep -c '^Package: ' "$APTDIR/var/lib/dpkg/status" || true)
[ "$INSTALLED" -gt 0 ] || { echo "build: no dpkg status in $FROM" >&2; exit 1; }
echo "build: $INSTALLED packages installed in $FROM" >&2

# ---- the plan: what to add, resolved by apt against that status ----

cat > "$APTDIR/apt.conf" <<EOF
Dir "$APTDIR/";
Dir::State::status "$APTDIR/var/lib/dpkg/status";
APT::Architecture "m68k";
APT::Architectures { "m68k"; };
APT::Install-Recommends "false";
Acquire::Check-Valid-Until "false";
Acquire::Languages "none";
EOF
export APT_CONFIG=$APTDIR/apt.conf
cat > "$APTDIR/etc/apt/sources.list" <<EOF
deb [check-valid-until=no signed-by=$KEYRING] $SNAPSHOT_URL unstable main
deb [check-valid-until=no signed-by=$KEYRING] $SNAPSHOT_URL unreleased main
EOF
apt-get update -q >&2

# Our own packages in local/ (Architecture: all, no compiler needed), built with the snapshot's
# time so they come out the same each time
OWN=$WORK/own
rm -rf "$OWN"
mkdir -p "$OWN"
for dir in "$HERE"/local/*/; do
    [ -f "$dir/DEBIAN/control" ] || continue
    name=$(basename "$dir")
    cp -R "$dir" "$OWN/$name"
    find "$OWN/$name" -name __pycache__ -prune -exec rm -rf {} + -o -name .DS_Store -exec rm -f {} +
    find "$OWN/$name" -type d -exec chmod 755 {} +
    find "$OWN/$name" -type f -exec chmod 644 {} +
    chmod 755 "$OWN/$name"/usr/bin/*
    echo "Installed-Size: $(du -sk --exclude=DEBIAN "$OWN/$name" | cut -f1)" >> "$OWN/$name/DEBIAN/control"
    version=$(sed -n 's/^Version: //p' "$OWN/$name/DEBIAN/control")
    SOURCE_DATE_EPOCH=$(date -u -d "$SNAPSHOT_TIME" +%s) \
        dpkg-deb --root-owner-group -Zxz --build "$OWN/$name" "$OWN/${name}_${version}_all.deb" > /dev/null
done

# Local packages are resolved with the rest, so whatever they depend on is in the archive too.
set --
for deb in "$OWN"/*.deb "$LOCAL"/*.deb; do
    [ -f "$deb" ] && set -- "$@" "$deb"
done
[ $# -gt 0 ] && echo "build: local packages: $*" >&2

PLAN=$WORK/plan.txt
apt-get install -y -s -q $STEER $PACKAGES "$@" > "$PLAN" 2>&1 || { cat "$PLAN" >&2; exit 1; }
if grep -E '^(Remv|Inst [^ ]+ \[)' "$PLAN" >&2; then
    echo "build: the plan removes or upgrades installed packages (above)" >&2
    exit 1
fi
for p in $FORBIDDEN; do
    if grep -q "^Inst $p " "$PLAN"; then
        echo "build: the plan installs $p; name an alternative in STEER" >&2
        exit 1
    fi
done

# URIs and sizes of every .deb to fetch: 'URI' name size MD5Sum:hash
URIS=$WORK/uris.txt
# (local packages are listed with file: URIs; they are copied from guest/apps/out below)
apt-get install -y -qq --print-uris $STEER $PACKAGES "$@" | grep "^'http" | tr -d "'" > "$URIS"
echo "build: $(wc -l < "$URIS") packages from the snapshot" >&2

# ---- the .debs, cached by pool path ----

while read -r uri name size hash; do
    pool=${uri#"$SNAPSHOT_URL/"}
    case "$pool" in
        pool*/*) ;;
        *) echo "build: $uri is not under $SNAPSHOT_URL/pool" >&2; exit 1 ;;
    esac
    echo "$uri $pool $size"
done < "$URIS" > "$WORK/uris-quoted.txt"
# The URIs are percent-encoded ('+' as %2b); pool paths are the files' real names
python3 -c 'import sys, urllib.parse
for line in sys.stdin:
    uri, pool, size = line.split()
    print(uri, urllib.parse.unquote(pool), size)' < "$WORK/uris-quoted.txt" > "$WORK/uris-pool.txt"
# apt prints MD5 sums; the SHA256 of each file comes from the snapshot's signed indexes instead
cat "$APTDIR"/var/lib/apt/lists/*_Packages | awk '
    /^Filename: / { f = $2 }
    /^SHA256: / { h = $2 }
    /^$/ { if (f != "") print f, h; f = ""; h = "" }
    END { if (f != "") print f, h }' > "$WORK/sha256.txt"
awk 'NR == FNR { h[$1] = $2; next } { if (!($2 in h)) { print "build: no SHA256 for " $2 > "/dev/stderr"; exit 1 }
    print $1, $2, $3, h[$2] }' "$WORK/sha256.txt" "$WORK/uris-pool.txt" > "$WORK/fetch.txt"

# Fetch what the cache lacks, four at a time (snapshot.debian.org is slow per connection), then
# check every file against its SHA256.
: > "$WORK/curl.cfg"
while read -r uri pool size hash; do
    if [ ! -f "$CACHE/$pool" ]; then
        mkdir -p "$(dirname "$CACHE/$pool")"
        printf 'url = "%s"\noutput = "%s.part"\n' "$uri" "$CACHE/$pool" >> "$WORK/curl.cfg"
    fi
done < "$WORK/fetch.txt"
if [ -s "$WORK/curl.cfg" ]; then
    echo "build: fetching $(grep -c '^url' "$WORK/curl.cfg") packages" >&2
    curl -fsSL --retry 5 --retry-delay 3 --parallel --parallel-max 4 -K "$WORK/curl.cfg"
    while read -r uri pool size hash; do
        if [ -f "$CACHE/$pool.part" ]; then mv "$CACHE/$pool.part" "$CACHE/$pool"; fi
    done < "$WORK/fetch.txt"
fi
while read -r uri pool size hash; do
    if [ "$(sha256sum < "$CACHE/$pool" | cut -d' ' -f1)" != "$hash" ]; then
        rm -f "$CACHE/$pool"
        echo "build: $uri does not match its SHA256; removed from the cache, run again" >&2
        exit 1
    fi
done < "$WORK/fetch.txt"

# ---- the archive ----

NEW=$WORK/archive
rm -rf "$NEW"
mkdir -p "$NEW"
while read -r uri pool size hash; do
    mkdir -p "$NEW/$(dirname "$pool")"
    cp "$CACHE/$pool" "$NEW/$pool"
done < "$WORK/fetch.txt"
for deb in "$@"; do
    mkdir -p "$NEW/pool/local"
    cp "$deb" "$NEW/pool/local/"
done

# Every package in binary-m68k, the "all" ones too (Release lists no "all" architecture, so apt
# looks for no binary-all index), as debian-ports does.
BIN=$NEW/dists/unstable/main/binary-m68k
mkdir -p "$BIN"
# debian-ports keeps arch-specific builds in pool-m68k/ and the rest in pool/. SHA256 only: apt
# computes every hash an index lists while it downloads, and on a 25 MHz 68040 MD5 and SHA1 on top
# of SHA256 cost real download speed.
: > "$BIN/Packages"
for dir in pool pool-m68k; do
    if [ -d "$NEW/$dir" ]; then
        (cd "$NEW" && dpkg-scanpackages --multiversion --hash sha256 "$dir") >> "$BIN/Packages" 2> "$WORK/scan.log" \
            || { cat "$WORK/scan.log" >&2; exit 1; }
    fi
done
gzip -9n < "$BIN/Packages" > "$BIN/Packages.gz"
xz -9e -T1 < "$BIN/Packages" > "$BIN/Packages.xz"

# Release: apt needs SHA256 for every index it fetches; it fetches by name (no Acquire-By-Hash).
REL=$NEW/dists/unstable/Release
{
    echo "Origin: Flippies"
    echo "Label: Flippies deb.flip"
    echo "Suite: unstable"
    echo "Codename: sid"
    echo "Date: $(LC_ALL=C date -u -d "$SNAPSHOT_TIME" '+%a, %d %b %Y %H:%M:%S UTC')"
    echo "Architectures: m68k"
    echo "Components: main"
    echo "Description: Programs for the Create Flippies PC, from debian-ports $SNAPSHOT"
    echo "SHA256:"
    for f in main/binary-m68k/Packages main/binary-m68k/Packages.gz main/binary-m68k/Packages.xz; do
        printf ' %s %16d %s\n' "$(sha256sum < "$NEW/dists/unstable/$f" | cut -d' ' -f1)" \
            "$(stat -c %s "$NEW/dists/unstable/$f")" "$f"
    done
} > "$REL"

# The signing key, made once (see the top). Its creation time is the snapshot's, as is every
# signature's, so a guest clock at or after the snapshot accepts them.
EPOCH=$(date -u -d "$SNAPSHOT_TIME" +%s)
KEYHOME=$OUT/signing
# gpg works on a copy in the container's own file system: its agent's socket cannot live in the
# bind-mounted repo
GNUPG=/tmp/flippies-apt-gnupg
rm -rf "$GNUPG"
mkdir -m 700 "$GNUPG"
mkdir -p "$KEYHOME"
chmod 700 "$KEYHOME"
for f in pubring.kbx trustdb.gpg private-keys-v1.d; do
    [ -e "$KEYHOME/$f" ] && cp -R "$KEYHOME/$f" "$GNUPG/"
done
GPG="gpg --homedir $GNUPG --batch --quiet --no-greeting --pinentry-mode loopback --passphrase= --faked-system-time $EPOCH!"
if [ -z "$($GPG --list-secret-keys 2>/dev/null)" ]; then
    $GPG --quick-gen-key "Create Flippies deb.flip archive <deb@flippies.invalid>" ed25519 sign never
    rm -rf "$KEYHOME"
    mkdir -m 700 "$KEYHOME"
    cp -R "$GNUPG/pubring.kbx" "$GNUPG/trustdb.gpg" "$GNUPG/private-keys-v1.d" "$KEYHOME/"
    echo "build: made a new signing key in $KEYHOME; rebuild the image (guest/debian/build.sh) so it trusts it" >&2
fi
$GPG --export > "$NEW/flippies-archive.gpg"
$GPG --digest-algo SHA512 --clearsign -o "$NEW/dists/unstable/InRelease" "$REL"
$GPG --digest-algo SHA512 --armor --detach-sign -o "$NEW/dists/unstable/Release.gpg" "$REL"
# Checked against the exported public key alone, in an empty GnuPG home
CHECK=$WORK/gpg-check
rm -rf "$CHECK"
mkdir -m 700 "$CHECK"
{ gpg --homedir "$CHECK" --batch --import "$NEW/flippies-archive.gpg" \
    && gpg --homedir "$CHECK" --batch --verify "$NEW/dists/unstable/InRelease" \
    && gpg --homedir "$CHECK" --batch --verify "$NEW/dists/unstable/Release.gpg" "$REL"; } > "$WORK/verify.log" 2>&1 \
    || { cat "$WORK/verify.log" >&2; echo "build: the archive's signatures do not check" >&2; exit 1; }

# A page for a browser in the guest (dillo): what is here and how to use it
{
    echo '<html><head><title>deb.flip</title></head><body>'
    echo '<h1>deb.flip: programs for the Flippies PC</h1>'
    echo "<p>A Debian package archive for apt, from debian-ports m68k at snapshot $SNAPSHOT.</p>"
    echo '<p>In a terminal: <code>sudo apt update</code>, then for example <code>sudo apt install vim python3</code>.</p>'
    echo '<p>A disk installed before this archive existed needs its key once:<br>'
    echo '<code>sudo wget -qO /etc/apt/trusted.gpg.d/flippies-archive.gpg http://deb.flip/flippies-archive.gpg</code></p>'
    echo '<p>Video: <code>sudo apt install flippies-video</code>, then <code>flippies-video FILE.ogv</code> plays'
    echo 'Ogg Theora, without sound, scaled to 160x120 (<code>-s 320x240</code> for larger, and slower).</p>'
    echo '<p>Java on a disk installed before this archive existed: run <code>sudo mkdir -p /usr/share/man/man1</code> first.'
    echo 'Java is the Zero VM (an interpreter): <code>java -version</code> takes about 20 seconds, a hello world'
    echo 'about 45, and <code>javac</code> about 10 minutes for one small file.</p>'
    echo '<p>The disk has room for some of these at a time, not all: the whole list needs about 750 MB.</p>'
    echo '<p>The desktop and Flippies Apps on a disk installed before they came with the image:<br>'
    echo '<code>sudo apt update && sudo apt install -y flippies-desktop flippies-apps</code></p>'
    echo '<h2>Programs</h2><ul>'
    for p in $PACKAGES flippies-video; do
        d=$(awk -v p="$p" '/^Package: /{n=$2} /^Description: /{if (n==p) {sub(/^Description: /, ""); print; exit}}' "$BIN/Packages")
        echo "<li><b>$p</b>: $d</li>"
    done
    echo '</ul></body></html>'
} > "$NEW/index.html"

rm -rf "$OUT/archive"
cp -R "$NEW" "$OUT/archive"
COUNT=$(grep -c '^Package: ' "$BIN/Packages")
echo "build: $OUT/archive: $COUNT packages, $(du -sm "$OUT/archive" | cut -f1) MB" >&2
