package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLlmInteractionRecordServiceImpl implements AgentLlmInteractionRecordSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 主 Agent 问答轮次（与 kernel LlmInteractionType.CHAT_REPLY 的落库字符串一致；instance 不能依赖 kernel）。 */
    private static final String INTERACTION_TYPE_CHAT_REPLY = "CHAT_REPLY";

    private final AgentLlmInteractionRecordMapper mapper;

    @Override
    public Long createRecord(AgentLlmInteractionRecordVO vo) {
        AgentLlmInteractionRecord record = newRecord(vo);
        record.setStatus(Boolean.TRUE.equals(vo.getSuccess())
                ? AgentLlmInteractionRecord.STATUS_COMPLETED
                : AgentLlmInteractionRecord.STATUS_FAILED);
        mapper.insert(record);
        return record.getId();
    }

    @Override
    public Long createOrGetRunning(AgentLlmInteractionRecordVO vo) {
        if (vo.getRunId() == null || vo.getTurnNo() == null) {
            throw new IllegalArgumentException("runId and turnNo are required for a running turn record");
        }
        int attemptNo = vo.getAttemptNo() == null ? 0 : vo.getAttemptNo();
        vo.setAttemptNo(attemptNo);
        AgentLlmInteractionRecord existing = findChatTurn(vo.getRunId(), vo.getTurnNo(), attemptNo);
        if (existing != null) {
            // 同一 attempt 重试：重置为 RUNNING，并刷新 updated_at 作为心跳。
            mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                    .eq(AgentLlmInteractionRecord::getId, existing.getId())
                    .set(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_RUNNING)
                    .set(AgentLlmInteractionRecord::getErrorMessage, null)
                    .set(AgentLlmInteractionRecord::getModelName, vo.getModelName())
                    .set(AgentLlmInteractionRecord::getUpdatedAt, java.time.LocalDateTime.now()));
            return existing.getId();
        }
        AgentLlmInteractionRecord record = newRecord(vo);
        record.setStatus(AgentLlmInteractionRecord.STATUS_RUNNING);
        try {
            mapper.insert(record);
            return record.getId();
        } catch (DuplicateKeyException e) {
            // 并发建同 attempt：唯一索引只放行一个，输家回查赢家，绝不返回幽灵 id
            AgentLlmInteractionRecord winner = findChatTurn(vo.getRunId(), vo.getTurnNo(), attemptNo);
            if (winner == null) {
                throw e;
            }
            return winner.getId();
        }
    }

    @Override
    public void complete(Long id, AgentLlmInteractionRecordVO vo) {
        if (id == null) {
            return;
        }
        // 同一轮只有一条记录：最终完成必须能落库。
        // 即使清理任务曾把 RUNNING 误判为 FAILED，这里也要用真实结果覆盖，避免答案丢失。
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getId, id)
                .set(AgentLlmInteractionRecord::getRequestBody, vo.getRequestBody())
                .set(AgentLlmInteractionRecord::getResponseBody, vo.getResponseBody())
                .set(AgentLlmInteractionRecord::getHttpStatus, vo.getHttpStatus())
                .set(AgentLlmInteractionRecord::getDurationMs, vo.getDurationMs())
                .set(AgentLlmInteractionRecord::getErrorMessage, vo.getErrorMessage())
                .set(AgentLlmInteractionRecord::getSuccess, vo.getSuccess())
                .set(AgentLlmInteractionRecord::getReasoning, vo.getReasoning())
                .set(AgentLlmInteractionRecord::getReplyContent, vo.getReplyContent())
                .set(AgentLlmInteractionRecord::getUsage, vo.getUsage())
                .set(AgentLlmInteractionRecord::getPromptTokens, vo.getPromptTokens())
                .set(AgentLlmInteractionRecord::getCompletionTokens, vo.getCompletionTokens())
                .set(AgentLlmInteractionRecord::getTotalTokens, vo.getTotalTokens())
                .set(AgentLlmInteractionRecord::getCacheHitTokens, vo.getCacheHitTokens())
                .set(AgentLlmInteractionRecord::getCacheMissTokens, vo.getCacheMissTokens())
                .set(AgentLlmInteractionRecord::getStatus, Boolean.TRUE.equals(vo.getSuccess())
                        ? AgentLlmInteractionRecord.STATUS_COMPLETED
                        : AgentLlmInteractionRecord.STATUS_FAILED)
                .set(AgentLlmInteractionRecord::getModelName, vo.getModelName()));
        if (updated == 0) {
            log.warn("LLM interaction complete skipped: id={} no longer exists", id);
        }
    }

    @Override
    public Long findByRunAndTurn(Long runId, Integer turnNo) {
        if (runId == null || turnNo == null) {
            return null;
        }
        // 多 attempt 时：优先当前 RUNNING 的 attempt，否则取最新 attempt
        AgentLlmInteractionRecord record = mapper.selectOne(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId)
                .eq(AgentLlmInteractionRecord::getTurnNo, turnNo)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                .last("ORDER BY (status = 'RUNNING') DESC, attempt_no DESC LIMIT 1"));
        return record == null ? null : record.getId();
    }

    @Override
    public int failStaleRunning(int staleMinutes) {
        if (staleMinutes <= 0) {
            return 0;
        }
        // 只处理主 Agent 问答轮；心跳基准用 updated_at（每次重试都会刷新）
        return mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_RUNNING)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .lt(AgentLlmInteractionRecord::getUpdatedAt,
                        java.time.LocalDateTime.now().minusMinutes(staleMinutes))
                .set(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_FAILED)
                .set(AgentLlmInteractionRecord::getErrorMessage, "stale running interaction"));
    }

    private AgentLlmInteractionRecord findChatTurn(Long runId, Integer turnNo, int attemptNo) {
        return mapper.selectOne(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId)
                .eq(AgentLlmInteractionRecord::getTurnNo, turnNo)
                .eq(AgentLlmInteractionRecord::getAttemptNo, attemptNo)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                .last("LIMIT 1"));
    }

    private static AgentLlmInteractionRecord newRecord(AgentLlmInteractionRecordVO vo) {
        AgentLlmInteractionRecord record = new AgentLlmInteractionRecord();
        record.setRunId(vo.getRunId());
        record.setSessionId(vo.getSessionId());
        record.setTurnNo(vo.getTurnNo());
        record.setAttemptNo(vo.getAttemptNo() == null ? 0 : vo.getAttemptNo());
        record.setInteractionType(vo.getInteractionType());
        record.setModelName(vo.getModelName());
        record.setRequestBody(vo.getRequestBody());
        record.setResponseBody(vo.getResponseBody());
        record.setHttpStatus(vo.getHttpStatus());
        record.setDurationMs(vo.getDurationMs());
        record.setErrorMessage(vo.getErrorMessage());
        record.setSuccess(vo.getSuccess());
        record.setReasoning(vo.getReasoning());
        record.setReplyContent(vo.getReplyContent());
        record.setSubAgentRunId(vo.getSubAgentRunId());
        record.setUsage(vo.getUsage());
        record.setPromptTokens(vo.getPromptTokens());
        record.setCompletionTokens(vo.getCompletionTokens());
        record.setTotalTokens(vo.getTotalTokens());
        record.setCacheHitTokens(vo.getCacheHitTokens());
        record.setCacheMissTokens(vo.getCacheMissTokens());
        if (vo.getCreatedBy() != null) {
            record.setCreatedBy(vo.getCreatedBy());
        }
        return record;
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
    public List<AgentLlmInteractionRecordVO> listByRunIdAsc(Long runId) {
        if (runId == null) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                        .eq(AgentLlmInteractionRecord::getRunId, runId)
                        .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                        // 未完成的轮次不进入历史：预建 RUNNING 行不能当已完成的 assistant 正文
                        .eq(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_COMPLETED)
                        .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                        // 只取恢复所需的轻量列；request_body/response_body 可能非常大
                        .select(AgentLlmInteractionRecord::getId,
                                AgentLlmInteractionRecord::getRunId,
                                AgentLlmInteractionRecord::getSessionId,
                                AgentLlmInteractionRecord::getInteractionType,
                                AgentLlmInteractionRecord::getReplyContent,
                                // 恢复思考模型时必须回放原始 reasoning，否则 DeepSeek 会 400
                                AgentLlmInteractionRecord::getReasoning,
                                AgentLlmInteractionRecord::getCreatedAt)
                        .orderByAsc(AgentLlmInteractionRecord::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public long countByRunId(Long runId) {
        return mapper.selectCount(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId));
    }

    @Override
    public AgentLlmInteractionRecordVO getById(Long id) {
        AgentLlmInteractionRecord record = mapper.selectById(id);
        return record == null ? null : toVO(record);
    }

    @Override
    public boolean updateLatestBlankReply(Long runId, String reply) {
        if (runId == null || reply == null || reply.isBlank()) {
            return false;
        }
        AgentLlmInteractionRecord latest = mapper.selectOne(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .eq(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_COMPLETED)
                .orderByDesc(AgentLlmInteractionRecord::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return false;
        }
        String existing = latest.getReplyContent();
        if (existing != null && !existing.isBlank()) {
            return false;
        }
        // 只更新 reply_content，且二次校验仍为空：不覆盖并发写入的正文，也不重写 request/response 大字段
        return mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getId, latest.getId())
                .and(w -> w.isNull(AgentLlmInteractionRecord::getReplyContent)
                        .or().eq(AgentLlmInteractionRecord::getReplyContent, ""))
                .set(AgentLlmInteractionRecord::getReplyContent, reply)) > 0;
    }

    @Override
    public SessionUsageVO usageSummary(Long sessionId) {
        SessionUsageVO vo = mapper.sumUsageBySession(sessionId);
        if (vo == null) {
            vo = new SessionUsageVO();
            vo.setSessionId(sessionId);
            vo.setRunCount(0L);
            vo.setInteractionCount(0L);
            vo.setPromptTokens(0L);
            vo.setCompletionTokens(0L);
            vo.setTotalTokens(0L);
            vo.setCacheHitTokens(0L);
            vo.setCacheMissTokens(0L);
        }
        vo.setLatestPromptTokens(mapper.selectLatestPromptTokens(sessionId, INTERACTION_TYPE_CHAT_REPLY));
        Long total = vo.getTotalTokens() != null ? vo.getTotalTokens() : 0L;
        Long hit = vo.getCacheHitTokens() != null ? vo.getCacheHitTokens() : 0L;
        Long miss = vo.getCacheMissTokens() != null ? vo.getCacheMissTokens() : 0L;
        long denom = hit + miss;
        if (denom > 0) {
            vo.setCacheHitRate(Math.round(hit * 10000.0 / denom) / 100.0);
        }
        return vo;
    }

    private AgentLlmInteractionRecordVO toVO(AgentLlmInteractionRecord record) {
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setId(record.getId());
        vo.setRunId(record.getRunId());
        vo.setSessionId(record.getSessionId());
        vo.setTurnNo(record.getTurnNo());
        vo.setAttemptNo(record.getAttemptNo());
        vo.setStatus(record.getStatus());
        vo.setInteractionType(record.getInteractionType());
        vo.setModelName(record.getModelName());
        vo.setRequestBody(record.getRequestBody());
        vo.setResponseBody(record.getResponseBody());
        vo.setHttpStatus(record.getHttpStatus());
        vo.setDurationMs(record.getDurationMs());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setSuccess(record.getSuccess());
        vo.setReasoning(record.getReasoning());
        vo.setReplyContent(record.getReplyContent());
        vo.setSubAgentRunId(record.getSubAgentRunId());
        vo.setUsage(record.getUsage());
        vo.setPromptTokens(record.getPromptTokens());
        vo.setCompletionTokens(record.getCompletionTokens());
        vo.setTotalTokens(record.getTotalTokens());
        vo.setCacheHitTokens(record.getCacheHitTokens());
        vo.setCacheMissTokens(record.getCacheMissTokens());
        if (record.getCreatedAt() != null) {
            vo.setCreatedAt(record.getCreatedAt().format(DTF));
        }
        return vo;
    }
}
