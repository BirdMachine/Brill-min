# Architecture

## Runtime flow

```text
WakeWordService or Hold Talk
        │
        ▼
AudioRecorder → WAV → optional sped-up WAV
        │
        ▼
BrainProvider.transcribe()
        │
        ▼
recognized text
        │
        ▼
BrainProvider.respond(previous_response_id)
        │
        ├── transcript + usage telemetry
        ├── avatar state transition
        └── Android TextToSpeech
```

## Provider boundary

`BrainProvider` intentionally has only two synchronous operations for the first milestone:

```java
Transcription transcribe(File wavFile);
BrainResponse respond(String userText, String previousResponseId);
```

The activity executes them on a single background executor. A future `LanProvider` can target an OpenAI-compatible server. A future `BrillProvider` can add tools, memory, or richer event streaming behind the same UI; a streaming provider interface should be added rather than overloading these synchronous methods.

## Identity versus body

- `identityId`: intended to identify the continuing Brill persona across devices.
- `bodyId`: identifies this physical Android endpoint.

They are currently sent only as runtime context in the instructions. No synchronization occurs yet. A later coordination layer should elect one speaking body per utterance and mirror avatar state to passive bodies.

## Key storage

`SecurePrefs` creates a 256-bit AES key inside Android Keystore and encrypts the API key with AES-GCM. The encrypted ciphertext and IV live in private SharedPreferences. The API key never appears in Gradle configuration, source code, or resources.

This is appropriate for a private prototype, not a public client distribution. A production design should use short-lived tokens minted by a trusted service.

## Wake phrase

`WakeWordService` is a microphone foreground service. It repeatedly starts Android `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE=true`, inspects final hypotheses for the configured phrase, and broadcasts `ACTION_WAKE`.

The main activity pauses the service during raw recording and TTS so Brill does not trigger herself. The speech-recognition provider may still use the network despite the preference flag.

## Avatar states

The activity maps each runtime state to a resource:

```text
IDLE      → brill_neutral
LISTENING → brill_listening
THINKING  → brill_thinking
SPEAKING  → brill_speaking
ERROR     → brill_error
```

Images crossfade and receive small scale/rotation animations. This keeps the MVP pose-based and allows later generated expression packs without adopting a full Live2D/VRM runtime yet.
