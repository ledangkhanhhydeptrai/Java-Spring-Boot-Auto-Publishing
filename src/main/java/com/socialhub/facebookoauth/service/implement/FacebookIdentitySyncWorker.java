package com.socialhub.facebookoauth.service.implement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class FacebookIdentitySyncWorker implements SmartLifecycle {
    private final FacebookIdentitySyncQueue queue;
    @Value("${app.facebook.identity-sync-worker-enabled:true}")
    private boolean enabled;
    @Value("${app.facebook.identity-sync-worker-delay-ms:2000}")
    private long delay;
    private volatile ScheduledExecutorService executor;

    public FacebookIdentitySyncWorker(FacebookIdentitySyncQueue queue) {
        this.queue = queue;
    }

    @Override
    public synchronized void start() {
        if (!enabled || isRunning()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "facebook-identity-sync");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                queue.processOne();
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Facebook identity sync queue could not complete an item.");
            }
        }, Math.max(2000, delay), Math.max(2000, delay), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        ScheduledExecutorService current = executor;
        return current != null && !current.isShutdown();
    }
}
