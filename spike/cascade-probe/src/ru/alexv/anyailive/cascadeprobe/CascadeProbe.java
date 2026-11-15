package ru.alexv.anyailive.cascadeprobe;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Throwaway instrumentation. Production classes are loaded only from the target APK. */
public final class CascadeProbe extends Instrumentation {
    private static final String BASE = "http://127.0.0.1:18765";
    private final List<String> binds = Collections.synchronizedList(new ArrayList<String>());
    private final List<Integer> states = Collections.synchronizedList(new ArrayList<Integer>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger spoken = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger userTexts = new AtomicInteger();
    private final AtomicInteger assistantTexts = new AtomicInteger();
    private Object engine;
    private Class<?> cascade;
    private Context target;
    private String selected;
    private String expected;
    private String before;
    private final JSONObject report = new JSONObject();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        expected = args == null ? "com.k2fsa.sherpa.onnx.tts.engine" :
                args.getString("expectedEngine", "com.k2fsa.sherpa.onnx.tts.engine");
        start();
    }

    @Override public void onStart() {
        int code = 1;
        try {
            report.put("scope", "partial TTS branch integration; no start/startNow, no STT");
            target = getTargetContext();
            before = counts();
            require(new JSONObject(before).length() == 0, "Fixture must start with empty /counts");
            ClassLoader loader = target.getClassLoader();
            Class<?> settings = loader.loadClass("ru.alexv.anyailive.data.SettingsStore");
            Object store = settings.getConstructor(Context.class).newInstance(target);
            String key = (String) settings.getField("TTS_ENGINE").get(null);
            selected = (String) settings.getMethod("get", String.class, String.class)
                    .invoke(store, key, "");
            require(expected.equals(selected), "Saved engine differs: " + selected);
            report.put("savedEngineBefore", selected);
            cascade = loader.loadClass("ru.alexv.anyailive.voice.CascadeHttp");
            Class<?> modelType = loader.loadClass("ru.alexv.anyailive.data.Model");
            Object model = modelType.getConstructor().newInstance();
            modelType.getField("baseUrl").set(model, BASE + "/v1");
            modelType.getField("keyRef").set(model, "");
            Class<?> chatType = loader.loadClass("ru.alexv.anyailive.voice.CascadeSystem$Chat");
            Object chat = Proxy.newProxyInstance(loader, new Class<?>[]{chatType}, (p, m, a) -> {
                if (m.getName().equals("complete")) {
                    chatCalls.incrementAndGet();
                    return "Привет! Это локальная проверка речи.";
                }
                return objectMethod(p, m, a);
            });
            Class<?> callbackType = loader.loadClass("ru.alexv.anyailive.voice.VoiceEngine$Callback");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (p, m, a) -> {
                switch (m.getName()) {
                    case "onState": states.add((Integer) a[0]); event("state=" + a[0]); break;
                    case "onError": errors.add(String.valueOf(a[0])); event("ERROR " + a[0]); break;
                    case "onUserText": userTexts.incrementAndGet(); break;
                    case "onAssistantText": assistantTexts.incrementAndGet(); break;
                    default: return objectMethod(p, m, a);
                }
                return null;
            });
            Context observed = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                private void recordBind(Intent intent, boolean result) {
                    ComponentName component = intent.getComponent();
                    String pkg = component == null ? intent.getPackage() : component.getPackageName();
                    binds.add(String.valueOf(pkg) + "=" + result);
                    event("bindService " + pkg + " result=" + result);
                }
                @Override public boolean bindService(Intent intent, ServiceConnection conn, int flags) {
                    boolean result = super.bindService(intent, conn, flags);
                    recordBind(intent, result);
                    return result;
                }
                @Override public boolean bindService(Intent intent, int flags,
                        java.util.concurrent.Executor executor, ServiceConnection conn) {
                    boolean result = super.bindService(intent, flags, executor, conn);
                    recordBind(intent, result);
                    return result;
                }
            };
            onMain(() -> {
                engine = cascade.getConstructor(Context.class, modelType, chatType)
                        .newInstance(observed, model, chat);
                set(engine, "cb", callback);
                set(engine, "selectedTtsEngine", selected);
                set(engine, "generation", 41);
                set(engine, "inflight", 1);
                // Deliberately no AudioOut.start or CascadeStt construction. SystemTts plays directly.
                Class<?> outType = loader.loadClass("ru.alexv.anyailive.voice.AudioOut");
                set(engine, "out", outType.getConstructor(int.class, int.class).newInstance(24000, 1));
                set(engine, "running", true);
                require(get(engine, "stt") == null, "Unexpected STT object");
                invoke(engine, "setState", new Class<?>[]{int.class}, 3);
                invoke(engine, "speakPhrase", new Class<?>[]{String.class, int.class},
                        "Проверка локального синтеза.", 41);
                new Handler(Looper.getMainLooper()).postDelayed((Runnable) get(engine, "watch"), 150);
            });
            waitUntil("initial real TTS completion", () -> onMainValue(() -> {
                Object tts = get(engine, "fallbackTts");
                return tts != null && (Boolean) invoke(tts, "ready", new Class<?>[0])
                        && ((Set<?>) get(engine, "pendingSystemSpeech")).isEmpty()
                        && ((Integer) get(engine, "inflight")) == 0
                        && ((Integer) invoke(engine, "state", new Class<?>[0])) == 1;
            }), 45000);
            onMain(() -> {
                require(get(engine, "stt") == null, "STT must stay null");
                Object system = get(engine, "fallbackTts");
                String requested = (String) get(system, "requestedEngine");
                require(selected.equals(requested), "SystemTts requested different engine: " + requested);
                if (binds.isEmpty()) {
                    report.put("bindObservation", "not_intercepted_external_log_required");
                    event("bind observer not intercepted; correlate fresh TextToSpeech logs externally");
                } else {
                    require(binds.contains(selected + "=true"), "No successful bind to selected engine: " + binds);
                    for (String binding : binds) {
                        require(binding.equals(selected + "=true"), "Unexpected engine bind: " + binding);
                    }
                    report.put("bindObservation", "selected_package_bind_intercepted");
                }
                TextToSpeech real = (TextToSpeech) get(system, "tts");
                Voice voice = real.getVoice();
                require(voice != null, "No actual TTS voice");
                require(!voice.isNetworkConnectionRequired(), "TTS voice requires network");
                report.put("requestedEngine", requested);
                report.put("voice", voice.getName());
                report.put("voiceRequiresNetwork", voice.isNetworkConnectionRequired());
                Object delegate = get(system, "cb");
                Class<?> ttsCallback = loader.loadClass("ru.alexv.anyailive.voice.SystemTts$Callback");
                Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{ttsCallback}, (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
                    if (m.getName().equals("onSpoken")) {
                        spoken.incrementAndGet(); event("onSpoken " + a[0]);
                    } else {
                        errors.add(m.getName() + ": " + java.util.Arrays.toString(a));
                    }
                    return m.invoke(delegate, a);
                });
                invoke(system, "setCallback", new Class<?>[]{ttsCallback}, observer);
                states.clear();
                invoke(engine, "sendText", new Class<?>[]{String.class}, "Скажи привет.");
            });
            waitUntil("sendText states and utterance callback", () -> onMainValue(() ->
                    spoken.get() > 0 && states.contains(2) && states.contains(3)
                    && ((Integer) invoke(engine, "state", new Class<?>[0])) == 1), 45000);
            onMain(() -> {
                require(states.equals(java.util.Arrays.asList(2, 3, 1)), "Wrong states: " + states);
                require(chatCalls.get() == 1 && userTexts.get() == 1 && assistantTexts.get() == 1,
                        "Missing/multiple Chat or text callbacks");
                require(get(engine, "stt") == null, "STT unexpectedly created");
                require((Boolean) invoke(engine, "usingSystemTts", new Class<?>[0]), "Wrong routing");
                require(!(Boolean) get(engine, "systemTtsFallback"), "Unexpected HTTP fallback flag");
            });
            String after = counts();
            require(new JSONObject(after).length() == 0, "Unexpected fixture requests: " + after);
            String savedAfter = (String) settings.getMethod("get", String.class, String.class)
                    .invoke(store, key, "");
            require(selected.equals(savedAfter), "Saved engine changed");
            report.put("savedEngineAfter", savedAfter);
            report.put("httpCountsBefore", new JSONObject(before));
            report.put("httpCountsAfter", new JSONObject(after));
            report.put("states", new org.json.JSONArray(states));
            report.put("binds", new org.json.JSONArray(binds));
            report.put("onSpoken", spoken.get());
            report.put("chatCalls", chatCalls.get());
            report.put("sttConstructed", false);
            require(errors.isEmpty(), "TTS/cascade errors: " + errors);
            report.put("result", "PASS_PARTIAL_TTS_BRANCH");
            code = -1;
        } catch (Throwable failure) {
            try { report.put("result", "FAIL"); report.put("error", android.util.Log.getStackTraceString(failure)); }
            catch (Exception ignored) { }
        } finally {
            if (engine != null) {
                try { onMain(() -> invoke(engine, "stop", new Class<?>[0])); }
                catch (Exception e) { code = 1; event("cleanup failed: " + e); }
            }
            Bundle result = new Bundle();
            result.putString("stream", "\n" + report.toString() + "\n");
            result.putString("report", report.toString());
            finish(code, result);
        }
    }

    private String counts() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/counts").openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(4000);
        try {
            require(c.getResponseCode() == 200, "Fixture /counts unavailable");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512]; int n;
                while ((n = in.read(buffer)) >= 0) {
                    require(out.size() + n <= 16384, "Fixture response too big");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), "UTF-8");
            }
        } finally { c.disconnect(); }
    }
    private void event(String message) {
        Bundle b = new Bundle(); b.putString("stream", "\nCASCADE_PROBE " + message + "\n"); sendStatus(0, b);
    }
    private interface Work { void run() throws Exception; }
    private interface Check { boolean test() throws Exception; }
    private void onMain(Work work) throws Exception {
        final Exception[] failure = new Exception[1];
        runOnMainSync(() -> { try { work.run(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
    }
    private boolean onMainValue(Check check) throws Exception {
        final boolean[] result = new boolean[1]; onMain(() -> result[0] = check.test()); return result[0];
    }
    private void waitUntil(String name, Check check, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            require(errors.isEmpty(), "Errors while waiting: " + errors);
            if (check.test()) return;
            SystemClock.sleep(75);
        }
        throw new IllegalStateException("Timeout: " + name + "; states=" + states + "; binds=" + binds);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args);
    }
    private static Object objectMethod(Object p, Method m, Object[] args) {
        if (m.getName().equals("toString")) return "CascadeProbe observer";
        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
        if (m.getName().equals("equals")) return p == args[0];
        return null;
    }
    private static void require(boolean pass, String message) {
        if (!pass) throw new IllegalStateException(message);
    }
}
