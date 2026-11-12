# Verba

A notes app for Android with a black, Nothing-inspired look. Type notes, or
speak them in **Hindi, English or both mixed**, and a speech model **running on
your phone** writes them down. You can also keep plain voice recordings, and a
resizable home-screen widget lets you add and delete notes without opening the
app.

Requirements: **Android 12 or newer** on a 64-bit ARM phone (about 6 GB of RAM
is plenty).

## Features

**Notes**

* **Text notes:** type them, speak them, or mix both. Tap the mic in the editor
  and talk; each sentence appears as you pause.
* **Voice notes:** the recording itself. Recording starts as soon as you tap
  **Voice**, with **Discard**, **Save** and **Pause/Resume** buttons.
  * **Auto-transcribe** (Settings, on by default) turns each saved recording
    into text in the background. It keeps going if you leave the screen or
    close the widget's pop-up, and picks up again if Android stops it halfway.
  * **Transcribe / Redo** runs it by hand; **Text note** makes a text note
    from the transcript.
* **Search** across all notes, and filter by **All / Text / Voice**.
* **Recently Deleted:** deleting a note, from the app or the widget, moves it
  to Recently Deleted. It stays there until you restore it or delete it from
  there. Nothing is removed automatically.

**Widget**

* Resizable from 2×1 up to full screen.
* Small sizes: the dotted **VERBA** title, the note count, and two buttons,
  ✎ for a text note and 🎙 for a voice note.
* Larger sizes also list your recent notes. Tap one to open it, or its 🗑 to
  move it to Recently Deleted.
* The ✎ and 🎙 buttons open a frosted card over your home screen.
* Shows "Transcribing…" next to a voice note while its text is being written.

**Look**

* Black everywhere, with one accent colour (**Settings → Colour**):
  * **Auto** (default): your phone's theme colour, which Android takes from
    your wallpaper, made as vivid as Nothing red. A black-and-white wallpaper
    makes Verba black and white.
  * **Red:** the original black and red.
  * **Mono:** always black and white.
* Dot-matrix type (Doto) for headings and numbers, uppercase mono labels, and
  your notes in a clean, normal font (Hindi uses the phone's Devanagari font).
* Frosted-glass panels: dense, grainy and blurred, so nothing behind them is
  readable. Content scrolling under the top and bottom bars blurs out softly.
* While you speak or record, a ring of dots lights up with your voice.
* The widget can't blur the wallpaper or use the app's fonts (Android doesn't
  allow either), so it is near-solid frosted black and its VERBA title is
  drawn as shapes.

**Private by default**

Notes, recordings and on-device transcription never leave the phone. The app
uses the internet only to download speech models, unless you choose Gemini
(below).

## Speech-to-text

Download models in **Settings** (the sliders icon at the top of the home
screen). Only one model is loaded at a time, so a 6 GB phone handles any of
them. The model you just downloaded becomes the one in use.

| | **Srota Hinglish** *(recommended)* | **Qwen3-ASR 0.6B** | **Nemotron 3.5 ASR** | **Srota-Conv Hinglish** |
|---|---|---|---|---|
| Made by | Qwen3-ASR 0.6B retrained on ~95 h of Indian Hinglish speech | Alibaba Qwen | NVIDIA | Same base, trained only on casual talk |
| How it feels | Sentence appears after you pause | Sentence appears after you pause | Words appear while you speak | Sentence appears after you pause |
| Mixed Hindi + English | Hindi in Devanagari, English in English, no setting needed | Guesses one language per sentence | English words written in Devanagari | Like Srota |
| Download / on phone | ~840 MB / ~950 MB | 880 MB / 955 MB | 475 MB / 653 MB | ~840 MB / ~950 MB |
| RAM while in use | ~1.6 GB | ~1.6 GB | ~1.0 GB | ~1.6 GB |
| Comes from | this repository's Releases (see below) | sherpa-onnx releases | sherpa-onnx releases | this repository's Releases |
| License | Apache-2.0 | Apache-2.0 | OpenMDW-1.1 | Apache-2.0 |

* **Srota Hinglish:** its authors report word errors on conversational
  Hinglish dropping from about 25% (stock Qwen3-ASR 0.6B) to about 16%.
* **Our own tests of the stock models** (desktop CPU, sherpa-onnx 2.0.1, the
  same int8 files the app downloads):
  * Hindi, from 12 short synthetic notes: 2.7% character errors for Nemotron,
    4.9% for Qwen3.
  * Hard, noisy real English: 41% word errors for Nemotron, 24% for Qwen3.
  * Synthetic speech is easier than a real voice, so expect more errors in
    real use, especially with background noise.
* **Language setting** (Settings, or the 🌐 chip next to the mic): **Auto**,
  **हिन्दी** or **English**. The Srota models ignore it, because they handle
  both languages in one sentence. If Qwen3 ever returns another script (e.g.
  Urdu or Chinese on short, noisy audio), the app re-runs that sentence with
  Hindi pinned.

### Gemini (cloud, your own key)

Optional, in **Settings → Cloud · your own key**.

* Paste a Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey).
  It is stored encrypted on the phone (Android Keystore) and is not part of
  backups.
