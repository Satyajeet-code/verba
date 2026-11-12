"""The only module Kotlin calls. Everything in and out is str / int / bool / JSON.

Call ``init(files_dir)`` once per process before anything else.
"""

import json
import os
import threading

from . import models, textproc
from .store import NoteStore

_store = None
_files_dir = None
_lock = threading.Lock()


def init(files_dir):
    """Open (or create) the database under the app's private files folder."""
    global _store, _files_dir
    with _lock:
        if _store is not None and _files_dir == files_dir:
            return True
        os.makedirs(files_dir, exist_ok=True)
        _files_dir = files_dir
        _store = NoteStore(os.path.join(files_dir, "notes.db"), audio_dir())
        return True


def _s():
    if _store is None:
        raise RuntimeError("verba.api.init() was not called")
    return _store


def _j(obj):
    return json.dumps(obj, ensure_ascii=False)


def audio_dir():
    return os.path.join(_files_dir, "audio")


def models_root():
    return os.path.join(_files_dir, "models")


# ---------------------------------------------------------------------- notes
def list_notes(kind="all", query="", limit=1000):
    return _j(_s().list(kind=kind, query=query, limit=limit))


def list_trash():
    return _j(_s().list(trashed=True))


def get_note(note_id):
    return _j(_s().get(note_id))


def counts():
    return _j(_s().counts())


def create_text(title="", body="", source="typed"):
    return _j(_s().create_text(title, body, source))


def create_voice(audio_path, duration_ms, title=""):
    return _j(_s().create_voice(audio_path, int(duration_ms), title))


def update_note(note_id, title, body, source=""):
    return _j(_s().update(note_id, title=title, body=body, source=source or None))


def discard_if_empty(note_id):
    return _s().discard_if_empty(note_id)


def trash(note_id):
    """Delete from the app or widget: moves the note to Recently Deleted."""
    return _j(_s().trash(note_id))


def restore(note_id):
    return _j(_s().restore(note_id))


def delete_forever(note_id):
    return _s().delete_forever(note_id)


def empty_trash():
    return _s().empty_trash()


def widget_notes(limit=30):
    """Compact rows for the home-screen widget."""
    rows = _s().list(limit=limit)
    return _j([
        {
            "id": r["id"],
            "kind": r["kind"],
            "title": r["display_title"],
            "preview": r["preview"],
            "duration_ms": r["duration_ms"],
            "updated_at": r["updated_at"],
        }
        for r in rows
    ])


def cleanup_orphans(min_age_s=6 * 3600):
    """Remove recordings no note refers to (left over if the app was killed while saving).

    Only files older than ``min_age_s`` are touched, so an active recording is safe.
    """
    n = 0
    for p in _s().orphan_audio_files(min_age_s):
        if p.endswith(".wav") or p.endswith(".part"):
            try:
                os.remove(p)
                n += 1
            except OSError:
                pass
    return n


# ---------------------------------------------------------------------- text
def clean_segment(text, final=True):
    return textproc.clean_segment(text, bool(final))


def join_text(before, segment):
    return textproc.join_text(before, segment)


def detect_lang(text):
    return textproc.detect_lang(text)


# ---------------------------------------------------------------------- models
def model_status():
    return _j({"models": models.status(models_root()), "free_bytes": models.free_bytes(models_root())})


def model_installed(model_id):
    return models.is_installed(models_root(), model_id)


def model_paths(model_id):
    return _j(models.paths(models_root(), model_id))


def install_model(model_id, sink):
    """Blocking download + install. Returns '' on success or an error message.

    Returns 'cancelled' if the sink asked to stop; the partial download is kept
    so the next attempt resumes.
    """
    try:
        models.install(models_root(), model_id, sink)
        return ""
    except models.Cancelled:
        return "cancelled"
    except models.InstallError as e:
        return str(e)
    except Exception as e:  # network/disk errors we did not anticipate
        return "%s: %s" % (type(e).__name__, e)


def delete_model(model_id):
    return models.delete(models_root(), model_id)
