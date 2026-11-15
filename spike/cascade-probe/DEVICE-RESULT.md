# Partial CascadeHttp TTS branch — handset evidence

2026-11-15 10:34 Europe/Samara; RMO-NX1 Android 13, adb 192.168.3.111:38555.

Installed production APK: current 201.4 KB development build. Separate same-signature instrumentation loaded production classes from the installed APK; no copied production implementation.

## Observed

- Instrumentation result PASS_PARTIAL_TTS_BRANCH; INSTRUMENTATION_CODE -1.
- Saved selected engine before/after: com.k2fsa.sherpa.onnx.tts.engine, unchanged.
- Public sendText with in-memory Chat response: states [2,3,1] (THINKING, SPEAKING, LISTENING); Chat called once; user/assistant callbacks once each.
- Two actual SystemTts onSpoken callbacks; voice name ru, Android voice reports networkRequired=false.
- Loopback fixture /counts before and after {}, independently checked on host. No POST reached /v1/audio/speech or /v1/audio/transcriptions.
- Fresh Android TextToSpeech logs PID17049: 10:34:24.597 Successfully bound to com.k2fsa.sherpa.onnx.tts.engine; connected 10:34:24.623; setup 10:34:24.626; disconnected during cleanup 10:34:30.136.
- Fresh crash buffer since10:34 empty.

## Observer correction and limitations

Initial two runs failed because ContextWrapper bindService hooks were not called on this Android implementation, despite fresh Android logs confirming real engine binding. Added the modern overload, still no interception. Final probe records bindObservation=not_intercepted_external_log_required, not a fabricated successful hook. Binding evidence comes from the fresh log above; all other assertions stayed enabled.

Probe never calls CascadeHttp.start/startNow, never constructs STT, and never starts AudioIn or AudioOut. It initializes only a probe-owned engine's private branch state, exercises real speakPhrase/SystemTts, then public sendText. Therefore this proves partial TTS routing/completion integration, NOT full HTTP recognition/chat startup, external model behavior, microphone conversation, acoustic quality, OS-wide offline operation or reliable interruption. Chat is a local test double. No user key/model/settings/history changed. Android instrumentation restarts the target process.
