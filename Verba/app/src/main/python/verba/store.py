"""SQLite note store with a Recently Deleted bin.

Rules
-----
* Deleting a note (from the app or the widget) only moves it to Recently
  Deleted (``deleted_at`` is set). Nothing is ever purged automatically.
* A note leaves Recently Deleted only when the user restores it or deletes it
  permanently from there (one note, or "Delete all").
* Permanent deletion also removes the voice recording file, but only if that
  file lives inside the app's own audio folder.
"""

import os
import sqlite3
import threading
import time
import uuid

from . import textproc

SCHEMA_VERSION = 1

_SCHEMA = """
CREATE TABLE IF NOT EXISTS notes (
    id          TEXT PRIMARY KEY,
    kind        TEXT NOT NULL CHECK (kind IN ('text', 'voice')),
    title       TEXT NOT NULL DEFAULT '',
    body        TEXT NOT NULL DEFAULT '',
    audio_path  TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    lang        TEXT NOT NULL DEFAULT '',
    source      TEXT NOT NULL DEFAULT 'typed',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    deleted_at  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_notes_live  ON notes (deleted_at, updated_at DESC);
"""

KINDS = ("text", "voice")
SOURCES = ("typed", "dictated", "mixed", "recorded")


class NotFound(KeyError):
    pass


class NotInTrash(ValueError):
    pass


def now_ms():
    return int(time.time() * 1000)


