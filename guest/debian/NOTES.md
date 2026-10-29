# Building the Debian desktop image from scratch

How to get from a clean checkout to a disk image that boots from the boot ROM to the JWM desktop
(spec 12.6 to 12.8), and to the tests that check it. Every step runs in the toolchain container
through `guest/docker/build.sh`; the Mac needs only Docker. Steps 1 to 4 must run in this order,
since each uses the outputs of the one before.

## 0. Once per machine

- Docker Desktop running. `guest/docker/build.sh --image-only` builds the toolchain image.
- The Debian build (step 4) runs privileged, which registers qemu's m68k binfmt handler. The
  registration lasts until the Docker VM restarts; the build script does it every time.
- Space: the named volume `create-flippies-guest-build` (mounted at `/build`) holds the kernel
  tree and its objects (`/build/linux-all` and `/build/linux-all-obj`, about 2 GB) and the
  Debian work area (`/build/debian`, about 1 GB). Check with
  `guest/docker/build.sh df -h /build`.

## 1. The kernel (spec 12.6)

```
guest/linux/build.sh --tree /build/linux-all --deb
```

- Fetches Linux 6.18.58 (checked against its pinned SHA-256), applies `guest/linux/patches/`,
  merges every fragment of `guest/linux/config.d/` (00-base, block, debian, input-fb, misc, net),
  and builds with `KCFLAGS=-fno-tree-slsr` (set in build.sh: GCC 14.2 miscompiles
  `lib/maple_tree.c` without it, and udev oopses on every Debian boot).
- Outputs in `guest/linux/out/`: `vmlinux`, `System.map`, `config`,
  `linux-image-6.18.58-flippies_6.18.58-N_m68k.deb` and `linux-libc-dev_*.deb`. The package
  revision N counts up with each `--deb` build in the same tree; build.sh removes the old
  packages, so there is always exactly one.
- About a minute when the tree and objects exist; a first build takes longer.

## 2. The initramfs (only for the kernel's own tests)

```
guest/linux/mkinitramfs.sh
```

- Busybox from the pinned snapshot, into `guest/linux/out/initramfs.cpio`. The Debian image does
  not use it; `LinuxBootTest` and the boot chain's `--testroot` do.

## 3. The boot chain (spec 12.7)

```
guest/boot/build.sh              stage1.bin, stage2.bin, flippies-boot_1.0_all.deb
guest/boot/build.sh --testroot   also testroot.ext4 for FlipbootE2eTest (needs step 2)
```

- Outputs in `guest/boot/out/`. `guest/boot/mkdisk.py` (plain Python 3, runs anywhere) writes
  disk images with this layout; the Debian build uses it.

## 4. The Debian image (spec 12.8)

```
guest/debian/build.sh --chunks
```

- Needs steps 1 and 3, and `guest/apps/build.sh` (the desktop's own packages in
  `guest/apps/out/`: flippies-desktop, flippies-apps, flippies-sharedfiles, flippies-sysinfo;
  about a second, its tests included), which stage 2 installs with `dpkg -i`, and preferably
  `guest/apt/build.sh` first (step 4b), whose public key the image trusts. Bootstraps
  debian-ports at snapshot 20261025T063552Z with mmdebstrap,
  installs `flippies-boot` and then the kernel package (the kernel's postinst hook runs
  `zz-flipboot`, which skips in the chroot because there is no `/dev/fhda1`), lays
  `guest/debian/rootfs/` over it, then writes the disk:
  - `mkdisk.py create` writes stage 1, stage 2, the partition table and the boot area, with the
    image's `/boot/vmlinuz-*` in slot A and the command line
    `root=/dev/fhda3 rw console=tty0 console=ttyS0,115200 quiet` (`--cmdline` changes it, at
    most 239 bytes; stage 2 adds ` flipboot.slot=A` or `B` for the slot it loaded);
  - mkswap on partition 2, the ext4 root (`mkfs.ext4 -d`) on partition 3;
  - `mkdisk.py show` checks both boot area headers and the kernel's CRC.
