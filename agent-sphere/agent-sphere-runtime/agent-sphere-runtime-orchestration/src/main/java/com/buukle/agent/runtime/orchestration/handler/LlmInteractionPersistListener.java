package com.buukle.agent.runtime.orchestration.handler;

import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionEvent;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmInteractionPersistListener {

    private final AgentLlmInteractionRecordSpi interactionRecordSpi;
    private final RunSpi runSpi;

    @EventListener
    public void handle(LlmInteractionEvent event) {
        LlmInteractionMeta meta = event.getMeta();
        if (meta == null || meta.getInteractionType() == null) return;

        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setRunId(meta.getRunId());
        vo.setSessionId(meta.getSessionId());
        vo.setInteractionType(meta.getInteractionType().name());
        vo.setModelName(event.getModelName());
        vo.setRequestBody(event.getRequestBody());
        vo.setResponseBody(event.getResponseBody());
        vo.setDurationMs((int) event.getDurationMs());
        vo.setSuccess(event.isSuccess());
        vo.setErrorMessage(event.getErrorMessage());

        try {
            RunVO run = runSpi.getRun(meta.getRunId());
            if (run != null && run.getCreatedBy() != null) {
                vo.setCreatedBy(run.getCreatedBy());
            }
        } catch (Exception ignored) {
        }

        interactionRecordSpi.createRecord(vo);
        log.debug("LLM interaction recorded: runId={}, type={}, duration={}ms, success={}",
            meta.getRunId(), meta.getInteractionType().name(), event.getDurationMs(), event.isSuccess());
    }
}