class NoteStore:
    def __init__(self, db_path, audio_dir):
        self.db_path = db_path
        self.audio_dir = os.path.realpath(audio_dir)
        os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
        os.makedirs(self.audio_dir, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        with self._lock:
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=NORMAL")
            self._db.executescript(_SCHEMA)
            self._db.execute("PRAGMA user_version=%d" % SCHEMA_VERSION)

    def close(self):
        with self._lock:
            self._db.close()

    # ------------------------------------------------------------------ helpers
    def _row(self, note_id):
        row = self._db.execute("SELECT * FROM notes WHERE id=?", (note_id,)).fetchone()
        if row is None:
            raise NotFound(note_id)
        return row

    @staticmethod
    def to_dict(row):
        d = dict(row)
        d["trashed"] = d["deleted_at"] is not None
        d["display_title"] = textproc.display_title(d["kind"], d["title"], d["body"])
        d["preview"] = textproc.preview(d["title"], d["body"])
        return d

    # ------------------------------------------------------------------ create / update
    def create_text(self, title="", body="", source="typed"):
        if source not in SOURCES:
            source = "typed"
        t = now_ms()
        nid = uuid.uuid4().hex
        with self._lock:
            self._db.execute(
                "INSERT INTO notes (id, kind, title, body, lang, source, created_at, updated_at)"
                " VALUES (?, 'text', ?, ?, ?, ?, ?, ?)",
                (nid, title.strip(), body, textproc.detect_lang(title + " " + body), source, t, t),
            )
            return self.to_dict(self._row(nid))

    def create_voice(self, audio_path, duration_ms, title=""):
        t = now_ms()
        nid = uuid.uuid4().hex
        with self._lock:
            self._db.execute(
                "INSERT INTO notes (id, kind, title, body, audio_path, duration_ms, source,"
                " created_at, updated_at) VALUES (?, 'voice', ?, '', ?, ?, 'recorded', ?, ?)",
                (nid, title.strip(), audio_path, int(duration_ms), t, t),
            )
            return self.to_dict(self._row(nid))

    def update(self, note_id, title=None, body=None, source=None):
        """Edit a note's title and/or body (for voice notes the body is the transcript)."""
        with self._lock:
            row = self._row(note_id)
            new_title = row["title"] if title is None else title.strip()
            new_body = row["body"] if body is None else body
            new_source = row["source"]
            if source in SOURCES and row["kind"] == "text":
                if new_source != source and new_source in ("typed", "dictated") and row["body"]:
                    new_source = "mixed"
                else:
                    new_source = source
            if (new_title, new_body, new_source) == (row["title"], row["body"], row["source"]):
                return self.to_dict(row)
            self._db.execute(
                "UPDATE notes SET title=?, body=?, source=?, lang=?, updated_at=? WHERE id=?",
                (new_title, new_body, new_source, textproc.detect_lang(new_title + " " + new_body),
                 now_ms(), note_id),
            )
            return self.to_dict(self._row(note_id))

    def discard_if_empty(self, note_id):
        """Drop a text note that was opened but never given any content.

        This is not a user deletion (there is nothing to recover), so it skips
        Recently Deleted. Returns True if the note was removed.
        """
        with self._lock:
            try:
                row = self._row(note_id)
            except NotFound:
                return False
            if row["kind"] == "text" and not row["title"].strip() and not row["body"].strip():
                self._db.execute("DELETE FROM notes WHERE id=?", (note_id,))
                return True
            return False

    # ------------------------------------------------------------------ read
    def get(self, note_id):
        with self._lock:
            return self.to_dict(self._row(note_id))

    def list(self, kind="all", query="", trashed=False, limit=1000):
        sql = ["SELECT * FROM notes WHERE deleted_at IS %s" % ("NOT NULL" if trashed else "NULL")]
        args = []
        if kind in KINDS:
            sql.append("AND kind=?")
            args.append(kind)
        query = (query or "").strip()
        if query:
            sql.append("AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
            pat = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            args += [pat, pat]
        sql.append("ORDER BY %s DESC LIMIT ?" % ("deleted_at" if trashed else "updated_at"))
        args.append(int(limit))
        with self._lock:
            rows = self._db.execute(" ".join(sql), args).fetchall()
        return [self.to_dict(r) for r in rows]

    def counts(self):
        with self._lock:
            row = self._db.execute(
                "SELECT"
                " SUM(deleted_at IS NULL),"
                " SUM(deleted_at IS NULL AND kind='text'),"
                " SUM(deleted_at IS NULL AND kind='voice'),"
                " SUM(deleted_at IS NOT NULL)"
                " FROM notes"
            ).fetchone()
        return {"all": row[0] or 0, "text": row[1] or 0, "voice": row[2] or 0, "trash": row[3] or 0}

    # ------------------------------------------------------------------ Recently Deleted
    def trash(self, note_id):
        with self._lock:
            row = self._row(note_id)
            if row["deleted_at"] is None:
                self._db.execute("UPDATE notes SET deleted_at=? WHERE id=?", (now_ms(), note_id))
            return self.to_dict(self._row(note_id))

    def restore(self, note_id):
        with self._lock:
            self._row(note_id)
            self._db.execute("UPDATE notes SET deleted_at=NULL WHERE id=?", (note_id,))
            return self.to_dict(self._row(note_id))

    def delete_forever(self, note_id):
        """Permanently delete one note. Only allowed from Recently Deleted."""
        with self._lock:
            row = self._row(note_id)
            if row["deleted_at"] is None:
                raise NotInTrash(note_id)
            self._db.execute("DELETE FROM notes WHERE id=?", (note_id,))
        self._remove_audio(row["audio_path"])
        return True

    def empty_trash(self):
        with self._lock:
            rows = self._db.execute(
                "SELECT id, audio_path FROM notes WHERE deleted_at IS NOT NULL"
            ).fetchall()
            self._db.execute("DELETE FROM notes WHERE deleted_at IS NOT NULL")
        for r in rows:
            self._remove_audio(r["audio_path"])
        return len(rows)

    def _remove_audio(self, path):
        if not path:
            return
        real = os.path.realpath(path)
        if os.path.dirname(real) != self.audio_dir:
            return  # never touch files outside our own audio folder
        try:
            os.remove(real)
        except FileNotFoundError:
            pass

    def orphan_audio_files(self, min_age_s=0):
        """Audio files on disk that no note points to (e.g. app killed mid-save).

        ``min_age_s`` skips recent files, so a recording still in progress is never
        mistaken for an orphan.
        """
        with self._lock:
            used = {
                os.path.realpath(r[0])
                for r in self._db.execute("SELECT audio_path FROM notes WHERE audio_path IS NOT NULL")
            }
        out = []
        now = time.time()
        for name in os.listdir(self.audio_dir):
            p = os.path.join(self.audio_dir, name)
            if os.path.isfile(p) and os.path.realpath(p) not in used:
                if now - os.path.getmtime(p) >= min_age_s:
                    out.append(p)
        return out
