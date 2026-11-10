package com.buukle.agent.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Shared Micrometer facade for Super-Agent and IM signals.
 *
 * <p>Metrics are optional at runtime: when no registry is configured all calls are no-ops.
 * Keep names low-cardinality; tag values must come from enumerations or fixed vocabularies.
 */
@Component
@RequiredArgsConstructor
public class AgentMetrics {

    public static final String EVENT_INGESTED = "superagent.event.ingested";
    public static final String GROUP_ROUTER_BATCH = "superagent.group_router.batch";
    public static final String GROUP_ROUTER_DEFERRED_EVENTS = "superagent.group_router.deferred_events";
    public static final String AGENT_ROUTER_DECISION = "superagent.agent_router.decision";
    public static final String AGENT_ROUTER_DELIVERY = "superagent.agent_router.delivery";
    public static final String AGENT_ROUTER_GROUP_LOCK_SKIPPED = "superagent.agent_router.group_lock_skipped";
    public static final String AGENT_ROUTER_START_FAILED = "superagent.agent_router.start_failed";
    public static final String AGENT_ROUTER_WAKE_SKIPPED = "superagent.agent_router.wake_skipped";
    public static final String AGENT_ROUTER_STREAM_PUBLISH_FAILED =
            "superagent.agent_router.stream_publish_failed";
    public static final String AGENT_ROUTER_FINAL_REPLY = "superagent.agent_router.final_reply";
    public static final String AGENT_ROUTER_FORK_CREATED = "superagent.agent_router.fork_created";
    public static final String AGENT_ROUTER_FORK_MERGED = "superagent.agent_router.fork_merged";
    public static final String AGENT_ROUTER_FORK_FAILED = "superagent.agent_router.fork_failed";
    public static final String AGENT_ROUTER_FORK_TIMEOUT = "superagent.agent_router.fork_timeout";
    public static final String WORKER_REPORT = "superagent.worker.report";
    public static final String WORKER_DISPATCH_LOCK_SKIPPED = "superagent.worker.dispatch_lock_skipped";
    public static final String RETENTION_ROWS = "superagent.retention.rows";
    public static final String RETENTION_TRUNCATED = "superagent.retention.truncated";
    public static final String WATCHDOG_RECOVERED = "superagent.watchdog.recovered";
    public static final String IM_MESSAGE_SENT = "im.message.sent";
    public static final String IM_SSE_REGISTERED = "im.sse.registered";
    public static final String IM_SSE_PUBLISH_FAILED = "im.sse.publish_failed";
    /** LLM streaming-layer retries and handshake failures (used to pinpoint network-related slow replies). */
    public static final String LLM_STREAM_RETRY = "llm.stream.retry";
    public static final String LLM_STREAM_HANDSHAKE_FAILED = "llm.stream.handshake_failed";
    /** Router parked input (clarification / tool approval) bridged to the IM DM. */
    public static final String ROUTER_INPUT_PROJECTED = "superagent.router_input.projected";
    public static final String ROUTER_INPUT_ANSWERED = "superagent.router_input.answered";
    public static final String ROUTER_INPUT_CANCELLED = "superagent.router_input.cancelled";
    public static final String ROUTER_INPUT_CARD_FAILED = "superagent.router_input.card_failed";

    private final ObjectProvider<MeterRegistry> registryProvider;

    public void increment(String name) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name).increment();
        }
    }

    public void increment(String name, String tagKey, String tagValue) {
        ensureTag(tagKey, tagValue);
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name, tagKey, tagValue).increment();
        }
    }

    public void add(String name, String tagKey, String tagValue, double amount) {
        ensureTag(tagKey, tagValue);
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name, tagKey, tagValue).increment(amount);
        }
    }

    private static void ensureTag(String tagKey, String tagValue) {
        if (tagKey == null || tagKey.isBlank() || tagValue == null || tagValue.isBlank()) {
            throw new IllegalArgumentException("metric tags must be non-blank");
        }
    }
}
