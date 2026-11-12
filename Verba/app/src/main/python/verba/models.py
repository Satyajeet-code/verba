"""Speech-to-text model catalog, download and install.

Most models are sherpa-onnx packages published on the sherpa-onnx GitHub
releases page. The Hinglish models are converted by this project's own
"Speech models" GitHub workflow and published on the app repository's
Releases page (tag ``models``) together with a small JSON manifest that holds
the archive's size and SHA-256. Installing one means:

1. download the .tar.bz2 archive (resumable: an interrupted download continues
   from where it stopped),
2. check its size and SHA-256,
3. extract only the files the app needs into a staging folder,
4. move the folder into place and write an ``.installed`` marker,
5. delete the archive.

Progress and cancellation go through a ``sink`` object supplied by the caller
(the Kotlin download service). It needs two methods:
``onProgress(phase: str, done: int, total: int)`` and ``isCancelled() -> bool``.
"""

import hashlib
import json
import os
import shutil
import tarfile
import time
import urllib.error
import urllib.request

RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

# GitHub repository whose "models" release holds the converted Hinglish models.
try:
    from ._repo import REPO          # optional override written at build time
except ImportError:
    REPO = "Satyajeet-code/verba"
PUBLISHED = "https://github.com/%s/releases/download/models/" % REPO

_VAD = {
    "url": RELEASES + "silero_vad.onnx",
    "size": 643854,
    "sha256": "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    "name": "silero_vad.onnx",
}
_QWEN_FILES = {
    "conv_frontend": "conv_frontend.onnx",
    "encoder": "encoder.int8.onnx",
    "decoder": "decoder.int8.onnx",
    "tokenizer": "tokenizer",
    "vad": "silero_vad.onnx",
}

