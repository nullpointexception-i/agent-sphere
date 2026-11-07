package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.WorkerControlPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Worker control: stop the current run of the specified Worker session. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerControlService implements WorkerControlPort {

    private final SessionSpi sessionSpi;
    private final ChatRuntimeService chatRuntimeService;

    @Override
    public boolean cancel(Long workerSessionId) {
        if (workerSessionId == null) {
            return false;
        }
        TenantUtil.stop();
        AuthContext.clear();
        try {
            SessionVO session = sessionSpi.getSession(workerSessionId);
            if (session == null || !SessionType.WORKER.equals(session.getSessionType())) {
                return false;
            }
            AuthContext.setSuperAdmin(true);
            chatRuntimeService.stopSession(workerSessionId);
            return true;
        } catch (Exception e) {
            log.warn("Worker cancel failed: session={}, error={}", workerSessionId, e.getMessage());
            return false;
        } finally {
            AuthContext.clear();
            TenantUtil.stop();
        }
    }
}
