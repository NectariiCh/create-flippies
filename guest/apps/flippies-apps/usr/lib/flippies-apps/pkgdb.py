"""What Flippies Apps knows about packages, without Tk: the archive's package lists as apt
downloaded them (from http://deb.flip/), dpkg's record of what is installed, Debian version order,
apt's machine-readable progress lines and its simulated removals, and the commands it runs.
"""

import glob
import os
import re

LISTS = "/var/lib/apt/lists"
STATUS = "/var/lib/dpkg/status"

# What the package center never removes: the desktop itself, the package tools and itself.
# Removing any of these (or a package whose removal takes one of them along) is refused.
PROTECTED = frozenset("""
flippies-apps flippies-desktop flippies-boot python3 python3-tk jwm xterm xinit
xserver-xorg-core xserver-xorg-video-fbdev xserver-xorg-input-evdev sudo apt dpkg udev
sysvinit-core ifupdown udhcpc wget
""".split())

NAME = re.compile(r"^[a-z0-9][a-z0-9+.-]+$")


class Package:
    __slots__ = ("name", "version", "section", "size", "installed_size", "summary", "description",
                 "maintainer")

    def __init__(self, fields):
        self.name = fields.get("Package", "")
        self.version = fields.get("Version", "")
        self.section = fields.get("Section", "")
        self.size = _int(fields.get("Size"))
        self.installed_size = _int(fields.get("Installed-Size"))
        self.maintainer = fields.get("Maintainer", "")
        desc = fields.get("Description", "")
        first, _, rest = desc.partition("\n")
        self.summary = first.strip()
        self.description = _long_description(rest)


def _int(s):
    try:
        return int(s)
    except (TypeError, ValueError):
        return 0


def _long_description(text):
    """A control file's extended description as plain text: " ." lines are blank lines."""
    lines = []
    for line in text.split("\n"):
        line = line[1:] if line.startswith(" ") else line
        lines.append("" if line.strip() == "." else line)
    return "\n".join(lines).strip()


def stanzas(lines, keep=None):
    """The paragraphs of a Packages or status file, as dicts of the fields in keep (all when None).
    A continuation line belongs to the field before it."""
    fields = {}
    key = None
    for line in lines:
        line = line.rstrip("\n")
        if not line:
            if fields:
                yield fields
            fields = {}
            key = None
            continue
        if line[0] in " \t":
            if key is not None:
                fields[key] += "\n" + line
            continue
        k, sep, v = line.partition(":")
        if not sep:
            key = None
            continue
        if keep is None or k in keep:
            key = k
            fields[k] = v.strip()
        else:
            key = None
    if fields:
        yield fields


PACKAGE_FIELDS = frozenset(("Package", "Version", "Section", "Size", "Installed-Size", "Description",
                            "Maintainer"))


def _open_list(path):
    if path.endswith(".gz"):
        import gzip
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    if path.endswith(".xz"):
        import lzma
        return lzma.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, encoding="utf-8", errors="replace")


def list_files(lists_dir=LISTS):
    """apt's downloaded package lists (any compression Python reads)."""
    found = []
    for pattern in ("*_Packages", "*_Packages.gz", "*_Packages.xz"):
        found.extend(glob.glob(os.path.join(lists_dir, pattern)))
    return sorted(found)


def read_available(lists_dir=LISTS):
    """Every package in the lists, by name; the newest version when a name is there twice."""
    pkgs = {}
    for path in list_files(lists_dir):
        with _open_list(path) as f:
            for fields in stanzas(f, PACKAGE_FIELDS):
                p = Package(fields)
                if not p.name:
                    continue
                old = pkgs.get(p.name)
                if old is None or compare_versions(p.version, old.version) > 0:
                    pkgs[p.name] = p
    return pkgs


def read_installed(status=STATUS):
    """Installed packages from dpkg's status file: name to version. Only "install ok installed"
    counts (not removed ones that left their configuration, nor half-installed ones)."""
    out = {}
    try:
        with open(status, encoding="utf-8", errors="replace") as f:
            for fields in stanzas(f, ("Package", "Status", "Version")):
                if fields.get("Status", "").split()[-1:] == ["installed"]:
                    out[fields.get("Package", "")] = fields.get("Version", "")
    except FileNotFoundError:
        pass
    return out


# ---- Debian version order (dpkg's verrevcmp) ----

def _order(c):
    if c == "" or _isdigit(c):
        return 0
    if c == "~":
        return -1
    if c.isalpha():
        return ord(c)
    return ord(c) + 256


def _isdigit(c):
    return c != "" and "0" <= c <= "9"


def _verrevcmp(a, b):
    i = j = 0
    while i < len(a) or j < len(b):
        first_diff = 0
        while (i < len(a) and not _isdigit(a[i])) or (j < len(b) and not _isdigit(b[j])):
            ac = _order(a[i] if i < len(a) else "")
            bc = _order(b[j] if j < len(b) else "")
            if ac != bc:
                return ac - bc
            i += 1
            j += 1
        while i < len(a) and a[i] == "0":
            i += 1
        while j < len(b) and b[j] == "0":
            j += 1
        while i < len(a) and j < len(b) and _isdigit(a[i]) and _isdigit(b[j]):
            if not first_diff:
                first_diff = ord(a[i]) - ord(b[j])
            i += 1
            j += 1
        if i < len(a) and _isdigit(a[i]):
            return 1
        if j < len(b) and _isdigit(b[j]):
            return -1
        if first_diff:
            return first_diff
    return 0


