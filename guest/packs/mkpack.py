"""Writes a content pack (.flippack) for the network modem's made-up internet.

A pack is a zip file:
  pack.json                  {"format": 1, "id": ..., "version": ..., "title": ..., "hosts": [...]}
  hosts/<host>/<path>        served at http://<host>/<path>

Already compressed data (the deflated chunk folders <name>.fdi.z/ of a disk image, .deb files,
.gz and .xz indexes, keys) is STORED; other files are deflated. The zip is deterministic: pack.json
first, then the files sorted by name, every entry with the same time and mode, no folder
entries, so the same inputs give the same bytes (and the same SHA-256) with the same zlib.

    mkpack.py OUT.flippack --id ID --version V --title T [--add HOST/PATH=SOURCE ...]

SOURCE is a file, or a folder whose files go under HOST/PATH/ (PATH may be empty: "deb.flip/=DIR").
mkpack.py index-line CHUNKS_DIR [SERVED_PATH] prints get.flip/index.txt's line for a disk image
stored as deflated chunks.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile
import zlib

FORMAT = 1
ID = re.compile(r"^[a-z0-9-]+$")
HOST = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
# Every entry's time: the Debian snapshot the packs are built from (zip times are local and have
# no zone; this is the UTC time)
TIME = (2026, 10, 25, 6, 35, 52)
STORED_SUFFIXES = (".deb", ".gz", ".xz", ".bz2", ".zst", ".gpg", ".zip", ".png", ".jpg", ".ogv")


def stored(name):
    """Whether an entry is stored rather than deflated: the chunks of a .fdi.z folder (and its
    size.txt) and files that are compressed already."""
    return ".fdi.z/" in name or name.endswith(STORED_SUFFIXES)


def collect(adds):
    """{entry name under hosts/: source file} from "HOST/PATH=SOURCE" pairs."""
    files = {}
    for spec in adds:
        target, sep, source = spec.partition("=")
        if not sep or not target or not source:
            raise ValueError("--add needs HOST/PATH=SOURCE: %s" % spec)
        host = target.split("/", 1)[0]
        if not HOST.match(host):
            raise ValueError("not a host name: %s" % host)
        if os.path.isdir(source):
            prefix = target.rstrip("/") + "/"
            for folder, dirs, names in os.walk(source, followlinks=True):
                dirs.sort()
                for n in names:
                    if n == ".DS_Store":
                        continue
                    path = os.path.join(folder, n)
                    rel = os.path.relpath(path, source).replace(os.sep, "/")
                    _put(files, prefix + rel, path)
        elif os.path.isfile(source):
            if target.endswith("/"):
                raise ValueError("a file needs a path, not a folder: %s" % spec)
            _put(files, target, source)
        else:
            raise ValueError("no such file or folder: %s" % source)
    return files


def _put(files, name, path):
    if name in files:
        raise ValueError("%s is added twice (%s and %s)" % (name, files[name], path))
    if "//" in name or name.startswith("/") or any(p in (".", "..") for p in name.split("/")):
        raise ValueError("not a clean path: %s" % name)
    files[name] = path


def pack_json(pack_id, version, title, hosts):
    if not ID.match(pack_id):
        raise ValueError("a pack id is [a-z0-9-]+: %s" % pack_id)
    doc = {"format": FORMAT, "id": pack_id, "version": version, "title": title, "hosts": hosts}
    return (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def _info(name, compress):
    info = zipfile.ZipInfo(name, date_time=TIME)
    info.create_system = 3  # Unix, so the mode below counts
    info.external_attr = 0o100644 << 16
    info.compress_type = zipfile.ZIP_STORED if not compress else zipfile.ZIP_DEFLATED
    # Deflate level 9 (the attribute is compress_level from Python 3.13, _compresslevel before)
    try:
        info.compress_level = 9
    except AttributeError:
        info._compresslevel = 9
    return info


def write_pack(out, pack_id, version, title, files):
    """Writes the pack to out (through out.part, renamed when complete). files: {host/path: source}."""
    if not files:
        raise ValueError("a pack needs at least one file")
    hosts = sorted({name.split("/", 1)[0] for name in files})
    meta = pack_json(pack_id, version, title, hosts)
    part = out + ".part"
    try:
        with zipfile.ZipFile(part, "w", allowZip64=True) as z:
            z.writestr(_info("pack.json", True), meta)
            for name in sorted(files):
                entry = "hosts/" + name
                info = _info(entry, not stored(entry))
                size = os.path.getsize(files[name])
                with open(files[name], "rb") as src, \
                        z.open(info, "w", force_zip64=size >= 0x7FFFFFFF) as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
        os.replace(part, out)
    except BaseException:
        try:
            os.remove(part)
        except OSError:
            pass
        raise
    return hosts


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def write_sha256(path):
    """path.sha256 in sha256sum's format; returns the hex digest."""
    digest = sha256_file(path)
    with open(path + ".sha256", "w", encoding="ascii") as f:
        f.write("%s  %s\n" % (digest, os.path.basename(path)))
    return digest


def index_line(chunks_dir, served_path=None):
    """get.flip/index.txt's line for a disk image stored as deflated chunks (spec 12.4.3):
    "image /<name>.fdi <size> <title>", the size from size.txt and the title from the FDI header
    in chunk 0, as the jar's internetIndex task wrote it."""
    name = os.path.basename(os.path.normpath(chunks_dir))
    if not name.endswith(".fdi.z"):
        raise ValueError("%s is not a <name>.fdi.z folder" % chunks_dir)
    with open(os.path.join(chunks_dir, "size.txt"), encoding="ascii") as f:
        size = int(f.read().strip())
    with open(os.path.join(chunks_dir, "0.z"), "rb") as f:
        head = zlib.decompressobj().decompress(f.read(), 84)
    if len(head) < 84 or head[:4] != b"FDI1":
        raise ValueError("%s: chunk 0 does not start with an FDI1 header" % chunks_dir)
    title = head[20:84].decode("utf-8", errors="replace").replace("\0", "").strip()
    path = served_path or "/" + name[:-2]
    return "image %s %d %s" % (path, size, title or name[:-2])


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["index-line"]:
        if len(argv) not in (2, 3):
            print("usage: mkpack.py index-line CHUNKS_DIR [SERVED_PATH]", file=sys.stderr)
            return 64
        print(index_line(*argv[1:]))
        return 0
    p = argparse.ArgumentParser(description="Writes a .flippack content pack and its .sha256.")
    p.add_argument("out")
    p.add_argument("--id", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--add", action="append", default=[], metavar="HOST/PATH=SOURCE")
    a = p.parse_args(argv)
    try:
        files = collect(a.add)
        hosts = write_pack(a.out, a.id, a.version, a.title, files)
    except (ValueError, OSError) as e:
        print("mkpack: %s" % e, file=sys.stderr)
        return 1
    digest = write_sha256(a.out)
    print("%s: %d files for %s, %d bytes, sha256 %s" % (
        a.out, len(files), " ".join(hosts), os.path.getsize(a.out), digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
