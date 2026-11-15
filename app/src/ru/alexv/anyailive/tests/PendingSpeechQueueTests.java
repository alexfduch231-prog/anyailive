package ru.alexv.anyailive.tests;
import ru.alexv.anyailive.voice.PendingSpeechQueue;
public final class PendingSpeechQueueTests {
    public static void main(String[] args) {
        PendingSpeechQueue<String> q = new PendingSpeechQueue<String>(20);
        for (int i=0;i<20;i++) check(q.offer("id"+i), "accept bounded phrase");
        check(!q.offer("overflow"), "overflow must be reported to caller");
        for (int i=0;i<20;i++) check(("id"+i).equals(q.poll()), "preserve accepted order");
        check(q.isEmpty(), "empty after drain");
        check(q.offer("new"), "space reusable");
        q.clear();
        check(q.isEmpty() && q.poll()==null, "stop clears pending phrases");
        System.out.println("PASS PendingSpeechQueue 44 checks");
    }
    private static void check(boolean value,String name) { if(!value) throw new AssertionError(name); }
}