- The bootstrap is cached in `/build/debian/base-<hash>.tar`; the hash covers the snapshot, the
  package list, both packages' SHA-256 and the bootstrap part of the script. With the cache a
  build takes one to two minutes, without it about three and a half.
- Outputs in `guest/debian/out/`: `debian.img` (1 GiB, sparse), `debian.fdi`, and with
  `--chunks` the deflated chunks in `chunks/debian.fdi.z/` that go into the debian-os content
  pack (step 4c).

## 4b. The package archive (deb.flip)

```
guest/apt/build.sh
guest/debian/build.sh --chunks     again, once, if the signing key was new, so the image trusts it
```

- Resolves the programs listed in `guest/apt/build.sh` against `guest/apt/oldest-image.status`
  (the dpkg status of the first image that was served, before the image held python3 and the
  desktop's packages, so the archive completes every disk installed since), downloads what is
  missing from the same debian-ports snapshot (cached in `/build/apt/cache`), adds
  `guest/apps/out/*.deb` and `guest/apt/local/` (flippies-video), and writes a signed archive to
  `guest/apt/out/archive/`. The debian-apps content pack (step 4c) serves it as `deb.flip`.
- The signing key is made on the first run in `guest/apt/out/signing/` and kept. The image build
  copies its public key to `/etc/apt/trusted.gpg.d/`. A disk installed before that adds it with
  `sudo wget -qO /etc/apt/trusted.gpg.d/flippies-archive.gpg http://deb.flip/flippies-archive.gpg`.
- Such a disk also needs `sudo mkdir -p /usr/share/man/man1` before installing Java (openjdk's
  postinst links a manual page there; the image build now makes the folder).
- The desktop on such a disk: `sudo apt update && sudo apt install -y flippies-desktop
  flippies-apps` (after the key). flippies-desktop adds the Apps submenu to `~/.jwmrc`.
- In the guest: `sudo apt update`, then `sudo apt install vim python3` and so on, or Flippies
  Apps. Not everything fits at once: the root file system has about 550 MiB free and the whole
  archive installs to about 730 MiB.

## 4c. The content packs

```
guest/packs/build.sh               both; "os" or "apps" for one
```

- Writes `guest/packs/out/debian-os.flippack` (get.flip: the image's chunks and the index.txt
  line for the netinstall menu) and `debian-apps.flippack` (deb.flip: the archive), each with a
  `.sha256`. A server imports them from `config/create_flippies/packs/` or
  `<world>/flippies_packs/`; they are not in the mod jar. Format: `guest/packs/mkpack.py`.
- Runs on the host with any Python 3, its tests first; a few seconds for each pack.

## 5. Running it

```
./gradlew -q --console=plain runHeadlessPc --args='--ram 128M --hdd guest/debian/out/debian.img --readonly --modem'
```

- No `--kernel`: the boot ROM loads flipboot from the MBR, and flipboot loads the kernel from the
  active slot. "login:" comes on COM1 (the user is `flip`, no password); the desktop is on
  display 0 (`--screenshot`, or `screenshot` lines in an `--input` script).
- `--readonly` keeps the image as built. Leave it out to keep what the guest writes.

## 6. Tests

```
./gradlew linuxTest
./gradlew test --tests 'com.nectariich.create_flippies.content.computer.*'
```

- `linuxTest` runs the tests tagged "linux": `LinuxBootTest` (steps 1 and 2), the two Linux
  tests of `FlipbootE2eTest` (steps 1 to 3 with `--testroot`) and `LinuxDesktopTest` (step 4).
  Each is skipped while its inputs are missing.
- `LinuxDesktopTest` boots `guest/debian/out/debian.img`, or the image named by the
  `FLIPPIES_DEBIAN_IMAGE` environment variable (or the `flippies.debianImage` system property),
  read-only and without network access. Its screenshots, COM1 transcript and timings stay in
  `build/linux-desktop`.
- Gradle does not track the guest outputs as test inputs: after rebuilding stage 2 or the kernel,
  run `cleanTest` first so the default suite does not report cached results. `linuxTest` always
  runs.
