import gzip
import os
import tempfile
import unittest

import paths
import pkgdb

PACKAGES = """\
Package: vim
Version: 2:9.2.0858-1
Installed-Size: 3246
Maintainer: Debian Vim Maintainers <team+vim@tracker.debian.org>
Architecture: m68k
Depends: vim-common (= 2:9.2.0858-1), vim-runtime (= 2:9.2.0858-1)
Description: Vi IMproved - enhanced vi editor
 Vim is an almost compatible version of the UNIX editor Vi.
 .
 Many new features have been added.
Section: editors
Size: 1612340

Package: feh
Version: 3.13.1-1
Installed-Size: 906
Section: graphics
Size: 300000
Description: imlib2 based image viewer

Package: vim
Version: 2:9.2.0100-1
Installed-Size: 3000
Description: older vim

Package: flippies-sysinfo
Version: 1.0
Maintainer: Create Flippies <flippies@deb.flip>
Installed-Size: 40
Section: utils
Description: System information panel
"""

STATUS = """\
Package: feh
Status: install ok installed
Version: 3.12.0-1

Package: nano
Status: install ok installed
Version: 8.2-1

Package: mc
Status: deinstall ok config-files
Version: 3:4.8.33-1.1

Package: vim
Status: install ok half-installed
Version: 2:9.2.0858-1
"""


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        with open(os.path.join(self.dir, "deb.flip_dists_unstable_main_binary-m68k_Packages"), "w") as f:
            f.write(PACKAGES)
        with open(os.path.join(self.dir, "status"), "w") as f:
            f.write(STATUS)

    def test_available_keeps_the_newest_version_and_parses_fields(self):
        av = pkgdb.read_available(self.dir)
        self.assertEqual(sorted(av), ["feh", "flippies-sysinfo", "vim"])
        vim = av["vim"]
        self.assertEqual(vim.version, "2:9.2.0858-1")
        self.assertEqual(vim.installed_size, 3246)
        self.assertEqual(vim.size, 1612340)
        self.assertEqual(vim.section, "editors")
        self.assertEqual(vim.summary, "Vi IMproved - enhanced vi editor")
        self.assertEqual(vim.description, "Vim is an almost compatible version of the UNIX editor Vi.\n\n"
                                          "Many new features have been added.")

    def test_gzipped_lists_are_read(self):
        os.remove(os.path.join(self.dir, "deb.flip_dists_unstable_main_binary-m68k_Packages"))
        with gzip.open(os.path.join(self.dir, "x_Packages.gz"), "wt") as f:
            f.write(PACKAGES)
        self.assertIn("feh", pkgdb.read_available(self.dir))

    def test_no_lists(self):
        self.assertEqual(pkgdb.read_available(tempfile.mkdtemp()), {})
        self.assertEqual(pkgdb.list_files(tempfile.mkdtemp()), [])

    def test_installed_counts_only_installed(self):
        inst = pkgdb.read_installed(os.path.join(self.dir, "status"))
        self.assertEqual(inst, {"feh": "3.12.0-1", "nano": "8.2-1"})
        self.assertEqual(pkgdb.read_installed(os.path.join(self.dir, "missing")), {})

    def test_states(self):
        av = pkgdb.read_available(self.dir)
        inst = pkgdb.read_installed(os.path.join(self.dir, "status"))
        self.assertEqual(pkgdb.state("feh", av, inst), pkgdb.UPDATE)
        self.assertEqual(pkgdb.state("vim", av, inst), pkgdb.AVAILABLE)
        self.assertEqual(pkgdb.state("nano", av, inst), pkgdb.LOCAL)
        inst["feh"] = "3.13.1-1"
        self.assertEqual(pkgdb.state("feh", av, inst), pkgdb.INSTALLED)

    def test_featured_rows(self):
        av = pkgdb.read_available(self.dir)
        rows = pkgdb.featured_rows([("vim", "Editors"), ("gimp", "Pictures"), ("feh", "Pictures")], av, {})
        self.assertEqual(rows, [("vim", "Editors"), ("feh", "Pictures"), ("flippies-sysinfo", "Flippies")])

    def test_featured_file_parses_and_names_are_valid(self):
        featured = pkgdb.read_featured(paths.FEATURED)
        self.assertGreater(len(featured), 20)
        for name, group in featured:
            self.assertRegex(name, pkgdb.NAME)
            self.assertTrue(group)


