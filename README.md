# Recorder

**Test version.** Record, trim and split audio into MP3 parts, and train your voice — feminine (MtF) or masculine (FtM) goal, switchable in the app: live pitch in Hz, resonance, vocal weight, 25 guided exercises per goal, reports and progress.

- **Android app** — `android/` (Kotlin). Built by GitHub Actions and attached to each release as `Recorder.apk`.
- **iPhone (web app)** — `web/app/` (plain JavaScript PWA, same voice engine as the Android app). Add to Home Screen from Safari.
- **Download page** — `web/index.html`, published with GitHub Pages.

Everything runs on the phone. No account, no server, no tracking.

## One-time GitHub setup

1. **Pages:** Settings → Pages → Build and deployment → Source: **GitHub Actions**.
   The page is then at `https://<user>.github.io/<repo>/` and the iPhone app at `…/<repo>/app/`.
2. **Android release:** create a tag, e.g. on GitHub: Releases → *Draft a new release* → tag `v1.0` → Publish.
   (Or `git tag v1.0 && git push --tags`.) The *Android APK* workflow builds the APK and attaches `Recorder.apk` to that release.
3. **Signing key (recommended):** without it, each build is signed with a throwaway debug key, so a new version won't install over the old one. Add four repository secrets (Settings → Secrets and variables → Actions):
   - `RECORDER_KEYSTORE_BASE64` — your keystore file as base64 (`base64 -w0 release.jks`)
   - `RECORDER_KEYSTORE_PASSWORD`, `RECORDER_KEY_ALIAS`, `RECORDER_KEY_PASSWORD`

   Make a keystore once in Android Studio: Build → Generate Signed App Bundle/APK → Create new. Keep the file and passwords safe.

## Local development

- Android: open `android/` in Android Studio.
- Web app: `cd web && python3 -m http.server 8000` and open `http://localhost:8000/app/` (the microphone needs `localhost` or HTTPS).

## Licences

MP3 encoding uses LAME (Android: Java port) and lamejs (web), both LGPL — see `android/LICENSE-LAME-LGPL.txt` and `web/app/vendor/`. PDF export on the web uses jsPDF (MIT).