CATALOG = [
    {
        "id": "hinglish",
        "name": "Srota Hinglish",
        "vendor": "Qwen3-ASR 0.6B, Hinglish-tuned",
        "mode": "pause",
        # Trained with the "language None" prompt: it picks the script word by word.
        "prompt": "none",
        "tagline": "Best for Hindi and English mixed in one sentence",
        "detail": "Qwen3-ASR 0.6B retrained on 95 hours of Indian Hinglish speech. "
                  "Hindi words come out in Devanagari and English words in English, "
                  "so the language setting is not needed. Same speed as Qwen3-ASR.",
        "license": "Apache-2.0",
        "ram_mb": 1600,
        "archive": {"published": "verba-hinglish-srota-0.6b-int8", "approx_size": 840 * 2**20},
        "files": dict(_QWEN_FILES),
        "extras": [dict(_VAD)],
        "installed_mb": 950,
    },
    {
        "id": "nemotron-live",
        "name": "Nemotron 3.5 ASR",
        "vendor": "NVIDIA",
        "mode": "live",
        "tagline": "Live: words appear while you speak",
        "detail": "Streaming model. Best for Hindi in our tests (2.7% character errors). "
                  "Auto-detects Hindi or English; also knows 38 other languages.",
        "license": "OpenMDW-1.1",
        "ram_mb": 1000,
        "archive": {
            "url": RELEASES + "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
            "size": 475271763,
            "sha256": "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a",
            "root": "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        },
        "files": {
            "encoder": "encoder.int8.onnx",
            "decoder": "decoder.int8.onnx",
            "joiner": "joiner.int8.onnx",
            "tokens": "tokens.txt",
        },
        "extras": [],
        "installed_mb": 653,
    },
    {
        "id": "qwen3-accurate",
        "name": "Qwen3-ASR 0.6B",
        "vendor": "Alibaba Qwen",
        "mode": "pause",
        "tagline": "Accurate: writes each sentence when you pause",
        "detail": "Stronger on English and Hinglish (keeps English words in English). "
                  "Slower; text appears a moment after each pause.",
        "license": "Apache-2.0",
        "ram_mb": 1600,
        "archive": {
            "url": RELEASES + "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            "size": 878702423,
            "sha256": "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
            "root": "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        },
        "files": dict(_QWEN_FILES),
        # Voice-activity detector used to cut speech at pauses.
        "extras": [dict(_VAD)],
        "installed_mb": 955,
    },
    {
        "id": "hinglish-casual",
        "name": "Srota-Conv Hinglish",
        "vendor": "Qwen3-ASR 0.6B, Hinglish-tuned",
        "mode": "pause",
        "prompt": "none",
        "tagline": "Hinglish, tuned only on casual everyday talk",
        "detail": "A sibling of Srota trained only on conversational Hinglish. "
                  "Try it if Srota misses words in quick, informal notes; "
                  "it can be weaker on long, formal sentences.",
        "license": "Apache-2.0",
        "ram_mb": 1600,
        "archive": {"published": "verba-hinglish-srota-conv-0.6b-int8", "approx_size": 840 * 2**20},
        "files": dict(_QWEN_FILES),
        "extras": [dict(_VAD)],
        "installed_mb": 950,
    },
]

_BY_ID = {m["id"]: m for m in CATALOG}
CHUNK = 256 * 1024
MARKER = ".installed"


class Cancelled(Exception):
    pass


class InstallError(Exception):
    pass


class _NullSink:
    def onProgress(self, phase, done, total):
        pass

    def isCancelled(self):
        return False


def get(model_id):
    try:
        return _BY_ID[model_id]
    except KeyError:
        raise InstallError("Unknown model: %s" % model_id)


def model_dir(root, model_id):
    return os.path.join(root, model_id)


def _part_path(root, model_id):
    return os.path.join(root, ".downloads", model_id + ".tar.bz2.part")


def is_installed(root, model_id):
    m = get(model_id)
    d = model_dir(root, model_id)
    if not os.path.isfile(os.path.join(d, MARKER)):
        return False
    return all(os.path.exists(os.path.join(d, rel)) for rel in m["files"].values())


def paths(root, model_id):
    """Absolute paths of the files a recognizer needs, keyed by role."""
    if not is_installed(root, model_id):
        raise InstallError("Model is not downloaded: %s" % model_id)
    d = model_dir(root, model_id)
    out = {k: os.path.join(d, rel) for k, rel in get(model_id)["files"].items()}
    out["id"] = model_id
    out["mode"] = get(model_id)["mode"]
    out["prompt"] = get(model_id).get("prompt", "")
    return out


def status(root):
    out = []
    for m in CATALOG:
        part = _part_path(root, m["id"])
        item = {k: m[k] for k in ("id", "name", "vendor", "mode", "tagline", "detail",
                                  "license", "ram_mb", "installed_mb")}
        arc = m["archive"]
        item["download_bytes"] = arc.get("size", arc.get("approx_size", 0)) + sum(e["size"] for e in m["extras"])
        item["installed"] = is_installed(root, m["id"])
        item["partial_bytes"] = os.path.getsize(part) if os.path.exists(part) else 0
        out.append(item)
    return out


def free_bytes(root):
    os.makedirs(root, exist_ok=True)
    st = os.statvfs(root)
    return st.f_bavail * st.f_frsize


def delete(root, model_id):
    get(model_id)
    shutil.rmtree(model_dir(root, model_id), ignore_errors=True)
    shutil.rmtree(os.path.join(root, ".staging", model_id), ignore_errors=True)
    try:
        os.remove(_part_path(root, model_id))
    except FileNotFoundError:
        pass
    return True


# ---------------------------------------------------------------------- install
def install(root, model_id, sink=None, opener=None):
    """Download and install a model. Blocking; call from a background thread."""
    sink = sink or _NullSink()
    m = get(model_id)
    if is_installed(root, model_id):
        sink.onProgress("done", 1, 1)
        return paths(root, model_id)

    arc = _resolve_archive(m, opener)
    part = _part_path(root, model_id)
    os.makedirs(os.path.dirname(part), exist_ok=True)
    have = os.path.getsize(part) if os.path.exists(part) else 0
    need = (arc["size"] - have) + m["installed_mb"] * 1024 * 1024 + 64 * 1024 * 1024
    if free_bytes(root) < need:
        raise InstallError(
            "Not enough free storage: need about %d MB more." % ((need - free_bytes(root)) // 2**20 + 1)
        )

    _download(arc["url"], part, arc["size"], sink, "download", opener)
    _verify(part, arc["size"], arc["sha256"], sink)

    staging = os.path.join(root, ".staging", model_id)
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging)
    _extract(part, arc["root"], set(m["files"].values()), staging, sink,
             gz=arc["url"].endswith((".tar.gz", ".tgz")))

    for extra in m["extras"]:
        target = os.path.join(staging, extra["name"])
        _download(extra["url"], target + ".part", extra["size"], sink, "extras", opener)
        _verify(target + ".part", extra["size"], extra["sha256"], None)
        os.replace(target + ".part", target)

    missing = [rel for rel in m["files"].values() if not os.path.exists(os.path.join(staging, rel))]
    if missing:
        raise InstallError("Archive is missing: %s" % ", ".join(missing))

    with open(os.path.join(staging, MARKER), "w") as f:
        json.dump({"id": model_id, "sha256": arc["sha256"], "installed_at": int(time.time())}, f)
    final = model_dir(root, model_id)
    shutil.rmtree(final, ignore_errors=True)
    os.replace(staging, final)
    os.remove(part)
    sink.onProgress("done", 1, 1)
    return paths(root, model_id)


def _resolve_archive(m, opener=None):
    """URL, size, SHA-256 and top folder of a model's archive.

    Models converted by this project's workflow are described by a JSON
    manifest next to the archive on the repository's "models" release.
    """
    arc = m["archive"]
    if "published" not in arc:
        return arc
    base = arc.get("base", PUBLISHED)
    url = base + arc["published"] + ".json"
    opener = opener or urllib.request.build_opener()
    req = urllib.request.Request(url, headers={"User-Agent": "Verba/1.0", "Cache-Control": "no-cache"})
    try:
        with opener.open(req, timeout=30) as resp:
            info = json.loads(resp.read(64 * 1024).decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise InstallError(
                "This model is not on your GitHub Releases page yet. Add the \"Speech models\" "
                "workflow to the repository and let it finish (about 30 minutes), then try again."
            )
        raise InstallError("Could not reach GitHub: %s" % e)
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise InstallError("Could not read the model's download details: %s" % e)
    try:
        size, sha = int(info["size"]), str(info["sha256"]).lower()
        name, top = str(info["file"]), str(info.get("root") or arc["published"])
    except (KeyError, TypeError, ValueError):
        raise InstallError("The model's download details are incomplete; run the Speech models workflow again.")
    if len(sha) != 64 or "/" in name or "/" in top or size <= 0:
        raise InstallError("The model's download details look wrong; run the Speech models workflow again.")
    return {"url": base + name, "size": size, "sha256": sha, "root": top}


def _check_cancel(sink):
    if sink is not None and sink.isCancelled():
        raise Cancelled()


def _download(url, part, total, sink, phase, opener=None, retries=4):
    """Resumable HTTP download into ``part``."""
    opener = opener or urllib.request.build_opener()
    attempt = 0
    while True:
        have = os.path.getsize(part) if os.path.exists(part) else 0
        if have == total:
            return
        if have > total:
            os.remove(part)
            have = 0
        req = urllib.request.Request(url, headers={"User-Agent": "Verba/1.0"})
        if have:
            req.add_header("Range", "bytes=%d-" % have)
        try:
            with opener.open(req, timeout=30) as resp:
                code = getattr(resp, "status", None) or resp.getcode()
                if have and code != 206:          # server ignored Range: start over
                    have = 0
                mode = "ab" if have else "wb"
                with open(part, mode) as out:
                    done = have
                    last = 0.0
                    while True:
                        _check_cancel(sink)
                        buf = resp.read(CHUNK)
                        if not buf:
                            break
                        out.write(buf)
                        done += len(buf)
                        now = time.monotonic()
                        if sink is not None and now - last > 0.25:
                            sink.onProgress(phase, done, total)
                            last = now
            if os.path.getsize(part) == total:
                if sink is not None:
                    sink.onProgress(phase, total, total)
                return
            raise InstallError("Download stopped early")
        except Cancelled:
            raise
        except (urllib.error.URLError, OSError, InstallError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code == 416:
                os.remove(part)               # our partial file is bad
            attempt += 1
            if attempt > retries:
                raise InstallError("Download failed: %s" % e)
            for _ in range(int(2 ** attempt * 4)):   # back off, still cancellable
                _check_cancel(sink)
                time.sleep(0.25)


def _verify(path, size, sha256, sink):
    if os.path.getsize(path) != size:
        os.remove(path)
        raise InstallError("Downloaded file has the wrong size; please try again.")
    h = hashlib.sha256()
    done = 0
    with open(path, "rb") as f:
        while True:
            _check_cancel(sink)
            buf = f.read(4 * CHUNK)
            if not buf:
                break
            h.update(buf)
            done += len(buf)
            if sink is not None and done % (64 * CHUNK) < 4 * CHUNK:
                sink.onProgress("verify", done, size)
    if sink is not None:
        sink.onProgress("verify", size, size)
    if h.hexdigest() != sha256:
        os.remove(path)
        raise InstallError("Downloaded file is corrupted (checksum mismatch); please try again.")


class _CountingReader:
    def __init__(self, f, sink, total):
        self.f, self.sink, self.total, self.done, self.last = f, sink, total, 0, 0.0

    def read(self, n=-1):
        _check_cancel(self.sink)
        buf = self.f.read(n)
        self.done += len(buf)
        now = time.monotonic()
        if self.sink is not None and now - self.last > 0.25:
            self.sink.onProgress("extract", self.done, self.total)
            self.last = now
        return buf


def _extract(archive, root_name, wanted, dest, sink, gz=False):
    """Stream-extract only ``wanted`` paths (relative to the archive's top folder)."""
    total = os.path.getsize(archive)
    dest_real = os.path.realpath(dest)
    with open(archive, "rb") as raw:
        reader = _CountingReader(raw, sink, total)
        with tarfile.open(fileobj=reader, mode="r|gz" if gz else "r|bz2") as tar:
            for member in tar:
                name = member.name.lstrip("./")
                if not name.startswith(root_name + "/"):
                    continue
                rel = name[len(root_name) + 1:]
                if not rel or not any(rel == w or rel.startswith(w + "/") for w in wanted):
                    continue
                target = os.path.realpath(os.path.join(dest, rel))
                if not target.startswith(dest_real + os.sep):
                    raise InstallError("Unsafe path in archive: %s" % member.name)
                if member.isdir():
                    os.makedirs(target, exist_ok=True)
                elif member.isfile():
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    src = tar.extractfile(member)
                    with open(target, "wb") as out:
                        shutil.copyfileobj(src, out, CHUNK)
                # links, devices etc. are ignored on purpose
    if sink is not None:
        sink.onProgress("extract", total, total)
