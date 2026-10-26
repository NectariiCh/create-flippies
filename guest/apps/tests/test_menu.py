import os
import subprocess
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

import paths

FILES = {
    "vim.desktop": """[Desktop Entry]
Name=Vim
Name[de]=Vim (de)
Comment=Edit text files
Exec=vim %F
Terminal=true
Type=Application
Icon=gvim
Categories=Utility;TextEditor;
""",
    "feh.desktop": """# a comment
[Desktop Entry]
Type=Application
Name=Feh & friends <viewer>
Exec=feh --start-at %u
Icon=feh
Categories=Graphics;2DGraphics;Viewer;
NoDisplay=false

[Desktop Action fullscreen]
Name=Fullscreen
Exec=feh -F %u
""",
    "hidden.desktop": "[Desktop Entry]\nType=Application\nName=Hidden\nExec=hidden\nNoDisplay=true\n",
    "gnome-only.desktop": "[Desktop Entry]\nType=Application\nName=Gnome\nExec=g\nOnlyShowIn=GNOME;\n",
    "link.desktop": "[Desktop Entry]\nType=Link\nName=A link\nURL=http://x/\n",
    "noexec.desktop": "[Desktop Entry]\nType=Application\nName=No exec\n",
    "percent.desktop": "[Desktop Entry]\nType=Application\nName=Percent\nExec=echo 100%% done %i %c\n",
    "idle.desktop": "[Desktop Entry]\nType=Application\nName=IDLE\nExec=idle\nCategories=Development;IDE;\n",
    "calc.desktop": "[Desktop Entry]\nType=Application\nName=calculator\nExec=galculator\nCategories=Utility;Calculator;\n",
    "abc.desktop": "[Desktop Entry]\nType=Application\nName=ABC\nExec=abc\n",
    "apps.desktop": "[Desktop Entry]\nType=Application\nName=Flippies Apps\nExec=flippies-apps\nCategories=System;PackageManager;\n",
    "crlf.desktop": "[Desktop Entry]\r\nType=Application\r\nName=Dos file\r\nExec=dos\r\nCategories=Game;\r\n",
}


def write(folder, files):
    os.makedirs(folder, exist_ok=True)
    for name, text in files.items():
        with open(os.path.join(folder, name), "w", newline="") as f:
            f.write(text)


class MenuTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.apps = os.path.join(self.tmp, "applications")
        self.home = os.path.join(self.tmp, "home")
        self.cache = os.path.join(self.tmp, "cache", "apps-menu.xml")
        os.makedirs(self.home)
        write(self.apps, FILES)

    def run_menu(self, *args, path_extra=None):
        env = dict(os.environ, FLIPPIES_APPLICATIONS=self.apps, FLIPPIES_MENU_CACHE=self.cache,
                   HOME=self.home)
        env.pop("XDG_DATA_HOME", None)
        # Without flippies-apps on PATH unless a test adds a folder holding it
        env["PATH"] = (path_extra + os.pathsep if path_extra else "") + "/usr/bin:/bin"
        return subprocess.run(["sh", paths.MENU] + list(args), env=env, check=True,
                              stdout=subprocess.PIPE, text=True).stdout

    def menus(self, xml):
        root = ET.fromstring(xml)
        self.assertEqual(root.tag, "JWM")
        out = []
        for m in root:
            if m.tag == "Menu":
                out.append((m.get("label"), [(p.get("label"), p.text, p.get("icon")) for p in m]))
            else:
                out.append((m.tag, m.get("label"), m.text))
        return out

    def test_folders_names_and_commands(self):
        got = self.menus(self.run_menu())
        self.assertEqual(got, [
            ("Accessories", [("calculator", "galculator", None),
                             ("Vim", "xterm -e vim", "gvim")]),
            ("Graphics", [("Feh & friends <viewer>", "feh --start-at", "feh")]),
            ("Programming", [("IDLE", "idle", None)]),
            ("Games", [("Dos file", "dos", None)]),
            ("System", [("Flippies Apps", "flippies-apps", None)]),
            ("Other", [("ABC", "abc", None), ("Percent", "echo 100% done", None)]),
        ])

    def test_tooltip_is_the_comment(self):
        root = ET.fromstring(self.run_menu())
        vim = [p for p in root.iter("Program") if p.get("label") == "Vim"][0]
        self.assertEqual(vim.get("tooltip"), "Edit text files")

    def test_install_more_when_the_package_center_is_there(self):
        bindir = os.path.join(self.tmp, "bin")
        os.makedirs(bindir)
        fake = os.path.join(bindir, "flippies-apps")
        with open(fake, "w") as f:
            f.write("#!/bin/sh\n")
        os.chmod(fake, 0o755)
        got = self.menus(self.run_menu(path_extra=bindir))
        self.assertEqual(got[-2:], [("Separator", None, None),
                                    ("Program", "Install more apps...", "flippies-apps")])

    def test_empty(self):
        for f in os.listdir(self.apps):
            os.remove(os.path.join(self.apps, f))
        self.assertEqual(self.menus(self.run_menu()), [("Program", "No apps installed", "true")])
        os.rmdir(self.apps)
        self.assertEqual(self.menus(self.run_menu()), [("Program", "No apps installed", "true")])

    def test_user_files_override_and_add(self):
        user = os.path.join(self.home, ".local", "share", "applications")
        write(user, {"vim.desktop": "[Desktop Entry]\nType=Application\nName=My vim\nExec=myvim\n"
                                    "Categories=Utility;\n",
                     "abc.desktop": "[Desktop Entry]\nType=Application\nName=ABC\nExec=abc\nHidden=true\n"})
        got = dict(m for m in self.menus(self.run_menu()) if len(m) == 2)
        self.assertEqual(got["Accessories"], [("calculator", "galculator", None), ("My vim", "myvim", None)])
        self.assertEqual(got["Other"], [("Percent", "echo 100% done", None)])

    def test_cache(self):
        self.run_menu("--update")
        with open(self.cache) as f:
            cached = f.read()
        self.assertEqual(cached, self.run_menu())
        # The cached copy is what is printed while the folder is not newer
        with open(self.cache, "w") as f:
            f.write("<JWM><Program label=\"cached\">x</Program></JWM>\n")
        future = time.time() + 5
        os.utime(self.cache, (future, future))
        self.assertIn("cached", self.run_menu())
        # A package adds a file: the folder is newer, the menu is made anew
        write(self.apps, {"new.desktop": "[Desktop Entry]\nType=Application\nName=New one\nExec=new\n"})
        later = future + 5
        os.utime(self.apps, (later, later))
        out = self.run_menu()
        self.assertNotIn("cached", out)
        self.assertIn("New one", out)

    def test_update_leaves_out_the_users_files(self):
        user = os.path.join(self.home, ".local", "share", "applications")
        write(user, {"mine.desktop": "[Desktop Entry]\nType=Application\nName=Mine\nExec=mine\n"})
        self.run_menu("--update")
        with open(self.cache) as f:
            self.assertNotIn("Mine", f.read())
        self.assertIn("Mine", self.run_menu())


if __name__ == "__main__":
    unittest.main()
