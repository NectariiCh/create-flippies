# The one toolchain image for every guest build (spec 12.9): the m68k cross GCC for the floppy
# programs and the kernel, the kernel's build dependencies, and mmdebstrap with qemu user mode for
# the debian-ports m68k userland. Built and run by build.sh next to this file.
#
# Base: Debian stable (13, trixie), pinned by digest so a rebuild gets the same packages' base.
FROM debian:trixie-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8

# Cross toolchain, kernel build (bindeb-pkg needs dpkg-dev and debhelper, and its generated
# debian/control asks for libdw-dev), image building (mmdebstrap, its Recommends arch-test without
# which it refuses any foreign-arch bootstrap that runs maintainer scripts, qemu-user for m68k
# binaries, the debian-ports key, partitioning and ext4), Python 3 for mkfloppy.py and mkfdi.py.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      gcc-m68k-linux-gnu binutils-m68k-linux-gnu \
      build-essential bc bison flex libssl-dev libelf-dev libdw-dev cpio kmod rsync \
      dpkg-dev debhelper fakeroot \
      patch git xz-utils zstd curl ca-certificates file \
      mmdebstrap arch-test gpg qemu-user qemu-user-binfmt debian-ports-archive-keyring \
      e2fsprogs fdisk \
      python3 \
 && rm -rf /var/lib/apt/lists/*

# gpg's agent, which holds the secret key while guest/apt/build.sh signs the deb.flip archive. A
# layer of its own so the one above stays cached.
RUN apt-get update \
 && apt-get install -y --no-install-recommends gpg-agent \
 && rm -rf /var/lib/apt/lists/*

# bindeb-pkg's control file also wants libssl-dev for the target arch (m68k), which Debian stable
# cannot install (m68k is a ports arch). That dependency only serves the linux-headers package,
# which our images do not need, and this profile drops both. The name follows KDEB_SOURCENAME
# (default linux-upstream); a build that changes it sets the profile itself.
ENV DEB_BUILD_PROFILES=pkg.linux-upstream.nokernelheaders

# /build is the case-sensitive work area (build.sh mounts a named volume there). Kernel trees are
# unpacked and built there, never in the repo: on a Mac the bind-mounted repo is case-insensitive
# and the kernel has paths that differ only in case. Mode 1777 is copied into a new volume, so a
# Linux host's non-root user can write it too.
RUN mkdir -p /build && chmod 1777 /build

# Registers the qemu m68k interpreter with the kernel's binfmt_misc when the container runs
# privileged and it is missing (Docker Desktop registers several foreign arches, not m68k).
COPY entrypoint.sh /usr/local/bin/flippies-entrypoint
RUN chmod 755 /usr/local/bin/flippies-entrypoint
ENTRYPOINT ["/usr/local/bin/flippies-entrypoint"]

WORKDIR /work
CMD ["bash"]
