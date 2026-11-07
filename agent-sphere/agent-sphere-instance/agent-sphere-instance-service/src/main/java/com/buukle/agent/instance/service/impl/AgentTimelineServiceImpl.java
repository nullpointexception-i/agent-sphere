package com.buukle.agent.instance.service.impl;



import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.AgentSubAgentRun;
import com.buukle.agent.instance.domain.AgentTimeline;
import com.buukle.agent.instance.domain.AgentToolApproval;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.dto.TimelineRefQuery;
import com.buukle.agent.instance.dtvo.enums.TimelineContentKey;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.AgentTimelineVO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO;
import com.buukle.agent.instance.domain.AgentCompactRecord;
import com.buukle.agent.instance.repository.AgentCompactRecordMapper;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.repository.AgentSubAgentRunMapper;
import com.buukle.agent.instance.repository.AgentTimelineMapper;
import com.buukle.agent.instance.repository.AgentToolApprovalMapper;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.service.MessageFeedbackService;
import com.buukle.agent.instance.service.util.ScreenshotRefParser;
import com.buukle.agent.instance.service.util.ToolMetaResolver;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Unified flattened Timeline display index implementation:
 * - session-level seq: Redis AtomicLong INCR (monotonic across replicas, never reset);
 * - row recording/state closing;
 * - keyset paging (beforeSeq scrolls back / afterSeq catches up after disconnects) + batch body resolution by ref (eager, not lazy).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTimelineServiceImpl extends ServiceImpl<AgentTimelineMapper, AgentTimeline>
        implements AgentTimelineSpi {

    /** Per-field body load cap (truncated when too long to avoid overloading a page; not persisted). */
    private static final int CONTENT_CAP = 20000;
    /** Snapshot title cap. */
    private static final int TITLE_CAP = 300;
    /** Default value of the common status column (the ACTIVE convention). */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final RedissonClient redissonClient;
    private final RunMapper runMapper;
    private final AgentToolCallRecordMapper toolCallMapper;
    private final AgentPendingClarificationMapper clarificationMapper;
    private final AgentSubAgentRunMapper subAgentRunMapper;
    private final AgentLlmInteractionRecordMapper interactionMapper;
    private final AgentCompactRecordMapper compactRecordMapper;
    private final AgentToolApprovalMapper toolApprovalMapper;
    private final MessageFeedbackService messageFeedbackService;
    private final CapabilityMcpSpi mcpSpi;

    @Override
    public long nextSeq(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId required");
        }
        return redissonClient.getAtomicLong(DistributedRuntimeConstants.timelineSeqKey(sessionId)).incrementAndGet();
    }

    @Override
    public long record(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                       Long refRunId, Long refInteractionId, Long refToolCallId,
                       Long refSubAgentRunId, Long refClarificationId) {
        long seq = nextSeq(sessionId);
        AgentTimeline row = new AgentTimeline();
        row.setSessionId(sessionId);
        row.setRunId(runId);
        row.setSeq(seq);
        row.setKind(kind);
        row.setSubtype(subtype);
        row.setState(state != null && !state.isBlank() ? state : TimelineState.RUNNING.getCode());
        warnIfInvalidState(kind, row.getState());
        row.setTitle(cap(title, TITLE_CAP));
        row.setRefRunId(refRunId);
        row.setRefInteractionId(refInteractionId);
        row.setRefToolCallId(refToolCallId);
        row.setRefSubAgentRunId(refSubAgentRunId);
        row.setRefClarificationId(refClarificationId);
        row.setStatus(STATUS_ACTIVE);
        try {
            save(row);
        } catch (Exception e) {
            log.warn("Timeline record failed (session={} seq={} kind={}): {}", sessionId, seq, kind, e.getMessage());
        }
        return seq;
    }

    @Override
    public long recordApproval(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                               Long refRunId, Long refApprovalId) {
        long seq = nextSeq(sessionId);
        AgentTimeline row = new AgentTimeline();
        row.setSessionId(sessionId);
        row.setRunId(runId);
        row.setSeq(seq);
        row.setKind(kind);
        row.setSubtype(subtype);
        row.setState(state != null && !state.isBlank() ? state : TimelineState.RUNNING.getCode());
        warnIfInvalidState(kind, row.getState());
        row.setTitle(cap(title, TITLE_CAP));
        row.setRefRunId(refRunId);
        row.setRefApprovalId(refApprovalId);
        row.setStatus(STATUS_ACTIVE);
        try {
            save(row);
        } catch (Exception e) {
            log.warn("Timeline approval record failed (session={} seq={}): {}", sessionId, seq, e.getMessage());
        }
        return seq;
    }

    @Override
    public void update(Long sessionId, Long seq, String state, String subtype, String title) {
        if (sessionId == null || seq == null) {
            return;
        }
        AgentTimeline row = new AgentTimeline();
        if (state != null && !state.isBlank()) {
            row.setState(state);
        }
        if (subtype != null && !subtype.isBlank()) {
            row.setSubtype(subtype);
        }
        if (title != null && !title.isBlank()) {
            row.setTitle(cap(title, TITLE_CAP));
        }
        lambdaUpdate()
                .eq(AgentTimeline::getSessionId, sessionId)
                .eq(AgentTimeline::getSeq, seq)
                .update(row);
    }

    @Override
    public void updateRefInteractionId(Long sessionId, Long seq, Long interactionId) {
        if (sessionId == null || seq == null || interactionId == null) {
            return;
        }
        AgentTimeline patch = new AgentTimeline();
        patch.setRefInteractionId(interactionId);
        lambdaUpdate()
                .eq(AgentTimeline::getSessionId, sessionId)
                .eq(AgentTimeline::getSeq, seq)
                .update(patch);
    }

    @Override
    public void updateRefCompactId(Long sessionId, Long seq, Long compactRecordId) {
        if (sessionId == null || seq == null || compactRecordId == null) {
            return;
        }
        AgentTimeline patch = new AgentTimeline();
        patch.setRefCompactId(compactRecordId);
        lambdaUpdate()
                .eq(AgentTimeline::getSessionId, sessionId)
                .eq(AgentTimeline::getSeq, seq)
                .update(patch);
    }

    @Override
    public void updateBySubAgentRun(Long subAgentRunId, String state) {
        if (subAgentRunId == null || state == null || state.isBlank()) {
            return;
        }
        AgentTimeline patch = new AgentTimeline();
        patch.setState(state);
        patch.setSubtype(state);
        lambdaUpdate().eq(AgentTimeline::getRefSubAgentRunId, subAgentRunId).update(patch);
    }

    @Override
    public long recordAssistantByInteraction(Long sessionId, Long runId, Long interactionId) {
        if (sessionId == null || runId == null || interactionId == null) {
            return -1L;
        }
        AgentTimeline existing = findAssistantByInteraction(runId, interactionId);
        if (existing != null) {
            return existing.getSeq();
        }
        // Only one RUNNING assistant row is allowed per run at a time: close the old row left over from a restart first
        closeRunningAssistant(sessionId, runId, TimelineState.CANCELLED.getCode());

        long seq = nextSeq(sessionId);
        AgentTimeline row = new AgentTimeline();
        row.setSessionId(sessionId);
        row.setRunId(runId);
        row.setSeq(seq);
        row.setKind(TimelineKind.ASSISTANT.getCode());
        row.setState(TimelineState.RUNNING.getCode());
        row.setRefRunId(runId);
        row.setRefInteractionId(interactionId);
        row.setStatus(STATUS_ACTIVE);
        try {
            save(row);
            return seq;
        } catch (DuplicateKeyException e) {
            // Concurrent creation of the same turn across replicas: the unique index only lets one through; look up the winner
            AgentTimeline winner = findAssistantByInteraction(runId, interactionId);
            if (winner != null) {
                return winner.getSeq();
            }
            log.warn("Assistant row insert conflicted without winner: run={} interaction={}", runId, interactionId);
            return -1L;
        }
    }

    @Override
    public int closeRunningAssistant(Long sessionId, Long runId, String state) {
        if (runId == null || state == null || state.isBlank()) {
            return 0;
        }
        AgentTimeline patch = new AgentTimeline();
        patch.setState(state);
        patch.setSubtype(state);
        return getBaseMapper().update(patch, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentTimeline>()
                .eq(AgentTimeline::getRunId, runId)
                .eq(AgentTimeline::getKind, TimelineKind.ASSISTANT.getCode())
                .eq(AgentTimeline::getState, TimelineState.RUNNING.getCode()));
    }

    @Override
    public Long findSeqByInteraction(Long runId, Long interactionId) {
        AgentTimeline row = findAssistantByInteraction(runId, interactionId);
        return row == null ? null : row.getSeq();
    }

    private AgentTimeline findAssistantByInteraction(Long runId, Long interactionId) {
        if (runId == null || interactionId == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<AgentTimeline>()
                .eq(AgentTimeline::getRunId, runId)
                .eq(AgentTimeline::getKind, TimelineKind.ASSISTANT.getCode())
                .eq(AgentTimeline::getRefInteractionId, interactionId)
                .orderByDesc(AgentTimeline::getSeq)
                .last("LIMIT 1"), false);
    }

    @Override
    public Long findLatestSeq(TimelineRefQuery query) {
        AgentTimeline row = findLatest(query);
        return row == null ? null : row.getSeq();
    }

    @Override
    public String findLatestState(TimelineRefQuery query) {
        AgentTimeline row = findLatest(query);
        return row == null ? null : row.getState();
    }

    /**
     * Fetch the most recent row by run + kind + ref.
     *
     * <p>Ref fields are ANDed one by one for each non-null value passed by the caller; run_status /
     * assistant rows have no ref and are matched by run + kind only.
     * MyBatis-Plus @TableLogic automatically appends delete_flag = 0, so historically deleted rows
     * never participate in reuse.
     */
    private AgentTimeline findLatest(TimelineRefQuery query) {
        if (query == null || query.getRunId() == null || query.getKind() == null) {
            return null;
        }
        LambdaQueryWrapper<AgentTimeline> wrapper = new LambdaQueryWrapper<AgentTimeline>()
                .eq(AgentTimeline::getRunId, query.getRunId())
                .eq(AgentTimeline::getKind, query.getKind())
                .eq(query.getRefClarificationId() != null,
                        AgentTimeline::getRefClarificationId, query.getRefClarificationId())
                .eq(query.getRefToolCallId() != null,
                        AgentTimeline::getRefToolCallId, query.getRefToolCallId())
                .eq(query.getRefApprovalId() != null,
                        AgentTimeline::getRefApprovalId, query.getRefApprovalId())
                .eq(query.getRefCompactId() != null,
                        AgentTimeline::getRefCompactId, query.getRefCompactId())
                .orderByDesc(AgentTimeline::getSeq)
                .last("LIMIT 1");
        return getOne(wrapper, false);
    }

    @Override
    public SessionTimelinePageVO page(Long sessionId, Long beforeSeq, Long afterSeq, int limit) {
        int size = limit > 0 ? Math.min(limit, 50) : 5;
        List<AgentTimeline> rows;
        boolean hasMore = false;
        if (beforeSeq != null) {
            // Scroll back: take the newest size rows with seq < beforeSeq (fetch one extra to detect hasMore), then restore ascending order
            rows = lambdaQuery()
                    .eq(AgentTimeline::getSessionId, sessionId)
                    .lt(AgentTimeline::getSeq, beforeSeq)
                    .orderByDesc(AgentTimeline::getSeq)
                    .last("LIMIT " + (size + 1))
                    .list();
            hasMore = rows.size() > size;
            if (hasMore) rows.remove(rows.size() - 1);
            Collections.reverse(rows);
        } else if (afterSeq != null) {
            // Reconnect catch-up: take the earliest size rows with seq > afterSeq
            rows = lambdaQuery()
                    .eq(AgentTimeline::getSessionId, sessionId)
                    .gt(AgentTimeline::getSeq, afterSeq)
                    .orderByAsc(AgentTimeline::getSeq)
                    .last("LIMIT " + (size + 1))
                    .list();
            hasMore = rows.size() > size;
            if (hasMore) rows.remove(rows.size() - 1);
        } else {
            // First page: the latest size rows, fetching one extra to detect hasMore
            rows = lambdaQuery()
                    .eq(AgentTimeline::getSessionId, sessionId)
                    .orderByDesc(AgentTimeline::getSeq)
                    .last("LIMIT " + (size + 1))
                    .list();
            hasMore = rows.size() > size;
            if (hasMore) rows.remove(rows.size() - 1);
            Collections.reverse(rows);
        }

        SessionTimelinePageVO pageVO = new SessionTimelinePageVO();
        if (rows.isEmpty()) {
            pageVO.setRows(new ArrayList<>());
            pageVO.setHasMore(false);
            return pageVO;
        }
        Map<Long, AgentRun> runs = loadRuns(rows);
        // Batch-prefetch run-level usage aggregation (run_status rows show the per-run SUM within the session; avoids one query per row)
        List<Long> distinctRunIds = rows.stream()
                .map(r -> r.getRefRunId() != null ? r.getRefRunId() : r.getRunId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, com.buukle.agent.instance.domain.vo.RunUsageVO> usageByRun =
                distinctRunIds.isEmpty() ? Map.of()
                        : interactionMapper.sumUsageByRunIds(distinctRunIds).stream()
                        .collect(Collectors.toMap(com.buukle.agent.instance.domain.vo.RunUsageVO::getRunId, u -> u));
        pageVO.setRows(rows.stream().map(r -> toVO(r, runs, usageByRun)).collect(Collectors.toList()));
        pageVO.setHasMore(hasMore);
        pageVO.setOldestSeq(rows.get(0).getSeq());
        pageVO.setNewestSeq(rows.get(rows.size() - 1).getSeq());
        return pageVO;
    }

    private AgentTimelineVO toVO(AgentTimeline row, Map<Long, AgentRun> runs,
                                 Map<Long, com.buukle.agent.instance.domain.vo.RunUsageVO> usageByRun) {
        AgentTimelineVO vo = new AgentTimelineVO();
        vo.setSeq(row.getSeq());
        vo.setRunId(row.getRunId());
        vo.setKind(row.getKind());
        vo.setSubtype(row.getSubtype());
        vo.setState(row.getState());
        vo.setGroupId(row.getGroupId());
        vo.setTitle(row.getTitle());
        vo.setRefRunId(row.getRefRunId());
        vo.setRefInteractionId(row.getRefInteractionId());
        vo.setRefToolCallId(row.getRefToolCallId());
        vo.setRefSubAgentRunId(row.getRefSubAgentRunId());
        vo.setRefClarificationId(row.getRefClarificationId());
        vo.setRefCompactId(row.getRefCompactId());
        vo.setContent(resolveContent(row, runs, usageByRun));
        return vo;
    }

    /** Batch-resolve bodies from source tables by ref (resolved eagerly on load, not lazily). */
    private Map<String, Object> resolveContent(AgentTimeline row, Map<Long, AgentRun> runs,
                                               Map<Long, com.buukle.agent.instance.domain.vo.RunUsageVO> usageByRun) {
        String kind = row.getKind();
        Map<String, Object> content = new HashMap<>();
        TimelineKind kindEnum = TimelineKind.from(kind);
        try {
            if (kindEnum == null) {
                return content;
            }
            switch (kindEnum) {
                case USER -> {
                    AgentRun r = runs.get(row.getRefRunId() != null ? row.getRefRunId() : row.getRunId());
                    if (r != null) {
                        content.put(TimelineContentKey.TEXT.getCode(), cap(r.getUserMessage(), CONTENT_CAP));
                        if (r.getAttachments() != null && !r.getAttachments().isBlank()) {
                            content.put(TimelineContentKey.IMAGES.getCode(), parseAttachments(r.getAttachments()));
                        }
                    }
                }
                case ASSISTANT -> {
                    // Per-turn LLM call row: the body directly references that turn's interaction
                    // reasoning/reply_content (response_body is for auditing only)
                    if (row.getRefInteractionId() != null) {
                        AgentLlmInteractionRecord rec = interactionMapper.selectById(row.getRefInteractionId());
                        if (rec != null) {
                            // Display state follows the execution record (the timeline snapshot is only a fallback)
                            content.put(TimelineContentKey.STATE.getCode(), rec.getStatus());
                            if (rec.getReasoning() != null && !rec.getReasoning().isBlank()) {
                                content.put(TimelineContentKey.THINKING.getCode(), cap(rec.getReasoning(), CONTENT_CAP));
                            }
                            // Only reply_content is accepted: response_body is the audit merge of
                            // reasoning + body, and falling back to it when a tool/clarification turn
                            // has no body would show reasoning as a visible reply.
                            String reply = rec.getReplyContent();
                            if (reply != null && !reply.isBlank()) {
                                content.put(TimelineContentKey.REPLY.getCode(), cap(reply, CONTENT_CAP));
                            }
                            // Iteration-level usage: normalized usage of this LLM call (attached only when present)
                            if (rec.getDurationMs() != null) {
                                content.put(TimelineContentKey.DURATION_MS.getCode(), rec.getDurationMs());
                            }
                            if (rec.getTotalTokens() != null) {
                                content.put(TimelineContentKey.USAGE.getCode(), usageMap(
                                        rec.getPromptTokens(), rec.getCompletionTokens(), rec.getTotalTokens(),
                                        rec.getCacheHitTokens(), rec.getCacheMissTokens()));
                            }
                            String feedback = messageFeedbackService.getFeedback(
                                    rec.getId(), AuthContext.getUserId());
                            if (feedback != null) {
                                content.put(TimelineContentKey.FEEDBACK.getCode(), feedback);
                            }
                        }
                    } else {
                        // Fallback: when there is no per-turn reference, take the run's aggregated body (old rows / edge paths)
                        AgentRun r = runs.get(row.getRefRunId() != null ? row.getRefRunId() : row.getRunId());
                        if (r != null) {
                            if (r.getReasoning() != null && !r.getReasoning().isBlank()) {
                                content.put(TimelineContentKey.THINKING.getCode(), cap(r.getReasoning(), CONTENT_CAP));
                            }
                            if (r.getAssistantReply() != null && !r.getAssistantReply().isBlank()) {
                                content.put(TimelineContentKey.REPLY.getCode(), cap(r.getAssistantReply(), CONTENT_CAP));
                            }
                        }
                    }
                }
                case TOOL -> {
                    if (row.getRefToolCallId() != null) {
                        AgentToolCallRecord rec = toolCallMapper.selectById(row.getRefToolCallId());
                        if (rec != null) {
                            content.put(TimelineContentKey.STATE.getCode(), mapToolState(rec.getStatus()));
                            content.put(TimelineContentKey.DISPLAY_NAME.getCode(), rec.getDisplayNameCn() != null ? rec.getDisplayNameCn() : rec.getToolName());
                            content.put(TimelineContentKey.DISPLAY_NAME_EN.getCode(), rec.getDisplayNameEn() != null ? rec.getDisplayNameEn() : rec.getToolName());
                            content.put(TimelineContentKey.TOOL_NAME.getCode(), rec.getToolName());
                            content.put(TimelineContentKey.STATUS.getCode(), rec.getStatus());
                            if (rec.getArgumentsJson() != null) content.put(TimelineContentKey.ARGS.getCode(), cap(rec.getCompressedArguments() != null ? rec.getCompressedArguments() : rec.getArgumentsJson(), CONTENT_CAP));
                            if (rec.getArtifact() != null) {
                                content.put(TimelineContentKey.ARTIFACT.getCode(), cap(rec.getCompressedArtifact() != null ? rec.getCompressedArtifact() : rec.getArtifact(), CONTENT_CAP));
                                // Browser screenshot: the tool result contains data.screenshot → the main panel tool card shows the image
                                List<RunAttachment> images = ScreenshotRefParser.extractScreenshotImages(rec.getArtifact());
                                if (!images.isEmpty()) {
                                    content.put(TimelineContentKey.IMAGES.getCode(), images);
                                }
                            }
                            content.put(TimelineContentKey.META.getCode(), ToolMetaResolver.build(rec, mcpSpi));
                        }
                    }
                    content.putIfAbsent(TimelineContentKey.DISPLAY_NAME.getCode(), row.getTitle());
                }
                case TOOL_APPROVAL -> {
                    if (row.getRefApprovalId() != null) {
                        AgentToolApproval approval = toolApprovalMapper.selectById(row.getRefApprovalId());
                        if (approval != null) {
                            content.put(TimelineContentKey.STATE.getCode(), mapApprovalState(approval.getStatus()));
                            content.put(TimelineContentKey.APPROVAL_ID.getCode(), approval.getApprovalId());
                            content.put(TimelineContentKey.STATUS.getCode(), approval.getStatus());
                            content.put(TimelineContentKey.DECISIONS.getCode(), approval.getDecisionsJson());
                            content.put(TimelineContentKey.TOOL_CALLS.getCode(), approval.getToolCallsJson());
                        }
                    }
                    content.putIfAbsent(TimelineContentKey.TEXT.getCode(), row.getTitle());
                }
                case CLARIFICATION -> {
                    if (row.getRefClarificationId() != null) {
                        AgentPendingClarification c = clarificationMapper.selectById(row.getRefClarificationId());
                        if (c != null) {
                            content.put(TimelineContentKey.STATE.getCode(), mapClarificationState(c.getUserResponse()));
                            content.put(TimelineContentKey.CLARIFICATION_ID.getCode(), c.getClarificationId());
                            content.put(TimelineContentKey.TITLE.getCode(), c.getTitle());
                            content.put(TimelineContentKey.TYPE.getCode(), c.getType());
                            content.put(TimelineContentKey.MULTIPLE.getCode(), Boolean.TRUE.equals(c.getMultiple()));
                            content.put(TimelineContentKey.OPTIONS.getCode(), c.getOptions());
                            content.put(TimelineContentKey.DESCRIPTION.getCode(), c.getDescription());
                            content.put(TimelineContentKey.RESPONSE.getCode(), c.getUserResponse());
                        }
                    } else {
                        content.put(TimelineContentKey.TITLE.getCode(), row.getTitle());
                    }
                }
                case SUBAGENT -> {
                    if (row.getRefSubAgentRunId() != null) {
                        AgentSubAgentRun s = subAgentRunMapper.selectById(row.getRefSubAgentRunId());
                        if (s != null) {
                            content.put(TimelineContentKey.DISPLAY_NAME.getCode(), s.getDisplayName());
                            content.put(TimelineContentKey.STATE.getCode(), s.getStatus());
                            content.put(TimelineContentKey.AGENT_TYPE.getCode(), s.getAgentType());
                            content.put(TimelineContentKey.AGENT_REF.getCode(), s.getAgentRef());
                            content.put(TimelineContentKey.PARENT_TOOL_CALL_ID.getCode(), s.getParentToolCallId());
                            content.put(TimelineContentKey.STARTED_AT.getCode(), s.getStartedAt() != null ? s.getStartedAt().toString() : null);
                        }
                    } else {
                        content.put(TimelineContentKey.DISPLAY_NAME.getCode(), row.getTitle());
                    }
                }
                case COMPACTION -> {
                    content.put(TimelineContentKey.TEXT.getCode(), row.getTitle());
                    if (row.getRefCompactId() != null) {
                        AgentCompactRecord rec = compactRecordMapper.selectById(row.getRefCompactId());
                        if (rec != null) {
                            content.put(TimelineContentKey.STATE.getCode(), mapCompactionState(rec.getStatus()));
                            String text = rec.getSummaryAfter() != null && !rec.getSummaryAfter().isBlank()
                                    ? rec.getSummaryAfter()
                                    : rec.getErrorMessage();
                            if (text != null && !text.isBlank()) {
                                content.put(TimelineContentKey.TEXT.getCode(), cap(text, CONTENT_CAP));
                            }
                        }
                    }
                }
                case RUN_STATUS, ERROR -> {
                    AgentRun r = runs.get(row.getRefRunId() != null ? row.getRefRunId() : row.getRunId());
                    content.put(TimelineContentKey.TEXT.getCode(), row.getTitle());
                    if (r != null) {
                        content.put(TimelineContentKey.STATE.getCode(), r.getStatus());
                        if (r.getCreatedAt() != null && r.getUpdatedAt() != null) {
                            content.put(TimelineContentKey.DURATION_MS.getCode(),
                                    java.time.Duration.between(r.getCreatedAt(), r.getUpdatedAt()).toMillis());
                        }
                        // Elapsed time and model name: take this run's latest main Agent LLM call record (the run table has neither field)
                        AgentLlmInteractionRecord last = interactionMapper.selectOne(
                                new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                                        .eq(AgentLlmInteractionRecord::getRunId, r.getId())
                                        .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                                        .orderByDesc(AgentLlmInteractionRecord::getId)
                                        .last("LIMIT 1"));
                        if (last != null && last.getModelName() != null) {
                            content.put(TimelineContentKey.MODEL_NAME.getCode(), last.getModelName());
                        }
                        // Run-level usage: the batch-prefetched run summary (SUM shown once the run ends)
                        com.buukle.agent.instance.domain.vo.RunUsageVO usage = usageByRun != null
                                ? usageByRun.get(r.getId()) : null;
                        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                            content.put(TimelineContentKey.USAGE.getCode(), usageMap(
                                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                                    usage.getCacheHitTokens(), usage.getCacheMissTokens()));
                        }
                    }
                }
                default -> { /* Unknown kind: metadata only */ }
            }
        } catch (Exception e) {
            log.warn("Timeline content resolve failed (seq={}, kind={}): {}", row.getSeq(), kind, e.getMessage());
        }
        return content;
    }

    private Map<Long, AgentRun> loadRuns(List<AgentTimeline> rows) {
        List<Long> runIds = rows.stream()
                .map(r -> r.getRefRunId() != null ? r.getRefRunId() : r.getRunId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (runIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AgentRun> runs = runMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .in(AgentRun::getId, runIds)
                .select(AgentRun::getId, AgentRun::getUserMessage, AgentRun::getAssistantReply,
                        AgentRun::getReasoning, AgentRun::getAttachments, AgentRun::getStatus,
                        AgentRun::getCreatedAt, AgentRun::getUpdatedAt));
        Map<Long, AgentRun> map = runs.stream().collect(Collectors.toMap(AgentRun::getId, Function.identity(), (a, b) -> a));
        return map;
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Assemble the usage aggregation map (uniform structure for the frontend; only valued fields are put in, zero values kept for display). */
    private static Map<String, Object> usageMap(Number prompt, Number completion, Number total,
                                                Number cacheHit, Number cacheMiss) {
        Map<String, Object> m = new HashMap<>(5);
        m.put("promptTokens", prompt != null ? prompt.longValue() : 0L);
        m.put("completionTokens", completion != null ? completion.longValue() : 0L);
        m.put("totalTokens", total != null ? total.longValue() : 0L);
        m.put("cacheHitTokens", cacheHit != null ? cacheHit.longValue() : 0L);
        m.put("cacheMissTokens", cacheMiss != null ? cacheMiss.longValue() : 0L);
        return m;
    }

    /** Vocabulary check: warn only, never block (the timeline is display-only; a failed write must not affect the main flow). */
    private static void warnIfInvalidState(String kind, String state) {
        TimelineKind k = TimelineKind.from(kind);
        TimelineState s = TimelineState.from(state);
        if (k != null && s != null && !s.isValidFor(k)) {
            log.warn("Timeline state {} not allowed for kind {}", state, kind);
        }
    }

    /** Tool execution status → display vocabulary. */
    private static String mapToolState(String status) {        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case RunStatus.Codes.PENDING, RunStatus.Codes.RUNNING -> TimelineState.IN_PROGRESS.getCode();
            case "SUCCEEDED" -> TimelineState.SUCCEEDED.getCode();
            case RunStatus.Codes.FAILED -> TimelineState.FAILED.getCode();
            default -> null;
        };
    }

    /** Approval status → display vocabulary. */
    private static String mapApprovalState(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case RunStatus.Codes.PENDING -> TimelineState.PENDING.getCode();
            case "DISMISSED" -> TimelineState.CANCELLED.getCode();
            default -> TimelineState.ANSWERED.getCode();
        };
    }

    /** Clarification status: any answer means ANSWERED; dismissed means CANCELLED. */
    private static String mapClarificationState(String userResponse) {
        if (userResponse == null) {
            return TimelineState.PENDING.getCode();
        }
        if (userResponse.contains("__dismissed__")) {
            return TimelineState.CANCELLED.getCode();
        }
        return TimelineState.ANSWERED.getCode();
    }

    /** Compaction record status → display vocabulary. */
    private static String mapCompactionState(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case RunStatus.Codes.PENDING, RunStatus.Codes.RUNNING -> TimelineState.RUNNING.getCode();
            case RunStatus.Codes.COMPLETED -> TimelineState.COMPLETED.getCode();
            case RunStatus.Codes.FAILED -> TimelineState.FAILED.getCode();
            default -> null;
        };
    }

    /** Parse the attachment reference JSON of a USER row; returns an empty list on failure (body rendering is never blocked). */
    public static List<RunAttachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<RunAttachment> list = JsonUtils.parse(json, new TypeReference<List<RunAttachment>>() {
            });
            return list != null ? list : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Run attachments parse failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}