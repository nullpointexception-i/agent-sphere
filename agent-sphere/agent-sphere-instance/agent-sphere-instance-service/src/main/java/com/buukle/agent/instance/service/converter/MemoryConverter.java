package com.buukle.agent.instance.service.converter;

import com.buukle.agent.instance.domain.AgentMemory;
import com.buukle.agent.instance.dtvo.vo.MemoryVO;
import org.springframework.stereotype.Component;

@Component
public class MemoryConverter {
    public MemoryVO toVO(AgentMemory memory) {
        if (memory == null) return null;
        MemoryVO vo = new MemoryVO();
        vo.setId(memory.getId());
        vo.setType(memory.getType());
        vo.setSessionId(memory.getSessionId());
        vo.setRunId(memory.getRunId());
        vo.setTaskId(memory.getTaskId());
        vo.setSummary(memory.getSummary());
        vo.setContent(memory.getContent());
        vo.setStatus(memory.getStatus());
        vo.setCreatedAt(memory.getCreatedAt() != null ? memory.getCreatedAt().toString() : null);
        return vo;
    }
}
