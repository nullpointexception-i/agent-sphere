package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiFunction;

@Slf4j
@Component
public class FallbackRouteExecutor {

    public <T> T execute(List<ModelRouteFullVO> routes, BiFunction<Integer, ModelRouteFullVO, T> fn) {
        Exception lastEx = null;
        for (int i = 0; i < routes.size(); i++) {
            ModelRouteFullVO route = routes.get(i);
            if (route.getBaseUrl() == null) continue;
            try {
                return fn.apply(i, route);
            } catch (RouteAttemptException e) {
                lastEx = e;
                if (!e.shouldFallback()) {
                    // Deterministic errors such as 400/401/403/404/413/422: switching models will not help,
                    // and continuing the fallback would only mask the root cause with a secondary error.
                    log.warn("Route {} failed with non-retryable error (status={}), stop fallback",
                            route.getId(), e.getHttpStatus());
                    throw e;
                }
                log.warn("Route {} failed, trying next", route.getId(), e);
            } catch (Exception e) {
                lastEx = e;
                log.warn("Route {} failed, trying next", route.getId(), e);
            }
        }
        if (lastEx instanceof RuntimeException re) throw re;
        throw new RuntimeException("All routes exhausted", lastEx);
    }
}
