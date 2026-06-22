package com.buukle.agent.runtime.kernel.port;

import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class KernelContext implements Serializable {
    private InstanceVO agentInstance;
    private SessionVO session;
    private RunVO run;
    private List<CapabilityFullVO> capabilities;
    private List<RuntimeTool> tools;
    private ModelRouteFullVO modelRoute;
    private List<ModelRouteFullVO> fallbackRoutes;
    private String userMessage;
}
