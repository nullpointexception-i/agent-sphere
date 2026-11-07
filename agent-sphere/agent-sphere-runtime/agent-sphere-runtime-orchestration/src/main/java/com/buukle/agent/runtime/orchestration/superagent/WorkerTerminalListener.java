package com.buukle.agent.runtime.orchestration.superagent;


import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.superagent.spi.WorkerReport;
import com.buukle.agent.superagent.spi.WorkerReportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Worker Run lifecycle → Work Item terminal-state write-back.
 *
 * <p>Only {@code sessionType=WORKER} sessions are handled; ordinary chat sessions are completely unaffected.
 * WAITING / AWAITING_USER are reported too, so the work item board can see "awaiting input" instead of staying RUNNING forever.
 *
 * <p><b>Must run first</b>: while handling the same event, {@code RuntimeEventListener} rewrites
 * {@code data.assistantReply} into the run_status row title (a timestamp). The event object is the same reference
 * across all listeners, so if this listener ran later, the worker report body would become a timestamp.
 * Ordering is only a defense; the real data source is the authoritative persisted value from {@link RunSpi#getRun}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerTerminalListener {

    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ObjectProvider<WorkerReportPort> workerReportPortProvider;

    @EventListener
    public void onRunEvent(RuntimeEventVO event) {
        if (!(event.getEventType() instanceof RunStatus status)) {
            return;
        }
        String reportStatus = switch (status) {
            case COMPLETED -> WorkItemStatus.DONE;
            case FAILED -> WorkItemStatus.FAILED;
            case CANCELLED -> WorkItemStatus.CANCELLED;
            case WAITING, AWAITING_USER -> WorkItemStatus.WAITING;
            case PENDING, RUNNING -> null;
        };
        if (reportStatus == null) {
            return;
        }
        RuntimeEventDataVO data = event.getData();
        if (data == null || data.getSessionId() == null) {
            return;
        }
        WorkerReportPort port = workerReportPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        SessionVO session;
        try {
            session = sessionSpi.getSession(data.getSessionId());
        } catch (Exception e) {
            log.debug("Worker terminal event ignored: session {} unavailable ({})",
                    data.getSessionId(), e.getMessage());
            return;
        }
        if (session == null || !SessionType.WORKER.equals(session.getSessionType())) {
            return;
        }
        try {
            port.onWorkerTerminal(new WorkerReport(
                    null,
                    session.getSuperAgentId(),
                    session.getId(),
                    data.getRunId(),
                    reportStatus,
                    authoritativeSummary(data),
                    data.getErrorMessage()));
        } catch (Exception e) {
            log.warn("Worker report failed: session={}, error={}", session.getId(), e.getMessage());
        }
    }

    /**
     * Prefer the persisted run reply over the event field: the event object is shared
     * with other listeners and its {@code assistantReply} is rewritten to a status-row
     * title (a timestamp) by the timeline handler. The run row is updated before the
     * terminal event is published, so it always carries the real final text.
     */
    private String authoritativeSummary(RuntimeEventDataVO data) {
        if (data.getRunId() != null) {
            try {
                RunVO run = runSpi.getRun(data.getRunId());
                if (run != null && run.getAssistantReply() != null
                        && !run.getAssistantReply().isBlank()) {
                    return run.getAssistantReply();
                }
            } catch (Exception e) {
                log.debug("Worker report run lookup failed: runId={}, error={}",
                        data.getRunId(), e.getMessage());
            }
        }
        return data.getAssistantReply();
    }
}
