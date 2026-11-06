package com.buukle.agent.model.service.impl;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.metrics.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLHandshakeException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Locks the transport retry ladder and handshake classification used for latency diagnosis. */
class ModelProviderRetryConfigTest {

    @Test
    void retryDelayFollowsConfiguredLadderThenSticksToLast() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getLlm().setStreamRetryDelays(List.of(
                Duration.ofMillis(300), Duration.ofSeconds(1)));
        ModelProviderServiceImpl service = service(properties);

        assertEquals(300L, retryDelay(service, 1));
        assertEquals(1000L, retryDelay(service, 2));
        assertEquals(1000L, retryDelay(service, 3));
    }

    @Test
    void emptyLadderFallsBackToFixedDelay() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getLlm().setStreamRetryDelays(List.of());
        properties.getLlm().setStreamRetryDelay(Duration.ofMillis(450));
        ModelProviderServiceImpl service = service(properties);

        assertEquals(450L, retryDelay(service, 1));
        assertEquals(450L, retryDelay(service, 2));
    }

    @Test
    void handshakeFailureIsDetectedFromCauseChain() {
        RuntimeException wrapper = new RuntimeException("send failed",
                new SSLHandshakeException("Remote host terminated the handshake"));

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                ModelProviderServiceImpl.class, "isHandshakeFailure", wrapper));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                ModelProviderServiceImpl.class, "isHandshakeFailure",
                new RuntimeException("connection reset")));
    }

    private static long retryDelay(ModelProviderServiceImpl service, int attempt) {
        Number delay = ReflectionTestUtils.invokeMethod(service, "retryDelayMillis", attempt);
        return delay == null ? -1L : delay.longValue();
    }

    @SuppressWarnings("unchecked")
    private static ModelProviderServiceImpl service(AgentRuntimeProperties properties) {
        ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(null);
        return new ModelProviderServiceImpl(
                mock(com.buukle.agent.model.service.converter.ModelProviderConverter.class),
                properties, new AgentMetrics(registryProvider), 128);
    }
}
