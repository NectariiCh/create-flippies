"""What System info shows, read from /proc and the file system without Tk: the CPU (the m68k
kernel's /proc/cpuinfo: CPU, MMU, FPU, Clocking, BogoMips), memory and swap, the root file
system, the network (eth0's address and its byte counts) and the uptime. Every reader takes the
text or path it reads, for the tests, and returns None for what it cannot find.
"""

import os
import socket
import struct
import sys

PROC = "/proc"


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def fields(text, sep=":"):
    """"Key: value" lines as a dict (the first of a repeated key)."""
    out = {}
    for line in (text or "").splitlines():
        k, s, v = line.partition(sep)
        if s and k.strip() not in out:
            out[k.strip()] = v.strip()
    return out


def cpu(text):
    """[(label, value)] for the CPU lines, the m68k ones first, then a generic "model name"."""
    f = fields(text)
    out = []
    for key, label in (("CPU", "CPU"), ("FPU", "FPU"), ("MMU", "MMU"), ("Clocking", "Clock"),
                       ("BogoMips", "BogoMips"), ("Model", "Machine"), ("model name", "CPU")):
        if f.get(key) and label not in (l for l, _ in out):
            out.append((label, f[key]))
    return out


def memory(text):
    """(total, available, swap total, swap free) in bytes from /proc/meminfo; available falls
    back to free + buffers + cached on kernels without MemAvailable."""
    f = fields(text)

    def kib(key):
        v = f.get(key, "").split()
        return int(v[0]) * 1024 if v and v[0].isdigit() else None
    total = kib("MemTotal")
    avail = kib("MemAvailable")
    if avail is None and kib("MemFree") is not None:
        avail = kib("MemFree") + (kib("Buffers") or 0) + (kib("Cached") or 0)
    return total, avail, kib("SwapTotal"), kib("SwapFree")


def disk(path="/"):
    """(total, free) bytes of the file system holding path, free as a user sees it."""
    try:
        st = os.statvfs(path)
    except OSError:
        return None, None
    return st.f_blocks * st.f_frsize, st.f_bavail * st.f_frsize


def uptime(text):
    """Seconds since boot from /proc/uptime."""
    try:
        return float(text.split()[0])
    except (AttributeError, IndexError, ValueError):
        return None


def load(text):
    """The 1, 5 and 15 minute load averages as one string."""
    parts = (text or "").split()
    return " ".join(parts[:3]) if len(parts) >= 3 else None


def net_bytes(text, iface="eth0"):
    """(received, sent) bytes of iface from /proc/net/dev."""
    for line in (text or "").splitlines():
        name, sep, rest = line.partition(":")
        if sep and name.strip() == iface:
            v = rest.split()
            if len(v) >= 9:
                return int(v[0]), int(v[8])
    return None, None


def ipv4(iface="eth0"):
    """iface's IPv4 address (SIOCGIFADDR), or None when it has none."""
    try:
        import fcntl
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            req = struct.pack("256s", iface.encode()[:15])
            return socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, req)[20:24])
        finally:
            s.close()
    except OSError:
        return None


def gateway(text, iface="eth0", byteorder=sys.byteorder):
    """The default gateway of iface from /proc/net/route. The kernel prints the address as a
    32-bit number in host order: its bytes come out reversed on a little-endian host and as they
    are on a big-endian one (m68k)."""
    for line in (text or "").splitlines()[1:]:
        v = line.split()
        if len(v) >= 3 and v[0] == iface and v[1] == "00000000":
            try:
                raw = bytes.fromhex(v[2])
            except ValueError:
                return None
            return socket.inet_ntoa(raw[::-1] if byteorder == "little" else raw)
    return None


def duration(seconds):
    """3 d 4 h, 2 h 5 min, 7 min 12 s."""
    s = int(seconds)
    d, s = divmod(s, 86400)
    h, s = divmod(s, 3600)
    m, s = divmod(s, 60)
    if d:
        return "%d d %d h" % (d, h)
    if h:
        return "%d h %d min" % (h, m)
    return "%d min %d s" % (m, s)


def size(n):
    """Bytes as 512 B, 1.5 KiB, 3.2 MiB, 1.0 GiB."""
    if n is None:
        return "?"
    if n < 1024:
        return "%d B" % n
    for unit in ("KiB", "MiB", "GiB"):
        n /= 1024.0
        if n < 1024 or unit == "GiB":
            return "%.1f %s" % (n, unit)


def snapshot(proc=PROC, root="/"):
    """Everything at once, as a dict for the window."""
    mem = memory(read(os.path.join(proc, "meminfo")))
    rx, tx = net_bytes(read(os.path.join(proc, "net/dev")))
    u = os.uname()
    return {
        "host": u.nodename,
        "kernel": "%s %s" % (u.sysname, u.release),
        "debian": (read("/etc/debian_version") or "").strip() or None,
        "cpu": cpu(read(os.path.join(proc, "cpuinfo"))),
        "mem": mem,
        "disk": disk(root),
        "uptime": uptime(read(os.path.join(proc, "uptime"))),
        "load": load(read(os.path.join(proc, "loadavg"))),
        "ip": ipv4(),
        "gateway": gateway(read(os.path.join(proc, "net/route"))),
        "net": (rx, tx),
    }
