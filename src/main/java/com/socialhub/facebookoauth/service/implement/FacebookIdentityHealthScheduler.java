package com.socialhub.facebookoauth.service.implement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class FacebookIdentityHealthScheduler implements SmartLifecycle {
    private final FacebookIdentityService identities;
    @Value("${app.facebook.identity-health-enabled:true}") private boolean enabled;
    @Value("${app.facebook.identity-health-batch-size:5}") private int batch;
    @Value("${app.facebook.identity-health-interval-minutes:30}") private int interval;
    @Value("${app.facebook.identity-health-delay-ms:60000}") private long delay;
    private volatile ScheduledExecutorService executor;
    public FacebookIdentityHealthScheduler(FacebookIdentityService identities) { this.identities = identities; }
    @Override public synchronized void start() {
        if (!enabled || isRunning()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "facebook-identity-health"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { identities.checkDue(Math.max(1, Math.min(batch, 50)), Math.max(5, interval)); }
            catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Facebook health scan could not complete.");
            }
        }, Math.max(1000, delay), Math.max(1000, delay), TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void stop() {
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    @Override public boolean isRunning() {
        ScheduledExecutorService current = executor;
        return current != null && !current.isShutdown();
    }
}
