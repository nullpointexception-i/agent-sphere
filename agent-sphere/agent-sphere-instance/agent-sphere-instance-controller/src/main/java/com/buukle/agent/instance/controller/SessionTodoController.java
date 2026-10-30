package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.dtvo.vo.SessionToolStatsVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class SessionTodoController extends BaseController {

    private final SessionTodoSpi sessionTodoSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final ToolApprovalGrantSpi toolApprovalGrantSpi;

    @GetMapping("/{id}/todos")
    public ResponseEntity<List<SessionTodoVO>> getTodos(@PathVariable Long id) {
        return ok(sessionTodoSpi.listBySession(id));
    }

    @GetMapping("/{id}/toolcalls/latest")
    public ResponseEntity<List<AgentToolCallRecordVO>> getLatestToolCalls(@PathVariable Long id) {
        return ok(toolCallRecordSpi.listBySessionId(id, null));
    }

    @GetMapping("/{id}/tool-stats")
    public ResponseEntity<SessionToolStatsVO> getToolStats(
            @PathVariable Long id,
            @RequestParam(required = false) Long runId) {
        SessionToolStatsVO vo = new SessionToolStatsVO();
        Long latestRunId = toolCallRecordSpi.getLatestRunIdWithToolCalls(id);
        Long currentRunId = runId != null ? runId : latestRunId;
        vo.setSessionToolCallCount(toolCallRecordSpi.countBySessionId(id));
        vo.setCurrentRunId(currentRunId);
        vo.setCurrentRunToolCallCount(toolCallRecordSpi.countByRunId(currentRunId));
        vo.setLatestRunId(latestRunId);
        vo.setLatestRunToolCallCount(toolCallRecordSpi.countByRunId(latestRunId));
        vo.setAuthorizedTools(toolApprovalGrantSpi.listApprovedBySession(id));
        return ok(vo);
    }
}
