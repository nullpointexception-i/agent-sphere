package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentRouterFork;
import com.buukle.agent.superagent.repository.AgentRouterForkMapper;
import com.buukle.agent.superagent.spi.AgentRouterReplyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Merges soft-forked Router replies back into the parent session strictly by event sequence.
 *
 * <p>Forks never post to the group directly; this service is the single writer for fork replies.
 * A completed fork whose predecessor has not merged stays {@code WAITING_SEQ} until the chain is
 * contiguous, while failures and timeouts advance the cursor with a bounded note so later forks do
 * not block forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterForkMergeScheduler {

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ERROR = 500;

    private final AgentRouterForkMapper forkMapper;
    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final AgentRouterReplyPort replyPort;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.agent-router.fork.enabled:false}")
    private boolean forkEnabled;
    @Value("${buukle.agent.super-agent.agent-router.fork.timeout-seconds:180}")
    private long timeoutSeconds;
    @Value("${buukle.agent.super-agent.agent-router.fork.merge-summary-max-chars:2000}")
    private int summaryMaxChars;
    @Value("${buukle.agent.super-agent.agent-router.fork.merge-batch:50}")
    private int mergeBatch;

    @Scheduled(fixedDelayString =
            "${buukle.agent.super-agent.agent-router.fork.merge-interval-ms:2000}")
    public void run() {
        if (!forkEnabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        try {
            refreshStates();
            mergeReady();
        } catch (Exception e) {
            log.warn("Router fork merge scan failed: {}", e.getMessage());
        } finally {
            TenantContext.clear();
            TenantScopeHolder.clear();
            TenantUtil.stop();
            AuthContext.clear();
        }
    }

    /** Pulls the child run terminal state and the captured reply into the fork row. */
    private void refreshStates() {
        List<AgentRouterFork> forks = forkMapper.selectList(
                Wrappers.<AgentRouterFork>lambdaQuery()
                        .in(AgentRouterFork::getState, AgentRouterFork.STATE_RUNNING,
                                AgentRouterFork.STATE_COMPLETED, AgentRouterFork.STATE_WAITING_SEQ,
                                AgentRouterFork.STATE_FAILED, AgentRouterFork.STATE_TIMEOUT)
                        .orderByAsc(AgentRouterFork::getParentSessionId)
                        .orderByAsc(AgentRouterFork::getSeqFrom)
                        .last("LIMIT " + Math.max(1, mergeBatch)));
        for (AgentRouterFork fork : forks) {
            if (AgentRouterFork.STATE_RUNNING.equals(fork.getState())) {
                if (fork.getReplyText() != null && !fork.getReplyText().isBlank()) {
                    fork.setState(AgentRouterFork.STATE_COMPLETED);
                    forkMapper.updateById(fork);
                    continue;
                }
                RunVO run = fork.getChildRunId() == null ? null : runSpi.getRun(fork.getChildRunId());
                if (run == null) {
                    markTimeoutIfExpired(fork);
                    continue;
                }
                if (RunStatus.COMPLETED.name().equals(run.getStatus())) {
                    fork.setReplyText(run.getAssistantReply());
                    fork.setState(AgentRouterFork.STATE_COMPLETED);
                    forkMapper.updateById(fork);
                } else if (RunStatus.FAILED.name().equals(run.getStatus())
                        || RunStatus.CANCELLED.name().equals(run.getStatus())) {
                    fork.setState(AgentRouterFork.STATE_FAILED);
                    fork.setMergeError(trim(run.getErrorMessage() == null
                            ? "fork run " + run.getStatus() : run.getErrorMessage()));
                    forkMapper.updateById(fork);
                    metrics.increment(AgentMetrics.AGENT_ROUTER_FORK_FAILED);
                } else {
                    markTimeoutIfExpired(fork);
                }
            }
        }
    }

    private void markTimeoutIfExpired(AgentRouterFork fork) {
        if (fork.getCreatedAt() == null) {
            return;
        }
        if (Duration.between(fork.getCreatedAt(), LocalDateTime.now()).getSeconds() >= timeoutSeconds) {
            fork.setState(AgentRouterFork.STATE_TIMEOUT);
            fork.setMergeError("fork timed out");
            forkMapper.updateById(fork);
            metrics.increment(AgentMetrics.AGENT_ROUTER_FORK_TIMEOUT);
        }
    }

    private void mergeReady() {
        List<AgentRouterFork> candidates = forkMapper.selectList(
                Wrappers.<AgentRouterFork>lambdaQuery()
                        .in(AgentRouterFork::getState, AgentRouterFork.STATE_COMPLETED,
                                AgentRouterFork.STATE_WAITING_SEQ, AgentRouterFork.STATE_FAILED,
                                AgentRouterFork.STATE_TIMEOUT)
                        .orderByAsc(AgentRouterFork::getParentSessionId)
                        .orderByAsc(AgentRouterFork::getSeqFrom)
                        .last("LIMIT " + Math.max(1, mergeBatch)));
        Map<Long, List<AgentRouterFork>> byParent = candidates.stream()
                .collect(Collectors.groupingBy(AgentRouterFork::getParentSessionId));
        for (Map.Entry<Long, List<AgentRouterFork>> entry : byParent.entrySet()) {
            mergeParent(entry.getKey(), entry.getValue());
        }
    }

    private void mergeParent(Long parentSessionId, List<AgentRouterFork> group) {
        RLock lock = redissonClient.getLock("superagent:router-fork-merge:" + parentSessionId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            SessionVO parent = sessionSpi.getSession(parentSessionId);
            if (parent == null) {
                return;
            }
            // Strict ordering: do not release a fork reply while the parent Router run is still
            // processing earlier events (including a parked clarification).
            if (runSpi.findActiveRun(parentSessionId) != null) {
                return;
            }
            long nextSeq = nextSeq(parentSessionId);
            List<AgentRouterFork> ordered = new ArrayList<>(group);
            ordered.sort(java.util.Comparator.comparing(AgentRouterFork::getSeqFrom));
            String summary = parent.getSummary();
            for (AgentRouterFork fork : ordered) {
                if (AgentRouterFork.STATE_MERGED.equals(fork.getState())) {
                    continue;
                }
                if (fork.getSeqFrom() > nextSeq) {
                    if (!AgentRouterFork.STATE_WAITING_SEQ.equals(fork.getState())) {
                        fork.setState(AgentRouterFork.STATE_WAITING_SEQ);
                        forkMapper.updateById(fork);
                    }
                    break;
                }
                if (fork.getSeqFrom() < nextSeq) {
                    fork.setState(AgentRouterFork.STATE_MERGED);
                    fork.setMergedAt(LocalDateTime.now());
                    forkMapper.updateById(fork);
                    closeChild(fork);
                    continue;
                }
                boolean completed = AgentRouterFork.STATE_COMPLETED.equals(fork.getState());
                String reply = fork.getReplyText();
                if (completed && reply != null && !reply.isBlank()) {
                    try {
                        replyPort.sendFinalReply(parentSessionId, fork.getChildRunId(),
                                fork.getThreadId(), reply);
                    } catch (Exception e) {
                        fork.setMergeError(trim("reply send failed: " + e.getMessage()));
                        forkMapper.updateById(fork);
                        break;
                    }
                    summary = appendSummary(summary, reply);
                } else if (AgentRouterFork.STATE_FAILED.equals(fork.getState())
                        || AgentRouterFork.STATE_TIMEOUT.equals(fork.getState())) {
                    summary = appendSummary(summary, "[" + fork.getState().toLowerCase()
                            + "] " + (fork.getMergeError() == null ? "" : fork.getMergeError()));
                }
                fork.setState(AgentRouterFork.STATE_MERGED);
                fork.setMergedAt(LocalDateTime.now());
                forkMapper.updateById(fork);
                closeChild(fork);
                nextSeq = fork.getSeqTo() + 1;
                metrics.increment(AgentMetrics.AGENT_ROUTER_FORK_MERGED);
            }
            if (summary != null && !summary.equals(parent.getSummary())) {
                sessionSpi.updateSummary(parentSessionId, summary,
                        LocalDateTime.now().format(DTF), parent.getSummaryModelRouteId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Router fork merge failed: parent={}, error={}", parentSessionId, e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            TenantContext.clear();
        }
    }

    private long nextSeq(Long parentSessionId) {
        AgentRouterFork merged = forkMapper.selectOne(
                Wrappers.<AgentRouterFork>lambdaQuery()
                        .eq(AgentRouterFork::getParentSessionId, parentSessionId)
                        .eq(AgentRouterFork::getState, AgentRouterFork.STATE_MERGED)
                        .orderByDesc(AgentRouterFork::getSeqTo)
                        .last("LIMIT 1"));
        if (merged != null && merged.getSeqTo() != null) {
            return merged.getSeqTo() + 1;
        }
        AgentRouterFork first = forkMapper.selectOne(
                Wrappers.<AgentRouterFork>lambdaQuery()
                        .eq(AgentRouterFork::getParentSessionId, parentSessionId)
                        .orderByAsc(AgentRouterFork::getSeqFrom)
                        .last("LIMIT 1"));
        return first == null || first.getSeqFrom() == null ? 0L : first.getSeqFrom();
    }

    private String appendSummary(String current, String addition) {
        String base = current == null ? "" : current;
        if (addition == null || addition.isBlank()) {
            return base;
        }
        String next = base.isBlank() ? addition : base + "\n" + addition;
        return next.length() <= summaryMaxChars
                ? next : next.substring(next.length() - summaryMaxChars);
    }

    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR);
    }

    private void closeChild(AgentRouterFork fork) {
        if (fork.getChildSessionId() == null) {
            return;
        }
        try {
            sessionSpi.closeSession(fork.getChildSessionId());
        } catch (Exception e) {
            log.debug("Router fork child close failed: session={}, error={}",
                    fork.getChildSessionId(), e.getMessage());
        }
    }
}
