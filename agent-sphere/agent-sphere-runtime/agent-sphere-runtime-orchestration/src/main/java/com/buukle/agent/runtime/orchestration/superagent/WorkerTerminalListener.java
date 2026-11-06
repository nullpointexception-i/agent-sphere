package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
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
 * Worker Run 生命周期 → Work Item 终态回写。
 *
 * <p>只处理 {@code sessionType=WORKER} 的会话，普通聊天会话完全不受影响。
 * WAITING / AWAITING_USER 也回报，让任务板能看到「等输入」而不是一直 RUNNING。
 *
 * <p><b>必须最先执行</b>：{@code RuntimeEventListener} 处理同一事件时会把
 * {@code data.assistantReply} 改写成 run_status 行的标题（时间戳）。事件对象在
 * 所有监听器间是同一个引用，若本监听器后执行，worker 报告正文就会变成时间戳。
 * 顺序只是防御，真正的数据来源是 {@link RunSpi#getRun} 的权威落库值。
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
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case WAITING, AWAITING_USER -> "WAITING";
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
