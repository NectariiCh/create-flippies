"""Shared files: browses the world's file share (http://localfilesys.com/, the localfilesys
folder of the Minecraft world, served by the network modem) and downloads files to ~/Downloads.
Double-click a folder to open it, a file to download it. Network work runs in a thread; only the
Tk thread touches widgets, fed through a queue.
"""

import os
import queue
import shutil
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import ttk

import sharelist

DOWNLOADS = os.path.expanduser("~/Downloads")
# Tried in order to show the Downloads folder
FILE_MANAGERS = ("xdg-open", "pcmanfm", "xfe")


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
    def __init__(self, root, base):
        self.root = root
        self.url = base
        self.entries = {}
        self.queue = queue.Queue()
        self.busy = False
        self.cancel = False

        root.title("Shared files")
        root.geometry("520x380")
        root.minsize(360, 240)
        setup_tk(root)

        top = ttk.Frame(root, padding=(4, 4, 4, 0))
        top.pack(fill="x")
        self.up_button = ttk.Button(top, text="Up", width=4, command=self.up)
        self.up_button.pack(side="left")
        self.where = ttk.Label(top, text="")
        self.where.pack(side="left", padx=6, fill="x", expand=True)
        ttk.Button(top, text="Refresh", command=lambda: self.open(self.url)).pack(side="right")

        mid = ttk.Frame(root, padding=4)
        mid.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(mid, columns=("size",), selectmode="extended")
        self.tree.heading("#0", text="Name", anchor="w")
        self.tree.heading("size", text="Size", anchor="e")
        self.tree.column("#0", width=380)
        self.tree.column("size", width=80, stretch=False, anchor="e")
        self.tree.tag_configure("folder", foreground="#000080")
        bar = ttk.Scrollbar(mid, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=bar.set)
        self.tree.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", self.activate)
        self.tree.bind("<Return>", self.activate)

        act = ttk.Frame(root, padding=4)
        act.pack(fill="x")
        self.get_button = ttk.Button(act, text="Download", command=self.download_selected)
        self.get_button.pack(side="left")
        self.stop_button = ttk.Button(act, text="Stop", command=self.stop, state="disabled")
        self.stop_button.pack(side="left", padx=4)
        ttk.Button(act, text="Open Downloads", command=self.show_downloads).pack(side="right")
        self.progress = ttk.Progressbar(act, length=120, maximum=100)
        self.progress.pack(side="left", padx=4)

        self.message = ttk.Label(root, text="", padding=(4, 0, 4, 4))
        self.message.pack(fill="x")

        root.after(100, self.poll)
        self.open(self.url)

    # ---- browsing ----

    def open(self, url):
        if self.busy:
            return
        self.set_busy(True, "Opening %s ..." % sharelist.shown_path(url))

        def work():
            try:
                self.queue.put(("listed", (url, sharelist.fetch_index(url))))
            except Exception as e:  # network and server errors are shown, never fatal
                self.queue.put(("failed", "Could not open %s: %s" % (url, e)))
        threading.Thread(target=work, daemon=True).start()

    def listed(self, url, entries):
        self.url = url
        self.entries = {}
        self.tree.delete(*self.tree.get_children())
        for i, e in enumerate(entries):
            iid = str(i)
            self.entries[iid] = e
            self.tree.insert("", "end", iid=iid, text=e.name + ("/" if e.folder else ""),
                             values=("" if e.folder else e.size,), tags=("folder",) if e.folder else ())
        self.where.configure(text="localfilesys " + sharelist.shown_path(url))
        self.root.title("Shared files: " + sharelist.shown_path(url))
        files = sum(1 for e in entries if not e.folder)
        self.say("%d folders, %d files" % (len(entries) - files, files) if entries else "This folder is empty")

    def up(self):
        if sharelist.parent(self.url) != self.url:
            self.open(sharelist.parent(self.url))

    def activate(self, _event=None):
        sel = self.tree.selection()
        if len(sel) != 1:
            return "break"
        e = self.entries.get(sel[0])
        if e and e.folder:
            self.open(sharelist.join(self.url, e.href))
        elif e:
            self.download([e])
        return "break"

    # ---- downloading ----

    def download_selected(self):
        files = [self.entries[i] for i in self.tree.selection() if not self.entries[i].folder]
        if not files:
            self.say("Select one or more files (folders are opened, not downloaded)")
            return
        self.download(files)

    def download(self, files):
        if self.busy:
            return
        jobs = [(e.name, sharelist.join(self.url, e.href)) for e in files]
        self.cancel = False
        self.set_busy(True, "Downloading...")
        self.enable(self.stop_button, True)

        def work():
            done = []
            try:
                os.makedirs(DOWNLOADS, exist_ok=True)
                for n, (name, url) in enumerate(jobs):
                    path = sharelist.free_path(DOWNLOADS, name)

                    def progress(got, total, name=name, n=n):
                        self.queue.put(("progress", (n, len(jobs), name, got, total)))
                    sharelist.download(url, path, progress, lambda: self.cancel)
                    done.append(os.path.basename(path))
                self.queue.put(("downloaded", done))
            except Exception as e:  # network, server and disk errors are shown, never fatal
                self.queue.put(("failed", "Download stopped (%s); %d of %d done" % (e, len(done), len(jobs))))
        threading.Thread(target=work, daemon=True).start()

    def stop(self):
        self.cancel = True

    def show_downloads(self):
        os.makedirs(DOWNLOADS, exist_ok=True)
        for prog in FILE_MANAGERS:
            if shutil.which(prog):
                subprocess.Popen([prog, DOWNLOADS], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
        self.say("No file manager installed (Flippies Apps has some); files are in ~/Downloads")

    # ---- the Tk side of the thread ----

    def poll(self):
        try:
            while True:
                kind, data = self.queue.get_nowait()
                if kind == "listed":
                    self.set_busy(False, None)
                    self.listed(*data)
                elif kind == "progress":
                    n, count, name, got, total = data
                    pct = 100.0 * got / total if total else 0
                    self.progress.configure(value=pct)
                    what = "%s of %s" % (sharelist.format_size(got), sharelist.format_size(total)) if total else sharelist.format_size(got)
                    self.say("%s%s: %s" % ("(%d/%d) " % (n + 1, count) if count > 1 else "", name, what))
                elif kind == "downloaded":
                    self.set_busy(False, "Saved in ~/Downloads: " + ", ".join(data))
                    self.progress.configure(value=100)
                elif kind == "failed":
                    self.set_busy(False, data)
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def set_busy(self, on, text):
        self.busy = on
        if text is not None:
            self.say(text)
        self.enable(self.get_button, not on)
        self.enable(self.up_button, not on)
        if not on:
            self.enable(self.stop_button, False)

    @staticmethod
    def enable(widget, on):
        widget.configure(state="normal" if on else "disabled")

    def say(self, text):
        self.message.configure(text=text)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else sharelist.BASE
    if not base.endswith("/"):
        base += "/"
    root = tk.Tk(className="SharedFiles")
    App(root, base)
    root.mainloop()
