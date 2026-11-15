package ru.alexv.anyailive.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure JVM regression tests; run without Android. */
public final class MemoryHistoryTests {
    private static final class Disk implements MemoryHistory.Storage<String> {
        final Map<Long, List<String>> rows = new HashMap<Long, List<String>>();
        int saves;
        public List<String> load(long chatId) {
            List<String> existing = rows.get(chatId);
            return existing == null ? new ArrayList<String>() : new ArrayList<String>(existing);
        }
        public String save(long chatId, String message) {
            saves++;
            if (!rows.containsKey(chatId)) rows.put(chatId, new ArrayList<String>());
            rows.get(chatId).add(message);
            return message;
        }
    }

    public static void main(String[] args) {
        Disk disk = new Disk();
        disk.save(7, "old saved");
        MemoryHistory<String> memory = new MemoryHistory<String>();
        memory.append(7, "private user", false, disk);
        memory.append(7, "private answer", false, disk);
        equal(Arrays.asList("old saved", "private user", "private answer"), memory.messages(7, disk), "private context is readable");
        equal(Arrays.asList("old saved"), disk.load(7), "private messages never reach disk");
        equal(Integer.valueOf(1), Integer.valueOf(disk.saves), "history off performs no saves");
        memory.append(7, "future saved", true, disk);
        equal(Arrays.asList("old saved", "future saved"), disk.load(7), "re-enable saves only future messages");
        equal(Arrays.asList("private answer", "future saved"), memory.lastMessages(7, 2, disk), "tail preserves actual insertion order");
        equal(Arrays.asList("old saved", "private user", "private answer", "future saved"), memory.lastMessages(7, 50, disk), "oversized tail returns full context");
        equal(new ArrayList<String>(), memory.lastMessages(7, 0, disk), "zero tail is empty");
        equal(new ArrayList<String>(), memory.lastMessages(7, -3, disk), "negative tail is empty");
        memory.messages(7, disk).clear();
        equal(Integer.valueOf(4), Integer.valueOf(memory.messages(7, disk).size()), "returned list cannot clear context");
        memory.append(9, "other chat private", false, disk);
        equal(Arrays.asList("other chat private"), memory.messages(9, disk), "chat contexts stay isolated");
        long firstId = memory.nextTemporaryId();
        long secondId = memory.nextTemporaryId();
        if (firstId >= 0 || secondId >= 0 || firstId == secondId) throw new AssertionError("temporary IDs must be unique and negative");
        memory.remove(7);
        disk.rows.remove(7L); // ChatStore deletes the corresponding database rows.
        equal(new ArrayList<String>(), memory.messages(7, disk), "delete drops private context");
        equal(Arrays.asList("other chat private"), memory.messages(9, disk), "delete does not clear another chat");
        MemoryHistory<String> restarted = new MemoryHistory<String>();
        equal(new ArrayList<String>(), restarted.messages(9, disk), "private messages do not survive process restart");
        System.out.println("MemoryHistoryTests: PASS (private context, toggles, ordering, limits, isolation, deletion, process restart, IDs)");
    }

    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
    }
}
