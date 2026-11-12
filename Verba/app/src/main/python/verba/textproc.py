"""Text helpers: tidy up speech-to-text output and build list previews.

Speech models return slightly different styles: Nemotron often omits the final
full stop, Qwen3 ends Hindi sentences with "." instead of the danda "।", both may
leave spaces before punctuation. Everything here is pure Python and fast enough
to call for every dictated sentence.
"""

import re
import unicodedata

_DEVANAGARI = re.compile(r"[ऀ-ॿ]")
_LATIN = re.compile(r"[A-Za-z]")
_SPACE_BEFORE_PUNCT = re.compile(r"\s+([,.!?;:।॥)])")
_MULTI_SPACE = re.compile(r"[ \t ]+")
_ARTIFACTS = re.compile(r"^\s*(language\s+\w+\s*)?<\s*asr_text\s*>\s*", re.IGNORECASE)
_TERMINAL = ("।", "॥", ".", "!", "?", "…", ":", ";")
# a full stop directly after a Devanagari letter/sign means "end of Hindi sentence"
_HINDI_FULL_STOP = re.compile(r"(?<=[ऀ-ॣ०-ॿ])\s*\.(?=\s|$)")
# lone lowercase "i" (some Hinglish models write English in lower case)
_LONE_I = re.compile(r"(?<![\w'’])i(?=(?:'|’)(?:m|d|ll|ve)\b|[\s,.!?;:]|$)")


def detect_lang(text):
    """'hi', 'en', 'mixed' or '' based on which script each word is written in."""
    dev = lat = 0
    for word in (text or "").split():
        if _DEVANAGARI.search(word):
            dev += 1
        elif _LATIN.search(word):
            lat += 1
    if dev == 0 and lat == 0:
        return ""
    share = dev / float(dev + lat)
    if share >= 0.9:
        return "hi"
    if share <= 0.1:
        return "en"
    return "mixed"


def clean_segment(text, final=True):
    """Tidy one recognised sentence/phrase.

    final=True means the speaker paused (end of an utterance), so a missing
    sentence end is added: "।" for Hindi, "." for English.
    """
    if not text:
        return ""
    t = unicodedata.normalize("NFC", text)
    t = _ARTIFACTS.sub("", t)
    t = t.replace("\n", " ")
    t = _MULTI_SPACE.sub(" ", t).strip()
    t = _SPACE_BEFORE_PUNCT.sub(r"\1", t)
    t = _HINDI_FULL_STOP.sub("।", t)
    if not t:
        return ""
    lang = detect_lang(t)
    if lang in ("en", "mixed"):
        t = _LONE_I.sub("I", t)
    if lang in ("en", "mixed") and t[0].isalpha() and t[0].islower() and _LATIN.match(t[0]):
        t = t[0].upper() + t[1:]
    if final and not t.endswith(_TERMINAL):
        last_letter = next((c for c in reversed(t) if c.isalpha() or _DEVANAGARI.match(c)), "")
        t += "।" if last_letter and _DEVANAGARI.match(last_letter) else "."
    return t


def join_text(before, segment):
    """Return ``segment`` prepared for insertion right after ``before``.

    Adds a separating space when needed and capitalises an English sentence
    start. ``before`` is the text to the left of the cursor.
    """
    seg = (segment or "").strip()
    if not seg:
        return ""
    prev = (before or "").rstrip(" \t")
    if prev and not prev.endswith("\n"):
        ends_sentence = prev.endswith(_TERMINAL)
        if ends_sentence and _LATIN.match(seg[0]) and seg[0].islower():
            seg = seg[0].upper() + seg[1:]
        if not (before or "").endswith((" ", "\t", "\n")):
            seg = " " + seg
    elif _LATIN.match(seg[0]) and seg[0].islower():
        seg = seg[0].upper() + seg[1:]
    return seg


def display_title(kind, title, body):
    if title and title.strip():
        return title.strip()
    for line in (body or "").splitlines():
        line = line.strip()
        if line:
            return line[:80]
    return "Voice note" if kind == "voice" else "New note"


def preview(title, body, limit=160):
    """Body text for list rows: skips the first line when it is used as the title."""
    lines = [l.strip() for l in (body or "").splitlines() if l.strip()]
    if not (title and title.strip()) and lines:
        lines = lines[1:]
    text = " ".join(lines)
    text = _MULTI_SPACE.sub(" ", text)
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"
