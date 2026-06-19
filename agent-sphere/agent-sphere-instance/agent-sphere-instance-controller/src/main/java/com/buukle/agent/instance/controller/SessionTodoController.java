package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class SessionTodoController extends BaseController {

    private final SessionTodoSpi sessionTodoSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;

    @GetMapping("/{id}/todos")
    public ResponseEntity<List<SessionTodoVO>> getTodos(@PathVariable Long id) {
        return ok(sessionTodoSpi.listBySession(id));
    }

    @GetMapping("/{id}/toolcalls/latest")
    public ResponseEntity<List<AgentToolCallRecordVO>> getLatestToolCalls(@PathVariable Long id) {
        return ok(toolCallRecordSpi.listBySessionId(id, null));
    }
}
