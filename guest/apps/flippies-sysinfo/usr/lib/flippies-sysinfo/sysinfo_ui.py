"""System info: one small window with the PC's CPU, memory, swap, disk, network and uptime,
read again every few seconds. Bars are plain rectangles in two colours, for 8 bits per pixel.
"""

import time
import tkinter as tk

import sysprobe

EVERY_MS = 3000
BG = "#C0C0C0"
FONT = ("Misc Fixed", -13)


class App:
    def __init__(self, root):
        self.root = root
        self.last_net = None
        root.title("System info")
        root.configure(background=BG)
        root.resizable(False, False)
        self.rows = {}
        self.bars = {}
        self.grid = tk.Frame(root, background=BG, padx=8, pady=6)
        self.grid.pack()
        self.r = 0
        for key, label in (("host", "Name"), ("kernel", "Kernel"), ("debian", "Debian"),
                           ("uptime", "Up"), ("load", "Load")):
            self.row(key, label)
        self.cpu_frame = tk.Frame(self.grid, background=BG)
        self.cpu_frame.grid(row=self.r, column=0, columnspan=2, sticky="w")
        self.r += 1
        for key, label in (("mem", "Memory"), ("swap", "Swap"), ("disk", "Disk /")):
            self.row(key, label, bar=True)
        for key, label in (("ip", "Address"), ("gateway", "Gateway"), ("net", "Network")):
            self.row(key, label)
        self.update()

    def row(self, key, label, bar=False):
        tk.Label(self.grid, text=label, font=FONT, background=BG, anchor="w", width=9).grid(
            row=self.r, column=0, sticky="nw")
        cell = tk.Frame(self.grid, background=BG)
        cell.grid(row=self.r, column=1, sticky="w")
        if bar:
            c = tk.Canvas(cell, width=200, height=10, background="#FFFFFF", highlightthickness=1,
                          highlightbackground="#000000")
            c.pack(anchor="w")
            self.bars[key] = c
        value = tk.Label(cell, text="", font=FONT, background=BG, anchor="w", width=40, justify="left")
        value.pack(anchor="w")
        self.rows[key] = value
        self.r += 1

    def set_bar(self, key, used, total, text):
        c = self.bars[key]
        c.delete("all")
        if total:
            c.create_rectangle(0, 0, int(200 * used / total), 11, fill="#000080", width=0)
        self.rows[key].configure(text=text)

    def update(self):
        s = sysprobe.snapshot()
        size = sysprobe.size
        self.rows["host"].configure(text=s["host"])
        self.rows["kernel"].configure(text=s["kernel"])
        self.rows["debian"].configure(text=s["debian"] or "?")
        self.rows["uptime"].configure(text=sysprobe.duration(s["uptime"]) if s["uptime"] is not None else "?")
        self.rows["load"].configure(text=s["load"] or "?")

        if not self.cpu_frame.winfo_children():
            for label, value in s["cpu"] or [("CPU", "?")]:
                tk.Label(self.cpu_frame, text="%-9s%s" % (label, value), font=FONT, background=BG,
                         anchor="w").pack(anchor="w")

        total, avail, swap_total, swap_free = s["mem"]
        if total:
            used = total - (avail or 0)
            self.set_bar("mem", used, total, "%s used of %s" % (size(used), size(total)))
        if swap_total:
            used = swap_total - (swap_free or 0)
            self.set_bar("swap", used, swap_total, "%s used of %s" % (size(used), size(swap_total)))
        else:
            self.set_bar("swap", 0, 0, "none")
        dtotal, dfree = s["disk"]
        if dtotal:
            self.set_bar("disk", dtotal - dfree, dtotal, "%s free of %s" % (size(dfree), size(dtotal)))

        self.rows["ip"].configure(text=s["ip"] or "none (no network modem or cable?)")
        self.rows["gateway"].configure(text=s["gateway"] or "none")
        rx, tx = s["net"]
        if rx is None:
            self.rows["net"].configure(text="no eth0")
        else:
            now = time.monotonic()
            rate = ""
            if self.last_net:
                t0, rx0, tx0 = self.last_net
                dt = max(now - t0, 0.001)
                rate = "  (%s/s in, %s/s out)" % (size((rx - rx0) / dt), size((tx - tx0) / dt))
            self.last_net = (now, rx, tx)
            self.rows["net"].configure(text="%s in, %s out%s" % (size(rx), size(tx), rate))
        self.root.after(EVERY_MS, self.update)


def main():
    root = tk.Tk(className="SysInfo")
    App(root)
    root.mainloop()
