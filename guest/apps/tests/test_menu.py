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
        self.extra = os.path.join(self.tmp, "extra")
        os.makedirs(self.home)
        os.makedirs(self.extra)
        write(self.apps, FILES)

    def run_menu(self, *args, path_extra=None):
        env = dict(os.environ, FLIPPIES_APPLICATIONS=self.apps, FLIPPIES_MENU_CACHE=self.cache,
                   FLIPPIES_EXTRA_APPLICATIONS=self.extra, HOME=self.home)
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

    def test_try_exec(self):
        write(self.apps, {
            "there.desktop": "[Desktop Entry]\nType=Application\nName=There\nExec=sh -c x\nTryExec=sh\n",
            "missing.desktop": "[Desktop Entry]\nType=Application\nName=Missing\nExec=nope\n"
                               "TryExec=no-such-program-here\n",
            "odd.desktop": "[Desktop Entry]\nType=Application\nName=Odd\nExec=sh\nTryExec=sh;true\n"})
        labels = [p.get("label") for p in ET.fromstring(self.run_menu()).iter("Program")]
        self.assertIn("There", labels)
        self.assertNotIn("Missing", labels)
        self.assertNotIn("Odd", labels)

    def test_extra_entries_after_the_systems(self):
        write(self.extra, {
            "xcalc.desktop": "[Desktop Entry]\nType=Application\nName=Calculator\nExec=xcalc\n"
                             "TryExec=sh\nCategories=Utility;Calculator;\n",
            "vim.desktop": "[Desktop Entry]\nType=Application\nName=Our vim\nExec=vim\n",
            "gone.desktop": "[Desktop Entry]\nType=Application\nName=Gone\nExec=gone\n"
                            "TryExec=no-such-program-here\n"})
        got = dict(m for m in self.menus(self.run_menu()) if len(m) == 2)
        self.assertEqual(got["Accessories"], [("Calculator", "xcalc", None), ("calculator", "galculator", None),
                                              ("Vim", "xterm -e vim", "gvim")])
        self.assertNotIn("Gone", self.run_menu())


OLD_JWMRC = """<?xml version="1.0"?>
<JWM>
    <StartupCommand>xterm</StartupCommand>
    <RootMenu onroot="1" label="Flippies">
        <Program label="Terminal">xterm</Program>
        <Separator/>
        <Program label="Display info">xdpyinfo</Program>
        <Separator/>
        <Restart label="Restart the desktop"/>
    </RootMenu>
    <RootMenu onroot="3">
        <Separator/>
        <Program label="Terminal">xterm</Program>
    </RootMenu>
</JWM>
"""


class AddToJwmrcTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.rc = os.path.join(self.tmp, ".jwmrc")

    def add(self, text):
        with open(self.rc, "w") as f:
            f.write(text)
        out = subprocess.run(["sh", paths.MENU, "--add-to", self.rc], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True)
        with open(self.rc) as f:
            return out, f.read()

    def test_after_the_first_separator_every_entry_kept(self):
        out, text = self.add(OLD_JWMRC)
        self.assertEqual((out.returncode, out.stdout), (0, "added\n"))
        root = ET.fromstring(text)
        start = root.findall("RootMenu")[0]
        self.assertEqual([(e.tag, e.get("label"), e.text) for e in start], [
            ("Program", "Terminal", "xterm"),
            ("Separator", None, None),
            ("Dynamic", "Apps", "exec:flippies-apps-menu"),
            ("Separator", None, None),
            ("Program", "Display info", "xdpyinfo"),
            ("Separator", None, None),
            ("Restart", "Restart the desktop", None),
        ])
        # the other menu as it was, and the icon folders before the start menu
        self.assertEqual(len(root.findall("RootMenu")[1]), 2)
        self.assertEqual([e.text for e in root.findall("IconPath")][:2],
                         ["/usr/share/icons/hicolor/16x16/apps", "/usr/share/pixmaps"])
        self.assertIn("    <IconPath>/usr/share/icons/hicolor/32x32/apps</IconPath>\n    <RootMenu onroot=\"1\"", text)

    def test_once_only(self):
        _, first = self.add(OLD_JWMRC)
        out, second = self.add(first)
        self.assertEqual((out.returncode, out.stdout), (0, ""))
        self.assertEqual(first, second)

    def test_without_a_separator_at_the_end_and_icon_paths_kept(self):
        text = OLD_JWMRC.replace("        <Separator/>\n", "").replace(
            "<JWM>\n", "<JWM>\n    <IconPath>/mine</IconPath>\n")
        _, got = self.add(text)
        start = ET.fromstring(got).findall("RootMenu")[0]
        self.assertEqual([e.tag for e in start][-2:], ["Dynamic", "Separator"])
        self.assertEqual([e.text for e in ET.fromstring(got).findall("IconPath")], ["/mine"])

    def test_no_menu(self):
        out, text = self.add("<JWM></JWM>\n")
        self.assertNotEqual(out.returncode, 0)
        self.assertEqual(text, "<JWM></JWM>\n")


if __name__ == "__main__":
    unittest.main()
