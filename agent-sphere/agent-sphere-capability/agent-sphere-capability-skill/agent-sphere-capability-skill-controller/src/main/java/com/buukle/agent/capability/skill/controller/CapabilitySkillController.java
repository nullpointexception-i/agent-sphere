package com.buukle.agent.capability.skill.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.capability.skill.dtvo.dto.BatchUpdateSkillStatusDTO;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.dto.UpdateSkillStatusDTO;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncTriggerEnum;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.SkillHubService;
import com.buukle.agent.capability.skill.service.impl.SkillSyncRunner;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping(ApiPrefix.V1 + "/capability/skill")
@RequiredArgsConstructor
@WithTenant
public class CapabilitySkillController extends BaseController {
    private final CapabilitySkillService capabilitySkillService;
    private final SkillHubService skillHubService;
    private final SkillSyncRunner skillSyncRunner;

    @AuditLog(action = "CREATE", resourceType = "Capability", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateSkillDTO dto) {
        return created(capabilitySkillService.createSkill(dto));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(capabilitySkillService.getSkill(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ok(capabilitySkillService.pageSkills(page, size, keyword, startTime, endTime));
    }

    @AuditLog(action = "UPDATE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateSkillDTO dto) {
        return ok(capabilitySkillService.updateSkill(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilitySkillService.deleteSkill(id);
        return ok();
    }

    @AuditLog(action = "BATCH_DELETE", resourceType = "Capability", resourceId = "#ids?.toString()")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_DELETE)
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilitySkillService.batchDeleteSkill(ids);
        return ok();
    }

    @AuditLog(action = "UPDATE_STATUS", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_UPDATE)
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateSkillStatusDTO dto) {
        return ok(capabilitySkillService.updateStatus(id, dto.getStatus()));
    }

    @AuditLog(action = "BATCH_UPDATE_STATUS", resourceType = "Capability", resourceId = "#dto?.ids?.toString()")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_UPDATE)
    @PostMapping("/batch/status")
    public ResponseEntity<?> batchUpdateStatus(@Valid @RequestBody BatchUpdateSkillStatusDTO dto) {
        capabilitySkillService.batchUpdateStatus(dto.getIds(), dto.getStatus());
        return ok();
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping("/hub")
    public ResponseEntity<?> hub(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ok(skillHubService.pageHubSkills(page, size, keyword));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping("/hub/followed")
    public ResponseEntity<?> followed() {
        return ok(skillHubService.listFollowed());
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping("/hub/{hubId}")
    public ResponseEntity<?> hubDetail(@PathVariable Long hubId) {
        return ok(skillHubService.getHub(hubId));
    }

    @AuditLog(action = "PUBLISH", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_PUBLISH)
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        return ok(skillHubService.publish(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_READ)
    @GetMapping("/{id}/publish-state")
    public ResponseEntity<?> publishState(@PathVariable Long id) {
        return ok(skillHubService.publishState(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_INSTALL)
    @PostMapping("/hub/{hubId}/follow")
    public ResponseEntity<?> follow(@PathVariable Long hubId) {
        skillHubService.follow(hubId);
        return ok(true);
    }

    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_INSTALL)
    @DeleteMapping("/hub/{hubId}/follow")
    public ResponseEntity<?> unfollow(@PathVariable Long hubId) {
        skillHubService.unfollow(hubId);
        return ok(true);
    }

    @AuditLog(action = "INSTALL", resourceType = "Capability", resourceId = "#hubId")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_INSTALL)
    @PostMapping("/hub/{hubId}/install")
    public ResponseEntity<?> installFromHub(
            @PathVariable Long hubId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        boolean autoUpdate = body != null && body.get("autoUpdate") != null
                && Boolean.parseBoolean(String.valueOf(body.get("autoUpdate")));
        return created(skillHubService.install(hubId, autoUpdate));
    }

    @AuditLog(action = "PUBLISH", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_PUBLISH)
    @PutMapping("/{id}/visibility")
    public ResponseEntity<?> setVisibility(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        return ok(capabilitySkillService.setVisibility(id, body != null ? body.get("visibility") : null));
    }

    @AuditLog(action = "INSTALL", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_INSTALL)
    @PostMapping("/{id}/install")
    public ResponseEntity<?> install(@PathVariable Long id, @RequestBody(required = false) java.util.Map<String, String> body) {
        return created(capabilitySkillService.installSkill(id, body != null ? body.get("name") : null));
    }

    /**
     * Manually triggers one "sync the latest version from the Skill Hub" round; submitted
     * asynchronously and returns the execution record immediately.
     *
     * <p>Non-blocking: a round scans every copy with auto-update enabled, so the frontend polls
     * {@code GET /sync-runs/{id}} with the returned id for progress and results.
     *
     * <p>The entry point lives on the "System Settings" page rather than the skill list - it is the
     * manual trigger for a system-level scheduled task, and its behavior is driven entirely by the
     * {@code skill} group in {@code agent_system_config} (global switch + record retention days).
     */
    @AuditLog(action = "SYNC", resourceType = "Capability")
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_UPDATE)
    @PostMapping("/sync-now")
    public ResponseEntity<?> syncNow() {
        return ok(skillSyncRunner.submitSync(SkillSyncTriggerEnum.TRIGGER_MANUAL));
    }

    /** Polls a single sync execution record (with live statistics and skip details). */
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_READ)
    @GetMapping("/sync-runs/{id}")
    public ResponseEntity<?> syncRun(@PathVariable Long id) {
        var run = skillSyncRunner.getRun(id);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    /**
     * Pages sync execution records. Both scheduled and manual triggers appear on this timeline.
     *
     * <p>This table is also the only evidence of whether auto-sync actually ran: the task logger is
     * pinned to WARN, so the logs alone cannot answer that.
     */
    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_READ)
    @GetMapping("/sync-runs")
    public ResponseEntity<?> syncRuns(@RequestParam(required = false) String triggerType,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "10") long size) {
        return ok(skillSyncRunner.listRuns(triggerType, status, page, size));
    }

    @AuditLog(action = "UPDATE_AUTO_UPDATE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_SKILL_UPDATE)
    @PutMapping("/{id}/auto-update")
    public ResponseEntity<?> setAutoUpdate(@PathVariable Long id, @RequestBody java.util.Map<String, Boolean> body) {
        return ok(capabilitySkillService.setAutoUpdate(id, body != null && Boolean.TRUE.equals(body.get("autoUpdate"))));
    }
}
