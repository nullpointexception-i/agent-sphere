package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two regression guards for the in-memory wake thread:
 *
 * <ol>
 *   <li><b>Must not inherit a tenant.</b> The pool is a lazily created long-lived single thread that inherits the
 *       {@code InheritableThreadLocal} of the thread that first called schedule. Reading wait records with a console user's {@code created_by}
 *       makes {@code DataPermissionInterceptor} filter other users' records to null, so the resume is silently skipped.</li>
 *   <li><b>Must not silently drop after claim.</b> If the record cannot be read back and is not reset to PENDING, cron never picks it up,
 *       and the run can only wait for the reconciler (in production each wait got stuck for 10+ minutes).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PendingWaitWakeSchedulerTest {

    @Mock
    PendingWaitSpi pendingWaitSpi;
    @Mock
    ChatRuntimeService chatRuntimeService;

    PendingWaitWakeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PendingWaitWakeScheduler(pendingWaitSpi, chatRuntimeService);
    }

    @AfterEach
    void tearDown() {
        TenantUtil.stop();
        AuthContext.clear();
    }

    private static PendingWaitVO pendingWait(long id, long runId) {
        return new PendingWaitVO(id, 1L, runId, "call-1", 1000,
                LocalDateTime.now(), WaitConstants.PENDING_STATUS, 0);
    }

    @Test
    void wake_clearsInheritedTenantBeforeReadingRecord() {
        // Simulate production: the wake thread is lazily created on a tenant-bearing request thread (inheritable from the parent).
        TenantUtil.start("leaked-user");
        PendingWaitVO vo = pendingWait(1L, 7L);
        AtomicReference<String> tenantSeenByRead = new AtomicReference<>("not-called");

        when(pendingWaitSpi.claimForResume(1L)).thenReturn(true);
        when(pendingWaitSpi.getById(1L)).thenAnswer(inv -> {
            tenantSeenByRead.set(TenantUtil.get());
            return vo;
        });
        when(chatRuntimeService.resumeFromWait(vo)).thenReturn(ChatRuntimeService.ResumeOutcome.RESUMED);

        scheduler.triggerNow(1L);

        verify(chatRuntimeService, timeout(2000)).resumeFromWait(vo);
        assertNull(tenantSeenByRead.get(),
                "后台线程必须无租户，否则 SELECT 会被追加 created_by=<别人> 而读不到记录");
    }

    @Test
    void wake_releasesClaimWhenRecordUnreadableInsteadOfDroppingIt() {
        when(pendingWaitSpi.claimForResume(9L)).thenReturn(true);
        when(pendingWaitSpi.getById(9L)).thenReturn(null);
        when(pendingWaitSpi.releaseClaim(9L)).thenReturn(true);

        scheduler.triggerNow(9L);

        verify(pendingWaitSpi, timeout(2000)).releaseClaim(9L);
        verify(chatRuntimeService, never()).resumeFromWait(any());
    }

    @Test
    void wake_requeuesWhenResumeThrows() {
        PendingWaitVO vo = pendingWait(5L, 7L);
        when(pendingWaitSpi.claimForResume(5L)).thenReturn(true);
        when(pendingWaitSpi.getById(5L)).thenReturn(vo);
        when(chatRuntimeService.resumeFromWait(vo)).thenThrow(new IllegalStateException("boom"));

        scheduler.triggerNow(5L);

        verify(chatRuntimeService, timeout(2000))
                .onResumeOutcome(vo, ChatRuntimeService.ResumeOutcome.ERROR);
    }
}
