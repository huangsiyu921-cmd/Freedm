"""Range-capable test origin for verifying segmented download + resume.

Prints every request line (including the Range header) so the client's resume
behaviour can be proven from the server side, not inferred.

Usage:
    python range_server.py [size_bytes] [port] [--slow BYTES_PER_SEC]

The payload is deterministic, so its sha256 is printed once at startup and can be
compared against the file the app wrote.
"""
import hashlib
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = 50 * 1024 * 1024
PORT = 8000
SLOW = 0

args = [a for a in sys.argv[1:] if not a.startswith("--")]
flags = [a for a in sys.argv[1:] if a.startswith("--")]
if args:
    SIZE = int(args[0])
if len(args) > 1:
    PORT = int(args[1])
for f in flags:
    if f.startswith("--slow"):
        SLOW = int(f.split("=", 1)[1]) if "=" in f else 0

# 1 MiB repeating block -> cheap to build, still content-verifiable.
_block = bytes(range(256)) * 4096
DATA = (_block * (SIZE // len(_block) + 1))[:SIZE]
SHA256 = hashlib.sha256(DATA).hexdigest()
ETAG = '"' + hashlib.md5(DATA).hexdigest() + '"'

_lock = threading.Lock()


def log(msg):
    with _lock:
        print(msg, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass  # replaced by log()

    def _headers(self, code, start, end, total):
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", ETAG)
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()

    def do_HEAD(self):
        log(f"HEAD  from={self.client_address[0]}  (full={SIZE})")
        self._headers(200, 0, SIZE - 1, SIZE)

    def do_GET(self):
        rng = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        log(f"GET   from={self.client_address[0]}  Range={rng}  If-Range={if_range}")

        # If-Range mismatch must fall back to a full 200.
        if rng and if_range and if_range != ETAG:
            log("      If-Range mismatch -> 200 full body")
            rng = None

        start, end = 0, SIZE - 1
        code = 200
        if rng and rng.startswith("bytes="):
            spec = rng[len("bytes="):].split(",")[0]
            a, _, b = spec.partition("-")
            try:
                if a == "":
                    start = max(0, SIZE - int(b))
                else:
                    start = int(a)
                    end = int(b) if b else SIZE - 1
            except ValueError:
                start, end = 0, SIZE - 1
            end = min(end, SIZE - 1)
            if start > end or start >= SIZE:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{SIZE}")
                self.end_headers()
                return
            code = 206
            log(f"      -> 206 bytes {start}-{end}/{SIZE} ({end - start + 1} B)")

        self._headers(code, start, end, SIZE)
        payload = memoryview(DATA)[start:end + 1]
        chunk = 32 * 1024
        sent = 0
        t0 = time.time()
        try:
            for off in range(0, len(payload), chunk):
                self.wfile.write(payload[off:off + chunk])
                sent += len(payload[off:off + chunk])
                if SLOW > 0:
                    expected = sent / SLOW
                    actual = time.time() - t0
                    if expected > actual:
                        time.sleep(expected - actual)
        except (BrokenPipeError, ConnectionResetError):
            log(f"      client aborted after {sent} B (retry expected)")


if __name__ == "__main__":
    print(f"serving {SIZE} bytes on 0.0.0.0:{PORT}")
    print(f"sha256={SHA256}")
    print(f"etag={ETAG}")
    if SLOW:
        print(f"throttle={SLOW} B/s")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
