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
 * `RuntimeValidator.toFull` 必须保留 max_input_tokens / max_output_tokens。
 *
 * <p>CompactionService 用 max_input_tokens 算压缩阈值；漏拷会回落 DEFAULT(1M)，预算变 700k，
 * 小上下文路由（如 32k / 20k）永远不触发压缩 —— 线上 session 951（route 4 = 20k）与
 * Qwen 32k 的 400 都是这个字段被丢掉导致的。
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
    }
}
