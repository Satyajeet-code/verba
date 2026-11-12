"""Desktop tests for the Python backend (no Android needed).

    python tools/test_backend.py
"""

import hashlib
import http.server
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))

from verba import api, models, textproc  # noqa: E402
from verba.store import NoteStore, NotInTrash  # noqa: E402


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.audio = os.path.join(self.tmp, "audio")
        self.s = NoteStore(os.path.join(self.tmp, "n.db"), self.audio)

    def tearDown(self):
        self.s.close()
        shutil.rmtree(self.tmp)

    def _wav(self, name="a.wav"):
        p = os.path.join(self.audio, name)
        with open(p, "wb") as f:
            f.write(b"RIFF....WAVE")
        return p

    def test_text_note_roundtrip_and_search(self):
        a = self.s.create_text("", "दूध लाना है\nऔर अंडे भी", "dictated")
        b = self.s.create_text("Groceries", "milk, eggs")
        self.assertEqual(a["display_title"], "दूध लाना है")
        self.assertEqual(a["preview"], "और अंडे भी")
        self.assertEqual(a["lang"], "hi")
        self.assertEqual(b["lang"], "en")
        self.assertEqual([n["id"] for n in self.s.list(query="अंडे")], [a["id"]])
        self.assertEqual([n["id"] for n in self.s.list(query="MILK")], [b["id"]])
        self.assertEqual(self.s.list(query="100%"), [])
        u = self.s.update(a["id"], body="दूध लाना है\nऔर अंडे भी\nmeeting at 5", source="typed")
        self.assertEqual(u["source"], "mixed")
        self.assertEqual(u["lang"], "mixed")

    def test_delete_goes_to_recently_deleted_and_stays(self):
        n = self.s.create_text("x", "y")
        v = self.s.create_voice(self._wav(), 3000)
        self.s.trash(n["id"])
        self.s.trash(v["id"])
        self.assertEqual(self.s.list(), [])
        self.assertEqual(self.s.counts(), {"all": 0, "text": 0, "voice": 0, "trash": 2})
        trashed = self.s.list(trashed=True)
        self.assertEqual({t["id"] for t in trashed}, {n["id"], v["id"]})
        self.assertTrue(all(t["trashed"] for t in trashed))
        # nothing expires on its own: still there after many store reopenings
        self.s.close()
        self.s = NoteStore(os.path.join(self.tmp, "n.db"), self.audio)
        self.assertEqual(len(self.s.list(trashed=True)), 2)
        # restore brings it back unchanged
        r = self.s.restore(n["id"])
        self.assertFalse(r["trashed"])
        self.assertEqual(r["title"], "x")

    def test_permanent_delete_only_from_trash_and_removes_audio(self):
        wav = self._wav()
        v = self.s.create_voice(wav, 1200)
        with self.assertRaises(NotInTrash):
            self.s.delete_forever(v["id"])
        self.s.trash(v["id"])
        self.assertTrue(os.path.exists(wav))
        self.s.delete_forever(v["id"])
        self.assertFalse(os.path.exists(wav))
        self.assertEqual(self.s.counts()["trash"], 0)

    def test_empty_trash_never_touches_files_outside_audio_dir(self):
        outside = os.path.join(self.tmp, "keep.wav")
        open(outside, "wb").close()
        a = self.s.create_voice(outside, 10)
        b = self.s.create_voice(self._wav("b.wav"), 10)
        keep = self.s.create_text("keep", "")
        for n in (a, b):
            self.s.trash(n["id"])
        self.assertEqual(self.s.empty_trash(), 2)
        self.assertTrue(os.path.exists(outside))
        self.assertFalse(os.path.exists(os.path.join(self.audio, "b.wav")))
        self.assertEqual([n["id"] for n in self.s.list()], [keep["id"]])

    def test_discard_empty_draft(self):
        n = self.s.create_text("", "   ")
        self.assertTrue(self.s.discard_if_empty(n["id"]))
        self.assertEqual(self.s.counts()["trash"], 0)
        m = self.s.create_text("", "hello")
        self.assertFalse(self.s.discard_if_empty(m["id"]))

    def test_orphans(self):
        used = self._wav("used.wav")
        self.s.create_voice(used, 5)
        stray = self._wav("stray.wav")
        self.assertEqual(self.s.orphan_audio_files(), [stray])
        # a fresh file (e.g. a recording in progress) is left alone
        self.assertEqual(self.s.orphan_audio_files(min_age_s=3600), [])
        old = time.time() - 7200
        os.utime(stray, (old, old))
        self.assertEqual(self.s.orphan_audio_files(min_age_s=3600), [stray])


