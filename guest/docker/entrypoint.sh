#!/bin/sh
# Entry point of the guest toolchain image. In a privileged container, makes sure the kernel's
# binfmt_misc runs m68k ELF binaries through qemu-m68k, so mmdebstrap can run the m68k userland's
# maintainer scripts. Docker Desktop's VM registers several foreign arches but not m68k.
#
# The registration lives in the Docker VM's kernel, not in this container: once made it holds for
# every later container until the VM restarts. The F flag in qemu's config makes the kernel open
# the interpreter now, so it also works inside a chroot that does not contain qemu.
set -e

BINFMT=/proc/sys/fs/binfmt_misc
CONF=/usr/lib/binfmt.d/qemu-m68k.conf

if [ ! -e "$BINFMT/qemu-m68k" ]; then
    if [ ! -e "$BINFMT/register" ]; then
        mount -t binfmt_misc binfmt_misc "$BINFMT" 2>/dev/null || true
    fi
    if [ ! -e "$BINFMT/qemu-m68k" ] && [ -w "$BINFMT/register" ]; then
        if grep -v '^#' "$CONF" > "$BINFMT/register" 2>/dev/null; then
            echo "flippies: registered qemu-m68k with binfmt_misc" >&2
        fi
    fi
fi

exec "$@"
