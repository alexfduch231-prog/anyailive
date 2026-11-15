# CascadeHttp Android probe (throwaway)

Standalone `android.app.Instrumentation` package: `ru.alexv.anyailive.cascadeprobe`.
Target: `ru.alexv.anyailive`. No production classes are bundled: reflection loads the installed target APK's classes, not the working tree. The target must contain the expected current implementation and be signed with the same certificate.

## Safety and honest scope

This is **partial TTS branch integration, not full STT/cascade startup integration**.

- Never calls `CascadeHttp.start` / `startNow`: those start `CascadeStt`/`AudioIn` before an immediate pause could safely prevent capture/dispatch. `LISTENING` also unpauses STT again.
- Never constructs STT, never starts `AudioOut`, never reads a microphone, never sends audio. No production hooks or changes.
- Initializes only a new probe-owned `CascadeHttp` object's private `cb`, `selectedTtsEngine`, `generation=41`, `inflight=1`, `out` (unstarted), and `running=true`.
- Calls private `setState(SPEAKING)` and `speakPhrase` once to enter the real production `SystemTts` branch; schedules the production `watch` runnable. Then calls public `sendText` with an in-memory Russian Chat implementation and observes real state callbacks.
- Only reads `SettingsStore.TTS_ENGINE`, fails rather than changing a different saved value. Does not touch API keys/model catalog/history or user settings.
- TTS is real and audible. The probe observes real `SystemTts.ready`, public Android `TextToSpeech.getVoice` with `isNetworkConnectionRequired=false`, and subsequent `SystemTts.Callback.onSpoken`. It forwards the callback to the original production delegate. It never uses hidden `getCurrentEngine`. It attempts both public `bindService` overloads; when intercepted, every observed bind must successfully target the selected package. Some Android versions bypass this ContextWrapper hook: an empty list is reported as `bindObservation=not_intercepted_external_log_required`, NOT as proof of a selected-engine bind. In that case root must separately correlate fresh target-PID Android `TextToSpeech` logs showing successful bind/connection to the saved package during this exact run.
- This is not packet capture or OS-level network isolation. `/counts={}` proves no requests reached the loopback fixture, not that every process on the phone was offline. A trusted installed local sherpa engine is a prerequisite. No guarantee about unrelated application startup/background work or another app's network traffic is made.
- Instrumentation can stop/restart the target process; save active sessions first. Normal APK install and instrumentation output are controlled by the root agent, not this build script.

## Build only (no installation)

From repository root, PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File spike/cascade-probe/build.ps1
```

Uses existing Android SDK 34 build-tools/platform and `JAVA_HOME` JDK 17+, existing `debug.keystore`, alias `anyailive`, password `android`. All outputs stay in `spike/cascade-probe/build`; does not clean shared build or run production build. JDK is on the normal boot classpath for lambda compilation; Android API stubs are compile classpath, d8 desugars for min API 24.

## Root-only installation/run after approval

The root-owned fixture must already listen on host loopback port 18765; adb reverse must map device 18765 to it. Its `GET /counts` returns a JSON object, initially `{}`. Every unexpected POST must count its exact path and reject it without reading/retaining the body. The probe does not start/reset the fixture.

```powershell
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
& $adb -s 192.168.3.111:38555 reverse tcp:18765 tcp:18765
& $adb -s 192.168.3.111:38555 install -r spike/cascade-probe/build/cascade-probe.apk
& $adb -s 192.168.3.111:38555 shell am instrument -w -r -e expectedEngine com.k2fsa.sherpa.onnx.tts.engine ru.alexv.anyailive.cascadeprobe/.CascadeProbe
```

Supply the exact saved sherpa package if different. There are no run arguments for a remote endpoint or production model. The only probe URL is `http://127.0.0.1:18765`; model base URL is `/v1` there and `keyRef` is empty. Fixture control `GET /counts` requests are expected; zero HTTP here means **zero model POST requests**, specifically no `/v1/audio/speech` or `/v1/audio/transcriptions`.

Success report: `PASS_PARTIAL_TTS_BRANCH`, `INSTRUMENTATION_CODE: -1`, states `[2,3,1]` (THINKING/SPEAKING/LISTENING), one fake Chat call and user/assistant text callback, positive `onSpoken`, selected bind `=true` OR explicit `not_intercepted_external_log_required` plus separately verified fresh Android logs, non-network voice, unchanged saved engine, STT absent, both count objects `{}`. Android instrumentation exit alone is insufficient: inspect the JSON result. Failure reports `FAIL` and stack trace, code `1`. Always stop/release the probe-owned engine on exit.

This deliverable was built and signature-verified only; device execution evidence is supplied separately by the root after approval.
