# Piper / sherpa-onnx: device spike

2026-11-14. Device RMO-NX1, Android 13, arm64-v8a, ADB 192.168.3.111:37383.

Installed separate official sherpa-onnx 1.12.22 TTS-engine APK with ru_RU-ruslan-medium. Package com.k2fsa.sherpa.onnx.tts.engine, versionCode 20260114. Download about 79.34 MiB. No model included in anyAIlive APK and no default Android TTS engine changed.

Source: https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html
APK: https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-new/1.12.22/sherpa-onnx-1.12.22-arm64-v8a-ru-tts-engine-vits-piper-ru_RU-ruslan-medium.apk

## Observed

The installed engine demo generated its default Russian sentence:

> Это движок преобразования текста в речь, использующий Kaldi следующего поколения.

UI reported:
- Number of threads: 2
- Elapsed: 0.827 s
- Audio duration: 4.888 s
- RTF: 0.169

Tapped Play on the phone. No microphone recording or listening evidence was collected by the assistant. Sound quality, intelligibility and emotional delivery require user listening. This elapsed time is full demo synthesis, NOT measured first-audio latency or end-to-end chat latency. No airplane-mode test was performed.

## License and scope

Ruslan model card links training dataset CC BY-NC-SA 4.0:
https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/ruslan/medium/MODEL_CARD

Use this as a personal evaluation only; do not bundle or describe it as unrestricted commercial/MIT material. sherpa framework license does not override model/data terms. Irina card instead says dataset license Unknown, so it was not chosen.

## anyAIlive integration — 2026-11-15

Phone reconnected at 192.168.3.111:38555. App settings now enumerate installed TTS engines using TTS_SERVICE package visibility and persist tts_engine_package on explicit Save. Selected com.k2fsa.sherpa.onnx.tts.engine without writing Android-wide settings.

Device log at 09:43:56: TextToSpeech successfully bound to com.k2fsa.sherpa.onnx.tts.engine. At 09:43:57.191, anyAIlive received PCM bytes=201736 rate=24000 via synthesizeToFile/WAV conversion. This is about 4.20 seconds of PCM16 mono; connection-to-PCM was approximately 0.90 seconds, not an end-to-end conversational latency measurement. PCM was submitted to AudioOut. At 09:44:37, pressing the test toggle released TTS and the device logged disconnection. This verifies cleanup after completion, not yet mid-speech cancellation.

Full APK build/signature and JVM TestMain passed, including 6 TtsEnginePolicy checks. Installed updated APK successfully. Explicit missing packages are rejected before construction. Android's TTS constructor may still fall back if an installed engine cannot bind; getCurrentEngine is not a public Android SDK method, so runtime fallback prevention is NOT claimed. Default Android secure tts_default_synth read null after test; no baseline was captured, but app code does not write system settings.

A second run at 09:47:31.869 connected to sherpa and delivered 195048 PCM bytes at 09:47:32.646 (about 4.06 seconds audio). Stop toggle disconnected at 09:47:34.994, approximately 2.35 seconds after submitting playback, before the nominal full clip duration. This exercises a mid-playback stop request; acoustic silence was not independently recorded. Fresh crash-buffer query since 09:43 returned no entries. Added preview identity checks so callbacks from an old preview cannot stop or feed a newly started preview. Rebuilt, reran TestMain and reinstalled successfully after this guard.

At 09:52:41.851 a rebuilt version bound to sherpa and returned 192820 PCM bytes at 09:52:42.827. SystemTts now registers output files before invoking synthesizeToFile, uses ConcurrentHashMap for Binder/UI access, and deletes failed synthesis files. Full host suite/build/signature/install passed. This handset smoke test is regression coverage of the usual path, not a deterministic reproduction of the immediate-completion race.

## HTTP STT + selected system TTS routing — 2026-11-15

CascadeHttp now snapshots the explicit TTS-engine selection on start. A nonblank package routes speech to SystemTts before the HTTP /audio/speech branch; blank/default preserves HTTP with fallback. SystemTts enqueue is dispatched on main, and pending utterance IDs prevent an early return to listening after just the first of multiple phrases. Nine JVM routing assertions passed after a failing regression assertion; the integrated JVM suite and signed APK build also passed. Installed this build on 192.168.3.111:38555.

Fresh settings-preview smoke: 10:16:57.536 bound to sherpa; 10:17:01.512 received PCM bytes=191148 rate=24000 (approximately 3.98 seconds of PCM16 mono). This checks preview regression, NOT the combined CascadeHttp flow or an HTTP-request trace. Connection-to-PCM includes service startup and is not conversational latency.

Follow-up fixed in the next build: SystemTts retains a 20-phrase pre-init bound, but now explicitly emits onFailed for rejected phrases instead of silently dropping them. CascadeHttp removes those utterance IDs, so rejected phrases do not remain pending forever. PendingSpeechQueue regression first failed on silent overflow, then passed 44 checks covering capacity, rejection, FIFO, reuse and clear. Full TestMain/build/signature passed; updated 201.4 KB APK installed. Android callback ordering and a >20-phrase handset conversation remain untested. Overflow phrases are explicitly rejected, not automatically retried; this is bounded failure handling, not unlimited narration.

## Still needed

- Validate the combined HTTP STT/chat → explicitly selected system TTS conversation, including absence of HTTP /audio/speech requests.
- Independently verify audible playback/cancellation; app-local selection and synthesizeToFile/PCM submission were exercised above, but acoustic silence was not recorded.
- Check offline behavior and repeat timings for cold/warm synthesis and longer phrases.
- User assessment of Russian voice quality. This is not Fish-style emotion control or voice cloning.
