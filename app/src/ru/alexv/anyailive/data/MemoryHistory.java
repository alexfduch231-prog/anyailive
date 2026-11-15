package ru.alexv.anyailive.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Process-local timeline. Private entries are never replayed into persistent storage. */
final class MemoryHistory<T> {
    interface Storage<T> {
        List<T> load(long chatId);
        T save(long chatId, T message);
    }

    private final Map<Long, List<T>> timelines = new HashMap<Long, List<T>>();
    private long temporaryId = -1;

    synchronized T append(long chatId, T message, boolean keepHistory, Storage<T> storage) {
        List<T> timeline = timeline(chatId, storage);
        T added = keepHistory ? storage.save(chatId, message) : message;
        timeline.add(added);
        return added;
    }

    synchronized List<T> messages(long chatId, Storage<T> storage) {
        return new ArrayList<T>(timeline(chatId, storage));
    }

    synchronized List<T> lastMessages(long chatId, int count, Storage<T> storage) {
        List<T> all = timeline(chatId, storage);
        int from = Math.max(0, all.size() - Math.max(0, count));
        return new ArrayList<T>(all.subList(from, all.size()));
    }

    synchronized void remove(long chatId) {
        timelines.remove(chatId);
    }

    synchronized long nextTemporaryId() {
        return temporaryId--;
    }

    private List<T> timeline(long chatId, Storage<T> storage) {
        List<T> result = timelines.get(chatId);
        if (result == null) {
            result = new ArrayList<T>(storage.load(chatId));
            timelines.put(chatId, result);
        }
        return result;
    }
}
