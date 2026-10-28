import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import mkpack  # noqa: E402


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def fdi_chunks(folder, title, size):
    """A <name>.fdi.z folder whose chunk 0 holds an FDI1 header with the title."""
    head = b"FDI1" + struct.pack(">IIII", 8, 65536, 1, 0) + title.encode().ljust(64, b"\0")
    head += struct.pack(">I", zlib.crc32(head))
    write(os.path.join(folder, "0.z"), zlib.compress(head + b"\0" * 1000))
    write(os.path.join(folder, "1.z"), zlib.compress(b"\x55" * 4000))
    write(os.path.join(folder, "size.txt"), b"%d\n" % size)


class PackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "src")
        self.chunks = os.path.join(self.src, "debian.fdi.z")
        fdi_chunks(self.chunks, "Debian GNU/Linux m68k desktop (20261025T063552Z)", 270238000)
        archive = os.path.join(self.src, "archive")
        write(os.path.join(archive, "dists/unstable/InRelease"), b"signed text\n" * 50)
        write(os.path.join(archive, "dists/unstable/main/binary-m68k/Packages.xz"), b"\xfd7zXZ" + b"x" * 300)
        write(os.path.join(archive, "pool/main/v/vim/vim_9.2+1~a_m68k.deb"), b"!<arch>\n" + b"d" * 5000)
        write(os.path.join(archive, "flippies-archive.gpg"), b"\x98\x33" + b"k" * 50)
        write(os.path.join(archive, ".DS_Store"), b"junk")
        self.archive = archive

    def build(self, name="a.flippack"):
        out = os.path.join(self.tmp, name)
        index = os.path.join(self.tmp, "index.txt")
        with open(index, "w") as f:
            f.write(mkpack.index_line(self.chunks) + "\n")
        files = mkpack.collect(["get.flip/debian.fdi.z=" + self.chunks, "get.flip/index.txt=" + index,
                                "deb.flip/=" + self.archive])
        hosts = mkpack.write_pack(out, "debian-os", "20261025T063552Z", "Debian GNU/Linux m68k desktop", files)
        return out, hosts

    def test_index_line_as_the_jar_task_wrote_it(self):
        self.assertEqual(mkpack.index_line(self.chunks),
                         "image /debian.fdi 270238000 Debian GNU/Linux m68k desktop (20261025T063552Z)")
        self.assertEqual(mkpack.index_line(self.chunks + "/", "/os/debian.fdi").split()[:3],
                         ["image", "/os/debian.fdi", "270238000"])
        write(os.path.join(self.tmp, "bad.fdi.z", "0.z"), zlib.compress(b"NOPE" + b"\0" * 100))
        write(os.path.join(self.tmp, "bad.fdi.z", "size.txt"), b"5\n")
        with self.assertRaises(ValueError):
            mkpack.index_line(os.path.join(self.tmp, "bad.fdi.z"))

    def test_layout_and_pack_json(self):
        out, hosts = self.build()
        self.assertEqual(hosts, ["deb.flip", "get.flip"])
        with zipfile.ZipFile(out) as z:
            names = z.namelist()
            self.assertEqual(names[0], "pack.json")
            self.assertEqual(names[1:], sorted(names[1:]))
            self.assertEqual(set(names[1:]), {
                "hosts/deb.flip/dists/unstable/InRelease",
                "hosts/deb.flip/dists/unstable/main/binary-m68k/Packages.xz",
                "hosts/deb.flip/flippies-archive.gpg",
                "hosts/deb.flip/pool/main/v/vim/vim_9.2+1~a_m68k.deb",
                "hosts/get.flip/debian.fdi.z/0.z",
                "hosts/get.flip/debian.fdi.z/1.z",
                "hosts/get.flip/debian.fdi.z/size.txt",
                "hosts/get.flip/index.txt",
            })
            meta = json.loads(z.read("pack.json"))
            self.assertEqual(list(meta), ["format", "id", "version", "title", "hosts"])
            self.assertEqual(meta, {"format": 1, "id": "debian-os", "version": "20261025T063552Z",
                                    "title": "Debian GNU/Linux m68k desktop", "hosts": ["deb.flip", "get.flip"]})
            self.assertEqual(z.read("hosts/get.flip/index.txt").decode(),
                             "image /debian.fdi 270238000 Debian GNU/Linux m68k desktop (20261025T063552Z)\n")
            with open(os.path.join(self.chunks, "1.z"), "rb") as f:
                self.assertEqual(z.read("hosts/get.flip/debian.fdi.z/1.z"), f.read())

    def test_compressed_data_is_stored(self):
        out, _ = self.build()
        with zipfile.ZipFile(out) as z:
            kinds = {i.filename: i.compress_type for i in z.infolist()}
        for name, kind in kinds.items():
            want = zipfile.ZIP_STORED if (".fdi.z/" in name or name.endswith((".deb", ".xz", ".gpg"))) \
                else zipfile.ZIP_DEFLATED
            self.assertEqual(kind, want, name)
        self.assertEqual(kinds["hosts/deb.flip/dists/unstable/InRelease"], zipfile.ZIP_DEFLATED)
        self.assertEqual(kinds["hosts/get.flip/debian.fdi.z/size.txt"], zipfile.ZIP_STORED)

    def test_same_inputs_same_bytes(self):
        a, _ = self.build("a.flippack")
        # Other file times on the inputs change nothing
        for folder, _, names in os.walk(self.src):
            for n in names:
                os.utime(os.path.join(folder, n), (1e9, 1e9))
        b, _ = self.build("b.flippack")
        self.assertEqual(mkpack.sha256_file(a), mkpack.sha256_file(b))
        with zipfile.ZipFile(a) as z:
            self.assertEqual({i.date_time for i in z.infolist()}, {mkpack.TIME})
            self.assertTrue(all(not i.is_dir() for i in z.infolist()))

    def test_sha256_file(self):
        out, _ = self.build()
        digest = mkpack.write_sha256(out)
        with open(out + ".sha256") as f:
            self.assertEqual(f.read(), "%s  a.flippack\n" % digest)

    def test_bad_input(self):
        with self.assertRaises(ValueError):
            mkpack.pack_json("Debian_OS", "1", "t", ["get.flip"])
        with self.assertRaises(ValueError):
            mkpack.collect(["localhost/x=" + self.chunks])
        with self.assertRaises(ValueError):
            mkpack.collect(["get.flip/a=" + os.path.join(self.tmp, "missing")])
        with self.assertRaises(ValueError):
            mkpack.collect(["get.flip/x=" + self.chunks + "/size.txt", "get.flip/x=" + self.chunks + "/0.z"])
        with self.assertRaises(ValueError):
            mkpack.write_pack(os.path.join(self.tmp, "e.flippack"), "e", "1", "t", {})
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "e.flippack.part")))

    def test_command_line(self):
        out = os.path.join(self.tmp, "c.flippack")
        r = subprocess.run([sys.executable, os.path.join(HERE, "..", "mkpack.py"), out, "--id", "debian-apps",
                            "--version", "v1", "--title", "Programs", "--add", "deb.flip/=" + self.archive],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isfile(out + ".sha256"))
        r = subprocess.run([sys.executable, os.path.join(HERE, "..", "mkpack.py"), "index-line", self.chunks],
                           stdout=subprocess.PIPE, text=True)
        self.assertTrue(r.stdout.startswith("image /debian.fdi 270238000 "))


if __name__ == "__main__":
    unittest.main()
