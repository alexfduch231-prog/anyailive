# Device validation — 2026-11-14

Device: RMO-NX1, Android 13, ADB 192.168.3.111:37383.
Package: ru.alexv.anyailive. Development build 0.1.0.

## Live screen + real-time waveform — 2026-11-15

- New Gemini-Live-style Live screen: fullscreen dark, centered 30sp title, five-bar
  WaveformView (ui/WaveformView.java), status text, subtitles and pill controls at the
  bottom. All previous view ids preserved; legacy live_circle hidden (gone).
- Waveform is audio-reactive: WaveformView.push(rms) from MicListener.onPcm (user voice)
  and LiveActivity.play (model voice), RMS of PCM16 normalized 0..1 with a boost for
  quiet speech; idle state is a slow breathing animation, no timers outside onDraw.
- Device smoke test with spike/realtime_fixture.py (stdlib RFC6455 OpenAI-Realtime
  fixture, ws://127.0.0.1:18765 over adb reverse): session.update accepted, mic PCM
  streamed as input_audio_buffer.append continuously, response.output_audio.delta
  (24 kHz PCM16, breathing amplitude) played and drove the waveform from idle dots to
  tall uneven bars while state showed Говорю; transcript deltas rendered as subtitles.
  Screenshots spike/live-fx-3.png (idle) and spike/live-fx2-2.png (speaking).
- NOTE: a dummy key `dummy-live-test-key` was saved on the mock DeviceTest model to pass
  the API-key gate; DeviceTest still points to loopback fixtures and is not a working
  provider configuration.
- Pitfall recorded: Windows allows double-binding the fixture port; zombie python
  fixtures silently stole connections. taskkill stale PIDs before restarting the fixture.

## Observed on handset

- APK installs and launches.
- Local HTTP fixture over adb reverse: sent `hello`; Android SSE client rendered `DEVICE_STREAM_OK` and retained it after process restart. This is a mock response, not an external model test.
- Missing-key Live regression: original build crashed in LiveActivity.connect after a synchronous startup error cleared mic. Rebuilt app now returns to ChatActivity; crash buffer since 12:33 contained no new crash in this retest.
- Privacy regression: disabled KEEP_HISTORY, explicitly saved, sent `private_probe_123` to local fixture. User/assistant messages were visible during the session. After force-stop/restart, the private pair disappeared and previously persisted `hello` / `DEVICE_STREAM_OK` remained.
- Direct SQLite snapshot was not available: run-as rejects this non-debuggable build. Privacy conclusion is based on observed process-restart behavior, not direct database inspection.
- Internal SettingsActivity cannot be launched by shell (not exported); normal in-app navigation works. Do not export it to facilitate tests.

## Verification on host

- build.ps1 completed aapt2/javac/d8/signature verification.
- Current signing certificate SHA256: fad01201d42bfc3cdc9f2fd8cbec696cca6d510ad9d7dbd63cb19d8e46988fe4.
- Integrated TestMain exit 0: core 16, JSON 15, Live 55, Chat 63, Audio 56; settings-policy assertions; MemoryHistory regression suite; AudioOutBuffer 15 assertions.
- Live/Audio test run methods now throw on failed assertions instead of merely printing failures.
- Final integration APK installed after adding AudioOut playback clock to OpenAI engine.
- Follow-up lifecycle integration: LiveSessionCallbacks rejects late events after stop, including queued delivery; empty assistant text discards audio rather than writing orphan WAV. JVM suite: 10 assertions passed; full TestMain exit 0. APK rebuilt at 197.4 KB, same signing certificate, installed successfully. Missing-key Live retest returned to the existing chat on handset. Real network-stop races still require a configured realtime provider.

## HTTP STT follow-up — 2026-11-15

Source inspection found CascadeStt.transcribe sending raw PCM with filename audio.wav and content type audio/wav. Production now uses TranscriptionBody.build, which encodes PCM16 mono 16 kHz as RIFF/WAVE before multipart packaging. Regression first failed on missing RIFF, then passed: 3200 PCM bytes produce a 3244-byte WAV; format, sample rate, fields and all PCM bytes verified. Exact multipart bytes also pass. Root full build/signature and integrated TestMain exit 0; APK 201.4 KB installed successfully on 192.168.3.111:38555 at 10:47–10:48. This is not evidence of successful remote transcription.

A suspected multipart CRLF escaping issue was disproved by an exact byte-level JVM test on unchanged HttpWire; file-tool rendering escapes backslashes. No multipart transport change is required.

Independent host wire check at 10:52: `python spike/verify_stt_wire.py` compiled a temporary Java caller against built production classes, posted via production HttpWire.post to an ephemeral loopback HTTP server, and parsed the received body with Python email MIME and wave parsers. Result: PRODUCTION_HTTP_RESPONSE_OK; PASS_HOST_STT_WIRE, path /v1/audio/transcriptions, WAV 3244 bytes, PCM 3200 bytes, 1600 frames, 16000 Hz mono PCM16. Model/language/filename/content type and all sample bytes matched. Server returned a fixture transcript accepted by the Java caller. Server/thread and temporary Java files cleaned up by the harness. This does not exercise Android networking, VAD/microphone, speech recognition quality, CascadeStt async delivery or a real external STT provider.

## Remaining acceptance work

- No real-provider microphone conversation tested on handset.
- No acoustic AEC/voice barge-in, AudioTrack timing or session-resumption test on handset.
- OpenAI live service not exercised: no direct key available in prior checks.
- Gemini JVM spike is prior evidence only, not an Android end-to-end result.
- Recording/export lifecycle, audio queue pressure and concurrent-stop cases need more testing.
- OpenAI playback baseline currently samples hardware head when a new item arrives; overlapping queued items and late cancelled deltas still need state-machine tests.
- KEEP_HISTORY disables new message persistence, not existing history/chat metadata; RAM cache currently unbounded.
- UI bottom clipping was disproved on 2026-11-15: UI Automator clips reported bounds at metrics.heightPixels=2316, while the physical screenshot is 1080×2400. A temporary geometry-only probe measured content at y=84 with height=2316 and the Settings button at y=2208 with height=144 (ends at 2352, leaving 48 px). The screenshot confirms the entire button is visible. No inset workaround is justified.
- dist artifacts predate fixes and are not a published release.

Local fixture stopped and adb reverse mapping removed after text/privacy tests. DeviceTest model still points to loopback fixture, so it is not a working real-provider configuration.