class TextTest(unittest.TestCase):
    def test_clean_segment(self):
        c = textproc.clean_segment
        self.assertEqual(c("आज का काम अच्छा रहा, लेकिन रिपोर्ट अभी भी अधूरी है."),
                         "आज का काम अच्छा रहा, लेकिन रिपोर्ट अभी भी अधूरी है।")
        self.assertEqual(c("सुबह दस बजे डॉक्टर के पास जाना है"), "सुबह दस बजे डॉक्टर के पास जाना है।")
        self.assertEqual(c("सुबह दस बजे", final=False), "सुबह दस बजे")
        self.assertEqual(c("hello world , this is a note"), "Hello world, this is a note.")
        self.assertEqual(c("Project की meeting शुक्रवार को होगी."), "Project की meeting शुक्रवार को होगी।")
        self.assertEqual(c("language Hindi<asr_text>नमस्ते"), "नमस्ते।")
        self.assertEqual(c("Is it done?"), "Is it done?")
        self.assertEqual(c("price is 5.5 rupees"), "Price is 5.5 rupees.")
        self.assertEqual(c("   "), "")

    def test_lone_i_is_capitalised(self):
        self.assertEqual(textproc.clean_segment("i think i'm late, i know"), "I think I'm late, I know.")
        self.assertEqual(textproc.clean_segment("मैं कल आऊँगा, i promise"), "मैं कल आऊँगा, I promise.")
        self.assertEqual(textproc.clean_segment("wifi is down"), "Wifi is down.")

    def test_join_text(self):
        j = textproc.join_text
        self.assertEqual(j("", "hello there."), "Hello there.")
        self.assertEqual(j("First line.", "second one."), " Second one.")
        self.assertEqual(j("First line. ", "second one."), "Second one.")
        self.assertEqual(j("दूध लाना है।", "अंडे भी।"), " अंडे भी।")
        self.assertEqual(j("list:\n", "eggs"), "Eggs")
        self.assertEqual(j("buy", "eggs"), " eggs")

    def test_detect_lang(self):
        self.assertEqual(textproc.detect_lang("नमस्ते दुनिया"), "hi")
        self.assertEqual(textproc.detect_lang("hello"), "en")
        self.assertEqual(textproc.detect_lang("Project की meeting"), "mixed")
        self.assertEqual(textproc.detect_lang("123"), "")


# ---------------------------------------------------------------------- model install
class _RangeHandler(http.server.BaseHTTPRequestHandler):
    files = {}
    range_requests = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        data = self.files.get(self.path)
        if data is None:
            self.send_error(404)
            return
        start = 0
        rng = self.headers.get("Range")
        if rng:
            start = int(rng.split("=")[1].split("-")[0])
            self.range_requests.append(start)
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, len(data) - 1, len(data)))
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(data) - start))
        self.end_headers()
        self.wfile.write(data[start:])


def _make_archive(root, extra_members=()):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:bz2") as tar:
        def add(name, payload):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
        add(root + "/encoder.int8.onnx", os.urandom(600_000))
        add(root + "/tokens.txt", b"a 0\nb 1\n")
        add(root + "/tokenizer/vocab.json", b"{}")
        add(root + "/test_wavs/en.wav", b"x" * 1000)
        add(root + "/README.md", b"readme")
        for name, payload in extra_members:
            add(name, payload)
    return buf.getvalue()


class _Sink:
    def __init__(self, cancel_after=None):
        self.events, self.cancel_after = [], cancel_after

    def onProgress(self, phase, done, total):
        self.events.append((phase, done, total))

    def isCancelled(self):
        if self.cancel_after is None:
            return False
        dl = [e for e in self.events if e[0] == "download"]
        return bool(dl) and dl[-1][1] >= self.cancel_after


class InstallTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _RangeHandler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:%d" % cls.server.server_port

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.saved = list(models.CATALOG)
        models.CHUNK = 64 * 1024

    def tearDown(self):
        models.CATALOG[:] = self.saved
        models._BY_ID.clear()
        models._BY_ID.update({m["id"]: m for m in models.CATALOG})
        shutil.rmtree(self.tmp)

    def _register(self, data, sha=None, files=None, extras=()):
        _RangeHandler.files["/m.tar.bz2"] = data
        entry = {
            "id": "test", "name": "T", "vendor": "v", "mode": "live", "tagline": "", "detail": "",
            "license": "x", "ram_mb": 1, "installed_mb": 1,
            "archive": {"url": self.base + "/m.tar.bz2", "size": len(data),
                        "sha256": sha or hashlib.sha256(data).hexdigest(), "root": "pkg"},
            "files": files or {"encoder": "encoder.int8.onnx", "tokens": "tokens.txt",
                               "tokenizer": "tokenizer"},
            "extras": list(extras),
        }
        models.CATALOG[:] = [entry]
        models._BY_ID.clear()
        models._BY_ID["test"] = entry

    def test_install_resume_and_paths(self):
        data = _make_archive("pkg")
        vad = os.urandom(5000)
        _RangeHandler.files["/vad.onnx"] = vad
        self._register(data, files={"encoder": "encoder.int8.onnx", "tokens": "tokens.txt",
                                    "tokenizer": "tokenizer", "vad": "vad.onnx"},
                       extras=[{"url": self.base + "/vad.onnx", "size": len(vad),
                                "sha256": hashlib.sha256(vad).hexdigest(), "name": "vad.onnx"}])
        _RangeHandler.range_requests.clear()
        # first attempt is cancelled part-way; the partial file must be kept
        with self.assertRaises(models.Cancelled):
            models.install(self.tmp, "test", _Sink(cancel_after=1))
        part = models._part_path(self.tmp, "test")
        self.assertTrue(0 < os.path.getsize(part) < len(data))
        self.assertGreater(models.status(self.tmp)[0]["partial_bytes"], 0)
        # second attempt resumes with a Range request and finishes
        sink = _Sink()
        p = models.install(self.tmp, "test", sink)
        self.assertTrue(_RangeHandler.range_requests, "download did not resume")
        self.assertEqual(sink.events[-1][0], "done")
        self.assertTrue({"download", "verify", "extract"} <= {e[0] for e in sink.events})
        self.assertTrue(models.is_installed(self.tmp, "test"))
        self.assertFalse(os.path.exists(part))
        d = models.model_dir(self.tmp, "test")
        self.assertFalse(os.path.exists(os.path.join(d, "test_wavs")))   # only needed files
        self.assertFalse(os.path.exists(os.path.join(d, "README.md")))
        self.assertTrue(os.path.isfile(os.path.join(d, "tokenizer", "vocab.json")))
        self.assertEqual(open(p["vad"], "rb").read(), vad)
        self.assertEqual(p["mode"], "live")
        # deleting removes everything
        models.delete(self.tmp, "test")
        self.assertFalse(models.is_installed(self.tmp, "test"))

    def test_checksum_mismatch_is_reported_and_part_removed(self):
        data = _make_archive("pkg")
        self._register(data, sha="0" * 64)
        with self.assertRaises(models.InstallError):
            models.install(self.tmp, "test")
        self.assertFalse(os.path.exists(models._part_path(self.tmp, "test")))
        self.assertFalse(models.is_installed(self.tmp, "test"))

    def test_path_traversal_is_rejected(self):
        data = _make_archive("pkg", extra_members=[("pkg/tokenizer/../../../evil.txt", b"bad")])
        self._register(data)
        with self.assertRaises(models.InstallError):
            models.install(self.tmp, "test")
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "evil.txt")))

    def test_published_model_uses_manifest(self):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            for name, payload in (("pkg-x/encoder.int8.onnx", b"e" * 70000), ("pkg-x/tokens.txt", b"t"),
                                  ("pkg-x/tokenizer/vocab.json", b"{}")):
                info = tarfile.TarInfo(name); info.size = len(payload); tar.addfile(info, io.BytesIO(payload))
        gz = buf.getvalue()
        _RangeHandler.files["/rel/pkg-x.tar.gz"] = gz
        manifest = {"file": "pkg-x.tar.gz", "size": len(gz), "sha256": hashlib.sha256(gz).hexdigest(), "root": "pkg-x"}
        _RangeHandler.files["/rel/pkg-x.json"] = json.dumps(manifest).encode()
        entry = {"id": "pub", "name": "P", "vendor": "v", "mode": "pause", "prompt": "none", "tagline": "",
                 "detail": "", "license": "x", "ram_mb": 1, "installed_mb": 1,
                 "archive": {"published": "pkg-x", "approx_size": 123, "base": self.base + "/rel/"},
                 "files": {"encoder": "encoder.int8.onnx", "tokens": "tokens.txt", "tokenizer": "tokenizer"},
                 "extras": []}
        models.CATALOG[:] = [entry]; models._BY_ID.clear(); models._BY_ID["pub"] = entry
        self.assertEqual(models.status(self.tmp)[0]["download_bytes"], 123)
        p = models.install(self.tmp, "pub", _Sink())
        self.assertEqual(p["prompt"], "none")
        self.assertEqual(open(p["encoder"], "rb").read(), b"e" * 70000)
        # not published yet -> a clear message
        entry["archive"] = {"published": "missing", "approx_size": 1, "base": self.base + "/rel/"}
        models.delete(self.tmp, "pub")
        with self.assertRaises(models.InstallError) as cm:
            models.install(self.tmp, "pub")
        self.assertIn("Speech models", str(cm.exception))
        # a manifest pointing outside the release is refused
        _RangeHandler.files["/rel/bad.json"] = json.dumps(dict(manifest, file="../x.tar.gz")).encode()
        entry["archive"] = {"published": "bad", "approx_size": 1, "base": self.base + "/rel/"}
        with self.assertRaises(models.InstallError):
            models.install(self.tmp, "pub")

    def test_api_wrapper_returns_message(self):
        api.init(self.tmp)
        data = _make_archive("pkg")
        self._register(data)
        self.assertEqual(api.install_model("test", _Sink()), "")
        st = json.loads(api.model_status())
        self.assertTrue(st["models"][0]["installed"])
        self.assertIn("encoder", json.loads(api.model_paths("test")))
        self.assertEqual(api.install_model("nope", _Sink()), "Unknown model: nope")


