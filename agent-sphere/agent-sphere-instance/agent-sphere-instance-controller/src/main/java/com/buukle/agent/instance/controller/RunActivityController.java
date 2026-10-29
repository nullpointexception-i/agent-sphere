package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.service.RunActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping("/api/v1/instance/runs")
@RequiredArgsConstructor
public class RunActivityController extends BaseController {

    private final RunActivityService runActivityService;

    @GetMapping("/{runId}/activities")
    public ResponseEntity<?> listByRun(
            @PathVariable Long runId,
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ok(runActivityService.listByRun(runId, sessionId, offset, limit));
    }

    /** 最近 300 条主 Agent LLM 交互的 token 序列；afterId 支持 5s 增量刷新。 */
    @GetMapping("/{runId}/usage-series")
    public ResponseEntity<?> usageSeries(
            @PathVariable Long runId,
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "300") int limit) {
        return ok(runActivityService.listUsageSeries(runId, sessionId, afterId, limit));
    }

    /** 列表只返回元数据；完整 request/response/args/artifact 在点击详情时按需拉取。 */
    @GetMapping("/{runId}/activities/{activityType}/{id}")
    public ResponseEntity<?> getActivity(
            @PathVariable Long runId,
            @PathVariable String activityType,
            @PathVariable Long id,
            @RequestParam Long sessionId) {
        return ok(runActivityService.getActivity(runId, sessionId, activityType, id));
    }
}
