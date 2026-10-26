"""Flippies Apps: the desktop's package center. Lists what the archive at http://deb.flip/ offers
(apt's downloaded lists), each package's state and size, and installs or removes one with a click
through apt-get as root (sudo, no password for the desktop user). Sized for 640x480 and plain
colours for 8 bits per pixel.

The package work runs in threads; only the Tk thread touches widgets, fed through a queue.
"""

import os
import queue
import subprocess
import threading
import tkinter as tk
from tkinter import messagebox, ttk

import pkgdb

HERE = os.path.dirname(os.path.abspath(__file__))
FEATURED = os.path.join(HERE, "featured.txt")
# Rows the All view shows at most: inserting thousands of rows takes Tk many seconds at 25 MHz
MAX_ROWS = 300
STATE_TEXT = {pkgdb.AVAILABLE: "", pkgdb.INSTALLED: "installed", pkgdb.UPDATE: "update",
              pkgdb.LOCAL: "installed"}


def setup_tk(root):
    """The desktop's look: the bitmap font JWM is set to (Misc Fixed, 13 px), grey and navy."""
    from tkinter import font
    for name in ("TkDefaultFont", "TkTextFont", "TkFixedFont", "TkHeadingFont", "TkMenuFont"):
        try:
            font.nametofont(name).configure(family="Misc Fixed", size=-13, weight="normal")
        except tk.TclError:
            pass
    style = ttk.Style(root)
    try:
        style.theme_use("default")
    except tk.TclError:
        pass
    style.configure(".", background="#C0C0C0")
    style.configure("Treeview", background="#FFFFFF", fieldbackground="#FFFFFF", rowheight=16)
    style.map("Treeview", background=[("selected", "#000080")], foreground=[("selected", "#FFFFFF")])
    style.configure("TProgressbar", background="#000080")
    root.configure(background="#C0C0C0")


