package com.buukle.agent.instance.service.converter;

import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.util.json.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.List;

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
        vo.setAttachments(parseAttachments(run.getAttachments()));
        vo.setStatus(run.getStatus());
        vo.setRunMode(run.getRunMode());
        vo.setLoopCapped(run.getLoopCapped());
        vo.setLoopCount(run.getLoopCount());
        vo.setLoopLimit(run.getLoopLimit());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setCreatedBy(run.getCreatedBy());
        vo.setCreatedAt(run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        return vo;
    }

    public AgentRun toDO(CreateRunDTO dto) {
        AgentRun run = new AgentRun();
        run.setSessionId(dto.getSessionId());
        run.setType(dto.getType());
        run.setUserMessage(dto.getUserMessage());
        run.setAttachments(dto.getAttachments() != null && !dto.getAttachments().isEmpty()
                ? JsonUtils.toJson(dto.getAttachments()) : null);
        run.setStatus(RunEnum.STATUS_PENDING);
        run.setRunMode(dto.getRunMode() != null ? dto.getRunMode() : RunMode.WRITE.name());
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
        run.setAttachments(vo.getAttachments() != null && !vo.getAttachments().isEmpty()
                ? JsonUtils.toJson(vo.getAttachments()) : null);
        run.setStatus(vo.getStatus());
        run.setRunMode(vo.getRunMode());
        run.setLoopCapped(vo.getLoopCapped());
        run.setLoopCount(vo.getLoopCount());
        run.setLoopLimit(vo.getLoopLimit());
        run.setErrorMessage(vo.getErrorMessage());
        return run;
    }

    private static List<RunAttachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtils.parse(json, new com.fasterxml.jackson.core.type.TypeReference<List<RunAttachment>>() {
        });
    }
}
