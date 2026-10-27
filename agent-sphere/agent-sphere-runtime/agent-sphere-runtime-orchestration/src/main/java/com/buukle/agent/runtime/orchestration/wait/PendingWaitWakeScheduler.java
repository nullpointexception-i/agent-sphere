package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.spi.PendingWaitCreatedEvent;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * wait 的主唤醒路径：每条等待记录一个内存定时器，到点触发恢复。
 *
 * <p>run 采用「单写者（本执行副本）」语义（见 RuntimeEventListener 注释），
 * 所以在创建等待的那台副本上挂定时器就够了，不需要跨副本协调。
 * 副本重启会让内存定时器全部丢失 —— 那由 {@code WaitWakeTask} 的 cron 兜底。
 *
 * <p>两条路径都会先 {@code claimForResume} 做 CAS，只有抢占成功的一方才真正恢复，
 * 因此「定时器与 cron 同时命中同一条记录」不会造成 run 被恢复两次。
 *
 * <p><b>唤醒线程必须无租户。</b>它是懒创建的长驻单线程，会通过 {@code InheritableThreadLocal}
 * 继承「第一次 schedule 时那个线程」的 {@code TenantUtil}（控制台会话的用户名）——
 * {@code DataPermissionInterceptor} 随后会把 {@code created_by=<那个用户>} 加到每条 SELECT 上，
 * 于是别的用户的等待记录 {@code getById} 读不到，恢复被静默跳过。任务型 run 不设
 * {@code TenantUtil}，所以过去多数时候线程是干净的、问题被掩盖。
 */
@Slf4j
@Component
public class PendingWaitWakeScheduler {

    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /**
     * 守护型单线程池：wait 恢复本身是快速的（落库 + 发事件 + 提交异步执行），
     * 真正的耗时在 asyncHandleUserMessage 里，不该占住这里。
     */
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pending-wait-wake");
                t.setDaemon(true);
                return t;
            });

    public PendingWaitWakeScheduler(PendingWaitSpi pendingWaitSpi, ChatRuntimeService chatRuntimeService) {
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
    }

    @EventListener
    public void onPendingWaitCreated(PendingWaitCreatedEvent event) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), event.wakeAt()).toMillis());
        log.info("Scheduling wait wake: waitId={} runId={} delayMs={}",
                event.waitId(), event.runId(), delayMs);
        schedule(event.waitId(), delayMs);
    }

    void schedule(Long waitId, long delayMs) {
        cancel(waitId);
        ScheduledFuture<?> future = executor.schedule(
                () -> wake(waitId), delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = scheduled.put(waitId, future);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    private void wake(Long waitId) {
        clearInheritedContext();
        try {
            if (!pendingWaitSpi.claimForResume(waitId)) {
                // 已被 cron 抢先，或已被另一条路径恢复：安静退出
                log.debug("Wait {} already claimed; skipping in-memory wake", waitId);
                return;
            }
            PendingWaitVO wait = pendingWaitSpi.getById(waitId);
            if (wait == null) {
                // 不能静默丢弃：claim 已经写进去了，就此返回会让记录停在 RESUMED、cron 永远捞不到。
                log.warn("Wait {} claimed but unreadable; releasing claim so cron can retry", waitId);
                releaseClaim(waitId);
                return;
            }
            chatRuntimeService.onResumeOutcome(wait, chatRuntimeService.resumeFromWait(wait));
        } catch (Throwable t) {
            // catch Throwable：Error（OOM 等）同样会把记录留在 RESUMED，必须先尽力回置再重抛。
            log.error("Failed to wake pending wait {}", waitId, t);
            requeueBestEffort(waitId);
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            scheduled.remove(waitId);
        }
    }

    /**
     * 清掉从创建线程继承来的租户/身份。后台线程本来就不该带请求上下文；
     * 不清会让 {@code DataPermissionInterceptor} 用别人的 {@code created_by} 过滤掉本记录。
     */
    static void clearInheritedContext() {
        TenantUtil.stop();
        AuthContext.clear();
    }

    /** claim 之后任何异常都要回置 PENDING（best-effort），否则记录停在 RESUMED、cron 不再重试。 */
    private void requeueBestEffort(Long waitId) {
        try {
            PendingWaitVO w = pendingWaitSpi.getById(waitId);
            if (w != null) {
                chatRuntimeService.onResumeOutcome(w, ChatRuntimeService.ResumeOutcome.ERROR);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to requeue wait {} after wake error", waitId, e);
        }
        // 回读也失败了：退回到「按 id 原子回置」，否则这条记录再没人碰。
        releaseClaim(waitId);
    }

    /** 把一条 claim 成功但读不回来的记录放回 PENDING（不做回读）。 */
    private void releaseClaim(Long waitId) {
        try {
            if (!pendingWaitSpi.releaseClaim(waitId)) {
                log.warn("Wait {} could not be released (already terminal or retries exhausted)", waitId);
            }
        } catch (Exception e) {
            log.error("Failed to release claim for wait {}", waitId, e);
        }
    }

    private void cancel(Long waitId) {
        ScheduledFuture<?> prev = scheduled.remove(waitId);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** 暴露给补偿任务：手动触发一次（用于进程内定时器丢失后的即时恢复）。 */
    public void triggerNow(Long waitId) {
        cancel(waitId);
        schedule(waitId, 0);
    }
}