import http.server
import os
import tempfile
import threading
import unittest

import paths
import sharelist

# FolderListing.html's output (the mod's index page for a shared folder) for "/games/"
INDEX = """<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">
<html><head>
<meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<title>Index of /games/</title>
</head><body>
<h1>Index of /games/</h1>
<pre>
<a href="../">../</a>
<a href="old%20saves/">old saves/</a>
<a href="a%26b.txt">a&amp;b.txt</a>                                         12
<a href="doom.wad">doom.wad</a>                                          4.0M
<a href="%C3%BCber.png">über.png</a>                                         1.5K
</pre>
<p>More entries are not shown.</p>
</body></html>
"""


class IndexTest(unittest.TestCase):
    def test_parse(self):
        got = [(e.name, e.href, e.folder, e.size) for e in sharelist.parse_index(INDEX)]
        self.assertEqual(got, [
            ("old saves", "old%20saves/", True, ""),
            ("a&b.txt", "a%26b.txt", False, "12"),
            ("doom.wad", "doom.wad", False, "4.0M"),
            ("über.png", "%C3%BCber.png", False, "1.5K"),
        ])

    def test_foreign_links_are_left_out(self):
        page = '<a href="/abs">x</a> <a href="http://x/">y</a> <a href="?C=N">z</a> <a href="a%2Fb">w</a>'
        self.assertEqual(sharelist.parse_index(page), [])

    def test_urls(self):
        base = "http://localfilesys.com/"
        self.assertEqual(sharelist.join(base + "games/", "old%20saves/"), base + "games/old%20saves/")
        self.assertEqual(sharelist.parent(base + "games/old%20saves/"), base + "games/")
        self.assertEqual(sharelist.parent(base + "games/"), base)
        self.assertEqual(sharelist.parent(base), base)
        self.assertEqual(sharelist.shown_path(base + "games/old%20saves/"), "/games/old saves/")

    def test_names(self):
        d = tempfile.mkdtemp()
        self.assertEqual(sharelist.safe_name("../../etc/passwd"), "etcpasswd")
        self.assertEqual(sharelist.safe_name("..."), "download")
        self.assertEqual(sharelist.free_path(d, "a.txt"), os.path.join(d, "a.txt"))
        open(os.path.join(d, "a.txt"), "w").close()
        open(os.path.join(d, "a (2).txt"), "w").close()
        self.assertEqual(sharelist.free_path(d, "a.txt"), os.path.join(d, "a (3).txt"))
        self.assertEqual(sharelist.format_size(512), "512")
        self.assertEqual(sharelist.format_size(1536), "1.5K")
        self.assertEqual(sharelist.format_size(4 * 1048576), "4.0M")


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/games/":
            body = INDEX.encode()
        elif self.path == "/games/doom.wad":
            body = bytes(range(256)) * 400
        elif self.path == "/short":
            self.send_response(200)
            self.send_header("Content-Length", "1000")
            self.end_headers()
            self.wfile.write(b"x" * 10)
            return
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.base = "http://127.0.0.1:%d/" % cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_fetch_and_download(self):
        entries = sharelist.fetch_index(self.base + "games/")
        self.assertEqual([e.name for e in entries], ["old saves", "a&b.txt", "doom.wad", "über.png"])
        d = tempfile.mkdtemp()
        path = os.path.join(d, "doom.wad")
        seen = []
        n = sharelist.download(self.base + "games/doom.wad", path, lambda got, total: seen.append((got, total)),
                               block=8192)
        self.assertEqual(n, 102400)
        with open(path, "rb") as f:
            self.assertEqual(f.read(), bytes(range(256)) * 400)
        self.assertEqual(seen[-1], (102400, 102400))
        self.assertEqual(os.listdir(d), ["doom.wad"])

    def test_errors_leave_no_part_file(self):
        d = tempfile.mkdtemp()
        with self.assertRaises(sharelist.HttpError):
            sharelist.fetch_index(self.base + "missing/")
        with self.assertRaises(sharelist.HttpError):
            sharelist.download(self.base + "games/doom.wad", os.path.join(d, "x"), cancelled=lambda: True)
        with self.assertRaises(Exception):
            sharelist.download(self.base + "short", os.path.join(d, "y"))
        self.assertEqual(os.listdir(d), [])


if __name__ == "__main__":
    unittest.main()
