package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.superagent.spi.AgentRouterReportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Routes Agent Router run terminal events back to delivery state. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouterTerminalListener {

    private final SessionSpi sessionSpi;
    private final ObjectProvider<AgentRouterReportPort> reportPortProvider;

    @EventListener
    public void onRunEvent(RuntimeEventVO event) {
        if (!(event.getEventType() instanceof RunStatus status)) {
            return;
        }
        String result = switch (status) {
            case COMPLETED, FAILED, CANCELLED -> status.name();
            default -> null;
        };
        if (result == null) {
            return;
        }
        RuntimeEventDataVO data = event.getData();
        if (data == null || data.getSessionId() == null) {
            return;
        }
        AgentRouterReportPort port = reportPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            SessionVO session = sessionSpi.getSession(data.getSessionId());
            if (session == null || !SessionType.ROUTER.equals(session.getSessionType())) {
                return;
            }
            port.onRouterTerminal(session.getId(), data.getRunId(), result);
        } catch (Exception e) {
            log.debug("Agent router terminal event ignored: {}", e.getMessage());
        }
    }
}