* Choose **3.8 Flash** (default, most accurate), **Flash-Lite** (faster and
  cheaper) or **Transcribe** (Google's speech-to-text model, preview).
* **Names and words to listen for:** e.g. `Amit, Matheran, DMart`, to help
  with spelling.
* **Test** checks the key and model; **Use Gemini** switches to it for both
  dictation and voice notes.
* With Gemini in use, your recordings are sent to Google and need internet.
  Usage limits and any charges are on your Google account.

**Also considered and not used:** Qwen3-ASR 1.7B and Whisper large-v3 based
Hinglish models (too heavy for a 6 GB phone), Meta Omnilingual (mixes up
scripts on Hinglish), AI4Bharat IndicConformer (Hindi only), Sarvam Edge (not
open), Cohere Transcribe (no Hindi).

## Build it on GitHub

There is nothing to install on a computer. Two workflows in
`.github/workflows` do the work:

* **Build APK** (`build-apk.yml`) builds the app on every commit to `main` and
  publishes `<repo-name>-N.apk` on the **Releases** page as `build-N`.
* **Speech models** (`speech-models.yml`) converts the two Srota models from
  Hugging Face into the format the app uses. It runs once, about 30 minutes,
  and later runs finish in seconds.
  * It checks each converted model against stock Qwen3-ASR on the clips in
    `tools/testclips.zip` and refuses to publish one that comes out mostly wrong.
    The comparison table is on the run's **Summary** page.
  * It publishes the models as a pre-release called **Speech models for
    Verba** (tag `models`). Every build points the app at its own
    repository's `models` release.

### First setup

1. **Create a repository** on github.com (**+** → **New repository**), e.g.
   `verba`. Tick **Add a README file** so it is not empty.
2. **Upload** from a computer with **Add file** → **Upload files**, so the
   repository looks like this:

   ```
   .github/workflows/build-apk.yml
   .github/workflows/speech-models.yml
   Verba/            the app project (drag the whole folder)
   README.md
   ```

   * The workflows must be in `.github/workflows` at the top of the
     repository: GitHub runs workflows only from there. The project itself can
     sit in its own folder; the workflows find it.
   * Upload the `Verba` folder first, then `.github` (or create the two
     workflow files with **Add file** → **Create new file** and paste them).
     The first build starts as soon as the workflows are there.
   * Everything fits in GitHub's 100-files-per-upload limit.
3. **Wait:** in the **Actions** tab, **Build APK** turns green after about
   10–15 minutes. **Speech models** takes about 30 minutes the first time.
4. **Install:** on your phone, open the repository's **Releases** page, open
   the newest `build-N`, tap the `.apk` under **Assets**, and allow
   **Install unknown apps** when asked.

**Updating:** upload the changed files with **Upload files**; every commit
starts a new build. Edits to `.md` files and the test clips don't trigger one.

**Signing:** every build is signed with the same key (`app/verba.jks`), so a
new build installs over the old one and keeps your notes, settings and
downloaded models.

**If a build fails:** open the red run in **Actions**, open the failed step,
and copy the last lines of its log.

**Without GitHub:** open the folder in Android Studio and press **Run**.

## First run

1. Open Verba, tap the sliders icon, and download **Srota Hinglish**. That
   needs the Speech models workflow to have finished; otherwise download
   **Qwen3-ASR** or **Nemotron** now. Downloads continue with the screen off,
   and **Pause** keeps what has been downloaded so far.
2. Tap **Text**, then the mic, and speak. Tap the mic again to stop. Or tap
   **Voice** to record a voice note.
3. Add the widget: long-press the home screen → **Widgets** → **Verba**,
   drag it out and resize it.

## How it is built

There is no server. Python runs on the phone inside the app (Chaquopy).
Kotlin handles the screens, microphone, widget and background work. The
on-device speech models run through sherpa-onnx (ONNX Runtime).

```
app/src/main/python/verba/        Python backend, runs inside the APK
    api.py         functions Kotlin calls (JSON in and out)
    store.py       SQLite notes + Recently Deleted (no auto-purge)
    models.py      model catalog, resumable download, SHA-256 check, safe extraction
    textproc.py    tidies speech output (। for Hindi, capitals, spacing, language detection)
app/src/main/java/com/verba/app/
    ui/            notes list, editor with dictation, voice recorder/player,
                   Recently Deleted, Settings, quick pop-ups for the widget
    ui/glass/      glass toolkit: backdrop, live GPU blur, icons, palette,
                   Accent (colour from the phone theme)
    stt/           speech engines: Nemotron (streaming), Qwen3/Srota (pause-based),
                   Gemini (cloud), background transcription queue
    audio/         16 kHz microphone capture + WAV writer
    data/          bridge to Python, preferences, encrypted key storage
    service/       model downloads, background transcription, theme-change watcher
    widget/        resizable home-screen widget
app/src/main/assets/silero_vad.onnx   voice-activity detector (used by Gemini dictation)
tools/
    test_backend.py      tests for the Python backend
    compare_models.py    speech-model comparison used by the Speech models workflow
    check_checkpoint.py  makes sure a downloaded checkpoint loads completely
    testclips.zip        synthetic Indian-accented English, Hinglish and Hindi clips
```

**Permissions:** microphone (dictation and voice notes), internet (model
downloads, and Gemini if you use it), notifications (download and
transcription progress), and foreground service and wake lock (so downloads
and transcriptions finish in the background).

## Notes and limits

* While you record, the screen stays on. If you leave the screen, the
  recording so far is saved.
* Recordings are 16 kHz WAV, about 1.9 MB per minute.
* Android backup includes the notes database only. Speech models and the
  Gemini key are not backed up.
* **Widget colour:** the app follows a new wallpaper or theme colour right
  away. The widget follows within a few seconds when Android reports the
  theme change, and otherwise when you next open Verba, or at Android's
  regular widget refresh (about every 30 minutes).
* Wallpapers set by some third-party apps don't update the phone's theme
  colour, so Verba (like Gboard) shows the phone's default colour until the
  wallpaper is set through the phone's own settings.
* The app icon stays red; use Android's **themed icons** option for a
  matching icon.
* Not yet: sync between devices, export, reminders.

## Tests

```bash
python tools/test_backend.py
```

These cover:

* the notes store and Recently Deleted rules: nothing expires, permanent
  delete only from Recently Deleted, and audio files outside the app folder
  are never touched;
* speech-text clean-up;
* the model installer: resume after pause, checksum failure, path-traversal
  protection, and models published with a manifest on this repository's
  Releases page.

**Build APK** runs them before every build.

## Credits and licenses

* **Speech:** sherpa-onnx (Apache-2.0), Qwen3-ASR (Apache-2.0), Srota and
  Srota-Conv by Suraj Prasad (Apache-2.0; trained on the HiACC and
  OpenSLR-104 datasets, CC BY 4.0), NVIDIA Nemotron 3.5 ASR
  (OpenMDW-1.1), Silero VAD (MIT). The Qwen3-ASR ONNX converter is by
  Wasser1462.
* **App:** Chaquopy (MIT).
* **Fonts:** Doto, Space Mono and Space Grotesk (SIL Open Font License, see
  `licenses/`).
* **Gemini** is Google's service, used only with your own key.

The look is inspired by Nothing's design language. It does not use Nothing's
fonts or logo.
