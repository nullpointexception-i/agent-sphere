package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
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
import org.springframework.stereotype.Component;

/**
 * Worker Run 生命周期 → Work Item 终态回写。
 *
 * <p>只处理 {@code sessionType=WORKER} 的会话，普通聊天会话完全不受影响。
 * WAITING / AWAITING_USER 也回报，让任务板能看到「等输入」而不是一直 RUNNING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerTerminalListener {

    private final SessionSpi sessionSpi;
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
                    data.getAssistantReply(),
                    data.getErrorMessage()));
        } catch (Exception e) {
            log.warn("Worker report failed: session={}, error={}", session.getId(), e.getMessage());
        }
    }
}
