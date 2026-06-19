package com.buukle.agent.instance.service.impl;

import com.buukle.agent.instance.domain.vo.RunActivityVO;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.service.RunActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RunActivityServiceImpl implements RunActivityService {

    private final AgentToolCallRecordMapper toolCallMapper;

    @Override
    public Map<String, Object> listByRun(Long runId, Long sessionId, int offset, int limit) {
        int total = toolCallMapper.countActivitiesByRun(runId, sessionId);
        List<RunActivityVO> records = toolCallMapper.selectActivitiesByRun(runId, sessionId, limit, offset);
        return Map.of("total", total, "records", records);
    }
}
