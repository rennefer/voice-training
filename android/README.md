# Recorder

Record from the phone microphone, trim, and save as MP3 split into parts
that fit a size limit (5, 10, 25 MB or your own size).

## Build and install
1. Android Studio → File → Open → this `Mp3Splitter` folder. Let Gradle sync finish.
2. Phone: Settings → Developer options → USB debugging on, plug in by USB.
3. Pick the phone in the device list and press Run (green ▶).

## Use
- **Record** / **Stop** (the big brass button). The brass ring is a loudness meter: it rises and falls
  with your voice so you can see the mic hears you. Recording continues with the screen off (Android shows a
  "Recording" notification while the mic is on; it goes away when you stop).
- **Open audio file**: load any audio (m4a, mp3, wav, ogg, amr, flac…) instead of recording.
  You can also pick Recorder from **Share** in My Files or Voice Recorder.
- **Phone calls (Samsung)**: Phone app → ⋮ → Settings → Record calls (on, or auto-record).
  Recordings are saved in Internal storage › Recordings › Call. Open one here to trim/split it.
- **Trim**: drag the two handles on the waveform, or use −1s / +1s. ▶ plays the selection.
- **kbps**: 32 (smallest) · 64 (clear speech) · 96 · 128 (music). Mono.
- **Even out volume**: lifts quiet speech, holds down loud parts.
- **MB per part**: Whole / 5 / 10 / 25 / Custom. Parts are filled to about
  98.5 % of the limit (1 MB = 1,000,000 bytes); the last part holds the rest.
- **Make MP3** → files are saved in `Music/Recorder` and can be shared from the list.

## Notes
- The MP3 encoder is a pure-Java port of LAME (LGPL, see LICENSE-LAME-LGPL.txt),
  bundled in `app/src/main/java/mp3` and `mpg`. No extra downloads.
- Only the latest recording is kept inside the app; a new recording replaces it.
