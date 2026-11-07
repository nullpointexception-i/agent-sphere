package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.instance.domain.AgentPendingWait;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.repository.AgentPendingWaitMapper;
import com.buukle.agent.instance.spi.PendingWaitCreatedEvent;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingWaitService implements PendingWaitSpi {

    private static final int ERROR_MAX = 500;

    private final AgentPendingWaitMapper waitMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Long createPending(Long sessionId, Long runId, String callId, int waitMs) {
        int bounded = Math.max(WaitConstants.MIN_WAIT_MS, Math.min(WaitConstants.MAX_WAIT_MS, waitMs));
        Instant wakeAt = Instant.now().plusMillis(bounded);

        AgentPendingWait entity = new AgentPendingWait();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setCallId(callId);
        entity.setWaitMs(bounded);
        entity.setWakeAt(LocalDateTime.ofInstant(wakeAt, ZoneId.systemDefault()));
        entity.setStatus(WaitConstants.PENDING_STATUS);
        entity.setAttempts(0);
        entity.setKind(WaitConstants.KIND_WAIT);
        waitMapper.insert(entity);

        log.info("Pending wait created: id={} runId={} callId={} waitMs={} wakeAt={}",
                entity.getId(), runId, callId, bounded, wakeAt);
        eventPublisher.publishEvent(
                new PendingWaitCreatedEvent(entity.getId(), sessionId, runId, wakeAt));
        return entity.getId();
    }

    @Override
    public Long createRetry(Long sessionId, Long runId, int attempt, LocalDateTime wakeAt,
                            String reason, Integer errorStatus, String lastError) {
        AgentPendingWait entity = new AgentPendingWait();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        // A retry has no tool call: callId/waitMs stay empty and the waker takes a different branch by kind
        entity.setWaitMs(0);
        entity.setWakeAt(wakeAt);
        entity.setStatus(WaitConstants.PENDING_STATUS);
        entity.setAttempts(attempt);
        entity.setKind(WaitConstants.KIND_LLM_RETRY);
        entity.setReason(reason);
        entity.setErrorStatus(errorStatus);
        entity.setLastError(truncate(lastError));
        waitMapper.insert(entity);

        Instant wakeInstant = wakeAt.atZone(ZoneId.systemDefault()).toInstant();
        log.info("LLM retry scheduled: id={} runId={} attempt={} reason={} status={} wakeAt={}",
                entity.getId(), runId, attempt, reason, errorStatus, wakeAt);
        eventPublisher.publishEvent(
                new PendingWaitCreatedEvent(entity.getId(), sessionId, runId, wakeInstant));
        return entity.getId();
    }

    @Override
    public boolean claimForResume(Long waitId) {
        if (waitId == null) {
            return false;
        }
        // CAS: only the party that successfully flips PENDING to RESUMED may wake the run.
        // The in-memory timer and the cron compensation can hit the same record at the same time;
        // without this step the same run would be resumed twice.
        int updated = waitMapper.update(null, new LambdaUpdateWrapper<AgentPendingWait>()
                .eq(AgentPendingWait::getId, waitId)
                .eq(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                .set(AgentPendingWait::getStatus, WaitConstants.RESUMED_STATUS)
                .set(AgentPendingWait::getResumedAt, LocalDateTime.now()));
        return updated > 0;
    }

    @Override
    public int requeueForRetry(Long waitId, String error) {
        if (waitId == null) {
            return -1;
        }
        AgentPendingWait p = waitMapper.selectById(waitId);
        if (p == null || !WaitConstants.RESUMED_STATUS.equals(p.getStatus())) {
            return -1;
        }
        int attempts = (p.getAttempts() == null ? 0 : p.getAttempts()) + 1;
        // RESUMED → PENDING: lets cron (listDue/claimForResume only accept PENDING) try again.
        int updated = waitMapper.update(null, new LambdaUpdateWrapper<AgentPendingWait>()
                .eq(AgentPendingWait::getId, waitId)
                .eq(AgentPendingWait::getStatus, WaitConstants.RESUMED_STATUS)
                .set(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                .set(AgentPendingWait::getAttempts, attempts)
                .set(AgentPendingWait::getLastError, truncate(error)));
        return updated > 0 ? attempts : -1;
    }

    @Override
    public boolean releaseClaim(Long waitId) {
        if (waitId == null) {
            return false;
        }
        // Single atomic UPDATE: even when the read-back fails (exactly the case this method exists
        // for) the record can still be put back on the queue.
        int updated = waitMapper.update(null, new LambdaUpdateWrapper<AgentPendingWait>()
                .eq(AgentPendingWait::getId, waitId)
                .eq(AgentPendingWait::getStatus, WaitConstants.RESUMED_STATUS)
                .lt(AgentPendingWait::getAttempts, WaitConstants.MAX_RESUME_ATTEMPTS)
                .set(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                .set(AgentPendingWait::getResumedAt, null)
                .set(AgentPendingWait::getLastError, truncate("claim released: record unreadable"))
                .setSql("attempts = attempts + 1"));
        return updated > 0;
    }

    @Override
    public void markTerminal(Long waitId, String status, String error) {
        if (waitId == null) {
            return;
        }
        waitMapper.update(null, new LambdaUpdateWrapper<AgentPendingWait>()
                .eq(AgentPendingWait::getId, waitId)
                .set(AgentPendingWait::getStatus, status)
                .set(AgentPendingWait::getLastError, truncate(error)));
    }

    @Override
    public PendingWaitVO getById(Long waitId) {
        if (waitId == null) {
            return null;
        }
        return toVO(waitMapper.selectById(waitId));
    }

    @Override
    public int cancelPendingByRunId(Long runId) {
        if (runId == null) {
            return 0;
        }
        // PENDING → CANCELLED: the waker's listDue / claimForResume only accept PENDING, so once set to
        // CANCELLED this run will not be resumed.
        return waitMapper.update(null, new LambdaUpdateWrapper<AgentPendingWait>()
                .eq(AgentPendingWait::getRunId, runId)
                .eq(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                .set(AgentPendingWait::getStatus, WaitConstants.CANCELLED_STATUS));
    }

    @Override
    public List<PendingWaitVO> listDue(Duration overdueBy, int limit) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.now().minus(overdueBy), ZoneId.systemDefault());
        List<AgentPendingWait> rows = waitMapper.selectList(
                new LambdaQueryWrapper<AgentPendingWait>()
                        .eq(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                        .le(AgentPendingWait::getWakeAt, cutoff)
                        .orderByAsc(AgentPendingWait::getWakeAt)
                        .last("LIMIT " + Math.max(1, limit)));
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public List<PendingWaitVO> listPendingByRunId(Long runId) {
        if (runId == null) {
            return List.of();
        }
        return waitMapper.selectList(new LambdaQueryWrapper<AgentPendingWait>()
                        .eq(AgentPendingWait::getRunId, runId)
                        .eq(AgentPendingWait::getStatus, WaitConstants.PENDING_STATUS)
                        .orderByDesc(AgentPendingWait::getId))
                .stream().map(this::toVO).toList();
    }

    private PendingWaitVO toVO(AgentPendingWait p) {
        if (p == null) {
            return null;
        }
        PendingWaitVO vo = new PendingWaitVO();
        vo.setId(p.getId());
        vo.setSessionId(p.getSessionId());
        vo.setRunId(p.getRunId());
        vo.setCallId(p.getCallId());
        vo.setWaitMs(p.getWaitMs());
        vo.setWakeAt(p.getWakeAt());
        vo.setStatus(p.getStatus());
        vo.setAttempts(p.getAttempts());
        vo.setKind(p.getKind());
        vo.setErrorStatus(p.getErrorStatus());
        vo.setReason(p.getReason());
        vo.setLastError(p.getLastError());
        return vo;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > ERROR_MAX ? s.substring(0, ERROR_MAX) : s;
    }
}