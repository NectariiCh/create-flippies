"""What Shared files knows without Tk: reading a folder's HTML index page from the world's file
share (http://localfilesys.com/, served by the network modem from the world's localfilesys
folder), and downloading a file into a folder under a name that is free there.

An index page is HTML 3.2 with one link per line in a <pre>: "../" at the top except in the top
folder, then folders (links ending in '/'), then files, each followed by its size ("512",
"1.5K", "3.2M"). Links are relative and percent-encoded.
"""

import html
import http.client
import os
import re
import urllib.parse

BASE = "http://localfilesys.com/"

LINK = re.compile(r'<a\s+href="([^"]*)"\s*>(.*?)</a>([^<\n]*)', re.IGNORECASE)


class Entry:
    __slots__ = ("name", "href", "folder", "size")

    def __init__(self, name, href, folder, size):
        self.name = name
        self.href = href
        self.folder = folder
        self.size = size

    def __repr__(self):
        return "Entry(%r, %r, %r, %r)" % (self.name, self.href, self.folder, self.size)


def parse_index(page):
    """The entries of an index page, in page order: relative links into this folder only (no
    parent, no absolute or query links). The name comes from the link, not its text, so a long
    name a server shortened in the text is whole."""
    out = []
    for m in LINK.finditer(page):
        href = html.unescape(m.group(1))
        if not href or href.startswith(("/", "?", "#", "..")) or "://" in href:
            continue
        folder = href.endswith("/")
        name = urllib.parse.unquote(href.rstrip("/"), errors="replace")
        if not name or "/" in name:
            continue
        size = "" if folder else m.group(3).strip().split(" ")[0]
        out.append(Entry(name, href, folder, size))
    return out


def join(url, href):
    return urllib.parse.urljoin(url, href)


def parent(url):
    """The folder above url (a folder URL ending in '/'), or url itself at the top."""
    parts = urllib.parse.urlsplit(url)
    path = parts.path.rstrip("/")
    if not path:
        return url
    path = path[:path.rfind("/") + 1]
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, "", ""))


def shown_path(url):
    """The folder's path for the title bar, decoded."""
    return urllib.parse.unquote(urllib.parse.urlsplit(url).path or "/", errors="replace")


def safe_name(name):
    """A file name that stays inside the download folder: no slashes, no leading dots, no
    control characters."""
    name = "".join(c for c in name if c >= " " and c not in "/\\\x7f")
    name = name.lstrip(".").strip()
    return name or "download"


def free_path(folder, name):
    """folder/name, or "name (2).ext", "name (3).ext"... when that exists already."""
    name = safe_name(name)
    path = os.path.join(folder, name)
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(name)
    n = 2
    while True:
        path = os.path.join(folder, "%s (%d)%s" % (stem, n, ext))
        if not os.path.exists(path):
            return path
        n += 1


def format_size(n):
    """Bytes as 512, 1.5K, 3.2M (as the index pages show sizes)."""
    if n < 1024:
        return str(n)
    for unit in "KMG":
        n /= 1024.0
        if n < 1024 or unit == "G":
            return "%.1f%s" % (n, unit)


class HttpError(Exception):
    pass


def _get(url, timeout):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "http":
        raise HttpError("only http:// is supported: %s" % url)
    conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    conn.request("GET", path, headers={"User-Agent": "flippies-sharedfiles/1.0"})
    resp = conn.getresponse()
    if resp.status != 200:
        conn.close()
        raise HttpError("%s: %d %s" % (shown_path(url), resp.status, resp.reason))
    return conn, resp


def fetch_index(url, timeout=30):
    """The entries of the folder at url (ending in '/')."""
    conn, resp = _get(url, timeout)
    try:
        return parse_index(resp.read().decode("utf-8", errors="replace"))
    finally:
        conn.close()


def download(url, path, progress=None, cancelled=None, timeout=60, block=32768):
    """Downloads url to path through path + ".part", renamed when complete (a part file is
    removed on failure). progress(done, total) is called per block (total None when unknown);
    cancelled() returning true stops it. Returns the bytes written."""
    conn, resp = _get(url, timeout)
    part = path + ".part"
    done = 0
    try:
        length = resp.getheader("Content-Length")
        total = int(length) if length and length.isdigit() else None
        with open(part, "wb") as f:
            while True:
                if cancelled and cancelled():
                    raise HttpError("cancelled")
                data = resp.read(block)
                if not data:
                    break
                f.write(data)
                done += len(data)
                if progress:
                    progress(done, total)
        if total is not None and done != total:
            raise HttpError("the download stopped at %d of %d bytes" % (done, total))
        os.replace(part, path)
        return done
    except BaseException:
        try:
            os.remove(part)
        except OSError:
            pass
        raise
    finally:
        conn.close()
