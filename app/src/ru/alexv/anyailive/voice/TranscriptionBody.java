package ru.alexv.anyailive.voice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure request body assembly for microphone PCM transcription. */
public final class TranscriptionBody {
    private TranscriptionBody() {
    }

    public static byte[] build(String boundary, byte[] pcm, String model, String language) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("model", model != null && model.trim().length() > 0
                ? model.trim() : "whisper-1");
        fields.put("language", language);
        return HttpWire.multipart(boundary, "file", "audio.wav", "audio/wav",
                Wav.encode(pcm, 16000, 1), fields);
    }
}
