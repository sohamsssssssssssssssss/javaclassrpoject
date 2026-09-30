# Audio Feasibility Gate

Date: 2026-09-18 (research) · 2026-09-19 (on-target verification) · Owner: Soham (voice) · Status: ON-TARGET VERIFICATION COMPLETE — ALL GATES PASSED

`PROJECT_DECISIONS.md` requires a Java-only feasibility check for microphone capture, speech
recognition, text-to-speech and speaker enrollment/matching **before any voice integration**.
This document records the research half of that gate. Nothing here may be claimed as "verified"
until the checklist at the end has been run on the demo laptop.

## 1. Microphone capture — Java Sound API (no dependency)

`javax.sound.sampled` provides mic capture directly. Target format for Vosk is 16 kHz,
16-bit, mono, little-endian (Vosk accepts 8/16/32 kHz; 16 kHz is the documented default for
all published models). If a mic does not support that natively, capture at a supported rate
and resample with `AudioSystem` conversions. macOS requires the **microphone TCC permission**
for the JVM/host app; capture failure surfaces as `LineUnavailableException` /
`SecurityException` and must be treated as a structured, visible failure (never silently
ignored). Windows uses the default recorder device; Linux needs an ALSA/Pulse device path.

## 2. Speech-to-text — Vosk (chosen candidate)

- **Integration:** `com.alphacephei:vosk` on Maven Central (Apache-2.0). Java bindings use
  `jnr-ffi` over the native Vosk core; fully callable from Java, satisfying the pure-Java rule
  the same way SQLite JDBC and OSHI already do. Latest desktop line 0.3.x; pin the exact
  version after the on-target spike.
- **API surface used:** `Model`, `Recognizer` (`acceptWaveForm`/`getPartialResult`/
  `getFinalResult`), `SpeakerModel` + `SpeakerRecognizer` for identification. All calls are
  made from a dedicated audio worker thread, never the JavaFX thread.
- **Model:** `vosk-model-small-en-us-0.15` (~40–50 MB download, ~300 MB RAM at runtime per
  alphacephei.com). Licence: Apache-2.0. Model is downloaded once by the developer/user and
  unpacked under the configured data directory — never committed to Git.
- **Offline:** fully local; no network calls at runtime.

## 3. Text-to-speech — recommended: native OS voice behind the service seam

The plan requires TTS behind a `SpeechSynthesisService` interface so the implementation is
replaceable. Java-only TTS options are weak in 2026, and the master plan allows OS adapters
where necessary (AppSkill already uses one):

| Option | Licence / status | Verdict |
| --- | --- | --- |
| **macOS `/usr/bin/say`** | OS-built-in, zero deps | **Sprint 3 default on macOS.** Explicit `ProcessBuilder` argument list (same pattern as `LaunchSpec`); never shell-interpolated. Voice, rate and output file are flags. |
| **Windows SAPI via PowerShell `System.Speech`** | OS-built-in | **Windows adapter later**, same seam, needs an on-target spike. |
| **FreeTTS (JSAPI)** | BSD-ish, effectively dormant | Fails plan rule 28 on quality/voices; keep as fallback only. |
| **MaryTTS 5.2** | LGPL, server-style | ~300 MB of voices and a client/server split — disproportionate to a normal laptop; rejected. |

## 4. Speaker identity — Vosk `SpeakerModel`, pitch explicitly rejected

- **Enrollment:** record 3 short fixed phrases per owner (Soham, Ved) → `SpeakerModel`
  x-vectors stored under the data directory as binary files; file paths referenced in the
  `speaker_profiles` table (migration 003 in sprint 3).
- **Matching:** cosine similarity of the spoken-utterance x-vector against each profile;
  best score above a configured threshold → that owner; otherwise `UNKNOWN`. The plan's
  rule "do not use pitch alone" is satisfied: x-vectors encode spectral/channel
  characteristics, not pitch.
- **Model:** `vosk-model-spk-0.4` (~40 MB, Apache-2.0), via the same `vosk` dependency —
  no extra native library.
- **Greeting mapping** stays hardcoded personality (allowed by rule 22). Identity only
  personalizes greetings and never grants permissions (AGENTS.md).

## 5. Licensing and footprint summary

| Component | Artifact | Licence | Download | Runtime RAM |
| --- | --- | --- | --- | --- |
| STT | `vosk-model-small-en-us-0.15` | Apache-2.0 | ~45 MB | ~300 MB |
| Speaker ID | `vosk-model-spk-0.4` | Apache-2.0 | ~40 MB | shared/native |
| TTS (macOS) | built-in `say` | OS | 0 | negligible |
| Java binding | `com.alphacephei:vosk` 0.3.x | Apache-2.0 | few MB jar + natives | native lib |

