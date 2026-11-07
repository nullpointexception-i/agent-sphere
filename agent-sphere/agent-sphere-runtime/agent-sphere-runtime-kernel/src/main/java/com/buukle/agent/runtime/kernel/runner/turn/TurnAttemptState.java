package com.buukle.agent.runtime.kernel.runner.turn;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CompactionService;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable state for one route attempt inside the turn executor. */
public final class TurnAttemptState {

    public final Long sessionId;
    public final Long runId;
    public final List<ChatMessageDTO> messages;
    public final List<ToolDefinitionDTO> toolDefs;
    public final List<RuntimeTool> tools;
    public final KernelContext ctx;
    public final String instanceConfig;
    public final Integer instanceMaxOutput;
    public final boolean skipCompaction;
    public final int loopCount;
    public final Integer retryMark;
    public final List<ModelRouteFullVO> activeRoutes;
    public final AtomicReference<String> contentRef;
    public final List<TurnToolCall> toolCalls;
    public final AtomicReference<String> errorRef;
    public final AtomicBoolean errorRetryableRef;
    public final AtomicReference<Integer> errorStatusRef;
    public final AtomicReference<String> reasoningRef;
    public final AtomicReference<String> finishReasonRef;
    public final AtomicBoolean compactionTriggered;
    public final AtomicBoolean outputExhaustedRef;
    public final AtomicReference<CompactionService.CompactionDecision> compactionDecisionRef;
    public final AtomicBoolean cancelled;
    public final AtomicBoolean acceptingEvents = new AtomicBoolean(true);

    public TurnAttemptState(Long sessionId, Long runId, List<ChatMessageDTO> messages,
                            List<ToolDefinitionDTO> toolDefs, List<RuntimeTool> tools,
                            KernelContext ctx, String instanceConfig, Integer instanceMaxOutput,
                            boolean skipCompaction, int loopCount, Integer retryMark,
                            List<ModelRouteFullVO> activeRoutes,
                            AtomicReference<String> contentRef, List<TurnToolCall> toolCalls,
                            AtomicReference<String> errorRef, AtomicBoolean errorRetryableRef,
                            AtomicReference<Integer> errorStatusRef,
                            AtomicReference<String> reasoningRef,
                            AtomicReference<String> finishReasonRef,
                            AtomicBoolean compactionTriggered,
                            AtomicBoolean outputExhaustedRef,
                            AtomicReference<CompactionService.CompactionDecision> compactionDecisionRef,
                            AtomicBoolean cancelled) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.messages = messages;
        this.toolDefs = toolDefs;
        this.tools = tools;
        this.ctx = ctx;
        this.instanceConfig = instanceConfig;
        this.instanceMaxOutput = instanceMaxOutput;
        this.skipCompaction = skipCompaction;
        this.loopCount = loopCount;
        this.retryMark = retryMark;
        this.activeRoutes = activeRoutes;
        this.contentRef = contentRef;
        this.toolCalls = toolCalls;
        this.errorRef = errorRef;
        this.errorRetryableRef = errorRetryableRef;
        this.errorStatusRef = errorStatusRef;
        this.reasoningRef = reasoningRef;
        this.finishReasonRef = finishReasonRef;
        this.compactionTriggered = compactionTriggered;
        this.outputExhaustedRef = outputExhaustedRef;
        this.compactionDecisionRef = compactionDecisionRef;
        this.cancelled = cancelled;
    }
}
