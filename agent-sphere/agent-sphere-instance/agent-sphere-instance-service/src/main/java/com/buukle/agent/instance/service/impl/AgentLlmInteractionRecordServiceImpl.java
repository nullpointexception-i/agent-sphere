package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentLlmInteractionRecordServiceImpl implements AgentLlmInteractionRecordSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentLlmInteractionRecordMapper mapper;

    @Override
    public void createRecord(AgentLlmInteractionRecordVO vo) {
        AgentLlmInteractionRecord record = new AgentLlmInteractionRecord();
        record.setRunId(vo.getRunId());
        record.setSessionId(vo.getSessionId());
        record.setInteractionType(vo.getInteractionType());
        record.setModelName(vo.getModelName());
        record.setRequestBody(vo.getRequestBody());
        record.setResponseBody(vo.getResponseBody());
        record.setHttpStatus(vo.getHttpStatus());
        record.setDurationMs(vo.getDurationMs());
        record.setErrorMessage(vo.getErrorMessage());
        record.setSuccess(vo.getSuccess());
        if (vo.getCreatedBy() != null) {
            record.setCreatedBy(vo.getCreatedBy());
        }
        mapper.insert(record);
    }

    @Override
    public List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit) {
        LambdaQueryWrapper<AgentLlmInteractionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentLlmInteractionRecord::getRunId, runId)
                .orderByDesc(AgentLlmInteractionRecord::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return mapper.selectList(wrapper).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public AgentLlmInteractionRecordVO getById(Long id) {
        AgentLlmInteractionRecord record = mapper.selectById(id);
        return record == null ? null : toVO(record);
    }

    private AgentLlmInteractionRecordVO toVO(AgentLlmInteractionRecord record) {
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setId(record.getId());
        vo.setRunId(record.getRunId());
        vo.setSessionId(record.getSessionId());
        vo.setInteractionType(record.getInteractionType());
        vo.setModelName(record.getModelName());
        vo.setRequestBody(record.getRequestBody());
        vo.setResponseBody(record.getResponseBody());
        vo.setHttpStatus(record.getHttpStatus());
        vo.setDurationMs(record.getDurationMs());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setSuccess(record.getSuccess());
        if (record.getCreatedAt() != null) {
            vo.setCreatedAt(record.getCreatedAt().format(DTF));
        }
        return vo;
    }
}
