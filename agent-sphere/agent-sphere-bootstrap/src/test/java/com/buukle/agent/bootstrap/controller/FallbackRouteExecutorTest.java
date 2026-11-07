package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.config.FallbackRouteExecutor;
import com.buukle.agent.runtime.kernel.config.RouteAttemptException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * fallback must only apply to retryable errors; 400/401/403/404/413/422 fail immediately,
 * so a secondary error from the fallback model does not mask the root cause.
 */
class FallbackRouteExecutorTest {

    private final FallbackRouteExecutor executor = new FallbackRouteExecutor();

    private static ModelRouteFullVO route(long id) {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setId(id);
        route.setBaseUrl("http://example.com/v1");
        return route;
    }

    @Test
    void nonRetryableClientErrorStopsFallback() {
        AtomicInteger calls = new AtomicInteger();
        List<ModelRouteFullVO> routes = List.of(route(1), route(2));

        assertThrows(RouteAttemptException.class, () -> executor.execute(routes, (i, r) -> {
            calls.incrementAndGet();
            throw new RouteAttemptException("bad request", 400, false);
        }));
        assertEquals(1, calls.get(), "400 不应尝试第二条路由");
    }

    @Test
    void retryableServerErrorFallsBack() {
        List<ModelRouteFullVO> routes = List.of(route(1), route(2));
        String result = executor.execute(routes, (i, r) -> {
            if (i == 0) {
                throw new RouteAttemptException("upstream 500", 500, true);
            }
            return "ok";
        });
        assertEquals("ok", result);
    }

    @Test
    void timeoutWithoutStatusFallsBack() {
        List<ModelRouteFullVO> routes = List.of(route(1), route(2));
        String result = executor.execute(routes, (i, r) -> {
            if (i == 0) {
                throw new RouteAttemptException("timeout", null, true);
            }
            return "ok";
        });
        assertEquals("ok", result);
    }
}