class VersionTest(unittest.TestCase):
    def check(self, a, b, sign):
        c = pkgdb.compare_versions(a, b)
        self.assertEqual((c > 0) - (c < 0), sign, "%s vs %s" % (a, b))
        c = pkgdb.compare_versions(b, a)
        self.assertEqual((c > 0) - (c < 0), -sign, "%s vs %s" % (b, a))

    def test_order(self):
        self.check("1.0", "1.0", 0)
        self.check("1.0-1", "1.0-2", -1)
        self.check("1:0.1", "9.9", 1)
        self.check("1.0~rc1", "1.0", -1)
        self.check("1.0", "1.0+b1", -1)
        self.check("1.10", "1.9", 1)
        self.check("1.0a", "1.0", 1)
        self.check("2:9.2.0858-1", "2:9.2.0100-1", 1)
        self.check("3.14.8-1", "3.14.10-1", -1)
        self.check("1.001", "1.1", 0)
        self.check("1.0-1", "1.0", 1)
        self.check("0.3.1-1", "0.3.1-1+b1", -1)


class AptOutputTest(unittest.TestCase):
    def test_status_lines(self):
        self.assertEqual(pkgdb.parse_status_line("dlstatus:1:12.5000:Retrieving file 1 of 3"),
                         ("download", 12.5, "Retrieving file 1 of 3"))
        self.assertEqual(pkgdb.parse_status_line("pmstatus:vim:40.0000:Installing vim (m68k): now"),
                         ("install", 40.0, "Installing vim (m68k): now"))
        self.assertEqual(pkgdb.parse_status_line("pmerror:vim:50:subprocess failed"),
                         ("error", 50.0, "vim: subprocess failed"))
        self.assertIsNone(pkgdb.parse_status_line("Reading package lists..."))
        self.assertIsNone(pkgdb.parse_status_line("Get:1 http://deb.flip unstable/main m68k vim"))

    def test_simulation(self):
        out = """NOTE: This is only a simulation!
Reading package lists...
The following packages will be REMOVED:
  idle idle-python3.14 python3-tk
Remv idle [3.14.8-1]
Remv idle-python3.14 [3.14.9-1]
Remv python3-tk [3.14.8-1]
Inst foo (1.0 deb.flip [all])
"""
        inst, remv = pkgdb.parse_simulation(out)
        self.assertEqual(inst, ["foo"])
        self.assertEqual(remv, ["idle", "idle-python3.14", "python3-tk"])
        self.assertEqual(pkgdb.blocked(remv), ["python3-tk"])
        self.assertEqual(pkgdb.blocked(["feh", "linux-image-6.18.58-flippies"]), ["linux-image-6.18.58-flippies"])

    def test_commands(self):
        cmd = pkgdb.install_cmd(["vim"])
        self.assertEqual(cmd[:4], ["sudo", "-n", "env", "DEBIAN_FRONTEND=noninteractive"])
        self.assertEqual(cmd[-2:], ["install", "vim"])
        self.assertIn("APT::Status-Fd=3", cmd)
        # fd 3 is opened by a root shell, after sudo
        i = cmd.index("sh")
        self.assertEqual(cmd[i:i + 4], ["sh", "-c", 'exec 3>&1; exec "$@"', "sh"])
        self.assertEqual(cmd[i + 4], "apt-get")
        self.assertEqual(pkgdb.remove_cmd(["feh"])[-3:], ["--autoremove", "remove", "feh"])
        self.assertNotIn("sudo", pkgdb.simulate_remove_cmd(["feh"]))
        # the check simulates the very removal that runs, autoremove included
        self.assertEqual(pkgdb.simulate_remove_cmd(["feh"])[-3:], ["--autoremove", "remove", "feh"])
        with self.assertRaises(ValueError):
            pkgdb.install_cmd(["-o"])
        with self.assertRaises(ValueError):
            pkgdb.remove_cmd(["vim; rm -rf /"])

    def test_sizes(self):
        self.assertEqual(pkgdb.format_kib(840), "840K")
        self.assertEqual(pkgdb.format_kib(3246), "3.2M")
        self.assertEqual(pkgdb.format_bytes(1612340), "1.5M")
        self.assertEqual(pkgdb.format_bytes(0), "0K")


if __name__ == "__main__":
    unittest.main()
