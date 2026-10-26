#!/bin/sh
# Builds the guest toolchain image (spec 12.9) and runs one command in it with the repo mounted at
# /work. The command runs in the directory you called this from, when that is inside the repo.
# A named volume is mounted at /build: a case-sensitive work area that outlives the container, for
# unpacked kernel trees and other large intermediates. Only inputs and results go in the repo.
#
#   guest/docker/build.sh                          an interactive shell
#   guest/docker/build.sh make -C guest/foo        any command
#   guest/docker/build.sh --privileged <command>   for mmdebstrap and the m68k binfmt registration
#   guest/docker/build.sh --image-only             build the image and stop
#
# The image is rebuilt from the cache on every call, which costs a second when nothing changed.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
IMAGE=${FLIPPIES_GUEST_IMAGE:-create-flippies-guest:latest}
VOLUME=${FLIPPIES_GUEST_VOLUME:-create-flippies-guest-build}

PRIVILEGED=
IMAGE_ONLY=
case "$1" in
    --privileged) PRIVILEGED=1; shift ;;
    --image-only) IMAGE_ONLY=1; shift ;;
esac

docker build -q -t "$IMAGE" "$HERE" > /dev/null
[ -n "$IMAGE_ONLY" ] && exit 0

# Same relative directory inside the container, or the repo root when called from outside it.
case "$PWD/" in
    "$REPO"/*) WORKDIR="/work${PWD#"$REPO"}" ;;
    *) WORKDIR=/work ;;
esac

set -- ${1+"$@"}
[ $# -eq 0 ] && set -- bash

OPTS="--rm -i"
[ -t 0 ] && [ -t 1 ] && OPTS="$OPTS -t"
if [ -n "$PRIVILEGED" ]; then
    OPTS="$OPTS --privileged"
elif [ "$(uname -s)" = Linux ]; then
    # Docker Desktop maps file owners on its own; a Linux host needs the caller's ids so the build
    # does not leave root-owned files in the tree. mmdebstrap needs root, hence not with --privileged.
    OPTS="$OPTS --user $(id -u):$(id -g) -e HOME=/tmp"
fi

# OPTS is split on purpose; the repo path may hold spaces, so it is passed quoted.
exec docker run $OPTS -v "$REPO:/work" -v "$VOLUME:/build" -w "$WORKDIR" "$IMAGE" "$@"
