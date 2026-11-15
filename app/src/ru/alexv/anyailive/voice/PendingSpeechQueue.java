package ru.alexv.anyailive.voice;
import java.util.ArrayDeque;
/** Main-thread bounded pre-initialization queue. */
public final class PendingSpeechQueue<T> {
    private final int limit;
    private final ArrayDeque<T> queue = new ArrayDeque<T>();
    public PendingSpeechQueue(int limit) { this.limit=limit; }
    public boolean offer(T item) {
        if (queue.size() >= limit) return false;
        queue.add(item);
        return true;
    }
    public T poll() { return queue.poll(); }
    public boolean isEmpty() { return queue.isEmpty(); }
    public void clear() { queue.clear(); }
}
