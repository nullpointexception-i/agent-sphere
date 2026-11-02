package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 压缩行生命周期：一次压缩（publishId）复用一行，RUNNING 建行、终态原地更新；
 * 下一次压缩另起一行。流式输出按 publishId 回填 seq/kind 后打到该行。
 */
@ExtendWith(MockitoExtension.class)
class TimelineRecorderTest {

    private static final long SESSION_ID = 953L;
    private static final long RUN_ID = 2263L;

    @Mock
    AgentTimelineSpi timelineSpi;
    @Mock
    AgentPendingClarificationMapper clarificationMapper;
    @Mock
    RunSpi runSpi;
    @Mock
    AgentLlmInteractionRecordSpi interactionRecordSpi;

    private TimelineRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new TimelineRecorder(timelineSpi, clarificationMapper, runSpi, interactionRecordSpi);
    }

    @Test
    void runningCreatesRow_andTerminalUpdatesSameSeq() {
        when(timelineSpi.record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.COMPACTION.getCode()),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(11L);

        long seq = recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-1", 7L,
                TimelineState.RUNNING.getCode(), "合并 3 轮历史");

        assertEquals(11L, seq);
        verify(timelineSpi).updateRefCompactId(SESSION_ID, 11L, 7L);

        recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-1", 7L,
                TimelineState.COMPLETED.getCode(), "合并 3 轮历史");

        // 同一次压缩：不再新建行，只 update
        verify(timelineSpi, times(1)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(timelineSpi).update(eq(SESSION_ID), eq(11L), any(), any(), any());
    }

    @Test
    void nextCompactionStartsANewRow() {
        when(timelineSpi.record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(11L, 12L);

        recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-1", 7L, TimelineState.RUNNING.getCode(), null);
        recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-1", 7L, TimelineState.COMPLETED.getCode(), null);
        long second = recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-2", 8L,
                TimelineState.RUNNING.getCode(), null);

        assertEquals(12L, second);
        verify(timelineSpi, times(2)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void stampCompactionSeq_writesSeqAndKindOntoOutputToken() {
        when(timelineSpi.record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(11L);
        recorder.recordCompaction(SESSION_ID, RUN_ID, "compact-1", 7L, TimelineState.RUNNING.getCode(), null);

        RuntimeEventDataVO token = new RuntimeEventDataVO()
                .setSessionId(SESSION_ID)
                .setRunId(RUN_ID)
                .setPublishId("compact-1")
                .setResponse("## Goal\n...");

        recorder.stampCompactionSeq(token);

        assertEquals(11L, token.getSeq());
        assertEquals(TimelineKind.COMPACTION.getCode(), token.getKind());
    }

    @Test
    void clarificationResponseReusesDbRowWhenMapIsEmpty() {
        AgentPendingClarification pending = new AgentPendingClarification();
        pending.setId(236L);
        pending.setClarificationId("c-1");
        when(clarificationMapper.selectOne(any())).thenReturn(pending);
        when(timelineSpi.findLatestSeq(any())).thenReturn(7L);

        recorder.recordClarification(SESSION_ID, RUN_ID, "c-1",
                TimelineState.ANSWERED.getCode(), "choose one");

        verify(timelineSpi).update(SESSION_ID, 7L,
                TimelineState.ANSWERED.getCode(), TimelineState.ANSWERED.getCode(), "choose one");
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void runTerminalReusesDbRowWhenMapIsEmpty() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(8L);

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.COMPLETED);

        verify(timelineSpi).update(eq(SESSION_ID), eq(8L),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any());
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void runTerminalAfterPauseCreatesNewRowAtEnd() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.AWAITING_USER.getCode());
        when(timelineSpi.record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any())).thenReturn(265L);

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.COMPLETED);

        // 暂停行保留在 seq 17，终态另起一行落在 run 末尾
        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi).record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any());
    }

    @Test
    void runTerminalAfterWaitCreatesNewRowAtEnd() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(23L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.WAITING.getCode());
        when(timelineSpi.record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.FAILED.getCode()), eq(TimelineState.FAILED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any())).thenReturn(266L);

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.FAILED);

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi).record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.FAILED.getCode()), eq(TimelineState.FAILED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any());
    }

    @Test
    void runTerminalWithoutPauseStillUpdatesExistingRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(8L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.RUNNING.getCode());

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.COMPLETED);

        verify(timelineSpi).update(eq(SESSION_ID), eq(8L),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any());
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void pauseStatusStillReusesPauseRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.AWAITING_USER.getCode());

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.AWAITING_USER);

        verify(timelineSpi).update(eq(SESSION_ID), eq(17L),
                eq(TimelineState.AWAITING_USER.getCode()), eq(TimelineState.AWAITING_USER.getCode()), any());
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void latePauseDoesNotDowngradeTerminalRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(265L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.COMPLETED.getCode());

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.AWAITING_USER);

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void markWaitResumedUpdatesWaitingRowToResumed() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.WAITING.getCode());

        recorder.markWaitResumed(SESSION_ID, RUN_ID, 7000L);

        verify(timelineSpi).update(eq(SESSION_ID), eq(17L),
                eq(TimelineState.RESUMED.getCode()), eq(TimelineState.RESUMED.getCode()),
                eq("▶️ 已恢复（等待 7s）"));
    }

    @Test
    void markWaitResumedIgnoresNonWaitingRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.COMPLETED.getCode());

        recorder.markWaitResumed(SESSION_ID, RUN_ID, 7000L);

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void runTerminalAfterResumedCreatesNewRowAtEnd() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.RESUMED.getCode());
        when(timelineSpi.record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any())).thenReturn(267L);

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.COMPLETED);

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi).record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.COMPLETED.getCode()), eq(TimelineState.COMPLETED.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any());
    }

    @Test
    void pauseAfterResumedStartsNewWaitingRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(17L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.RESUMED.getCode());
        when(timelineSpi.record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.WAITING.getCode()), eq(TimelineState.WAITING.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any())).thenReturn(25L);

        recorder.recordRunTerminal(SESSION_ID, RUN_ID, RunStatus.WAITING);

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi).record(eq(SESSION_ID), eq(RUN_ID), eq(TimelineKind.RUN_STATUS.getCode()),
                eq(TimelineState.WAITING.getCode()), eq(TimelineState.WAITING.getCode()), any(),
                eq(RUN_ID), any(), any(), any(), any());
    }

    @Test
    void lateRunningStatusDoesNotDowngradeTerminalRow() {
        when(timelineSpi.findLatestSeq(any())).thenReturn(12L);
        when(timelineSpi.findLatestState(any())).thenReturn(TimelineState.COMPLETED.getCode());

        recorder.recordRunStatus(SESSION_ID, RUN_ID, TimelineState.RUNNING.getCode(), "running");

        verify(timelineSpi, times(0)).update(anyLong(), anyLong(), any(), any(), any());
        verify(timelineSpi, times(0)).record(anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }
}
