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
            } catch (Exception e) {
                lastEx = e;
                log.warn("Route {} failed, trying next", route.getId(), e);
            }
        }
        if (lastEx instanceof RuntimeException re) throw re;
        throw new RuntimeException("All routes exhausted", lastEx);
    }
}
