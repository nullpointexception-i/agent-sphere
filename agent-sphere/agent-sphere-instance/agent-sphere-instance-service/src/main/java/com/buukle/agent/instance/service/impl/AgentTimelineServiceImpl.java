package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.AgentSubAgentRun;
import com.buukle.agent.instance.domain.AgentTimeline;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.TimelineContentKey;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.AgentTimelineVO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.repository.AgentSubAgentRunMapper;
import com.buukle.agent.instance.repository.AgentTimelineMapper;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.service.util.ScreenshotRefParser;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 统一 Timeline 打平展示索引实现：
 * - 会话级 seq：Redis AtomicLong INCR（多副本单调、不重置）；
 * - 行记录/状态封口；
 * - keyset 分页（beforeSeq 向上翻 / afterSeq 断线补档）+ 按 ref 批量解析正文（非懒取）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTimelineServiceImpl extends ServiceImpl<AgentTimelineMapper, AgentTimeline>
        implements AgentTimelineSpi {

    private static final String TIMELINE_SEQ_KEY = "agent:timeline:seq:";
    /** 单字段正文加载上限（超长截断，避免单页过载；不落库）。 */
    private static final int CONTENT_CAP = 20000;
    /** 快照标签上限。 */
    private static final int TITLE_CAP = 300;
    /** 公共状态列默认值（通用约定 ACTIVE）。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final RedissonClient redissonClient;
    private final RunMapper runMapper;
    private final AgentToolCallRecordMapper toolCallMapper;
    private final AgentPendingClarificationMapper clarificationMapper;
    private final AgentSubAgentRunMapper subAgentRunMapper;
    private final AgentLlmInteractionRecordMapper interactionMapper;

    @Override
    public long nextSeq(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId required");
        }
        return redissonClient.getAtomicLong(TIMELINE_SEQ_KEY + sessionId).incrementAndGet();
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
    public SessionTimelinePageVO page(Long sessionId, Long beforeSeq, Long afterSeq, int limit) {
        int size = limit > 0 ? Math.min(limit, 50) : 5;
        List<AgentTimeline> rows;
        boolean hasMore = false;
        if (beforeSeq != null) {
            // 向上翻页：取 seq < beforeSeq 的最新 size 行（补 1 判 hasMore），再转正序
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
            // 断线补档：取 seq > afterSeq 的最早 size 行
            rows = lambdaQuery()
                    .eq(AgentTimeline::getSessionId, sessionId)
                    .gt(AgentTimeline::getSeq, afterSeq)
                    .orderByAsc(AgentTimeline::getSeq)
                    .last("LIMIT " + (size + 1))
                    .list();
            hasMore = rows.size() > size;
            if (hasMore) rows.remove(rows.size() - 1);
        } else {
            // 首页：最近 size 行，补 1 判 hasMore
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
        // run 级用量聚合批量预取（run_status 行展示会话内单 run SUM；避免逐行查询）
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
        vo.setContent(resolveContent(row, runs, usageByRun));
        return vo;
    }

    /** 按 ref 从原表批量解析正文（loading 时取好，非懒取）。 */
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
                    // 每轮 LLM 调用行：正文直接引用该轮 interaction 记录的 reasoning/reply_content（response_body 兜底）
                    if (row.getRefInteractionId() != null) {
                        AgentLlmInteractionRecord rec = interactionMapper.selectById(row.getRefInteractionId());
                        if (rec != null) {
                            if (rec.getReasoning() != null && !rec.getReasoning().isBlank()) {
                                content.put(TimelineContentKey.THINKING.getCode(), cap(rec.getReasoning(), CONTENT_CAP));
                            }
                            String reply = rec.getReplyContent();
                            if ((reply == null || reply.isBlank()) && rec.getResponseBody() != null) {
                                reply = rec.getResponseBody();
                            }
                            if (reply != null && !reply.isBlank()) {
                                content.put(TimelineContentKey.REPLY.getCode(), cap(reply, CONTENT_CAP));
                            }
                            // 迭代级用量：该次 LLM 调用的归一化用量（有值才附）
                            if (rec.getTotalTokens() != null) {
                                content.put(TimelineContentKey.USAGE.getCode(), usageMap(
                                        rec.getPromptTokens(), rec.getCompletionTokens(), rec.getTotalTokens(),
                                        rec.getCacheHitTokens(), rec.getCacheMissTokens()));
                            }
                        }
                    } else {
                        // 兜底：无 per-turn 引用时取 run 聚合正文（旧行/极端路径）
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
                            content.put(TimelineContentKey.DISPLAY_NAME.getCode(), rec.getDisplayNameCn() != null ? rec.getDisplayNameCn() : rec.getToolName());
                            content.put(TimelineContentKey.STATUS.getCode(), rec.getStatus());
                            if (rec.getArgumentsJson() != null) content.put(TimelineContentKey.ARGS.getCode(), cap(rec.getCompressedArguments() != null ? rec.getCompressedArguments() : rec.getArgumentsJson(), CONTENT_CAP));
                            if (rec.getArtifact() != null) {
                                content.put(TimelineContentKey.ARTIFACT.getCode(), cap(rec.getCompressedArtifact() != null ? rec.getCompressedArtifact() : rec.getArtifact(), CONTENT_CAP));
                                // 浏览器截图：工具结果含 data.screenshot → 主面板工具卡回显图片
                                List<RunAttachment> images = ScreenshotRefParser.extractScreenshotImages(rec.getArtifact());
                                if (!images.isEmpty()) {
                                    content.put(TimelineContentKey.IMAGES.getCode(), images);
                                }
                            }
                        }
                    }
                    content.putIfAbsent(TimelineContentKey.DISPLAY_NAME.getCode(), row.getTitle());
                }
                case CLARIFICATION -> {
                    if (row.getRefClarificationId() != null) {
                        AgentPendingClarification c = clarificationMapper.selectById(row.getRefClarificationId());
                        if (c != null) {
                            content.put(TimelineContentKey.CLARIFICATION_ID.getCode(), c.getClarificationId());
                            content.put(TimelineContentKey.TITLE.getCode(), c.getTitle());
                            content.put(TimelineContentKey.OPTIONS.getCode(), c.getOptions());
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
                case RUN_STATUS, ERROR -> {
                    AgentRun r = runs.get(row.getRefRunId() != null ? row.getRefRunId() : row.getRunId());
                    content.put(TimelineContentKey.TEXT.getCode(), row.getTitle());
                    if (r != null) {
                        if (r.getCreatedAt() != null && r.getUpdatedAt() != null) {
                            content.put(TimelineContentKey.DURATION_MS.getCode(),
                                    java.time.Duration.between(r.getCreatedAt(), r.getUpdatedAt()).toMillis());
                        }
                        // 耗时与模型名：取该 run 最近一次主 Agent LLM 调用记录（run 表无这两个字段）
                        AgentLlmInteractionRecord last = interactionMapper.selectOne(
                                new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                                        .eq(AgentLlmInteractionRecord::getRunId, r.getId())
                                        .isNull(AgentLlmInteractionRecord::getSubAgentRunId)
                                        .orderByDesc(AgentLlmInteractionRecord::getId)
                                        .last("LIMIT 1"));
                        if (last != null && last.getModelName() != null) {
                            content.put(TimelineContentKey.MODEL_NAME.getCode(), last.getModelName());
                        }
                        // run 级用量：批量预取的 run 汇总（run 结束后 SUM 展示）
                        com.buukle.agent.instance.domain.vo.RunUsageVO usage = usageByRun != null
                                ? usageByRun.get(r.getId()) : null;
                        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                            content.put(TimelineContentKey.USAGE.getCode(), usageMap(
                                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                                    usage.getCacheHitTokens(), usage.getCacheMissTokens()));
                        }
                    }
                }
                default -> { /* 未知 kind：仅元数据 */ }
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

    /** 组装用量聚合 Map（前端统一结构；仅在有值字段时放入，零值保留便于展示）。 */
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

    /** 解析 USER 行附件引用 JSON；失败返回空列表（不阻断正文渲染）。 */
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