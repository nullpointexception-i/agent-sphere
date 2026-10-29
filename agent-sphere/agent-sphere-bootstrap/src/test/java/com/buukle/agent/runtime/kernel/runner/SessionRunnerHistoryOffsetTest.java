package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** HistoryResult.currentRunStart 相对 history 列表；runner 必须加上已存在的 system prompt 偏移。 */
class SessionRunnerHistoryOffsetTest {

    @Test
    void addsBaseOffsetForResumedCurrentRun() {
        HistoryLoader.HistoryResult history = new HistoryLoader.HistoryResult(
                List.of(new ChatMessageDTO().setRole("user").setContent("task")), 0);

        assertEquals(1, SessionRunner.resolveCurrentRunStart(1, history, 5));
        assertEquals(3, SessionRunner.resolveCurrentRunStart(3, history, 5));
    }

    @Test
    void usesTotalWhenHistoryHasNoCurrentRun() {
        HistoryLoader.HistoryResult history = new HistoryLoader.HistoryResult(
                List.of(new ChatMessageDTO().setRole("assistant").setContent("old")), -1);

        assertEquals(7, SessionRunner.resolveCurrentRunStart(1, history, 7));
        assertEquals(7, SessionRunner.resolveCurrentRunStart(1, null, 7));
    }
}