class ApiTest(unittest.TestCase):
    def test_json_roundtrip(self):
        tmp = tempfile.mkdtemp()
        try:
            api._store = None
            api.init(tmp)
            n = json.loads(api.create_text("", "पहला नोट", "dictated"))
            json.loads(api.update_note(n["id"], "Title", "पहला नोट", "typed"))
            w = json.loads(api.widget_notes())
            self.assertEqual(w[0]["title"], "Title")
            json.loads(api.trash(n["id"]))
            self.assertEqual(json.loads(api.counts())["trash"], 1)
            self.assertEqual(len(json.loads(api.list_trash())), 1)
            self.assertEqual(api.empty_trash(), 1)
            self.assertEqual(api.clean_segment("नमस्ते दोस्तों.", True), "नमस्ते दोस्तों।")
        finally:
            shutil.rmtree(tmp)

    def test_real_catalog_is_consistent(self):
        ids = set()
        for m in models.CATALOG:
            self.assertNotIn(m["id"], ids)
            ids.add(m["id"])
            arc = m["archive"]
            if "published" in arc:
                self.assertTrue(models.PUBLISHED.startswith("https://github.com/"))
                self.assertTrue(models.PUBLISHED.endswith("/releases/download/models/"))
                self.assertGreater(arc["approx_size"], 100 * 2**20)
                self.assertEqual(m.get("prompt"), "none")
            else:
                self.assertEqual(len(arc["sha256"]), 64)
                self.assertTrue(arc["url"].startswith("https://github.com/k2-fsa/sherpa-onnx/"))
                self.assertTrue(arc["url"].endswith(arc["root"] + ".tar.bz2"))
            for e in m["extras"]:
                self.assertEqual(len(e["sha256"]), 64)
        self.assertEqual(models.CATALOG[0]["id"], "hinglish")


if __name__ == "__main__":
    unittest.main(verbosity=2)
