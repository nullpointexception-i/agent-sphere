package com.buukle.agent.instance.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.SessionCleanupRequestDTO;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupTriggerEnum;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupRunVO;
import com.buukle.agent.instance.service.impl.SessionCleanupTask;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger entry point for historical session cleanup (for operators).
 *
 * <p>The scheduled task runs during off-peak hours by default (04:30); this endpoint is for
 * "free the disk now" or "see exactly what would be deleted first".
 *
 * <p><b>Dry-run by default</b>: cleanup is an <b>irreversible hard delete</b>, so the request body
 * must explicitly pass {@code "dryRun": false} before anything is actually deleted.
 *
 * <p><b>Asynchronous</b>: submission returns the execution record immediately (status=RUNNING);
 * the frontend polls {@link #run(Long)} with its id to follow progress and results. A single
 * cleanup round covers up to 200×50 sessions and can take minutes, so making the request thread
 * wait would exhaust the connection pool and leave the frontend guessing from timeouts.
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/session-cleanup")
@RequiredArgsConstructor
public class SessionCleanupController extends BaseController {

    private final SessionCleanupTask sessionCleanupTask;

    /**
     * Submit one cleanup round (asynchronous).
     *
     * <p>Reuses the {@code admin:settings:update} permission: it is already the existing permission
     * for "change system configuration", and cleanup behavior is entirely driven by system
     * configuration (retention days / switch), so the semantics match and no new permission seed is
     * needed.
     *
     * @return execution record (including the id, for polling); cleanup results are not returned here, poll for them
     */
    @AuditLog(action = "CLEANUP", resourceType = "Session")
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_UPDATE)
    @PostMapping
    public ResponseEntity<?> cleanup(@Valid @RequestBody(required = false) SessionCleanupRequestDTO dto) {
        boolean dryRun = dto == null || dto.isDryRun();
        SessionCleanupRunVO run = sessionCleanupTask.submitCleanup(dryRun, SessionCleanupTriggerEnum.TRIGGER_MANUAL);
        return ok(run);
    }

    /**
     * Poll a single execution record (with live progress: processed sessions / deleted rows / elapsed time).
     *
     * <p>A crashed process leaves the record stuck in RUNNING forever; the SQL flags it with
     * {@code stale} (finished_at is null and it started more than 30 minutes ago) so it is not
     * mistaken for one still running.
     */
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_READ)
    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable Long id) {
        SessionCleanupRunVO run = sessionCleanupTask.getRun(id);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    /**
     * Execution record paging. Both scheduled and manual triggers show up here, and rehearsals and
     * real deletions share the same timeline (distinguished by the "type" column), so
     * "who rehearsed what, when, and how many rows they saw" stays traceable.
     *
     * @param dryRun null = do not filter by rehearsal/real deletion
     */
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_READ)
    @GetMapping("/runs")
    public ResponseEntity<?> runs(@RequestParam(required = false) String triggerType,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Boolean dryRun,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size) {
        return ok(sessionCleanupTask.listRuns(triggerType, status, dryRun, page, size));
    }
}