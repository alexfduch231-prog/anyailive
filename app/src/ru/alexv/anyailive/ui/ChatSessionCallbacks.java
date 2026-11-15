package ru.alexv.anyailive.ui;

/**
 * Граница колбэков голосового каскада на экране чата.
 *
 * Экран переписки включает и глушит каскад сколько угодно раз, поэтому у каждой
 * сессии свой номер. Колбэк, пришедший от старой сессии — уже поставленный в
 * очередь UI-потока, синхронно вызванный из engine.stop() или пришедший после
 * позднего разрешения на микрофон — не должен ни писать в историю, ни гасить ту
 * сессию, что успела начаться вместо него.
 *
 * Проверка делается непосредственно перед эффектом, поэтому внутри
 * runOnUiThread её нужно перепроверять, а не полагаться на момент постановки.
 */
public final class ChatSessionCallbacks {

    /** Получатель финального текста реплики. */
    public interface TextSink {
        void persist(String text);
    }

    /** Номер последней выданной сессии. */
    private int issued;
    /** Номер текущей сессии; 0 — каскад выключен. */
    private int current;

    /** Открыть новую сессию и вернуть её номер. */
    public int begin() {
        issued++;
        current = issued;
        return current;
    }

    /** Закрыть сессию: все её последующие колбэки станут no-op. Идемпотентно. */
    public void stop() {
        current = 0;
    }

    /** Идёт ли ещё сессия с таким номером. */
    public boolean active(int token) {
        return token != 0 && token == current;
    }

    /** Выполнить эффект колбэка, если его сессия всё ещё текущая. */
    public void runIfActive(int token, Runnable effect) {
        if (active(token) && effect != null) {
            effect.run();
        }
    }

    /** Сохранить финальный текст реплики: пустой текст в историю не попадает. */
    public void deliverText(int token, String text, TextSink sink) {
        if (!active(token) || sink == null) {
            return;
        }
        String clean = text == null ? "" : text.trim();
        if (clean.length() == 0) {
            return;
        }
        sink.persist(clean);
    }
}
