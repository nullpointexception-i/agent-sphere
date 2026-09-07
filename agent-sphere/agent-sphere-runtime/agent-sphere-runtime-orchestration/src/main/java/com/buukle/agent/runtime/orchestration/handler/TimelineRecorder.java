package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 Timeline 打平索引的运行时记录器：
 * - 主 Agent 流式容器行（首个 token 建行并回填 seq，run 终态封口）；
 * - 工具行 / 澄清行 / run_status 行；
 * - chat() 用户消息行、SkillReActExecutor 子 Agent 头行（均经 SPI 直接调用）。
 * 本类不改变既有事件语义，仅消费其副作用追加展示序列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineRecorder {

    private final AgentTimelineSpi timelineSpi;
    private final AgentPendingClarificationMapper clarificationMapper;

    private final ConcurrentHashMap<Long, Long> assistantSeqByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolSeqByRunStep = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> clarificationSeqByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> runStatusSeqByRun = new ConcurrentHashMap<>();

    /** run 终态展示文案。成功 run 展示完成时间 yyyy-MM-dd HH:mm:ss（不再显示「任务完成」）。 */
    private static final DateTimeFormatter RUN_TERMINAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TITLE_RUN_FAILED = "❌ 执行失败";
    private static final String TITLE_RUN_CANCELLED = "⏹️ 已取消";
    private static final String TITLE_RUN_AWAITING = "⏸️ 等待澄清";

    /** 直接记录一行（chat 用户消息 / 子 Agent 头行等），返回 seq。 */
    public long record(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                       Long refRunId, Long refInteractionId, Long refToolCallId,
                       Long refSubAgentRunId, Long refClarificationId) {
        try {
            return timelineSpi.record(sessionId, runId, kind, subtype, state, title, refRunId,
                    refInteractionId, refToolCallId, refSubAgentRunId, refClarificationId);
        } catch (Exception e) {
            log.warn("Timeline record failed: {}", e.getMessage());
            return -1L;
        }
    }

    public void update(Long sessionId, Long seq, String state, String subtype, String title) {
        try {
            timelineSpi.update(sessionId, seq, state, subtype, title);
        } catch (Exception e) {
            log.warn("Timeline update failed: {}", e.getMessage());
        }
    }

    /** 主 Agent 助手容器：首个 token 建行并回填 seq（打字机目标）。 */
    public void stampStreamingSeq(Long sessionId, Long runId, com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO data) {
        if (runId == null) {
            return;
        }
        Long seq = assistantSeqByRun.get(runId);
        if (seq == null) {
            seq = timelineSpi.record(sessionId, runId, TimelineKind.ASSISTANT.getCode(), null,
                    TimelineState.RUNNING.getCode(), null, runId, null, null, null, null);
            assistantSeqByRun.put(runId, seq);
        }
        data.setSeq(seq);
        data.setKind(TimelineKind.ASSISTANT.getCode());
    }

    /** run 终态：封口主助手容器（兜底；每轮 LLM 调用已由 closeAssistantTurn 封口，仅兜底极端路径）。 */
    public void closeAssistant(Long sessionId, Long runId, String state) {
        Long seq = assistantSeqByRun.remove(runId);
        if (seq != null) {
            update(sessionId, seq, state, null, null);
        }
    }

    /** 每轮 LLM 调用结束（interaction 落库后）：封口当前打开的助手行并回填该轮引用，一轮一行。 */
    public void closeAssistantTurn(Long sessionId, Long runId, String state, Long interactionId) {
        if (runId == null) {
            return;
        }
        Long seq = assistantSeqByRun.remove(runId);
        if (seq == null) {
            return;
        }
        update(sessionId, seq, state, null, null);
        try {
            timelineSpi.updateRefInteractionId(sessionId, seq, interactionId);
        } catch (Exception e) {
            log.warn("Timeline ref interaction failed: {}", e.getMessage());
        }
    }

    /** 工具行：开始/更新状态（主 Agent 工具；子 Agent 工具由子 Agent 详情懒取，不入主行）。 */
    public void recordTool(Long sessionId, Long runId, String stepKey, Long refToolCallId, String state, String title) {
        try {
            String key = runId + ":" + stepKey;
            Long seq = toolSeqByRunStep.get(key);
            if (seq == null) {
                seq = timelineSpi.record(sessionId, runId, TimelineKind.TOOL.getCode(), state, state, title,
                        null, null, refToolCallId, null, null);
                toolSeqByRunStep.put(key, seq);
            } else {
                timelineSpi.update(sessionId, seq, state, state, title);
            }
        } catch (Exception e) {
            log.warn("Timeline tool row failed: {}", e.getMessage());
        }
    }

    public void removeTool(Long runId, String stepKey) {
        toolSeqByRunStep.remove(runId + ":" + stepKey);
    }

    /** run 状态行：同一 run 复用一行（RUNNING 建行，终态原地更新），避免历史残留「运行中」行。 */
    public void recordRunStatus(Long sessionId, Long runId, String state, String title) {
        if (runId == null) {
            return;
        }
        Long seq = runStatusSeqByRun.get(runId);
        if (seq == null) {
            seq = timelineSpi.record(sessionId, runId, TimelineKind.RUN_STATUS.getCode(), state, state, title,
                    runId, null, null, null, null);
            runStatusSeqByRun.put(runId, seq);
        } else {
            timelineSpi.update(sessionId, seq, state, state, title);
        }
    }

    /** run 终态：状态/title 由 RunStatus 映射（避免魔法值散落）；更新同一行并释放该 run 的占位。 */
    public void recordRunTerminal(Long sessionId, Long runId, RunStatus status) {
        if (runId == null) {
            return;
        }
        String state = switch (status) {
            case COMPLETED -> TimelineState.COMPLETED.getCode();
            case FAILED -> TimelineState.FAILED.getCode();
            case CANCELLED -> TimelineState.CANCELLED.getCode();
            case AWAITING_USER -> "AWAITING_USER";
            default -> status.name();
        };
        String title = switch (status) {
            case COMPLETED -> RUN_TERMINAL_FMT.format(LocalDateTime.now());
            case FAILED -> TITLE_RUN_FAILED;
            case CANCELLED -> TITLE_RUN_CANCELLED;
            case AWAITING_USER -> TITLE_RUN_AWAITING;
            default -> throw new IllegalStateException("unexpected run status " + status);
        };
        Long seq = runStatusSeqByRun.get(runId);
        if (seq != null) {
            timelineSpi.update(sessionId, seq, state, state, title);
            // AWAITING_USER 之后还可能续跑同一行；仅真正终态释放 map 条目
            if (!"AWAITING_USER".equals(state)) {
                runStatusSeqByRun.remove(runId, seq);
            }
        } else {
            // 兜底：没有 RUNNING 行则新建终态行（极端路径）
            timelineSpi.record(sessionId, runId, TimelineKind.RUN_STATUS.getCode(), state, state, title,
                    runId, null, null, null, null);
        }
    }

    /** 澄清行：pending→answered 复用同一行。 */
    public void recordClarification(Long sessionId, Long runId, String clarificationId, String state, String title) {
        try {
            String key = runId + ":" + clarificationId;
            Long seq = clarificationSeqByRun.get(key);
            Long refId = lookupClarificationRef(runId, clarificationId);
            if (seq == null) {
                seq = timelineSpi.record(sessionId, runId, TimelineKind.CLARIFICATION.getCode(), state, state, title,
                        runId, null, null, null, refId);
                clarificationSeqByRun.put(key, seq);
            } else {
                timelineSpi.update(sessionId, seq, state, null, title);
            }
        } catch (Exception e) {
            log.warn("Timeline clarification row failed: {}", e.getMessage());
        }
    }

    private Long lookupClarificationRef(Long runId, String clarificationId) {
        try {
            AgentPendingClarification c = clarificationMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .eq(AgentPendingClarification::getClarificationId, clarificationId)
                            .last("LIMIT 1"));
            return c != null ? c.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}