class App:
    def __init__(self, root, lists_dir=pkgdb.LISTS, status=pkgdb.STATUS):
        self.root = root
        self.lists_dir = lists_dir
        self.status = status
        self.available = {}
        self.installed = {}
        self.featured = pkgdb.read_featured(FEATURED)
        self.queue = queue.Queue()
        self.busy = False
        self.search_job = None

        root.title("Flippies Apps")
        root.geometry("624x420")
        root.minsize(480, 320)
        setup_tk(root)

        top = ttk.Frame(root, padding=(4, 4, 4, 0))
        top.pack(fill="x")
        self.view = tk.StringVar(value="apps")
        for text, value in (("Apps", "apps"), ("Installed", "installed"), ("All", "all")):
            ttk.Radiobutton(top, text=text, value=value, variable=self.view,
                            command=self.fill).pack(side="left")
        ttk.Label(top, text="  Find:").pack(side="left")
        self.search = tk.StringVar()
        entry = ttk.Entry(top, textvariable=self.search, width=18)
        entry.pack(side="left")
        entry.bind("<KeyRelease>", self.search_later)
        self.refresh_button = ttk.Button(top, text="Refresh", command=self.refresh)
        self.refresh_button.pack(side="right")

        mid = ttk.Frame(root, padding=4)
        mid.pack(fill="both", expand=True)
        cols = ("name", "group", "state", "size", "summary")
        self.tree = ttk.Treeview(mid, columns=cols, height=11, selectmode="browse", show="headings")
        for col, text, width, anchor in (("name", "Package", 140, "w"), ("group", "Group", 84, "w"),
                                         ("state", "State", 66, "w"), ("size", "Size", 48, "e"),
                                         ("summary", "Description", 250, "w")):
            self.tree.heading(col, text=text, anchor=anchor)
            self.tree.column(col, width=width, anchor=anchor, stretch=col == "summary")
        self.tree.tag_configure(pkgdb.INSTALLED, foreground="#000080")
        self.tree.tag_configure(pkgdb.LOCAL, foreground="#000080")
        self.tree.tag_configure(pkgdb.UPDATE, foreground="#800000")
        bar = ttk.Scrollbar(mid, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=bar.set)
        self.tree.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")
        self.tree.bind("<<TreeviewSelect>>", lambda e: self.show_selected())

        self.info = tk.Text(root, height=3, wrap="word", background="#FFFFE0", relief="flat",
                            borderwidth=2, highlightthickness=0)
        self.info.pack(fill="x", padx=4)
        self.info.configure(state="disabled")

        act = ttk.Frame(root, padding=4)
        act.pack(fill="x")
        self.install_button = ttk.Button(act, text="Install", command=self.install, state="disabled")
        self.install_button.pack(side="left")
        self.remove_button = ttk.Button(act, text="Remove", command=self.remove, state="disabled")
        self.remove_button.pack(side="left", padx=4)
        self.progress = ttk.Progressbar(act, length=140, maximum=100)
        self.progress.pack(side="left", padx=4)
        self.message = ttk.Label(act, text="")
        self.message.pack(side="left", fill="x", expand=True)

        logf = ttk.Frame(root, padding=(4, 0, 4, 4))
        logf.pack(fill="x")
        self.log = tk.Text(logf, height=4, wrap="none", background="#000000", foreground="#C0C0C0",
                           relief="flat", highlightthickness=0)
        lbar = ttk.Scrollbar(logf, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=lbar.set, state="disabled")
        self.log.pack(side="left", fill="x", expand=True)
        lbar.pack(side="right", fill="y")

        root.after(100, self.poll)
        self.load(update_if_empty=True)

    # ---- reading the lists ----

    def load(self, update_if_empty=False):
        """Reads the lists and dpkg's status in a thread; with no lists at all (a fresh image),
        downloads them first."""
        self.set_busy(True, "Reading the package lists...")

        def work():
            try:
                if update_if_empty and not pkgdb.list_files(self.lists_dir):
                    self.queue.put(("update-first", None))
                    return
                available = pkgdb.read_available(self.lists_dir)
                installed = pkgdb.read_installed(self.status)
                self.queue.put(("loaded", (available, installed)))
            except Exception as e:  # any read failure is shown, never fatal
                self.queue.put(("failed", "Could not read the package lists: %s" % e))
        threading.Thread(target=work, daemon=True).start()

    def refresh(self):
        self.run_job("Downloading the package lists from deb.flip", pkgdb.update_cmd(), reload_lists=True)

    # ---- the list ----

    def search_later(self, _event=None):
        if self.search_job:
            self.root.after_cancel(self.search_job)
        self.search_job = self.root.after(400, self.fill)

    def rows(self):
        view = self.view.get()
        if view == "apps":
            rows = pkgdb.featured_rows(self.featured, self.available, self.installed)
        elif view == "installed":
            rows = [(n, self.section(n)) for n in sorted(self.installed)]
        else:
            rows = [(n, self.section(n)) for n in sorted(self.available)]
        needle = self.search.get().strip().lower()
        if needle:
            rows = [(n, g) for n, g in rows if needle in n or needle in self.summary(n).lower()]
        return rows

    def section(self, name):
        p = self.available.get(name)
        return p.section.rpartition("/")[2] if p else ""

    def summary(self, name):
        p = self.available.get(name)
        return p.summary if p else ""

    def fill(self):
        self.search_job = None
        keep = self.selected()
        self.tree.delete(*self.tree.get_children())
        rows = self.rows()
        for name, group in rows[:MAX_ROWS]:
            st = pkgdb.state(name, self.available, self.installed)
            p = self.available.get(name)
            size = pkgdb.format_kib(p.installed_size) if p and p.installed_size else ""
            self.tree.insert("", "end", iid=name, tags=(st,),
                             values=(name, group, STATE_TEXT[st], size, self.summary(name)))
        if len(rows) > MAX_ROWS:
            self.say("%d packages; the first %d shown, type to find others" % (len(rows), MAX_ROWS))
        elif not self.busy:
            self.say("%d packages" % len(rows))
        if keep and self.tree.exists(keep):
            self.tree.selection_set(keep)
            self.tree.see(keep)
        self.show_selected()

    def selected(self):
        sel = self.tree.selection()
        return sel[0] if sel else None

    def show_selected(self):
        name = self.selected()
        self.info.configure(state="normal")
        self.info.delete("1.0", "end")
        if name:
            p = self.available.get(name)
            st = pkgdb.state(name, self.available, self.installed)
            head = name
            if p:
                head += " %s   download %s, installed %s" % (
                    p.version, pkgdb.format_bytes(p.size), pkgdb.format_kib(p.installed_size))
            if name in self.installed:
                head += "   (installed: %s)" % self.installed[name]
            self.info.insert("end", head + "\n")
            if p:
                self.info.insert("end", p.summary + ". " + p.description.replace("\n", " "))
            can_install = st in (pkgdb.AVAILABLE, pkgdb.UPDATE)
            self.install_button.configure(text="Update" if st == pkgdb.UPDATE else "Install")
            self.enable(self.install_button, can_install and not self.busy)
            self.enable(self.remove_button, name in self.installed and not self.busy)
        else:
            self.enable(self.install_button, False)
            self.enable(self.remove_button, False)
        self.info.configure(state="disabled")

    @staticmethod
    def enable(button, on):
        button.configure(state="normal" if on else "disabled")

    # ---- install and remove ----

    def install(self):
        name = self.selected()
        if name and not self.busy:
            self.run_job("Installing " + name, pkgdb.install_cmd([name]))

    def remove(self):
        name = self.selected()
        if not name or self.busy:
            return
        self.set_busy(True, "Checking what removing %s takes along..." % name)

        def work():
            try:
                out = subprocess.run(pkgdb.simulate_remove_cmd([name]), stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                     text=True, errors="replace").stdout
            except OSError as e:
                out = "E: %s" % e
            self.queue.put(("simulated", (name, out)))
        threading.Thread(target=work, daemon=True).start()

    def simulated(self, name, out):
        self.set_busy(False, "")
        _, removed = pkgdb.parse_simulation(out)
        if not removed:
            self.append_log(out)
            messagebox.showerror("Flippies Apps", "apt cannot remove %s; see the log below." % name)
            return
        stop = pkgdb.blocked(removed)
        if stop:
            messagebox.showerror("Flippies Apps", "Removing %s would also remove %s, which the "
                                 "desktop needs. Nothing was removed." % (name, ", ".join(stop)))
            return
        others = [n for n in removed if n != name]
        if others:
            shown = ", ".join(others[:12]) + (" and %d more" % (len(others) - 12) if len(others) > 12 else "")
            if not messagebox.askyesno("Flippies Apps", "Removing %s also removes %s. Go on?" % (name, shown)):
                return
        self.run_job("Removing " + name, pkgdb.remove_cmd([name]))

    def run_job(self, title, argv, reload_lists=False):
        """Runs apt-get, its output into the log and its progress lines into the bar."""
        self.set_busy(True, title + "...")
        self.progress.configure(value=0)
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

        def work():
            try:
                p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     stdin=subprocess.DEVNULL, text=True, errors="replace", bufsize=1)
            except OSError as e:
                self.queue.put(("done", (title, 127, str(e), reload_lists)))
                return
            for line in p.stdout:
                self.queue.put(("line", line.rstrip("\n")))
            self.queue.put(("done", (title, p.wait(), None, reload_lists)))
        threading.Thread(target=work, daemon=True).start()

    # ---- the Tk side of the threads ----

    def poll(self):
        try:
            while True:
                kind, data = self.queue.get_nowait()
                self.handle(kind, data)
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def handle(self, kind, data):
        if kind == "line":
            st = pkgdb.parse_status_line(data)
            if st is None:
                if data.strip():
                    self.append_log(data)
            elif st[0] == "error":
                self.append_log("error: " + st[2])
            else:
                self.progress.configure(value=st[1])
                self.say(("Downloading: " if st[0] == "download" else "") + st[2])
        elif kind == "done":
            title, code, err, reload_lists = data
            if err:
                self.append_log(err)
            if code == 0:
                self.progress.configure(value=100)
                self.say(title + ": done")
            else:
                self.say(title + ": failed (see the log)")
                if code == 1 and not err:
                    self.append_log("sudo did not run apt-get as root (it must not ask the desktop "
                                    "user for a password).")
                elif code == 100:
                    self.append_log("apt-get failed. If it could not fetch a file, try Refresh.")
            self.busy = False
            if reload_lists:
                self.load()
            else:
                self.installed = pkgdb.read_installed(self.status)
                self.set_busy(False, None)
                self.fill()
        elif kind == "loaded":
            self.available, self.installed = data
            self.set_busy(False, None)
            self.fill()
            if not self.available:
                self.say("The archive lists nothing; try Refresh")
        elif kind == "update-first":
            self.set_busy(False, None)
            self.refresh()
        elif kind == "simulated":
            self.simulated(*data)
        elif kind == "failed":
            self.set_busy(False, data)

    def set_busy(self, on, text):
        self.busy = on
        if text is not None:
            self.say(text)
        self.enable(self.refresh_button, not on)
        self.show_selected()

    def say(self, text):
        self.message.configure(text=text)

    def append_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")


def main():
    root = tk.Tk(className="FlippiesApps")
    App(root)
    root.mainloop()
