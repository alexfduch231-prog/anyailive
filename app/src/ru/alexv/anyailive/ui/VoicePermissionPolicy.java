package ru.alexv.anyailive.ui;

/**
 * Состояние запроса разрешения RECORD_AUDIO на экране чата.
 *
 * <p>Системный диалог разрешения ставит Activity на паузу, поэтому запрос обязан
 * пережить эту паузу: иначе разрешение, выданное в диалоге, выглядело бы как
 * «чужой» результат. На обычном пути результат приходит раньше {@code onResume},
 * тогда он откладывается ({@link #DEFER}) и забирается один раз в {@code onResume}
 * ({@link #consumePendingGrant()}). Микрофон не открывается на закрытом экране
 * ({@link #onClose()}) и на новом экземпляре, у которого нет своего запроса.
 *
 * <p>Класс без зависимостей от Android: вызывается только с главного потока.
 */
public final class VoicePermissionPolicy {
    /** Результат не наш или уже обработан: ничего не делать. */
    public static final int IGNORE = 0;
    /** Разрешение выдано, экран видим: можно запускать голос сразу. */
    public static final int START = 1;
    /** Разрешение выдано, но экран на паузе: запускать в {@code onResume}. */
    public static final int DEFER = 2;

    /** Запрос отправлен и ещё не получил результата. */
    private boolean pending;
    /** Разрешение пришло, пока экран был на паузе. */
    private boolean grantedWhilePaused;
    /** Экран видим. Запрос бывает только с видимого экрана, поэтому старт — true. */
    private boolean resumed = true;
    /** Экран уничтожен: запрос и отложенное разрешение больше не действуют. */
    private boolean closed;

    /**
     * Мы собираемся вызвать {@code requestPermissions()}.
     *
     * @return false, если экран уже закрыт и диалог показывать не нужно.
     */
    public boolean request() {
        if (closed) return false;
        pending = true;
        return true;
    }

    /** Экран на паузе. Ожидание разрешения при этом сохраняется. */
    public void onPause() {
        resumed = false;
    }

    /** Экран снова видим. Отложенное разрешение забирается через {@link #consumePendingGrant()}. */
    public void onResume() {
        resumed = true;
    }

    /** Экран уничтожен: забываем и запрос, и отложенное разрешение. */
    public void onClose() {
        closed = true;
        pending = false;
        grantedWhilePaused = false;
    }

    /**
     * Результат системного диалога.
     *
     * @return {@link #START}, {@link #DEFER} или {@link #IGNORE}.
     */
    public int onResult(boolean granted) {
        if (closed || !pending) return IGNORE;
        if (!granted) {
            // Отказ закрывает запрос: запоздавшее разрешение по нему уже не наше.
            pending = false;
            grantedWhilePaused = false;
            return IGNORE;
        }
        if (resumed) {
            pending = false;
            grantedWhilePaused = false;
            return START;
        }
        grantedWhilePaused = true;
        return DEFER;
    }

    /**
     * Вызывается из {@code onResume} после {@link #onResume()}.
     *
     * @return true один раз, если разрешение пришло до возобновления экрана.
     */
    public boolean consumePendingGrant() {
        if (closed || !resumed || !pending || !grantedWhilePaused) return false;
        pending = false;
        grantedWhilePaused = false;
        return true;
    }
}
