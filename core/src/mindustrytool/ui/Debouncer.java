package mindustrytool.ui;

import java.util.concurrent.*;

import arc.Core;

public class Debouncer {
    // daemon: иначе пул из не-daemon потока держал бы JVM живой после закрытия игры
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "debouncer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> future;
    private final long delay;

    public Debouncer(long delay, TimeUnit unit) {
        this.delay = unit.toMillis(delay);
    }

    public synchronized void debounce(Runnable task) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        future = scheduler.schedule(() -> Core.app.post(task), delay, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
