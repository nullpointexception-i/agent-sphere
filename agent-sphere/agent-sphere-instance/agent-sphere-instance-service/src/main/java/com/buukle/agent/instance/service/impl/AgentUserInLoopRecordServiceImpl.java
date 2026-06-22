package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentUserInLoopRecord;
import com.buukle.agent.instance.dtvo.vo.AgentUserInLoopRecordVO;
import com.buukle.agent.instance.repository.AgentUserInLoopRecordMapper;
import com.buukle.agent.instance.spi.AgentUserInLoopRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AgentUserInLoopRecordServiceImpl implements AgentUserInLoopRecordSpi {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AgentUserInLoopRecordMapper mapper;

    private static AgentUserInLoopRecordVO toVO(AgentUserInLoopRecord r) {
        AgentUserInLoopRecordVO vo = new AgentUserInLoopRecordVO();
        vo.setId(r.getId());
        vo.setStepId(r.getStepId());
        vo.setRunId(r.getRunId());
        vo.setSessionId(r.getSessionId());
        vo.setInteractionType(r.getInteractionType());
        vo.setStatus(r.getStatus());
        vo.setPrompt(r.getPrompt());
        vo.setResponse(r.getResponse());
        vo.setRespondedBy(r.getRespondedBy());
        vo.setResult(r.getResult());
        vo.setComment(r.getComment());
        if (r.getCreatedAt() != null) vo.setCreatedAt(r.getCreatedAt().format(DATE_FMT));
        if (r.getUpdatedAt() != null) vo.setUpdatedAt(r.getUpdatedAt().format(DATE_FMT));
        return vo;
    }

    @Override
    public AgentUserInLoopRecordVO createRecord(Long stepId, Long runId, Long sessionId,
                                                String interactionType, String prompt) {
        AgentUserInLoopRecord record = new AgentUserInLoopRecord();
        record.setStepId(stepId);
        record.setRunId(runId);
        record.setSessionId(sessionId);
        record.setInteractionType(interactionType);
        record.setStatus("WAITING");
        record.setPrompt(prompt);
        mapper.insert(record);
        return toVO(record);
    }

    @Override
    public AgentUserInLoopRecordVO getRecord(Long id) {
        AgentUserInLoopRecord r = mapper.selectById(id);
        return r != null ? toVO(r) : null;
    }

    @Override
    public AgentUserInLoopRecordVO getByStepId(Long stepId) {
        AgentUserInLoopRecord r = mapper.selectOne(
                new LambdaQueryWrapper<AgentUserInLoopRecord>()
                        .eq(AgentUserInLoopRecord::getStepId, stepId)
                        .orderByDesc(AgentUserInLoopRecord::getId)
                        .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    @Override
    public void respondRecord(Long id, String response, String respondedBy, String result, String comment) {
        AgentUserInLoopRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus("RESPONDED");
        record.setResponse(response);
        record.setRespondedBy(respondedBy);
        record.setResult(result);
        record.setComment(comment);
        mapper.updateById(record);
    }
}
