package com.buukle.agent.runtime.kernel.runner.turn;

import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.runner.support.ToolJsonSupport;
import com.buukle.agent.runtime.kernel.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Folds one streaming LLM event into the active turn attempt and publishes the matching
 * runtime events.
 *
 * <p>Kept separate from the route executor so new event kinds can be handled without touching
 * request/await logic.
 */
@Component
@RequiredArgsConstructor
public class TurnEventSink {

    private final ApplicationEventPublisher eventPublisher;
    private final ToolExecutor toolExecutor;

    public void handle(LLMEvent event, TurnAttemptState s) {
        // After a timeout/exception the old stream may still emit events; reject writes so the fallback is not polluted.
        if (!s.acceptingEvents.get()) {
            return;
        }
        switch (event) {
            case LLMEvent.TextDelta t -> {
                s.contentRef.updateAndGet(c -> c + t.text());
                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                        RuntimeEventDataVO.builder()
                                .sessionId(s.sessionId).runId(s.runId).response(t.text()).build()));
            }
            case LLMEvent.ReasoningDelta r -> {
                s.reasoningRef.updateAndGet(c -> c + r.text());
                eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.REASONING_TOKEN,
                        RuntimeEventDataVO.builder()
                                .sessionId(s.sessionId).runId(s.runId)
                                .response(r.text())
                                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_LLM)
                                .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_MODEL_REASON)
                                .publishId(UUID.randomUUID().toString()).build()));
            }
            case LLMEvent.ToolInputStart start -> {
                // Give the frontend "generating call arguments" feedback before the arguments finish.
                eventPublisher.publishEvent(new RuntimeEventVO(
                        FlowEventType.REASONING_TOKEN,
                        RuntimeEventDataVO.builder()
                                .sessionId(s.sessionId)
                                .runId(s.runId)
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + start.id())
                                .reasoningType(RuntimeEventTypeConstant.REASONING_TYPE_SYSTEM)
                                .reasoningSubType(RuntimeEventTypeConstant.REASONING_SUB_TYPE_TOOL_INPUT_PREPARING)
                                .toolName(start.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(start.name(), s.tools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(start.name(), s.tools))
                                .response("⚙️ " + toolExecutor.resolveDisplayName(start.name(), s.tools)
                                        + ": generating call arguments…").build()));
            }
            case LLMEvent.ToolCall tc -> {
                s.toolCalls.add(new TurnToolCall(tc.id(), tc.name(), tc.arguments()));
                eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.PENDING,
                        RuntimeEventDataVO.builder()
                                .sessionId(s.sessionId).runId(s.runId)
                                .turnNo(s.loopCount)
                                .toolName(tc.name())
                                .displayNameCn(toolExecutor.resolveDisplayName(tc.name(), s.tools))
                                .displayNameEn(toolExecutor.resolveDisplayNameEn(tc.name(), s.tools))
                                .argumentsJson(tc.arguments())
                                .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + tc.id()).build()));
                // finish's summary is the final answer: push it as the assistant body when no text streamed.
                if (FinishConstants.TOOL_NAME.equals(tc.name())) {
                    String summary = ToolJsonSupport.field(tc.arguments(), FinishConstants.JSON_SUMMARY);
                    if (summary != null && !summary.isBlank() && s.contentRef.get().isEmpty()) {
                        s.contentRef.set(summary);
                        eventPublisher.publishEvent(new RuntimeEventVO(FlowEventType.CONTENT_TOKEN,
                                RuntimeEventDataVO.builder()
                                        .sessionId(s.sessionId).runId(s.runId)
                                        .response(summary).build()));
                    }
                }
            }
            case LLMEvent.Finish f -> s.finishReasonRef.set(f.reason());
            case LLMEvent.Error e -> {
                s.errorRef.set(e.message());
                s.errorRetryableRef.set(e.retryable());
                s.errorStatusRef.set(e.httpStatus());
            }
            default -> {
            }
        }
    }
}
