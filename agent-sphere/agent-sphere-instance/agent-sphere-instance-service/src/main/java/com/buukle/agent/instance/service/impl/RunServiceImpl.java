package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.PendingWaitKind;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.service.ClarificationService;
import com.buukle.agent.instance.service.RunService;
import com.buukle.agent.instance.service.converter.RunConverter;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RunServiceImpl extends ServiceImpl<RunMapper, AgentRun> implements RunService {
    private final RunConverter runConverter;
    private final ClarificationService clarificationService;
    private final AgentLlmInteractionRecordMapper interactionMapper;
    private final PendingWaitSpi pendingWaitSpi;
    private final AgentRuntimeProperties agentRuntimeProperties;

    /** Property name of the large field excluded from list/incremental queries (fetched on demand via findReasoningBatch to keep rows small) */
    private static final String REASONING_PROPERTY = "reasoning";
    /** Terminal statuses: no non-terminal event may move a run out of these states. */
    private static final List<String> TERMINAL_STATUSES = List.of(
            RunEnum.STATUS_COMPLETED, RunEnum.STATUS_FAILED, RunEnum.STATUS_CANCELLED);

    @Override
    public RunVO createRun(CreateRunDTO dto) {
        AgentRun run = runConverter.toDO(dto);
        save(run);
        RunVO vo = runConverter.toVO(run);
        // Mirror the persisted NOT NULL column: absent means false, never null.
        vo.setNoClarification(Boolean.TRUE.equals(dto.getNoClarification()));
        return vo;
    }

    @Override
    public RunVO getRun(Long id) {
        AgentRun run = getById(id);
        if (run == null) throw new BizException(InstanceErrorCode.RUN_NOT_FOUND);
        RunVO vo = runConverter.toVO(run);
        fillRetryState(vo);
        return vo;
    }

    @Override
    public RunVO getRunMeta(Long id) {
        if (id == null) return null;
        AgentRun run = lambdaQuery()
                .eq(AgentRun::getId, id)
                .select(AgentRun.class, i -> !REASONING_PROPERTY.equals(i.getProperty()))
                .one();
        if (run == null) {
            return null;
        }
        RunVO vo = runConverter.toVO(run);
        fillRetryState(vo);
        return vo;
    }

    @Override
    public RunVO findActiveRun(Long sessionId) {
        AgentRun run = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .in(AgentRun::getStatus, List.of(
                        RunEnum.STATUS_PENDING,
                        RunEnum.STATUS_RUNNING,
                        RunEnum.STATUS_AWAITING_USER,
                        // WAITING is also a parked state: the loop has exited but the run has not
                        // finished, so it must count as active; otherwise stop/guards would treat it as
                        // "no session" and neither cancel it nor clean it up.
                        RunEnum.STATUS_WAITING))
                .orderByDesc(AgentRun::getId)
                .last("LIMIT 1")
                .one();
        RunVO vo = run == null ? null : runConverter.toVO(run);
        fillRetryState(vo);
        return vo;
    }

    @Override
    public List<RunVO> findStaleByStatus(String status, LocalDateTime olderThan, int limit) {
        if (status == null || olderThan == null) {
            return List.of();
        }
        return lambdaQuery()
                .eq(AgentRun::getStatus, status)
                .lt(AgentRun::getUpdatedAt, olderThan)
                .orderByAsc(AgentRun::getId)
                .last("LIMIT " + Math.max(1, limit))
                .list()
                .stream().map(runConverter::toVO).toList();
    }

    @Override
    public List<Long> findStaleRunIdsByStatus(String status, LocalDateTime olderThan, int limit) {
        if (status == null || olderThan == null) {
            return List.of();
        }
        return lambdaQuery()
                .select(AgentRun::getId)
                .eq(AgentRun::getStatus, status)
                .lt(AgentRun::getUpdatedAt, olderThan)
                .orderByAsc(AgentRun::getId)
                .last("LIMIT " + Math.max(1, limit))
                .list().stream().map(AgentRun::getId).toList();
    }

    @Override
    public boolean existsRunAfterId(Long sessionId, Long runId) {
        if (sessionId == null || runId == null) return false;
        return lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .gt(AgentRun::getId, runId)
                .count() > 0;
    }

    /**
     * When WAITING and kind=LLM_RETRY, fill "attempt k/N + next time + error category" into the VO.
     *
     * <p>The detail fields stay in agent_pending_wait (single source of truth); the run row only
     * duplicates waitKind/retryAttempt. Only WAITING runs trigger one query, so the normal path has
     * zero overhead.
     */
    private void fillRetryState(RunVO vo) {
        if (vo == null || !RunEnum.STATUS_WAITING.equals(vo.getStatus())
                || !PendingWaitKind.LLM_RETRY.equals(vo.getWaitKind())) {
            return;
        }
        List<PendingWaitVO> pending = pendingWaitSpi.listPendingByRunId(vo.getId());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        PendingWaitVO wait = pending.get(0);
        if (!PendingWaitKind.LLM_RETRY.equals(wait.getKind())) {
            // Defensive: run.wait_kind may be a leftover from historical writes; the suspend record is the real source of truth
            return;
        }
        vo.setRetryAttempt(wait.getAttempts());
        vo.setRetryMax(agentRuntimeProperties.getRunner().getLlmRetryLadder().size());
        if (wait.getWakeAt() != null) {
            vo.setNextRetryAt(wait.getWakeAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        vo.setRetryHint(wait.getReason());
        vo.setRetryHttpStatus(wait.getErrorStatus());
        vo.setRetryDetail(wait.getLastError());
    }

    @Override
    public void updateRun(RunVO run) {        AgentRun entity = runConverter.toDO(run);
        updateById(entity);
    }

    @Override
    public boolean resetRetryState(Long runId) {
        if (runId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .set(AgentRun::getRetryAttempt, 0)
                .set(AgentRun::getWaitKind, null)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public boolean markRunning(Long runId, int loopCount, int loopLimit) {
        if (runId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .notIn(AgentRun::getStatus, TERMINAL_STATUSES)
                .set(AgentRun::getStatus, RunEnum.STATUS_RUNNING)
                .set(AgentRun::getLoopCount, loopCount)
                .set(AgentRun::getLoopLimit, loopLimit)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public boolean markWaitingForRetry(Long runId, int attempt) {
        if (runId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .in(AgentRun::getStatus, List.of(RunEnum.STATUS_RUNNING, RunEnum.STATUS_WAITING))
                .set(AgentRun::getStatus, RunEnum.STATUS_WAITING)
                .set(AgentRun::getRetryAttempt, attempt)
                .set(AgentRun::getWaitKind, PendingWaitKind.LLM_RETRY)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public boolean markWaiting(Long runId) {
        if (runId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, RunEnum.STATUS_RUNNING)
                .set(AgentRun::getStatus, RunEnum.STATUS_WAITING)
                .set(AgentRun::getWaitKind, null)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public RunVO claimForResume(Long runId) {
        if (runId == null) {
            return null;
        }
        boolean claimed = lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, RunEnum.STATUS_WAITING)
                .set(AgentRun::getStatus, RunEnum.STATUS_RUNNING)
                .set(AgentRun::getWaitKind, null)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
        return claimed ? getRun(runId) : null;
    }

    @Override
    public boolean updateStatusIfActive(Long runId, String status) {
        if (runId == null || status == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .notIn(AgentRun::getStatus, TERMINAL_STATUSES)
                .set(AgentRun::getStatus, status)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public boolean cancelIfActive(Long runId) {
        if (runId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(AgentRun::getId, runId)
                .notIn(AgentRun::getStatus, TERMINAL_STATUSES)
                .set(AgentRun::getStatus, RunEnum.STATUS_CANCELLED)
                .set(AgentRun::getWaitKind, null)
                .set(AgentRun::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    @Override
    public List<RunVO> listRunsBySessionAfterId(Long sessionId, Long afterRunId) {
        // Off-row: list/incremental queries exclude the large reasoning field and fetch it on demand via findReasoningBatch
        List<AgentRun> runs = lambdaQuery().eq(AgentRun::getSessionId, sessionId)
                .gt(AgentRun::getId, afterRunId)
                .select(AgentRun.class, i -> !REASONING_PROPERTY.equals(i.getProperty()))
                .orderByAsc(AgentRun::getId)
                .list();
        return runs.stream().map(runConverter::toVO).peek(this::fillRetryState).toList();
    }

    @Override
    public IPage<RunVO> pageRunsBySession(Long sessionId, String keyword, int page, int size) {
        Page<AgentRun> p = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .like(keyword != null && !keyword.isBlank(), AgentRun::getUserMessage, keyword)
                .select(AgentRun.class, i -> !REASONING_PROPERTY.equals(i.getProperty()))
                .orderByDesc(AgentRun::getCreatedAt)
                .page(new Page<>(page, size));
        IPage<RunVO> voPage = p.convert(runConverter::toVO);
        // Attach clarification data to each run; the userMessage of a clarification-answer run carries a prefix that the view layer strips
        List<Long> runIds = voPage.getRecords().stream().map(RunVO::getId).collect(Collectors.toList());
        if (!runIds.isEmpty()) {
            Map<Long, List<ClarificationVO>> clarificationMap = clarificationService.mapByRunIdList(runIds);
            for (RunVO runVO : voPage.getRecords()) {
                stripClarificationPrefix(runVO);
                List<ClarificationVO> cvs = clarificationMap.get(runVO.getId());
                if (cvs != null && !cvs.isEmpty()) runVO.setClarifications(cvs);
            }
            // Batch-backfill run-level usage aggregation (consumed row by row by RunDrawer)
            Map<Long, RunUsageVO> usageMap = interactionMapper.sumUsageByRunIds(runIds)
                    .stream().collect(Collectors.toMap(RunUsageVO::getRunId, u -> u));
            for (RunVO runVO : voPage.getRecords()) {
                runVO.setUsageSummary(usageMap.get(runVO.getId()));
            }
            for (RunVO runVO : voPage.getRecords()) {
                fillRetryState(runVO);
            }
        }
        return voPage;
    }

    @Override
    public RunUsageVO usageSummary(Long runId) {
        RunUsageVO vo = interactionMapper.getRunUsage(runId);
        return vo != null ? vo : RunUsageVO.empty(runId);
    }

    @Override
    public Map<Long, String> findReasoningBatch(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        return lambdaQuery()
                .in(AgentRun::getId, runIds)
                .select(AgentRun::getId, AgentRun::getReasoning)
                .list()
                .stream()
                .filter(r -> r.getReasoning() != null)
                .collect(Collectors.toMap(AgentRun::getId, AgentRun::getReasoning));
    }

    private static void stripClarificationPrefix(RunVO runVO) {
        String userMessage = runVO.getUserMessage();
        if (userMessage != null && userMessage.startsWith(ChatClarification.CLARIFICATION_RESUME_PREFIX)) {
            runVO.setClarificationResponse(true);
            runVO.setUserMessage(userMessage.substring(ChatClarification.CLARIFICATION_RESUME_PREFIX.length()));
        }
    }

    @Override
    public MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId) {
        var query = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .ne(AgentRun::getUserMessage, "")
                .select(AgentRun::getId, AgentRun::getUserMessage);

        if (runId != null) {
            if (RunEnum.DIRECTION_PREV.equals(direction)) {
                query.lt(AgentRun::getId, runId).orderByDesc(AgentRun::getId);
            } else {
                query.gt(AgentRun::getId, runId).orderByAsc(AgentRun::getId);
            }
        } else {
            if (RunEnum.DIRECTION_NEXT.equals(direction)) {
                return new MessageHistoryVO(null, null, false);
            }
            query.orderByDesc(AgentRun::getId);
        }

        AgentRun run = query.last("LIMIT 1").one();
        if (run == null) return new MessageHistoryVO(null, null, false);

        boolean hasMore;
        if (RunEnum.DIRECTION_PREV.equals(direction) || runId == null) {
            hasMore = lambdaQuery()
                    .eq(AgentRun::getSessionId, sessionId)
                    .ne(AgentRun::getUserMessage, "")
                    .lt(AgentRun::getId, run.getId())
                    .count() > 0;
        } else {
            hasMore = lambdaQuery()
                    .eq(AgentRun::getSessionId, sessionId)
                    .ne(AgentRun::getUserMessage, "")
                    .gt(AgentRun::getId, run.getId())
                    .count() > 0;
        }

        return new MessageHistoryVO(run.getId(), run.getUserMessage(), hasMore);
    }
}
