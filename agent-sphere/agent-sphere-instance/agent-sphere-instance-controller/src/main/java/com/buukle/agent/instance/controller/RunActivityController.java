package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.service.RunActivityService;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WithTenant
@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/runs")
@RequiredArgsConstructor
public class RunActivityController extends BaseController {

    private final RunActivityService runActivityService;
    private final SessionAccessGuard sessionAccessGuard;

    @GetMapping("/{runId}/activities")
    public ResponseEntity<?> listByRun(
            @PathVariable Long runId,
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        sessionAccessGuard.requireReadable(sessionId);
        return ok(runActivityService.listByRun(runId, sessionId, offset, limit));
    }

    /** Token series of the most recent 300 LLM interactions; afterId supports 5s incremental refresh. */
    @GetMapping("/{runId}/usage-series")
    public ResponseEntity<?> usageSeries(
            @PathVariable Long runId,
            @RequestParam Long sessionId,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "300") int limit) {
        sessionAccessGuard.requireReadable(sessionId);
        return ok(runActivityService.listUsageSeries(runId, sessionId, afterId, limit));
    }

    /** The list returns metadata only; full request/response/args/artifact are fetched on demand
     * when the details view is opened. */
    @GetMapping("/{runId}/activities/{activityType}/{id}")
    public ResponseEntity<?> getActivity(
            @PathVariable Long runId,
            @PathVariable String activityType,
            @PathVariable Long id,
            @RequestParam Long sessionId) {
        sessionAccessGuard.requireReadable(sessionId);
        return ok(runActivityService.getActivity(runId, sessionId, activityType, id));
    }
}
