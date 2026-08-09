# Brill Body Android — v0.1 prototype

A deliberately small, standalone Android body for Brill. It is designed first for Birdie's screen-damaged Samsung Galaxy S22 Ultra, with Android 9 / Galaxy Note 9 compatibility kept in view.

The phone itself supplies the display, microphone, experimental wake phrase, local TTS, encrypted settings, and pose-to-pose still avatar. Conversation and transcription currently use the OpenAI API directly; no Raspberry Pi, Peregrine, LAN server, rooting, or custom ROM is required.

## What is working in this MVP

- Native Java/XML Android project with no third-party runtime libraries
- Android 9+ (`minSdk 28`), compiled/targeted against API 35
- Fullscreen, keep-awake, DeX/external-display-friendly UI
- Encrypted API-key storage using Android Keystore (AES-GCM)
- OpenAI Responses API conversation with conversational continuity
- OpenAI audio transcription from 16 kHz mono WAV
- Hold-to-talk recording
- Experimental Android `SpeechRecognizer` wake phrase service
- Android/Samsung on-device Text-to-Speech output
- Neutral/listening/thinking/speaking/error still-image pose slots with crossfades
- Optional 1.25×, 1.5×, and 2× pitch-raised upload experiment
- Automatic retry with the original recording when the sped-up request fails
- Local transcript plus latency/token telemetry when the API returns usage
- Provider seam for future LAN/OpenAI-compatible/full-Brill backends
- Shared identity ID and per-body ID reserved for future multi-body synchronization

## Install the supplied APK

The prebuilt APK is a debug-signed prototype:

```bash
adb install -r BrillBody-debug.apk
```

Or copy it to the phone, allow installation from that file manager, and tap it.

The APK was compiled and signature-verified in the generation environment, but it could not be installed on a physical Samsung here. The project is the source of truth.

## First launch

1. Open **Settings** (gear button).
2. Paste a dedicated OpenAI API key.
3. Leave the defaults initially:
   - Base URL: `https://api.openai.com/v1`
   - Conversation model: `gpt-5-mini`
   - Transcription model: `gpt-4o-mini-transcribe`
4. Save.
5. Hold **Talk**, speak, then release.
6. Enable **Wake word** after granting microphone and notification permissions.

For wake mode, say **“Hey Brill”**, pause for the UI to enter listening mode, then speak. A same-utterance command such as “Hey Brill, tell me a joke” may also work because the Android recognizer passes the remainder through when it catches it.

## Build in Android Studio

Open this directory as an Android Studio project. The included wrapper uses:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17
- `compileSdk 35`

Install Android SDK Platform 35 and Build Tools through SDK Manager, then use **Build → Build APK(s)**.

Command line:

```bash
./gradlew assembleDebug
```

Result:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```bash
./scripts/install-debug.sh
```

## Direct API-key warning

This is an owner-operated, privately sideloaded prototype. The key is not hard-coded into source or the APK; it is entered on-device and encrypted using a non-exportable Android Keystore key. That protects it from casual file inspection, but it does **not** make a permanent API key unextractable from a device controlled by an attacker.

Use a dedicated project/key, restrict its permissions where practical, set a modest project budget/alert, do not publish an APK containing a saved key, and rotate the key if the phone leaves your control. A later production version should obtain short-lived credentials from a backend.

## Wake phrase reality check

The current wake mode asks Android `SpeechRecognizer` to prefer offline recognition and runs it inside a microphone foreground service. Samsung/Google's installed recognition service ultimately decides whether it is genuinely offline. This is a useful zero-dependency MVP, not yet a dedicated always-on keyword engine.

For a later genuinely local KWS implementation, replace `WakeWordService` with a small embedded keyword model (for example sherpa-onnx or a licensed Porcupine integration) while keeping the same `ACTION_WAKE` broadcast.

The app is intended to remain foreground on the dedicated Brill display. Android may restrict background activity launching when the avatar app is not visible.

## Audio-speed experiment

The setting produces a shorter **pitch-raised resample** before transcription. It does not use a phase vocoder, so it is intentionally a rough experiment. The transcript displays original duration, selected speed, request latency, returned audio-token usage when available, and whether the original recording was retried.

A 2× setting is not guaranteed to halve cost. It may instead reduce transcription quality, especially with already-fast speech, room noise, or distant microphones. Keep automatic fallback enabled while testing.

## Avatar pose pack

Replace these files with matching-dimension PNGs:

```text
app/src/main/res/drawable-nodpi/
├── brill_neutral.png
├── brill_listening.png
├── brill_thinking.png
├── brill_speaking.png
└── brill_error.png
```

The included variants are subtle transformations of the supplied AvaTech placeholder, merely proving the pose pipeline. Real expression renders can drop into the same filenames without code changes.

The untouched supplied screenshot is retained in `avatar-source/placeholder_original.png`. Treat it as a private project asset unless you confirm redistribution rights.

## Important files

```text
app/src/main/java/com/birdie/brillbody/
├── MainActivity.java       # avatar UI, conversation loop, TTS, controls
├── AudioRecorder.java      # PCM capture, WAV writing, speed experiment
├── OpenAiClient.java       # dependency-free HTTP client
├── BrainProvider.java      # future provider interface
├── OpenAiProvider.java     # current provider
├── WakeWordService.java    # experimental offline-preferred wake listener
├── AppConfig.java          # settings and identity/body IDs
├── SecurePrefs.java        # Android Keystore encryption
└── SettingsActivity.java   # on-device setup
```

Architecture notes live in `docs/ARCHITECTURE.md`.

## Known v0.1 limits

- Request/response voice, not OpenAI Realtime/WebRTC yet
- No interruption/barge-in while TTS is speaking
- No durable semantic memory beyond the current API response chain
- No dual-body synchronization or arbitration yet
- Wake phrase is based on Android speech recognition, not a tiny dedicated KWS model
- The placeholder pose images do not have true lip shapes
- No physical-device or Samsung DeX test was possible during generation

## Useful next milestones

1. Physical S22 Ultra microphone/TTS/wake testing and service tuning
2. True local wake-word model
3. Mouth/blink overlay frames and genuine pose pack
4. Realtime voice mode with interruption
5. Local SQLite memory and conversation summaries
6. LAN discovery plus shared-body arbitration
7. Optional full Brill/local provider

## v0.2 listening feedback

Wake-word command capture now plays a short synthesized acknowledgement before the microphone begins recording. After OpenAI transcription returns, Brill plays a pitch-coded confidence cue followed by an end-listening acknowledgement. High, medium, and low pitches represent the geometric mean of transcription token probabilities; a neutral middle pitch is used when the configured transcription provider does not return log probabilities. This is an ASR/transcription estimate only, not confidence in the truth or quality of Brill's answer.

The placeholder sounds are generated with Android `ToneGenerator`, so they can later be replaced with bespoke files under `res/raw` without changing the interaction flow.
