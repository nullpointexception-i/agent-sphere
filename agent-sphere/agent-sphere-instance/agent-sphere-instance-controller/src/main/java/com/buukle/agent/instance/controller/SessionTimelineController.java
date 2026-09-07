package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/sessions")
@RequiredArgsConstructor
public class SessionTimelineController extends BaseController {

    private final AgentTimelineSpi agentTimelineSpi;

    /** 统一 Timeline 打平列表：keyset 分页（beforeSeq 向上翻 / afterSeq 断线补档 / 空=最近一页）。 */
    @GetMapping("/{sessionId}/timeline")
    public ResponseEntity<?> timeline(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(defaultValue = "5") int limit) {
        return ok(agentTimelineSpi.page(sessionId, beforeSeq, afterSeq, limit));
    }
}