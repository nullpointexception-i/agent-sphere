package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * `RuntimeValidator.toFull` must preserve max_input_tokens / max_output_tokens.
 *
 * <p>CompactionService uses max_input_tokens to compute the compaction threshold; a missed copy falls back to DEFAULT(1M), making the budget 700k,
 * so small-context routes (e.g. 32k / 20k) never trigger compaction — production session 951 (route 4 = 20k) and
 * the Qwen 32k 400s were all caused by this field being dropped.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeValidatorTest {

    @Mock
    SessionSpi sessionSpi;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    RouteSpi routeSpi;
    @Mock
    ModelProviderSpi providerSpi;

    RuntimeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RuntimeValidator(sessionSpi, instanceSpi, routeSpi, providerSpi);
    }

    private static ModelRouteVO route(long id, Long maxInput, Long maxOutput) {
        ModelRouteVO route = new ModelRouteVO();
        route.setId(id);
        route.setProviderId(81L);
        route.setModelName("deepseek-flash");
        route.setCompany("deepseek");
        route.setWeight(100);
        route.setStatus("ACTIVE");
        route.setMaxInputTokens(maxInput);
        route.setMaxOutputTokens(maxOutput);
        route.setCompletionProtocol("STRICT_FINISH");
        return route;
    }

    @Test
    void validate_preservesMaxTokensForPrimaryAndFallbackRoutes() {
        SessionVO session = new SessionVO();
        session.setAgentInstanceId(2L);
        InstanceVO instance = new InstanceVO();
        instance.setModelRouteId(4L);
        ModelRouteVO primary = route(4L, 20_000L, 384_000L);
        primary.setFallbackIds("3");
        primary.setCompletionProtocol("IMPLICIT_TEXT");
        ModelRouteVO fallback = route(3L, 1_000_000L, 384_000L);

        when(sessionSpi.getSession(1L)).thenReturn(session);
        when(instanceSpi.getInstance(2L)).thenReturn(instance);
        when(routeSpi.getRoute(4L)).thenReturn(primary);
        lenient().when(routeSpi.getRoute(3L)).thenReturn(fallback);

        ValidationResult result = validator.validate(1L, null);

        assertEquals(20_000L, result.getModelRoute().getMaxInputTokens().longValue(),
                "primary route 的 max_input_tokens 必须在 ModelRouteFullVO 上保留");
        assertEquals(384_000L, result.getModelRoute().getMaxOutputTokens().longValue());
        assertEquals(1, result.getFallbackRoutes().size());
        assertEquals(1_000_000L, result.getFallbackRoutes().get(0).getMaxInputTokens().longValue(),
                "fallback route 同样不能丢 max_input_tokens");
        assertEquals("IMPLICIT_TEXT", result.getModelRoute().getCompletionProtocol(),
                "primary route 的完成协议必须在 ModelRouteFullVO 上保留");
        assertEquals("STRICT_FINISH", result.getFallbackRoutes().get(0).getCompletionProtocol(),
                "fallback route 同样不能丢完成协议");
    }
}
