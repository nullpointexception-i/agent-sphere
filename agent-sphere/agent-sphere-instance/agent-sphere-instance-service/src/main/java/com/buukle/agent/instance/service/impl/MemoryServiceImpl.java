package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.instance.domain.AgentMemory;
import com.buukle.agent.instance.dtvo.vo.MemoryVO;
import com.buukle.agent.instance.repository.MemoryMapper;
import com.buukle.agent.instance.service.MemoryService;
import com.buukle.agent.instance.service.converter.MemoryConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryServiceImpl extends ServiceImpl<MemoryMapper, AgentMemory> implements MemoryService {
    private final MemoryConverter memoryConverter;

    @Override
    public List<MemoryVO> getMemoryBySession(Long sessionId) {
        List<AgentMemory> memories = lambdaQuery().eq(AgentMemory::getSessionId, sessionId).list();
        return memories.stream().map(memoryConverter::toVO).toList();
    }

    @Override
    public List<MemoryVO> getMemoryByRun(Long runId) {
        List<AgentMemory> memories = lambdaQuery().eq(AgentMemory::getRunId, runId).list();
        return memories.stream().map(memoryConverter::toVO).toList();
    }

    @Override
    public List<MemoryVO> getMemoryByTask(Long taskId) {
        List<AgentMemory> memories = lambdaQuery().eq(AgentMemory::getTaskId, taskId).list();
        return memories.stream().map(memoryConverter::toVO).toList();
    }
}
