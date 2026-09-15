package com.faction.clientportal.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workflowTaskExecutor}'s queue is generous, but a saturated queue must never run a rename out of
 * order on the caller's thread ({@code CallerRunsPolicy} would do exactly that) — it must leave the task
 * pending so the next startup's {@code resumeInterruptedRenames} picks it up in order.
 */
class AsyncConfigTest {

    @Test
    void aRejectedWorkflowTaskIsNotRunOnTheCallingThreadWhenTheQueueIsFull() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().workflowTaskExecutor();
        try {
            RejectedExecutionHandler handler = executor.getThreadPoolExecutor().getRejectedExecutionHandler();
            AtomicBoolean ranInline = new AtomicBoolean(false);

            handler.rejectedExecution(() -> ranInline.set(true), executor.getThreadPoolExecutor());

            assertThat(ranInline).isFalse();
        } finally {
            executor.shutdown();
        }
    }
}
