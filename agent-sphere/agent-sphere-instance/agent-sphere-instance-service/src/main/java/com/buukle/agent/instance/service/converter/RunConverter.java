package com.buukle.agent.instance.service.converter;

import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import org.springframework.stereotype.Component;

@Component
public class RunConverter {
    public RunVO toVO(AgentRun run) {
        if (run == null) return null;
        RunVO vo = new RunVO();
        vo.setId(run.getId());
        vo.setSessionId(run.getSessionId());
        vo.setType(run.getType());
        vo.setUserMessage(run.getUserMessage());
        vo.setAssistantReply(run.getAssistantReply());
        vo.setReasoning(run.getReasoning());
        vo.setIntentClassification(run.getIntentClassification());
        vo.setStatus(run.getStatus());
        vo.setCreatedBy(run.getCreatedBy());
        vo.setCreatedAt(run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        return vo;
    }

    public AgentRun toDO(CreateRunDTO dto) {
        AgentRun run = new AgentRun();
        run.setSessionId(dto.getSessionId());
        run.setType(dto.getType());
        run.setUserMessage(dto.getUserMessage());
        run.setStatus(RunEnum.STATUS_PENDING);
        return run;
    }

    public AgentRun toDO(RunVO vo) {
        AgentRun run = new AgentRun();
        run.setId(vo.getId());
        run.setSessionId(vo.getSessionId());
        run.setType(vo.getType());
        run.setUserMessage(vo.getUserMessage());
        run.setAssistantReply(vo.getAssistantReply());
        run.setReasoning(vo.getReasoning());
        run.setIntentClassification(vo.getIntentClassification());
        run.setStatus(vo.getStatus());
        return run;
    }
}
