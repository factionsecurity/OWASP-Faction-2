package com.faction.clientportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables Spring's {@code @Async} support and configures a dedicated thread
 * pool for background report generation tasks.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor used by {@link com.faction.clientportal.service.ReportGenerationTrigger}.
     *
     * <p>Report generation is CPU- and I/O-heavy so a small pool is appropriate.
     * Adjust pool sizes via application properties if needed.
     */
    @Bean("reportGenerationExecutor")
    public Executor reportGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-gen-");
        executor.initialize();
        return executor;
    }

    /**
     * Executor for outbound email.
     *
     * <p>SMTP latency belongs to nobody's request. Notification email used to be sent
     * inline, so an unreachable mail server stalled whatever action produced the
     * notification — adding a comment, assigning an assessment — for the length of the
     * SMTP timeout (10s connect + 10s read, per EmailConfigService).
     *
     * <p>It also must not run on the shared {@code @Scheduled} pool, which is
     * single-threaded and carries the 2-second assessment lock sweep and the 30-second
     * SSE heartbeat; a blocking send there would stall both.
     *
     * <p>Mail is I/O-bound, so the pool is small and the queue generous. Sends are
     * best-effort: if the queue ever saturates, {@code CallerRunsPolicy} pushes the send
     * back onto the calling thread rather than dropping the notification silently.
     */
    // Declared as ThreadPoolTaskExecutor, not Executor: InboundEmailPoller injects it as a
    // TaskExecutor, and Spring matches on the factory method's return type.
    @Bean("mailExecutor")
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Executor for App Store extension hooks.
     *
     * <p>Extension code is third-party and typically calls out over the network — the
     * reference Jira extension opens an HTTP connection per finding when an assessment
     * is finalized. None of that latency belongs to the user's save, and it must not
     * run on a pool Faction depends on: a hung extension that saturated the mail or
     * scheduling pool would take unrelated features down with it.
     *
     * <p>{@code CallerRunsPolicy} would defeat that isolation by pushing extension work
     * back onto a Faction thread, so the queue is generous instead and saturation
     * degrades to the default abort — logged and contained by
     * {@link com.faction.clientportal.service.extension.ExtensionEventService}.
     */
    @Bean("extensionTaskExecutor")
    public ThreadPoolTaskExecutor extensionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("extension-");
        executor.initialize();
        return executor;
    }

    /**
     * Executor for recalculating open findings' stored SLA due dates after an SLA edit
     * ({@link com.faction.clientportal.service.SlaRecalculationService}).
     *
     * <p>One thread, so two edits in quick succession recalculate in order and a run over every open
     * finding never competes with another for the database. Each run reads the SLAs when it starts,
     * so when the queue is full a new request can be discarded: a run already queued will start
     * after the latest save and pick it up.
     */
    @Bean("slaRecalculationExecutor")
    public ThreadPoolTaskExecutor slaRecalculationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("sla-recalc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Background work on workflows (vulnerability status renames), one task at a time in the order the
     * edits were saved. Tasks are durable rows and resume at startup, so nothing is lost; a full queue
     * leaves the task {@code RUNNING} rather than running it out of order on another thread —
     * {@code CallerRunsPolicy} would do exactly that and break the oldest-first resume order.
     */
    @Bean("workflowTaskExecutor")
    public ThreadPoolTaskExecutor workflowTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("workflow-task-");
        executor.setRejectedExecutionHandler((task, pool) -> log.warn(
                "Workflow task queue is full; the rename stays pending and resumes at the next startup"));
        executor.initialize();
        return executor;
    }
}
