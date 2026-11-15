package android.os;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
public final class Handler {
    public static final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { queue.add(r); return true; }
    public boolean postDelayed(Runnable r, long delay) { return true; }
    public static Runnable next() throws Exception {
        Runnable r = queue.poll(5, TimeUnit.SECONDS);
        if (r == null) throw new AssertionError("worker did not post callback");
        return r;
    }
}
