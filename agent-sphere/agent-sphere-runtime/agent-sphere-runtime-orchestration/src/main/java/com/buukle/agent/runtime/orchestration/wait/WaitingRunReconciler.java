package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 「卡住 WAITING」对账任务（安全网）。
 *
 * <p>唤醒路径都是「先 CAS 置 RESUMED、再恢复」；任何静默失败（恢复抛异常、事件丢失、
 * 副本重启丢内存定时器、cron 也恰好失败）都可能让 run 永久停在 WAITING。这里周期扫描
 * 「超时仍 WAITING 且名下已无 PENDING 挂起记录」的 run，直接再推进一次；失败则置终态事件，
 * 保证 run 不会永远卡在「执行中」。
 */
@Slf4j
@Component
public class WaitingRunReconciler {

    private static final String LOCK_KEY = "scheduler:wait-reconcile";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final int BATCH_LIMIT = 50;

    private final RunSpi runSpi;
    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final RedissonClient redissonClient;

    /** 判定"卡住"的阈值：大于 MAX_WAIT_MS(10min)，避免抢救正常的长 wait。 */
    @Value("${buukle.agent.wait.reconcile-stale-minutes:11}")
    private long staleMinutes;

    public WaitingRunReconciler(RunSpi runSpi, PendingWaitSpi pendingWaitSpi,
                                ChatRuntimeService chatRuntimeService, RedissonClient redissonClient) {
        this.runSpi = runSpi;
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelayString = "${buukle.agent.wait.reconcile-interval:PT30S}")
    public void reconcile() {
        // 对账是后台动作：清掉调度线程可能残留的租户，否则 findStaleByStatus 会被 created_by 过滤。
        PendingWaitWakeScheduler.clearInheritedContext();
        boolean locked = false;
        try {
            locked = redissonClient.getLock(LOCK_KEY).tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, staleMinutes));
            List<RunVO> stale = runSpi.findStaleByStatus(RunStatus.WAITING.name(), cutoff, BATCH_LIMIT);
            if (stale.isEmpty()) {
                return;
            }
            log.warn("Waiting-run reconcile: {} stale WAITING run(s)", stale.size());
            for (RunVO run : stale) {
                try {
                    List<PendingWaitVO> pending = pendingWaitSpi.listPendingByRunId(run.getId());
                    if (!pending.isEmpty()) {
                        continue; // 仍有待唤醒记录 → 正常路径会处理
                    }
                    rescue(run);
                } catch (Exception e) {
                    log.error("Reconcile failed for run {}", run.getId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Waiting-run reconcile sweep failed", e);
        } finally {
            if (locked) {
                try {
                    if (redissonClient.getLock(LOCK_KEY).isHeldByCurrentThread()) {
                        redissonClient.getLock(LOCK_KEY).unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release reconcile lock", e);
                }
            }
        }
    }

    /** 记录已被消费但 run 没恢复 → 直接再推进一次（不依赖 PENDING 记录）；失败则置终态。 */
    private void rescue(RunVO run) {
        PendingWaitVO synthetic = new PendingWaitVO(
                null, run.getSessionId(), run.getId(), null, 0, null, RunStatus.WAITING.name(), null);
        ChatRuntimeService.ResumeOutcome outcome = chatRuntimeService.resumeFromWait(synthetic);
        if (outcome != ChatRuntimeService.ResumeOutcome.RESUMED) {
            chatRuntimeService.failStuckWait(run.getSessionId(), run.getId(), "reconcile rescue: " + outcome);
        } else {
            log.info("Reconciled stuck WAITING run {}", run.getId());
        }
    }
}