package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ValidationResult {
    private SessionVO session;
    private InstanceVO agentInstance;
    private ModelRouteFullVO modelRoute;
    @Builder.Default
    private List<ModelRouteFullVO> fallbackRoutes = java.util.Collections.emptyList();
}