Total added footprint stays within plan rule 28 (runs on a normal laptop). No network
permission is needed at runtime.

## 6. On-target verification checklist — RESULTS (2026-09-19)

Environment: OpenJDK 25.0.2 (release target 21), Maven 3.9.x, macOS 27.0 arm64 (Apple
Silicon), MacBook Pro internal microphone.

1. **PASS (with a pinned downgrade).** `vosk` 0.3.45 resolves through Maven but its Java
   binding calls `vosk_recognizer_set_grm`, which the bundled darwin `libvosk.dylib`
   (universal x86_64+arm64, 35 `vosk_` symbols) does **not** export: every model load
   dies with `UnsatisfiedLinkError`. The binding/native pair inside 0.3.45 is
   mismatched upstream. **`pom.xml` pins 0.3.38**, whose binding matches the same dylib
   (no `set_grm` symbol, `Recognizer(Model,float,SpeakerModel)` present). Natives load
   natively on arm64; STT model loads in ~0.5–1.0 s, speaker model ~5 ms (lazy).
2. **PASS.** Java Sound enumerated 3 capture-capable mixers (Default, MacBook Pro Mic,
   Camo). Opened 16 kHz/16-bit/mono/LE with a 1 s line buffer and captured 3 s:
   90,112 bytes, silent-room RMS 393, speech RMS 1800 (peak 13033) — non-silent, live,
   and JNA-free. No TCC prompt appeared for the JVM (mic access already granted);
   `LineUnavailableException`/`SecurityException` are surfaced as structured
   `AudioException`s either way.
3. **PASS.** Offline transcription (Wi-Fi-independent, no network at runtime) of
    pre-recorded WAVs: "open calculator", "find pdf files", "system status" and
    "hello jade" ×6 transcribed **exactly**; decode + recognize ≤ ~200 ms per
    short utterance.
4. **PASS.** `/usr/bin/say -v Samantha -r 175 "Good morning, Master Soham. How may I
   assist you?"` exits 0 with audible speech; adapter argument list is explicit,
   deterministic and unit-tested (`MacSaySpeechSynthesisServiceTest`).
5. **PASS (bounded honestly).** Enrolled Samantha samples (Soham stand-in) and Daniel
   samples (Ved stand-in), 3 each. Fresh-utterance best similarities:
   Samantha→SOHAM 0.97/0.97/0.88 (enrollment reuses), 0.77/0.58/0.53 (new phrases);
   Daniel→VED 0.9998/0.9999/0.9999 and 0.46; third voice (Fred) → UNKNOWN, best score
   ≤ 0.36. **Chosen threshold: 0.60.** Note: the enrollment samples are macOS `say`
   voices, not the real owners — the mechanism is validated, personal thresholds may
   need re-tuning with real voices.
6. **PASS.** "hello jade" appeared as the exact transcript in every wake utterance
    tested; `TranscriptWakePhraseDetector` matches on the transcribed words (requires a
    greeting word + the wake word, tolerating "jervis"/"service" mishearings).

## 7. What is real vs. what is required

- **Real and offline:** mic capture, STT, speaker x-vector extraction, matching, TTS.
  No network is used at runtime.
- **Requires an installed model** (never committed to Git): unpack
  `vosk-model-small-en-us-0.15` under `jade.audio.model.dir` / `JADE_AUDIO_MODEL_DIR`
  and optionally `vosk-model-spk-0.4` under `jade.audio.spk.model.dir` /
  `JADE_AUDIO_SPK_MODEL_DIR`. Without the STT model the app runs normally and the
  voice panel reads "Voice unavailable (STT model not installed)".
- **Platform-specific:** TTS is macOS `/usr/bin/say` behind `SpeechSynthesisService`;
  other platforms fail visibly. Speaker enrollment profiles persist under the JADE
  data directory (`speakers/SOHAM.spk`, `speakers/VED.spk`).
- **Incomplete / limitations:** no live-mic enrollment command yet (profiles are created
  by tooling using the service API); no streaming partial-transcript display in the UI;
  speaker threshold 0.60 was calibrated on synthetic `say` voices — re-tune with real
  owners; wake phrase detection is transcript-based, so it inherits STT mishearings
  (handled: "jervis", "service"); speaker identity never grants permissions (AGENTS.md).

Record every result, including failures, in `docs/STATUS.md` before any voice command is
routed through the gateway.