def _split_version(v):
    epoch, sep, rest = v.partition(":")
    if not sep:
        epoch, rest = "0", v
    upstream, sep, revision = rest.rpartition("-")
    if not sep:
        upstream, revision = rest, ""
    return _int(epoch), upstream, revision


def compare_versions(a, b):
    """Negative, zero or positive as Debian version a sorts before, with or after b."""
    ea, ua, ra = _split_version(a)
    eb, ub, rb = _split_version(b)
    if ea != eb:
        return ea - eb
    return _verrevcmp(ua, ub) or _verrevcmp(ra, rb)


# ---- the shown state ----

AVAILABLE, INSTALLED, UPDATE, LOCAL = "available", "installed", "update", "local"


def state(name, available, installed):
    """available (in the archive, not installed), installed, update (a newer version in the
    archive) or local (installed but not in the archive)."""
    have = installed.get(name)
    pkg = available.get(name)
    if have is None:
        return AVAILABLE
    if pkg is None:
        return LOCAL
    return UPDATE if compare_versions(pkg.version, have) > 0 else INSTALLED


def format_kib(kib):
    """Installed-Size (KiB) as 840K, 3.2M."""
    if kib < 1000:
        return "%dK" % kib
    if kib < 1024 * 1000:
        return "%.1fM" % (kib / 1024)
    return "%.1fG" % (kib / 1048576)


def format_bytes(n):
    return format_kib((n + 1023) // 1024) if n else "0K"


# ---- the list of apps shown first ----

def read_featured(path):
    """featured.txt: "package group" per line, '#' comments. In file order."""
    out = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if not line:
                    continue
                name, _, group = line.partition(" ")
                out.append((name, group.strip() or "Other"))
    except FileNotFoundError:
        pass
    return out


def featured_rows(featured, available, installed):
    """The Apps view: the featured packages the archive has, then any other package of ours
    (maintained at deb.flip), each with its group."""
    rows = []
    seen = set()
    for name, group in featured:
        if name in available and name not in seen:
            rows.append((name, group))
            seen.add(name)
    for name in sorted(available):
        if name not in seen and "@deb.flip" in available[name].maintainer:
            rows.append((name, "Flippies"))
            seen.add(name)
    return rows


# ---- apt's output ----

def parse_status_line(line):
    """An APT::Status-Fd line: ("download" or "install", percent, message), ("error", 0, message),
    or None for any other line. Formats: dlstatus:<n>:<percent>:<text>,
    pmstatus:<package>:<percent>:<text>, pmerror:<package>:<percent>:<text>."""
    kind, sep, rest = line.partition(":")
    if not sep or kind not in ("dlstatus", "pmstatus", "pmerror", "pmconffile", "media-change"):
        return None
    parts = rest.split(":", 2)
    if kind == "media-change" or len(parts) < 3:
        return ("error", 0.0, rest)
    try:
        pct = float(parts[1])
    except ValueError:
        pct = 0.0
    if kind == "dlstatus":
        return ("download", pct, parts[2])
    if kind == "pmstatus":
        return ("install", pct, parts[2])
    return ("error", pct, parts[0] + ": " + parts[2])


SIM_LINE = re.compile(r"^(Inst|Remv|Purg) (\S+)")


def parse_simulation(text):
    """apt-get -s output: (names installed or upgraded, names removed)."""
    inst, remv = [], []
    for line in text.splitlines():
        m = SIM_LINE.match(line)
        if m:
            (inst if m.group(1) == "Inst" else remv).append(m.group(2))
    return inst, remv


def blocked(removed):
    """The protected packages in a removal (kernel images count as protected)."""
    return [n for n in removed if n in PROTECTED or n.startswith("linux-image-")]


# ---- the commands ----

APT_OPTS = ["-q", "-y", "-o", "APT::Status-Fd=3", "-o", "Dpkg::Use-Pty=0",
            "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"]


def _names(names):
    bad = [n for n in names if not NAME.match(n)]
    if bad:
        raise ValueError("not a package name: %s" % bad[0])
    return list(names)


def as_root(argv):
    """sudo without a password prompt (the desktop user has passwordless sudo), apt never asking
    a question. apt's progress lines go to fd 3, which a root shell points at standard output:
    apt marks its status fd close-on-exec, so with fd 1 dpkg and the maintainer scripts would run
    with standard output closed; and sudo closes every fd above 2 it is given."""
    return ["sudo", "-n", "env", "DEBIAN_FRONTEND=noninteractive", "LC_ALL=C",
            "sh", "-c", 'exec 3>&1; exec "$@"', "sh"] + argv


def update_cmd():
    return as_root(["apt-get", "-q", "-o", "APT::Status-Fd=3", "update"])


def install_cmd(names):
    return as_root(["apt-get"] + APT_OPTS + ["install"] + _names(names))


# One removal for both the check and the real thing, so the dialog and the protected list see
# every package apt takes, autoremoved ones (anything no longer needed, not only this app's) too.
REMOVE = ["--autoremove", "remove"]


def remove_cmd(names):
    """Removes the packages and whatever is no longer needed."""
    return as_root(["apt-get"] + APT_OPTS + REMOVE + _names(names))


def simulate_remove_cmd(names):
    """What remove_cmd would take along (no root needed)."""
    return ["env", "LC_ALL=C", "apt-get", "-s", "-q"] + REMOVE + _names(names)
