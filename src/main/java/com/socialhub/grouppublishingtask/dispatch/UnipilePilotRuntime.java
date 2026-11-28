package com.socialhub.grouppublishingtask.dispatch;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in, exact-task LIVE pilot. Not a bulk publisher. Never promotes pending tasks. */
@Component
@ConditionalOnProperty(name="app.group.unipile-pilot.enabled",havingValue="true")
public class UnipilePilotRuntime {
    private static final Logger log=LoggerFactory.getLogger(UnipilePilotRuntime.class);
    private final JdbcTemplate db;
    private final CachingConnectionFactory factory;
    private final UnipilePilotPublisher publisher;
    private final GroupDispatchClaimStore claims;
    private final GroupProductionExecutionStore execution;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"group-unipile-pilot");t.setDaemon(true);return t;});
    private volatile SimpleMessageListenerContainer listener;
    private GroupProductionOutboxRelay relay;

    public UnipilePilotRuntime(JdbcTemplate db, CachingConnectionFactory factory, PlatformTransactionManager manager,
        @Value("${app.group.legacy-pilot.enabled:false}") boolean legacy,
        @Value("${app.group.unipile-pilot.confirm:}") String confirm,
        @Value("${app.group.unipile-pilot.workspace-id:}") String workspace,
        @Value("${app.group.unipile-pilot.group-id:}") String group,
        @Value("${app.group.unipile-pilot.task-id:}") String task,
        @Value("${app.group.unipile-pilot.external-group-id:}") String externalGroup,
        @Value("${app.group.unipile-pilot.approved-sha256:}") String hash,
        @Value("${UNIPILE_API_KEY:}") String key,
        @Value("${app.group.unipile-pilot.account-id:}") String account) {
        boolean mediaMode="ONE_AUTHORIZED_MEDIA_TASK".equals(confirm);
        if(legacy || (!mediaMode && !"ONE_AUTHORIZED_TEXT_TASK".equals(confirm)))
            throw new IllegalStateException("Disable legacy pilot and explicitly confirm exact authorized LIVE task");
        this.db=db; this.factory=factory;
        publisher=new UnipilePilotPublisher(db,new UnipilePilotClient(key,account),UUID.fromString(workspace),UUID.fromString(group),UUID.fromString(task),externalGroup,hash,mediaMode);
        claims=new GroupDispatchClaimStore(db); execution=new GroupProductionExecutionStore(db,manager);
    }
    @Scheduled(initialDelayString="${app.group.unipile-pilot.initial-delay-ms:15000}",fixedDelayString="${app.group.unipile-pilot.poll-ms:30000}")
    public void tick() {
        if(worker.isShutdown() || !busy.compareAndSet(false,true)) return;
        try { worker.execute(()->{try { cycle(); } catch(RuntimeException ex) {
            log.warn("[UNIPILE PILOT] Cycle blocked: {}",ex.getClass().getSimpleName());
        } finally { busy.set(false); }}); }
        catch(RuntimeException rejected) { busy.set(false); }
    }
    private synchronized void cycle() {
        UUID w=publisher.workspace,g=publisher.group,t=publisher.task;
        // Existing production queue must not be shared with any other active run.
        if(Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM group_dispatch_outbox o JOIN group_publishing_tasks t ON t.id=o.task_id
                    WHERE o.task_id<>? AND t.status='PROCESSING')
                """,Boolean.class,t))) {
            if(listener!=null) { listener.stop(); listener=null; }
            log.warn("[UNIPILE PILOT] Another production task is active; isolated pilot blocked"); return;
        }
        execution.reviewExpired(w,g,1,t);
        execution.reviewDead(w,g,1,t);
        if(!publisher.isGroupAuthorized(w,g)) return;
        if(listener==null) {
            GroupProductionBroker broker=new GroupProductionBroker(factory); broker.declareTopology();
            var delegate=new GroupProductionConsumer(execution,publisher);
            var container=new SimpleMessageListenerContainer(factory);
            container.setQueueNames(GroupProductionBroker.QUEUE);
            container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1); container.setPrefetchCount(1);
            container.setMessageListener((org.springframework.amqp.core.MessageListener) message->{
                final GroupDispatchEnvelope e;
                try { e=GroupDispatchEnvelope.decode(message.getBody()); }
                catch(RuntimeException invalid) { throw new AmqpRejectAndDontRequeueException("Invalid pilot envelope",invalid); }
                if(!publisher.matches(e)) throw new AmqpRejectAndDontRequeueException("Outside exact pilot scope");
                delegate.onMessage(message);
            });
            container.afterPropertiesSet(); container.start(); listener=container;
            relay=new GroupProductionOutboxRelay(db,broker);
        }
        if(publisher.ready()) {
            var staged=claims.claimAndStage(w,g,1,t);
            if(!staged.isEmpty()) log.info("[UNIPILE PILOT] Staged exact task={}",t);
        }
        relay.relay(w,g,1,3,t);
        execution.reviewDead(w,g,1,t);
    }
    @PreDestroy public synchronized void stop() {
        worker.shutdownNow(); if(listener!=null) { listener.stop(); listener.destroy(); listener=null; }
    }
}
