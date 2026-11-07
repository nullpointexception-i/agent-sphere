package com.buukle.agent.bootstrap.controller;


import com.buukle.agent.instance.dtvo.enums.RunType;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.converter.RunConverter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunConverterTest {

    private final RunConverter converter = new RunConverter();

    @Test
    void toDO_persistsAttachmentsAsJson() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType(RunType.AUTO);
        dto.setUserMessage("看图");
        dto.setAttachments(List.of(
                new RunAttachment("key-a", "image/png"),
                new RunAttachment("key-b", "image/jpeg")));

        AgentRun run = converter.toDO(dto);

        assertEquals("看图", run.getUserMessage());
        assertEquals("[{\"fileKey\":\"key-a\",\"contentType\":\"image/png\"},{\"fileKey\":\"key-b\",\"contentType\":\"image/jpeg\"}]",
                run.getAttachments());
    }

    @Test
    void toDO_skipsEmptyAttachments() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType(RunType.AUTO);
        dto.setUserMessage("hi");
        dto.setAttachments(List.of());

        AgentRun run = converter.toDO(dto);

        assertNull(run.getAttachments());
    }

    @Test
    void toVO_restoresAttachments() {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setSessionId(1L);
        run.setType(RunType.AUTO);
        run.setUserMessage("看图");
        run.setAttachments("[{\"fileKey\":\"key-a\",\"contentType\":\"image/png\"}]");

        RunVO vo = converter.toVO(run);

        assertEquals(1, vo.getAttachments().size());
        assertEquals("key-a", vo.getAttachments().get(0).fileKey());
        assertEquals("image/png", vo.getAttachments().get(0).contentType());
    }

    @Test
    void toVO_handlesNullAttachments() {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setUserMessage("no imagie");

        RunVO vo = converter.toVO(run);

        assertNull(vo.getAttachments());
    }

    @Test
    void errorMessage_roundTripsBothDirections() {
        // RunConverter is hand-written; missing one direction raises no error and only loses the failure reason silently (the V87 lesson)
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setErrorMessage("rate limited by upstream");

        AgentRun do_ = converter.toDO(vo);
        assertEquals("rate limited by upstream", do_.getErrorMessage(), "VO → DO 必须带上失败原因");

        RunVO back = converter.toVO(do_);
        assertEquals("rate limited by upstream", back.getErrorMessage(), "DO → VO 必须带上失败原因");
    }

    @Test
    void loopLimit_roundTripsBothDirections() {
        // loop_limit pins the loop-budget denominator (V88). Missing one direction raises no error
        // and only makes resumed runs resolve a smaller budget and shrink mid-run — the failure is silent.
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setLoopCount(50);
        vo.setLoopLimit(2000);

        AgentRun do_ = converter.toDO(vo);
        assertEquals(2000, do_.getLoopLimit(), "VO → DO 必须带上固化的轮次上限");

        RunVO back = converter.toVO(do_);
        assertEquals(2000, back.getLoopLimit(), "DO → VO 必须带上固化的轮次上限");
        assertEquals(50, back.getLoopCount());
    }

    @Test
    void completionProtocol_roundTripsButStartsNullForFirstEntryResolution() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType(RunType.AUTO);
        dto.setUserMessage("hi");
        assertNull(converter.toDO(dto).getCompletionProtocol(),
                "新 run 必须留空，等 RuntimeOrchestrator 解析实例/路由后固化");

        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setCompletionProtocol("IMPLICIT_TEXT");
        AgentRun run = converter.toDO(vo);
        assertEquals("IMPLICIT_TEXT", run.getCompletionProtocol());
        assertEquals("IMPLICIT_TEXT", converter.toVO(run).getCompletionProtocol());
    }

    @Test
    void loopLimit_nullStaysNullSoFirstEntryStillResolves() {
        // loop_limit is NULL for legacy runs: it must pass through unchanged and not be overwritten by a default,
        // otherwise the first entry has no chance to resolve the correct (possibly larger) budget.
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setLoopLimit(null);

        assertNull(converter.toDO(vo).getLoopLimit());
        assertNull(converter.toVO(converter.toDO(vo)).getLoopLimit());
    }
}