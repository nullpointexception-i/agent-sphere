package com.buukle.agent.superagent.dtvo.vo;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import lombok.Data;

import java.io.Serializable;

/** Router/worker instances and guardian session owned by one super agent. */
@Data
public class SuperAgentInstancesVO implements Serializable {
    private Long superAgentId;
    private InstanceVO routerInstance;
    private InstanceVO workerInstance;
    private SessionVO guardianSession;
}
