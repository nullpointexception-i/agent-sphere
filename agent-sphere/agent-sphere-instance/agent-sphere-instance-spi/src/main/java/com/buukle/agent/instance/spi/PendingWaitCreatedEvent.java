package com.buukle.agent.instance.spi;

import java.io.Serializable;
import java.time.Instant;

/**
 * A wait record has been created.
 *
 * <p>Published as a Spring local event by {@code PendingWaitService#createPending} and listened to by
 * the orchestration module's {@code PendingWaitWakeScheduler}, which registers the in-memory timer.
 *
 * <p>Why an event instead of letting the SPI call the wake logic directly: the kernel (ToolExecutor)
 * may only depend on instance-spi, while {@code ChatRuntimeService#resumeFromWait}, which actually
 * resumes the run, lives in orchestration — the dependency direction is orchestration → kernel, and a
 * reverse call would create a cycle. Spring local events are the existing cross-module solution in
 * this repository (same as RuntimeEventVO).
 */
public record PendingWaitCreatedEvent(Long waitId, Long sessionId, Long runId, Instant wakeAt) implements Serializable {
}