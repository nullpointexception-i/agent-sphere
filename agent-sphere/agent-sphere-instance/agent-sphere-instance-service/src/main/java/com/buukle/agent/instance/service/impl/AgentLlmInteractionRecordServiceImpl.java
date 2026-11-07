package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.common.util.TimeUtils;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLlmInteractionRecordServiceImpl implements AgentLlmInteractionRecordSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Main Agent question/answer turn (matches the persisted value of kernel LlmInteractionType.CHAT_REPLY; instance cannot depend on kernel). */
    private static final String INTERACTION_TYPE_CHAT_REPLY = "CHAT_REPLY";
    /** Number of concurrent retry_no allocation attempts (re-allocate when the unique index conflicts). */
    private static final int MAX_RETRY_NO_ALLOCATIONS = 5;

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
        // 0 means a non-retry turn; 1..N are backoff ladder positions used only for display/troubleshooting
        if (vo.getRetryAttempt() != null && vo.getRetryAttempt() <= 0) {
            vo.setRetryAttempt(null);
        }

        // Every new call gets its own row (V117): a completed successful result must not be
        // overwritten by a later retry.
        // RUNNING rows are not reused: persistence of the previous turn and row creation for the next
        // may run concurrently, and reuse would let the two calls overwrite each other; RUNNING rows
        // left behind by a crashed process are handled by failStaleRunning (the Timeline takes the
        // RUNNING row with the highest retry_no).
        for (int i = 0; i < MAX_RETRY_NO_ALLOCATIONS; i++) {
            vo.setRetryNo(nextRetryNo(vo.getRunId(), vo.getTurnNo(), attemptNo));
            AgentLlmInteractionRecord record = newRecord(vo);
            record.setStatus(AgentLlmInteractionRecord.STATUS_RUNNING);
            try {
                mapper.insert(record);
                return record.getId();
            } catch (DuplicateKeyException e) {
                log.debug("Retry record allocation raced: run={}, turn={}, attempt={}, retryNo={}",
                        vo.getRunId(), vo.getTurnNo(), attemptNo, vo.getRetryNo());
            }
        }
        throw new IllegalStateException("cannot allocate LLM interaction record for run="
                + vo.getRunId() + ", turn=" + vo.getTurnNo() + ", attempt=" + attemptNo);
    }

    @Override
    public void complete(Long id, AgentLlmInteractionRecordVO vo) {
        if (id == null) {
            return;
        }
        AgentLlmInteractionRecord existing = mapper.selectById(id);
        if (existing == null) {
            log.warn("LLM interaction complete skipped: id={} no longer exists", id);
            return;
        }
        // A completed row is an immutable historical fact: a late failing retry may not downgrade it,
        // let alone clear an already successful body.
        if (AgentLlmInteractionRecord.STATUS_COMPLETED.equals(existing.getStatus())
                && !Boolean.TRUE.equals(vo.getSuccess())) {
            log.warn("LLM interaction {} already completed; ignoring late failure", id);
            return;
        }
        // There is exactly one record per turn: final completion must be persisted.
        // Even if a cleanup task once misjudged RUNNING as FAILED, the real result must overwrite it
        // here so the answer is not lost.
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getId, id)
                .set(AgentLlmInteractionRecord::getRequestBody, vo.getRequestBody())
                .set(AgentLlmInteractionRecord::getResponseBody, vo.getResponseBody())
                .set(AgentLlmInteractionRecord::getHttpStatus, vo.getHttpStatus())
                .set(AgentLlmInteractionRecord::getFinishReason, vo.getFinishReason())
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
                .set(AgentLlmInteractionRecord::getStreamAttempts, vo.getStreamAttempts())
                .set(AgentLlmInteractionRecord::getStreamRetryCount, vo.getStreamRetryCount())
                .set(AgentLlmInteractionRecord::getStreamError, vo.getStreamError())
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
        // With multiple attempts: prefer the currently RUNNING attempt, otherwise take the latest attempt
        AgentLlmInteractionRecord record = mapper.selectOne(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId)
                .eq(AgentLlmInteractionRecord::getTurnNo, turnNo)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                .last("ORDER BY (status = 'RUNNING') DESC, retry_no DESC, attempt_no DESC LIMIT 1"));
        return record == null ? null : record.getId();
    }

    @Override
    public int failStaleRunning(int staleMinutes) {
        if (staleMinutes <= 0) {
            return 0;
        }
        // Only main Agent question/answer turns are handled; the heartbeat baseline uses updated_at (refreshed on every retry)
        return mapper.update(null, new LambdaUpdateWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_RUNNING)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .lt(AgentLlmInteractionRecord::getUpdatedAt,
                        java.time.LocalDateTime.now().minusMinutes(staleMinutes))
                .set(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_FAILED)
                .set(AgentLlmInteractionRecord::getErrorMessage, "stale running interaction"));
    }

    /** Largest existing retry_no under this (run, turn, attempt) + 1; starts at 0 when there is no record. */
    private int nextRetryNo(Long runId, Integer turnNo, int attemptNo) {
        AgentLlmInteractionRecord latest = mapper.selectOne(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .select(AgentLlmInteractionRecord::getRetryNo)
                .eq(AgentLlmInteractionRecord::getRunId, runId)
                .eq(AgentLlmInteractionRecord::getTurnNo, turnNo)
                .eq(AgentLlmInteractionRecord::getAttemptNo, attemptNo)
                .eq(AgentLlmInteractionRecord::getInteractionType, INTERACTION_TYPE_CHAT_REPLY)
                .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                .orderByDesc(AgentLlmInteractionRecord::getRetryNo)
                .last("LIMIT 1"));
        if (latest == null || latest.getRetryNo() == null) {
            return 0;
        }
        return latest.getRetryNo() + 1;
    }

    private static AgentLlmInteractionRecord newRecord(AgentLlmInteractionRecordVO vo) {
        AgentLlmInteractionRecord record = new AgentLlmInteractionRecord();
        record.setRunId(vo.getRunId());
        record.setSessionId(vo.getSessionId());
        record.setSuperAgentId(vo.getSuperAgentId());
        record.setSessionType(vo.getSessionType());
        record.setThreadId(vo.getThreadId());
        record.setTurnNo(vo.getTurnNo());
        record.setAttemptNo(vo.getAttemptNo() == null ? 0 : vo.getAttemptNo());
        record.setRetryNo(vo.getRetryNo() == null ? 0 : vo.getRetryNo());
        record.setRetryAttempt(vo.getRetryAttempt());
        record.setInteractionType(vo.getInteractionType());
        record.setModelName(vo.getModelName());
        record.setRequestBody(vo.getRequestBody());
        record.setResponseBody(vo.getResponseBody());
        record.setHttpStatus(vo.getHttpStatus());
        record.setFinishReason(vo.getFinishReason());
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
        record.setStreamAttempts(vo.getStreamAttempts());
        record.setStreamRetryCount(vo.getStreamRetryCount());
        record.setStreamError(vo.getStreamError());
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
                        // Unfinished turns do not enter history: a pre-created RUNNING row must not be
                        // treated as a completed assistant body
                        .eq(AgentLlmInteractionRecord::getStatus, AgentLlmInteractionRecord.STATUS_COMPLETED)
                        .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                        // Fetch only the lightweight columns needed for recovery; request_body/response_body can be very large
                        .select(AgentLlmInteractionRecord::getId,
                                AgentLlmInteractionRecord::getRunId,
                                AgentLlmInteractionRecord::getSessionId,
                                AgentLlmInteractionRecord::getInteractionType,
                                AgentLlmInteractionRecord::getReplyContent,
                                // When restoring a thinking model the original reasoning must be replayed, otherwise DeepSeek returns 400
                                AgentLlmInteractionRecord::getReasoning,
                                // Turn number: matches the turn_no of tool records exactly (see HistoryLoader)
                                AgentLlmInteractionRecord::getTurnNo,
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
        // Update reply_content only, and re-check that it is still empty: do not overwrite concurrently
        // written content, and do not rewrite the large request/response fields
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

    @Override
    public List<SuperAgentUsageVO> usageBySuperAgent(Long superAgentId, int days) {
        int window = Math.max(1, days);
        LocalDateTime since = LocalDate.now().minusDays(window - 1L).atStartOfDay();
        return mapper.sumUsageBySuperAgent(superAgentId, since);
    }

    private AgentLlmInteractionRecordVO toVO(AgentLlmInteractionRecord record) {
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setId(record.getId());
        vo.setRunId(record.getRunId());
        vo.setSessionId(record.getSessionId());
        vo.setSuperAgentId(record.getSuperAgentId());
        vo.setSessionType(record.getSessionType());
        vo.setThreadId(record.getThreadId());
        vo.setTurnNo(record.getTurnNo());
        vo.setAttemptNo(record.getAttemptNo());
        vo.setRetryNo(record.getRetryNo());
        vo.setRetryAttempt(record.getRetryAttempt());
        vo.setStatus(record.getStatus());
        vo.setInteractionType(record.getInteractionType());
        vo.setModelName(record.getModelName());
        vo.setRequestBody(record.getRequestBody());
        vo.setResponseBody(record.getResponseBody());
        vo.setHttpStatus(record.getHttpStatus());
        vo.setFinishReason(record.getFinishReason());
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
            // A second-level string is not precise enough for pairing (the next turn may start within
            // the same second), so the millisecond value is stored separately
            vo.setCreatedAtMillis(TimeUtils.toEpochMillis(record.getCreatedAt()));
        }
        return vo;
    }
}
